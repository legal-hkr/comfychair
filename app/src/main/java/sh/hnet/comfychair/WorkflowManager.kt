package sh.hnet.comfychair

import sh.hnet.comfychair.model.CustomFields
import sh.hnet.comfychair.model.CustomField
import android.content.Context
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.NodeAttributeEdits
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.BypassNodeResolver
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraInjectionUtils
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.ModelSlot
import sh.hnet.comfychair.workflow.ComfyUiExporter
import sh.hnet.comfychair.workflow.Subgraphs
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowGraphPreparation

/**
 * Workflow types supported by ComfyChair
 */
enum class WorkflowType {
    TTI,              // Text-to-Image
    ITI_INPAINTING,   // Image-to-Image Inpainting
    ITI_EDITING,      // Image-to-Image Editing
    TTV,              // Text-to-Video
    ITV,              // Image-to-Video
    V2V               // Video-to-Video
}

/**
 * Display utilities for WorkflowType in Compose UI.
 * Provides stable list of types with their string resource IDs.
 */
object WorkflowTypeDisplay {
    /**
     * All workflow types with their display name string resource IDs.
     * Use this instead of creating lists inline to avoid recomposition.
     */
    val allTypes: List<Pair<WorkflowType, Int>> = listOf(
        WorkflowType.TTI to R.string.workflow_section_tti,
        WorkflowType.ITI_INPAINTING to R.string.workflow_section_iti_inpainting,
        WorkflowType.ITI_EDITING to R.string.workflow_section_iti_editing,
        WorkflowType.TTV to R.string.workflow_section_ttv,
        WorkflowType.ITV to R.string.workflow_section_itv,
        WorkflowType.V2V to R.string.workflow_section_v2v
    )

    /**
     * Get the display name string resource ID for a workflow type.
     */
    fun getDisplayNameResId(type: WorkflowType): Int {
        return allTypes.first { it.first == type }.second
    }
}

/**
 * Result of workflow validation
 */
sealed class WorkflowValidationResult {
    object Success : WorkflowValidationResult()
    data class InvalidFilename(val message: String) : WorkflowValidationResult()
    data class InvalidJson(val message: String) : WorkflowValidationResult()
    data class MissingPlaceholders(val missing: List<String>) : WorkflowValidationResult()
    data class MissingKeys(val missing: List<String>) : WorkflowValidationResult()
}

/**
 * WorkflowManager - Manages ComfyUI workflow JSON files
 *
 * This singleton handles:
 * - Loading workflow JSON files from res/raw (built-in) and internal storage (user-uploaded)
 * - Extracting workflow names and descriptions
 * - Validating workflows against required placeholders
 * - Replacing template variables with actual values
 * - Providing workflow data for API calls
 */
object WorkflowManager {

    private const val TAG = "Workflow"
    private const val USER_WORKFLOWS_PREFS = "UserWorkflowsPrefs"
    private const val USER_WORKFLOWS_KEY = "user_workflows_json"
    private const val USER_WORKFLOWS_DIR = "user_workflows"

    // Workflow data class
    data class Workflow(
        val id: String,
        val name: String,
        val description: String,
        val jsonContent: String,
        val type: WorkflowType,
        val isBuiltIn: Boolean,
        val defaults: WorkflowDefaults = WorkflowDefaults()
    ) {
        /** Fields the user made from node values (spec §18), read from the workflow's wrapper. */
        val customFields: List<CustomField> by lazy { CustomFields.fieldsOf(jsonContent) }

        /** The workflow's own labels for its built-in fields, by field key. */
        val fieldLabels: Map<String, String> by lazy { CustomFields.labelsOf(jsonContent) }
    }

    // Context initialization
    private lateinit var applicationContext: Context
    private var isInitialized = false

    /**
     * Initialize the WorkflowManager with application context.
     * Must be called before first use.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (!isInitialized) {
            applicationContext = context.applicationContext
            loadAllWorkflows()
            isInitialized = true
            DebugLogger.i(TAG, "WorkflowManager initialized")
        }
    }

    /**
     * Ensure initialized, or initialize lazily with provided context.
     */
    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    // Constants - delegate to WorkflowJsonAnalyzer
    val REQUIRED_PATTERNS get() = WorkflowJsonAnalyzer.REQUIRED_PATTERNS
    val PREFIX_TO_TYPE get() = WorkflowJsonAnalyzer.PREFIX_TO_TYPE

    // Available workflows loaded from res/raw and user storage
    private val workflows = mutableListOf<Workflow>()
    private val workflowValuesStorage by lazy { WorkflowValuesStorage(applicationContext) }

    // Workflow change notification - increments when workflows are added/updated/deleted
    // ViewModels can observe this to refresh their workflow lists
    private val _workflowsVersion = MutableStateFlow(0L)
    val workflowsVersion: StateFlow<Long> = _workflowsVersion.asStateFlow()

    /**
     * Load all workflows (built-in and user-uploaded)
     */
    private fun loadAllWorkflows() {
        workflows.clear()
        loadBuiltInWorkflows()
        loadUserWorkflows()
        disabledWorkflowIds = AppSettings.getDisabledWorkflowIds(
            applicationContext,
            workflows.filter { it.isBuiltIn }.map { it.id }
        )
    }

    // Workflows switched off by the user (see AppSettings.getDisabledWorkflowIds)
    @Volatile
    private var disabledWorkflowIds: Set<String> = emptySet()

    /** IDs of the workflows switched off by the user. */
    fun getDisabledWorkflowIds(): Set<String> = disabledWorkflowIds

    /** Whether a workflow is offered on the generation screens. */
    fun isWorkflowEnabled(workflowId: String): Boolean = workflowId !in disabledWorkflowIds

    /**
     * Switches a workflow on or off. The generation screens refresh their lists through
     * [workflowsVersion] and fall back to another workflow if the selected one was switched off.
     */
    fun setWorkflowEnabled(workflowId: String, enabled: Boolean) {
        val updated = if (enabled) disabledWorkflowIds - workflowId else disabledWorkflowIds + workflowId
        if (updated == disabledWorkflowIds) return
        disabledWorkflowIds = updated
        AppSettings.setDisabledWorkflowIds(applicationContext, updated)
        _workflowsVersion.value++
    }

    /** Switches several workflows on or off at once (a section of the workflows page), with one write. */
    fun setWorkflowsEnabled(workflowIds: Collection<String>, enabled: Boolean) {
        val updated = if (enabled) disabledWorkflowIds - workflowIds.toSet() else disabledWorkflowIds + workflowIds
        if (updated == disabledWorkflowIds) return
        disabledWorkflowIds = updated
        AppSettings.setDisabledWorkflowIds(applicationContext, updated)
        _workflowsVersion.value++
    }

    /**
     * Reload all workflows from disk (after a backup restore, a reset, or a save in the editor); the screens follow
     * through [workflowsVersion].
     */
    fun reloadWorkflows() {
        loadAllWorkflows()
        _workflowsVersion.value++
    }

    /**
     * Load built-in workflow JSON files from res/raw
     * Auto-discovers all workflow resources by scanning R.raw fields that match workflow prefixes
     */
    private fun loadBuiltInWorkflows() {
        // Get all workflow resource IDs by scanning R.raw fields
        val workflowResources = discoverWorkflowResources()

        for (resId in workflowResources) {
            try {
                val inputStream: InputStream = applicationContext.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val resourceName = applicationContext.resources.getResourceEntryName(resId)
                val type = parseWorkflowType(resourceName)
                if (type == null) {
                    // Old-format resource names (tti_checkpoint_*, tti_unet_*, etc.) are no longer supported
                    DebugLogger.w(TAG, "Skipping built-in workflow '$resourceName': unrecognized prefix")
                    continue
                }
                val defaults = WorkflowDefaults.fromJson(jsonObject.optJSONObject("defaults"))

                // Generate deterministic UUID from resource name
                val id = UuidUtils.generateDeterministicId(resourceName)

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = true, defaults))
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Built-in workflow could not be loaded: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Discover all workflow resources in R.raw using reflection
     * Looks for fields matching workflow prefixes (tti_, iti_inpainting_, iti_editing_, ttv_, itv_)
     */
    private fun discoverWorkflowResources(): List<Int> {
        val resources = mutableListOf<Int>()
        val prefixes = PREFIX_TO_TYPE.keys

        try {
            // Use reflection to scan R.raw class fields
            val rawClass = R.raw::class.java
            for (field in rawClass.fields) {
                val fieldName = field.name
                // Check if field name matches any workflow prefix
                if (prefixes.any { fieldName.startsWith(it) }) {
                    try {
                        val resId = field.getInt(null)
                        resources.add(resId)
                    } catch (e: Exception) {
                        // Skip fields that can't be read
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Built-in workflows could not be listed: ${e.javaClass.simpleName}")
        }

        return resources
    }

    /**
     * Load user-uploaded workflows from internal storage
     */
    private fun loadUserWorkflows() {
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return

        val metadataArray = try {
            JSONArray(metadataJson)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "User workflow list could not be read: ${e.javaClass.simpleName}")
            return
        }
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)

        // Each workflow on its own, so one broken entry doesn't hide the others
        for (i in 0 until metadataArray.length()) {
            try {
                val metadata = metadataArray.getJSONObject(i)
                val id = metadata.getString("id")
                val name = metadata.getString("name")
                val description = metadata.optString("description", "")
                val typeStr = metadata.getString("type")
                val filename = metadata.getString("filename")

                val type = try {
                    WorkflowType.valueOf(typeStr)
                } catch (e: Exception) {
                    // Old-format workflow types (TTI_CHECKPOINT, TTI_UNET, ITI_CHECKPOINT, ITI_UNET, ITE_UNET, TTV_UNET, ITV_UNET)
                    // are no longer supported. These workflows will be silently skipped.
                    DebugLogger.w(TAG, "Skipping workflow ${Obfuscator.workflowName(name)}: unsupported type '$typeStr' (old format)")
                    continue
                }

                val file = File(dir, filename)
                if (!file.exists()) {
                    DebugLogger.w(TAG, "Skipping workflow ${Obfuscator.workflowName(name)}: file missing")
                    continue
                }

                val jsonContent = file.readText()
                val jsonObject = try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    null
                }
                val defaults = WorkflowDefaults.fromJson(jsonObject?.optJSONObject("defaults"))

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = false, defaults))
            } catch (e: Exception) {
                DebugLogger.e(TAG, "User workflow ${i + 1} could not be loaded: ${e.javaClass.simpleName}")
            }
        }
    }

    // Workflow JSON analysis - delegates to WorkflowJsonAnalyzer

    /** Parse workflow type from filename prefix */
    fun parseWorkflowType(filename: String): WorkflowType? =
        WorkflowJsonAnalyzer.parseWorkflowType(filename)

    /** Detect workflow type by analyzing JSON content */
    fun detectWorkflowType(jsonContent: String): WorkflowType? =
        WorkflowJsonAnalyzer.detectWorkflowType(jsonContent)

    /** Validate workflow JSON against type requirements */
    fun validateWorkflow(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflow(jsonContent, type)

    /** Validate workflow JSON by checking for required input keys */
    fun validateWorkflowKeys(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflowKeys(jsonContent, type)

    /** Extract all class_type values from workflow JSON */
    fun extractClassTypes(jsonContent: String): Result<Set<String>> =
        WorkflowJsonAnalyzer.extractClassTypes(jsonContent)

    /** Validate workflow nodes against available server nodes */
    fun validateNodesAgainstServer(
        workflowClassTypes: Set<String>,
        availableNodes: Set<String>
    ): List<String> =
        WorkflowJsonAnalyzer.validateNodesAgainstServer(workflowClassTypes, availableNodes)

    /**
     * Check if workflow name is already taken
     * @param name The name to check
     * @param excludeWorkflowId Optional workflow ID to exclude from the check (used when editing)
     */
    fun isWorkflowNameTaken(name: String, excludeWorkflowId: String? = null): Boolean {
        return workflows.any { workflow ->
            workflow.name.equals(name, ignoreCase = true) &&
            (excludeWorkflowId == null || workflow.id != excludeWorkflowId)
        }
    }

    /**
     * A name for a copy of a workflow: "<name> copy", then "<name> copy 2", 3… (in the user's language), the first not
     * taken. A copy of a copy counts on from the same base name instead of stacking suffixes.
     */
    fun generateUniqueDuplicateName(originalName: String): String {
        val marker = "\u0000"
        val number = 987654321
        fun pattern(template: String): Regex {
            val parts = template.replace(number.toString(), "\u0001").split(marker)
            val withNumber = parts.joinToString("(.+)") { Regex.escape(it).replace("\u0001", "\\E(\\d+)\\Q") }
            return Regex("^$withNumber$")
        }
        val single = pattern(applicationContext.getString(R.string.label_copy_name, marker))
        val numbered = pattern(applicationContext.getString(R.string.label_copy_name_numbered, marker, number))
        val base = numbered.matchEntire(originalName)?.groupValues?.get(1)
            ?: single.matchEntire(originalName)?.groupValues?.get(1)
            ?: originalName

        val first = applicationContext.getString(R.string.label_copy_name, base)
        if (!isWorkflowNameTaken(first)) return first
        var n = 2
        while (isWorkflowNameTaken(applicationContext.getString(R.string.label_copy_name_numbered, base, n))) n++
        return applicationContext.getString(R.string.label_copy_name_numbered, base, n)
    }


    /**
     * Duplicate a workflow (built-in or user) as a new user workflow.
     * The duplicate is always a user workflow that can be edited.
     */
    fun duplicateWorkflow(
        sourceWorkflowId: String,
        newName: String,
        newDescription: String
    ): Result<Workflow> {
        val sourceWorkflow = workflows.find { it.id == sourceWorkflowId }
            ?: return Result.failure(Exception("Source workflow not found"))

        // Validate new name
        val nameError = validateWorkflowName(newName)
        if (nameError != null) {
            return Result.failure(Exception(nameError))
        }

        // Check for duplicate name
        if (isWorkflowNameTaken(newName)) {
            return Result.failure(Exception("A workflow with this name already exists"))
        }

        // Generate filename for the new workflow
        val filename = generateFilename(sourceWorkflow.type, newName)

        // Parse and update the JSON with new name and description
        val json = try {
            JSONObject(sourceWorkflow.jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse source workflow"))
        }

        // Update metadata in the JSON
        if (json.has("name")) {
            json.put("name", newName)
        }
        if (json.has("description")) {
            json.put("description", newDescription)
        }

        val updatedJsonContent = json.toString(2)

        // Save the new workflow file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(updatedJsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Create the new workflow entry with a random UUID
        val newId = UuidUtils.generateRandomId()

        // Clean placeholders from copied defaults - they should be null so ViewModels use proper fallbacks
        val cleanedDefaults = sourceWorkflow.defaults.copy(
            samplerName = sourceWorkflow.defaults.samplerName?.takeIf { !isPlaceholder(it) },
            scheduler = sourceWorkflow.defaults.scheduler?.takeIf { !isPlaceholder(it) },
            negativePrompt = sourceWorkflow.defaults.negativePrompt?.takeIf { !isPlaceholder(it) }
        )

        val newWorkflow = Workflow(
            id = newId,
            name = newName,
            description = newDescription,
            jsonContent = updatedJsonContent,
            type = sourceWorkflow.type,
            isBuiltIn = false,
            defaults = cleanedDefaults
        )

        // Save metadata
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", newId)
            put("filename", filename)
            put("name", newName)
            put("description", newDescription)
            put("type", sourceWorkflow.type.name)
            put("defaults", WorkflowDefaults.toJson(newWorkflow.defaults))
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        // Add to in-memory list
        workflows.add(newWorkflow)

        DebugLogger.i(TAG, "Duplicated workflow: ${Obfuscator.workflowName(sourceWorkflow.name)} -> ${Obfuscator.workflowName(newName)} (id: $newId)")

        _workflowsVersion.value++
        return Result.success(newWorkflow)
    }

    /**
     * Export a workflow in ComfyUI's API format: only the nodes that run (subgraphs flattened), as generation sends
     * them but with the values of [exportableRoot].
     *
     * @return Result containing the JSON string in ComfyUI format, or error
     */
    fun exportWorkflowToComfyUIFormat(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))
        return try {
            val flat = Subgraphs.flatten(exportableRoot(workflow))
            DebugLogger.i(TAG, "Exported workflow in API format: ${Obfuscator.workflowName(workflow.name)}")
            Result.success((flat.optJSONObject("nodes") ?: flat).toString(2))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse workflow JSON"))
        }
    }

    /**
     * Export a workflow in ComfyUI's UI format (what its frontend opens with layout, groups, notes and subgraphs),
     * with the values of [exportableRoot] and the editor's layout. Needs the server's node definitions (the order of
     * the widgets' values comes from them).
     */
    fun exportWorkflowToComfyUIUiFormat(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))
        val registry = ConnectionManager.nodeTypeRegistry
        if (!registry.isPopulated()) return Result.failure(IllegalStateException("No node definitions"))
        return try {
            val exporter = ComfyUiExporter(registry::getNodeDefinition) { level ->
                WorkflowGraphPreparation.levelPositions(level, registry)
            }
            val document = exporter.export(exportableRoot(workflow))
            DebugLogger.i(TAG, "Exported workflow in UI format: ${Obfuscator.workflowName(workflow.name)}")
            Result.success(document.toString(2))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "UI format export failed: ${Obfuscator.error(e)}")
            Result.failure(e)
        }
    }

    /**
     * A workflow as it's exported for ComfyUI (stored form): every value the app fills (a placeholder) replaced by what
     * was last used for this workflow on this server, else the workflow's default; the edits made in Node values
     * applied. Images the app uploads keep their markers: the user picks them in ComfyUI.
     */
    private fun exportableRoot(workflow: Workflow): JSONObject {
        val saved = ConnectionManager.currentServerId?.let { workflowValuesStorage.loadValues(it, workflow.id) }
        val root = JSONObject(applyNodeAttributeEdits(Subgraphs.exposeJson(workflow.jsonContent), workflow.id))
        val placeholder = Regex("""^\{\{(\w+)\}\}$""")
        fun fill(nodes: JSONObject?) {
            if (nodes == null) return
            for (nodeId in nodes.keys()) {
                val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                for (key in inputs.keys().asSequence().toList()) {
                    val name = (inputs.opt(key) as? String)?.let { placeholder.find(it)?.groupValues?.get(1) } ?: continue
                    exportValueFor(name, saved, workflow)?.let { inputs.put(key, it) }
                }
            }
        }
        fill(root.optJSONObject("nodes") ?: root)
        Subgraphs.definitionsOf(root)?.let { definitions ->
            for (id in definitions.keys()) fill(definitions.optJSONObject(id)?.optJSONObject("nodes"))
        }
        Subgraphs.absorb(root)
        return root
    }

    /** The value an export gives the app's field [placeholder]: the last one used ([saved]), else the default. */
    private fun exportValueFor(placeholder: String, saved: WorkflowValues?, workflow: Workflow): Any? {
        CustomFields.PLACEHOLDER.find("{{$placeholder}}")?.let { match ->
            val field = workflow.customFields.find { it.id == match.groupValues[1] } ?: return null
            return saved?.customValues?.get(field.id)?.let { CustomFields.typedValue(field.type, it) }
                ?: CustomFields.typedValue(field.type, field.default) ?: field.default
        }
        ModelSlot.entries.find { it.placeholder == placeholder }?.let { slot ->
            return saved?.let(slot.saved)?.takeIf { it.isNotEmpty() }
        }
        val defaults = workflow.defaults
        return when (placeholder) {
            "width" -> saved?.width ?: defaults.width
            "height" -> saved?.height ?: defaults.height
            "steps" -> saved?.steps ?: defaults.steps
            "cfg" -> (saved?.cfg ?: defaults.cfg)?.toDouble()
            "sampler_name", "sampler" -> saved?.samplerName ?: defaults.samplerName
            "scheduler" -> saved?.scheduler ?: defaults.scheduler
            "denoise" -> saved?.denoise?.toDouble() ?: 1.0
            "positive_prompt", "positive_text" -> ""
            "negative_prompt", "negative_text" -> saved?.negativePrompt ?: defaults.negativePrompt ?: ""
            "seed" -> saved?.seed ?: 0L
            "batch_size" -> saved?.batchSize ?: 1
            "megapixels" -> (saved?.megapixels ?: defaults.megapixels)?.toDouble()
            "length" -> saved?.length ?: defaults.length
            "frame_rate", "fps" -> saved?.frameRate ?: defaults.frameRate
            "upscale_method" -> saved?.upscaleMethod
            "scale_by" -> saved?.scaleBy?.toDouble()
            "stop_at_clip_layer" -> saved?.stopAtClipLayer
            else -> null
        }?.takeUnless { it is String && isPlaceholder(it) }
    }

    /**
     * Export a workflow in internal format (preserves placeholders, groups, notes, etc.).
     * This exports the complete workflow JSON as stored internally.
     *
     * @return Result containing the JSON string in internal format, or error
     */
    fun exportWorkflowInternal(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        return try {
            // Parse and re-format with indentation for readability
            val json = JSONObject(workflow.jsonContent)
            DebugLogger.i(TAG, "Exported workflow in internal format: ${Obfuscator.workflowName(workflow.name)}")
            Result.success(json.toString(2))
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse workflow JSON"))
        }
    }

    /**
     * Get model options for a specific field based on the workflow's actual node type.
     * Returns node-specific options (e.g., only GGUF files for UnetLoaderGGUF)
     * or null if node type can't be determined (caller should fall back to global options).
     */
    fun getNodeSpecificOptionsForField(workflowId: String, fieldKey: String): List<String>? {
        val workflow = getWorkflowById(workflowId) ?: return null
        val placeholder = "{{${TemplateKeyRegistry.getPlaceholderForKey(fieldKey)}}}"
        val (classType, inputKey) = placeholderInputIndex(workflow.jsonContent)[placeholder] ?: return null
        // Node definitions are looked up on each call: the registry may be loaded after the index was built
        return ConnectionManager.nodeTypeRegistry.getNodeDefinition(classType)
            ?.inputs
            ?.find { it.name == inputKey }
            ?.options
    }

    /** Placeholder index per workflow JSON; screens query it for many fields right after loading a workflow. */
    private val placeholderIndexCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, Pair<String, String>>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Pair<String, String>>>?) =
                size > 16
        }
    )

    /**
     * Map each placeholder ("{{name}}") to the first node input holding it, as (class_type, input name).
     * Parsed once per workflow JSON instead of once per queried field.
     */
    private fun placeholderInputIndex(jsonContent: String): Map<String, Pair<String, String>> =
        placeholderIndexCache.getOrPut(jsonContent) {
            val index = mutableMapOf<String, Pair<String, String>>()
            try {
                // The nodes that hold them, inside subgraphs too
                val json = JSONObject(Subgraphs.flattenJson(jsonContent))
                val nodesJson = if (json.has("nodes")) json.optJSONObject("nodes") ?: json else json
                for (nodeId in nodesJson.keys()) {
                    val node = nodesJson.optJSONObject(nodeId) ?: continue
                    val inputs = node.optJSONObject("inputs") ?: continue
                    val classType = node.optString("class_type")
                    for (inputKey in inputs.keys()) {
                        val value = inputs.optString(inputKey, "")
                        if (value.startsWith("{{") && value.endsWith("}}")) {
                            index.putIfAbsent(value, classType to inputKey)
                        }
                    }
                }
            } catch (_: Exception) {
                // Invalid JSON: no node-specific options
            }
            index
        }

    /**
     * Generate a sanitized filename for export (without type prefix).
     */
    fun generateExportFilename(workflowName: String): String {
        val sanitized = workflowName
            .replace(Regex("[^a-zA-Z0-9 _\\-]"), "")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return "$sanitized.json"
    }

    /**
     * Generate filename from workflow type and name
     */
    fun generateFilename(type: WorkflowType, name: String): String {
        val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_"
        val sanitizedName = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "${prefix}${sanitizedName}.json"
    }

    /**
     * Check if a filename already exists in user workflows
     */
    fun isFilenameExists(filename: String): Boolean {
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        return File(dir, filename).exists()
    }

    /**
     * Validate workflow name format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String): String? {
        return ValidationUtils.validateWorkflowName(name, applicationContext)
    }

    /**
     * A workflow's JSON with its subgraph instances' values exposed ([Subgraphs]), each value a mapping names from
     * inside a subgraph shown by its instance.
     */
    private fun exposedForMapping(jsonContent: String, mappings: Collection<Pair<String, String>>): JSONObject {
        val json = JSONObject(jsonContent)
        if (Subgraphs.definitionsOf(json) != null) {
            mappings.forEach { (nodeId, inputKey) -> Subgraphs.promote(json, nodeId, inputKey) }
            Subgraphs.expose(json)
        }
        return json
    }

    /**
     * Add a user workflow with explicit type and field mappings
     * This is used for the new upload flow where the user selects the type
     */
    fun addUserWorkflowWithMapping(
        name: String,
        description: String,
        jsonContent: String,
        type: WorkflowType,
        fieldMappings: Map<String, Pair<String, String>>,  // fieldKey -> (nodeId, inputKey)
        customFields: List<CustomField>? = null  // made or changed while mapping; null keeps the file's
    ): Result<Workflow> {
        // Parse JSON; mappings and custom fields may name values an instance shows from its subgraph (or doesn't show
        // yet: promoted then), so its values are exposed
        val json = try {
            exposedForMapping(jsonContent, fieldMappings.values + customFields.orEmpty().flatMap { field -> field.targets.map { it.nodeId to it.input } })
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Custom fields made while mapping: their placeholders into the values they fill (before built-in fields
        // take theirs, so a value mapped to both ends with the built-in one)
        val fileFields = if (isWrapped) CustomFields.fieldsOf(jsonContent) else emptyList()
        val finalCustomFields = customFields?.let { CustomFields.place(nodesJson, it, fileFields) } ?: fileFields

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    // Only capture numeric and string values as defaults (not model names, but include prompts and params)
                    // Skip placeholder strings - they should be replaced with actual defaults by ViewModels
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate", "negative_text"
                        ))) {
                        if (!isPlaceholder(originalValue)) {
                            extractedDefaults[fieldKey] = originalValue
                        }
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    // Convert input key to proper placeholder name
                    // e.g., "text" -> "positive_prompt", "ckpt_name" -> "checkpoint"
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }

        // Build reverse map: placeholder name -> target node ID for all mapped fields
        val placeholderToTargetNodeId = fieldMappings.entries.associate { (fieldKey, mapping) ->
            TemplateKeyRegistry.getPlaceholderForKey(fieldKey) to mapping.first
        }

        // Remove stale and unmapped placeholders in a single pass:
        // - Stale: node still holds {{placeholder}} for a MAPPED field but is no longer the
        //   target node (left over when the user re-saves with a different node selected).
        //   replaceCommonPlaceholders() does a global String.replace, so a stale placeholder
        //   in an unrelated node (e.g. {{latent_upscale_model}} in UltralyticsDetectorProvider)
        //   would receive the wrong value and cause a server 400 error.
        // - Unmapped: node holds {{placeholder}} for an optional field that has no mapping at all.
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys
        val placeholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys().asSequence().toList()) {
                val value = inputs.optString(inputKey, "")
                val placeholderMatch = placeholderRegex.find(value) ?: continue
                val placeholderName = placeholderMatch.groupValues[1]

                // Case 1: placeholder belongs to a mapped field but this is not the target node
                val targetNodeId = placeholderToTargetNodeId[placeholderName]
                if (targetNodeId != null && nodeId != targetNodeId) {
                    inputs.put(inputKey, "")
                    continue
                }

                // Case 2: placeholder belongs to an unmapped optional field
                val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                if (placeholderName in unmappedOptionalKeys || fieldKey in unmappedOptionalKeys) {
                    inputs.put(inputKey, "")
                }
            }
        }

        // Create defaults object from extracted values
        // Field visibility is determined by placeholder detection, not by has* flags
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt()
        )

        // Create wrapped JSON with metadata and defaults
        val finalJson = JSONObject().apply {
            put("name", name)
            put("description", description)
            // Add defaults if any were extracted
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            // Include groups if present in original JSON
            if (isWrapped && json.has("groups")) {
                put("groups", json.getJSONArray("groups"))
            }
            // Include notes if present in original JSON
            if (isWrapped && json.has("notes")) {
                put("notes", json.getJSONArray("notes"))
            }
            // Include manual_placement if present in original JSON
            if (isWrapped && json.has("manual_placement")) {
                put("manual_placement", json.getBoolean("manual_placement"))
            }
            // Custom fields and labels of built-in fields go with the workflow (spec §18)
            val keptFields = finalCustomFields.mapNotNull { field ->
                // A value a built-in field took leaves the custom one; a field left with none goes
                val kept = field.targets.filter { t -> nodesJson.optJSONObject(t.nodeId)?.optJSONObject("inputs")?.opt(t.input) == field.placeholder }
                if (kept.isEmpty()) null else field.copy(targets = kept)
            }
            if (keptFields.isNotEmpty()) put(CustomFields.FIELDS_KEY, CustomFields.toJson(keptFields))
            if (isWrapped && json.has(CustomFields.LABELS_KEY)) put(CustomFields.LABELS_KEY, json.getJSONObject(CustomFields.LABELS_KEY))
            if (isWrapped && json.has(Subgraphs.KEY)) put(Subgraphs.KEY, json.getJSONObject(Subgraphs.KEY))
            // Store field mappings for reference
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Values shown on subgraph instances back into their definitions
        Subgraphs.absorb(finalJson)

        // Generate filename
        val filename = generateFilename(type, name)

        // Check for duplicate filename
        if (isFilenameExists(filename)) {
            return Result.failure(Exception("Workflow with this name already exists"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata with UUID
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", filename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson.toString(2), type, isBuiltIn = false, defaults)
        workflows.add(workflow)

        _workflowsVersion.value++
        return Result.success(workflow)
    }

    /**
     * Update an existing user workflow's JSON structure with new field mappings.
     * Preserves the workflow's name, description, and type.
     * @return Result with the updated Workflow or error message
     */
    fun updateUserWorkflowWithMapping(
        workflowId: String,
        jsonContent: String,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ): Result<Workflow> {
        // Find existing workflow
        val existingWorkflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        if (existingWorkflow.isBuiltIn) {
            return Result.failure(Exception("Cannot edit built-in workflows"))
        }

        // Parse JSON; mappings may name values an instance shows from its subgraph, so its values are exposed
        val json = try {
            exposedForMapping(jsonContent, fieldMappings.values)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null
        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    // Skip placeholder strings - they should be replaced with actual defaults by ViewModels
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate", "negative_text"
                        ))) {
                        if (!isPlaceholder(originalValue)) {
                            extractedDefaults[fieldKey] = originalValue
                        }
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }

        // Build reverse map: placeholder name -> target node ID for all mapped fields
        val placeholderToTargetNodeId = fieldMappings.entries.associate { (fieldKey, mapping) ->
            TemplateKeyRegistry.getPlaceholderForKey(fieldKey) to mapping.first
        }

        // Remove stale and unmapped placeholders in a single pass:
        // - Stale: node still holds {{placeholder}} for a MAPPED field but is no longer the
        //   target node (left over when the user re-saves with a different node selected).
        //   replaceCommonPlaceholders() does a global String.replace, so a stale placeholder
        //   in an unrelated node (e.g. {{latent_upscale_model}} in UltralyticsDetectorProvider)
        //   would receive the wrong value and cause a server 400 error.
        // - Unmapped: node holds {{placeholder}} for an optional field that has no mapping at all.
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(existingWorkflow.type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys
        val placeholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys().asSequence().toList()) {
                val value = inputs.optString(inputKey, "")
                val placeholderMatch = placeholderRegex.find(value) ?: continue
                val placeholderName = placeholderMatch.groupValues[1]

                // Case 1: placeholder belongs to a mapped field but this is not the target node
                val targetNodeId = placeholderToTargetNodeId[placeholderName]
                if (targetNodeId != null && nodeId != targetNodeId) {
                    inputs.put(inputKey, "")
                    continue
                }

                // Case 2: placeholder belongs to an unmapped optional field
                val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                if (placeholderName in unmappedOptionalKeys || fieldKey in unmappedOptionalKeys) {
                    inputs.put(inputKey, "")
                }
            }
        }

        // Create defaults object from extracted values
        // Field visibility is determined by placeholder detection, not by has* flags
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt()
        )

        // Create wrapped JSON with original metadata preserved
        val finalJson = JSONObject().apply {
            put("name", existingWorkflow.name)
            put("description", existingWorkflow.description)
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            // Include groups if present in original JSON
            if (isWrapped && json.has("groups")) {
                put("groups", json.getJSONArray("groups"))
            }
            // Include notes if present in original JSON
            if (isWrapped && json.has("notes")) {
                put("notes", json.getJSONArray("notes"))
            }
            // Include manual_placement if present in original JSON
            if (isWrapped && json.has("manual_placement")) {
                put("manual_placement", json.getBoolean("manual_placement"))
            }
            // Custom fields and labels of built-in fields go with the workflow (spec §18)
            if (isWrapped && json.has(CustomFields.FIELDS_KEY)) put(CustomFields.FIELDS_KEY, json.getJSONArray(CustomFields.FIELDS_KEY))
            if (isWrapped && json.has(CustomFields.LABELS_KEY)) put(CustomFields.LABELS_KEY, json.getJSONObject(CustomFields.LABELS_KEY))
            if (isWrapped && json.has(Subgraphs.KEY)) put(Subgraphs.KEY, json.getJSONObject(Subgraphs.KEY))
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Values shown on subgraph instances back into their definitions
        Subgraphs.absorb(finalJson)

        // Get filename from preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null)
            ?: return Result.failure(Exception("Workflow metadata not found"))

        var filename: String? = null
        val metadataArray = JSONArray(metadataJson)
        for (i in 0 until metadataArray.length()) {
            val metadata = metadataArray.getJSONObject(i)
            if (metadata.getString("id") == workflowId) {
                filename = metadata.getString("filename")
                break
            }
        }

        if (filename == null) {
            return Result.failure(Exception("Workflow file not found"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save: ${e.message}"))
        }

        // Update in-memory list
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex >= 0) {
            val updatedWorkflow = existingWorkflow.copy(
                jsonContent = finalJson.toString(2),
                defaults = defaults
            )
            workflows[workflowIndex] = updatedWorkflow
            _workflowsVersion.value++
            return Result.success(updatedWorkflow)
        }

        return Result.failure(Exception("Failed to update workflow list"))
    }

    /**
     * Add a user workflow
     * Auto-detects workflow type from content and wraps raw ComfyUI workflows if needed
     * @return Result with the created Workflow or error message
     */
    fun addUserWorkflow(
        filename: String,
        name: String,
        description: String,
        jsonContent: String
    ): Result<Workflow> {
        // Parse JSON to check format
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message ?: ""}"))
        }

        // Check if already wrapped (has "nodes" object at top level)
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        // Auto-detect type from content
        val type = detectWorkflowType(jsonContent)
            ?: return Result.failure(Exception("Cannot determine workflow type"))

        // Wrap if needed
        val finalJson = if (isWrapped) {
            jsonContent
        } else {
            // Wrap raw workflow: {"name": "...", "description": "...", "nodes": {...}}
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("nodes", json)
            }.toString(2)
        }

        // Validate workflow - first try placeholder-based validation (for our wrapped format),
        // then try key-based validation (for raw ComfyUI workflows)
        val placeholderValidation = validateWorkflow(finalJson, type)
        val keyValidation = if (placeholderValidation !is WorkflowValidationResult.Success) {
            validateWorkflowKeys(finalJson, type)
        } else {
            placeholderValidation
        }

        // Use key validation result if placeholder validation failed
        val validationResult = if (placeholderValidation is WorkflowValidationResult.Success) {
            placeholderValidation
        } else {
            keyValidation
        }

        if (validationResult !is WorkflowValidationResult.Success) {
            val errorMessage = when (validationResult) {
                is WorkflowValidationResult.InvalidJson -> validationResult.message
                is WorkflowValidationResult.MissingPlaceholders ->
                    "Missing placeholders: ${validationResult.missing.joinToString(", ")}"
                is WorkflowValidationResult.MissingKeys ->
                    "Missing inputs: ${validationResult.missing.joinToString(", ")}"
                else -> "Unknown error"
            }
            return Result.failure(Exception(errorMessage))
        }

        // Generate filename with correct prefix if not already prefixed
        val prefixedFilename = if (parseWorkflowType(filename) != null) {
            filename
        } else {
            // Add prefix based on detected type
            val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_"
            prefix + filename.substringBeforeLast(".") + ".json"
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, prefixedFilename)
        try {
            file.writeText(finalJson)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", prefixedFilename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson, type, isBuiltIn = false)
        workflows.add(workflow)

        _workflowsVersion.value++
        return Result.success(workflow)
    }

    /**
     * Delete a user workflow
     */
    fun deleteUserWorkflow(workflowId: String): Boolean {
        val workflow = workflows.find { it.id == workflowId }
        if (workflow == null || workflow.isBuiltIn) return false

        // Remove from list
        workflows.removeAll { it.id == workflowId }

        // Remove file
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)
            var filename: String? = null
            val newArray = JSONArray()

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    filename = metadata.getString("filename")
                } else {
                    newArray.put(metadata)
                }
            }

            // Delete file
            filename?.let {
                val file = File(applicationContext.filesDir, "$USER_WORKFLOWS_DIR/$it")
                file.delete()
            }

            // Update preferences
            prefs.edit().putString(USER_WORKFLOWS_KEY, newArray.toString()).apply()

            if (workflowId in disabledWorkflowIds) {
                disabledWorkflowIds = disabledWorkflowIds - workflowId
                AppSettings.setDisabledWorkflowIds(applicationContext, disabledWorkflowIds)
            }

            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Clear all user-uploaded workflows
     */
    fun clearAllUserWorkflows(): Boolean {
        try {
            // Remove all user workflows from the list
            workflows.removeAll { !it.isBuiltIn }

            // Delete all files in user workflows directory
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            // Clear preferences
            val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(USER_WORKFLOWS_KEY).apply()

            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Update user workflow metadata (name and description)
     */
    fun updateUserWorkflowMetadata(workflowId: String, name: String, description: String): Boolean {
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex == -1) return false

        val workflow = workflows[workflowIndex]
        if (workflow.isBuiltIn) return false

        // Update in list
        workflows[workflowIndex] = workflow.copy(name = name, description = description)

        // Update in preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    metadata.put("name", name)
                    metadata.put("description", description)
                    break
                }
            }

            prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()
            _workflowsVersion.value++
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get all workflows
     */
    fun getAllWorkflows(): List<Workflow> = workflows.toList()

    /**
     * Get workflows by type
     */
    fun getWorkflowsByType(type: WorkflowType): List<Workflow> {
        return workflows.filter { it.type == type }.sortedBy { it.name.lowercase() }
    }

    /**
     * Get the workflows of a type the user hasn't switched off, i.e. those offered on the
     * generation screens
     */
    fun getEnabledWorkflowsByType(type: WorkflowType): List<Workflow> =
        getWorkflowsByType(type).filter { isWorkflowEnabled(it.id) }

    /**
     * Check if a workflow can be deleted (not built-in)
     */
    fun canDeleteWorkflow(workflowId: String): Boolean {
        return workflows.find { it.id == workflowId }?.isBuiltIn == false
    }

    /**
     * Get list of text-to-image workflow names for dropdown
     */
    fun getTtiWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTI }.map { it.name }
    }

    /**
     * Get list of Image-to-image inpainting workflow names for dropdown
     */
    fun getItiInpaintingWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_INPAINTING }.map { it.name }
    }

    /**
     * Get list of Image-to-image editing workflow names for dropdown
     */
    fun getItiEditingWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_EDITING }.map { it.name }
    }

    /**
     * Get list of text-to-video workflow names for dropdown
     */
    fun getTtvWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTV }.map { it.name }
    }

    /**
     * Get list of image-to-video workflow names for dropdown
     */
    fun getItvWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITV }.map { it.name }
    }

    /**
     * Get workflow by name
     */
    fun getWorkflowByName(name: String): Workflow? {
        return workflows.find { it.name == name }
    }

    /**
     * Get workflow by ID
     */
    fun getWorkflowById(id: String): Workflow? {
        return workflows.find { it.id == id }
    }

    /**
     * Get workflow defaults by workflow name
     */
    fun getWorkflowDefaults(workflowName: String): WorkflowDefaults? {
        return getWorkflowByName(workflowName)?.defaults
    }

    /**
     * Get workflow defaults by workflow ID
     */
    fun getWorkflowDefaultsById(workflowId: String): WorkflowDefaults? {
        return getWorkflowById(workflowId)?.defaults
    }

    /**
     * Get all placeholders (template variables) used in a workflow.
     * This scans the workflow JSON for {{placeholder}} patterns and returns
     * the set of placeholder names (without the braces).
     *
     * @param workflowId The workflow ID to analyze
     * @return Set of placeholder names found in the workflow, or empty set if workflow not found
     */
    fun getWorkflowPlaceholders(workflowId: String): Set<String> {
        val workflow = getWorkflowById(workflowId) ?: return emptySet()
        return extractPlaceholdersFromJson(workflow.jsonContent)
    }

    /**
     * Get all placeholders (template variables) used in a workflow by name.
     *
     * @param workflowName The workflow name to analyze
     * @return Set of placeholder names found in the workflow, or empty set if workflow not found
     */
    fun getWorkflowPlaceholdersByName(workflowName: String): Set<String> {
        val workflow = getWorkflowByName(workflowName) ?: return emptySet()
        return extractPlaceholdersFromJson(workflow.jsonContent)
    }

    /**
     * Extract all placeholders from a workflow JSON string.
     */
    private fun extractPlaceholdersFromJson(jsonContent: String): Set<String> {
        val placeholderRegex = """\{\{(\w+)\}\}""".toRegex()
        return placeholderRegex.findAll(jsonContent)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Escape special characters in a string for safe JSON insertion
     */
    private fun escapeForJson(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }

    /**
     * Replace all common parameter placeholders in a workflow JSON.
     *
     * This is the SINGLE SOURCE OF TRUTH for common placeholder handling.
     * ALL prepare*WorkflowById functions MUST call this to ensure consistent
     * placeholder replacement across all workflow types.
     *
     * IMPORTANT: Any workflow can use any placeholder. Field visibility is
     * controlled by WorkflowCapabilities, not by screen type. This function
     * must handle ALL possible parameter placeholders uniformly.
     *
     * @param json The workflow JSON string
     * @param seed Random seed for generation
     * @param steps Number of sampling steps
     * @param cfg CFG scale value
     * @param samplerName Sampler algorithm name
     * @param scheduler Scheduler algorithm name
     * @param denoise Denoising strength (0.0-1.0)
     * @param batchSize Number of images to generate
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param megapixels Target megapixels for resolution
     * @param length Video frame count
     * @param frameRate Video FPS
     * @param upscaleMethod Upscaling algorithm name
     * @param scaleBy Upscale factor
     * @param stopAtClipLayer CLIP skip value
     * @return JSON with all matching placeholders replaced
     */
    private fun replaceCommonPlaceholders(
        json: String,
        seed: Long,
        steps: Int? = null,
        cfg: Float? = null,
        samplerName: String? = null,
        scheduler: String? = null,
        denoise: Float? = null,
        batchSize: Int? = null,
        width: Int? = null,
        height: Int? = null,
        megapixels: Float? = null,
        length: Int? = null,
        frameRate: Int? = null,
        upscaleMethod: String? = null,
        scaleBy: Float? = null,
        stopAtClipLayer: Int? = null
    ): String {
        var result = json
        // Seed - always required, support both {{seed}} and {{noise_seed}} placeholders
        result = result.replace("{{seed}}", seed.toString())
        result = result.replace("{{noise_seed}}", seed.toString())
        // Also handle legacy "seed": 0 and "noise_seed": 0 patterns
        result = result.replace("\"seed\": 0", "\"seed\": $seed")
        result = result.replace("\"noise_seed\": 0", "\"noise_seed\": $seed")
        // Optional parameters - only replace if provided
        steps?.let { result = result.replace("{{steps}}", it.toString()) }
        cfg?.let { result = result.replace("{{cfg}}", it.toString()) }
        samplerName?.let { result = result.replace("{{sampler_name}}", it) }
        scheduler?.let { result = result.replace("{{scheduler}}", it) }
        denoise?.let { result = result.replace("{{denoise}}", it.toString()) }
        batchSize?.let { result = result.replace("{{batch_size}}", it.toString()) }
        width?.let { result = result.replace("{{width}}", it.toString()) }
        height?.let { result = result.replace("{{height}}", it.toString()) }
        megapixels?.let { result = result.replace("{{megapixels}}", it.toString()) }
        length?.let { result = result.replace("{{length}}", it.toString()) }
        frameRate?.let { result = result.replace("{{frame_rate}}", it.toString()) }
        upscaleMethod?.let { result = result.replace("{{upscale_method}}", it) }
        scaleBy?.let { result = result.replace("{{scale_by}}", it.toString()) }
        stopAtClipLayer?.let { result = result.replace("{{stop_at_clip_layer}}", it.toString()) }
        return result
    }

    /**
     * Apply node attribute edits to a workflow JSON.
     * This modifies the input values in specific nodes based on user edits.
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @param edits Map of nodeId -> (inputName -> value)
     * @return Modified workflow JSON with edits applied
     */
    fun applyNodeAttributeEdits(
        workflowJson: String,
        edits: Map<String, Map<String, Any>>
    ): String {
        if (edits.isEmpty()) return workflowJson

        return try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: json
            val definitions = Subgraphs.definitionsOf(json)

            for ((nodeId, nodeEdits) in edits) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                for ((inputName, value) in nodeEdits) {
                    // A value inside a subgraph that its instance doesn't show (changed inside it, spec §19.2.5) goes
                    // into the definition, as a value the instance shows does when it's taken back
                    if (!inputs.has(inputName) && definitions != null && Subgraphs.isPromotedKey(inputName) &&
                        definitions.has(node.optString("class_type"))
                    ) {
                        Subgraphs.setValueAt(definitions, node.optString("class_type"), inputName, if (value is Float) value.toDouble() else value)
                        continue
                    }
                    // Only update if the input exists and is not a connection
                    if (inputs.has(inputName)) {
                        val currentValue = inputs.opt(inputName)
                        // Don't overwrite connections (JSONArray format)
                        if (currentValue !is JSONArray) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value)
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                        }
                    }
                }
            }

            json.toString()
        } catch (e: Exception) {
            workflowJson
        }
    }

    // Bypass handling - delegates to BypassNodeResolver

    /**
     * Apply bypass logic to a workflow JSON before submission to ComfyUI API.
     * Delegates to BypassNodeResolver.
     */
    fun applyBypassedNodes(workflowJson: String): String =
        BypassNodeResolver.applyBypassedNodes(workflowJson)

    // LoRA chain injection - delegates to LoraInjectionUtils

    /**
     * Inject a chain of LoRA loaders into a workflow JSON.
     * Delegates to LoraInjectionUtils.
     */
    fun injectLoraChain(
        workflowJson: String,
        loraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        workflowType: WorkflowType
    ): String = LoraInjectionUtils.injectLoraChain(workflowJson, loraChain, workflowType)

    /**
     * Inject additional LoRAs into a video workflow.
     * Delegates to LoraInjectionUtils.
     */
    fun injectAdditionalVideoLoras(
        workflowJson: String,
        additionalLoraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        isHighNoise: Boolean
    ): String = LoraInjectionUtils.injectAdditionalVideoLoras(workflowJson, additionalLoraChain, isHighNoise)

    /**
     * Apply node attribute edits from storage to the processed workflow JSON.
     * This allows users to customize node attributes via the Workflow Editor
     * that will be applied when generating.
     *
     * @param processedJson The workflow JSON after placeholder replacement
     * @param workflowId The workflow ID to load edits for
     * @return The JSON with node attribute edits applied
     */
    private fun applyNodeAttributeEdits(processedJson: String, workflowId: String): String {
        // Load edits from storage
        val serverId = sh.hnet.comfychair.connection.ConnectionManager.currentServerId ?: run {
            DebugLogger.w(TAG, "applyNodeAttributeEdits: No serverId (ConnectionManager.currentServerId is null)")
            return processedJson
        }
        val values = workflowValuesStorage.loadValues(serverId, workflowId) ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val editsJson = values.nodeAttributeEdits ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No node edits in saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val edits = NodeAttributeEdits.fromJson(editsJson)

        if (edits.isEmpty()) return processedJson

        // Parse the workflow JSON
        val json = try {
            JSONObject(processedJson)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse workflow JSON for edits: ${Obfuscator.error(e)}")
            return processedJson
        }

        // Get the nodes object (could be at root level or under "nodes" key)
        val nodesJson = if (json.has("nodes")) {
            json.optJSONObject("nodes") ?: json
        } else {
            json
        }

        // Template inputs, to skip edits of values the app fills in at generation time. The editor
        // no longer offers these, but older versions saved such edits (e.g. a stale image filename).
        val templateNodes = getWorkflowById(workflowId)?.jsonContent?.let { content ->
            try {
                JSONObject(Subgraphs.exposeJson(content)).let { it.optJSONObject("nodes") ?: it }
            } catch (e: Exception) {
                null
            }
        }

        // Apply edits to each node
        var modified = false
        edits.edits.forEach { (nodeId, nodeEdits) ->
            val node = nodesJson.optJSONObject(nodeId)
            if (node != null) {
                val inputs = node.optJSONObject("inputs")
                if (inputs != null) {
                    val templateInputs = templateNodes?.optJSONObject(nodeId)?.optJSONObject("inputs")
                    nodeEdits.forEach { (inputName, value) ->
                        if (TemplateKeyRegistry.isAppManagedValue(templateInputs?.opt(inputName))) {
                            DebugLogger.d(TAG, "Skipped edit of app-managed input: node $nodeId, $inputName")
                            return@forEach
                        }
                        // Only apply if input exists (don't add new inputs)
                        if (inputs.has(inputName)) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value)
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                            modified = true
                            DebugLogger.d(TAG, "Applied edit: node $nodeId, $inputName (${value::class.simpleName})")
                        }
                    }
                }
            }
        }

        return if (modified) json.toString() else processedJson
    }

    /**
     * Images uploaded to the server for a workflow (server-side file names).
     */
    data class WorkflowImages(
        val source: String? = null,
        val reference1: String? = null,
        val reference2: String? = null,
        /** The uploaded source video (Video to Video) */
        val video: String? = null
    )

    /**
     * Prepare a workflow for submission: every placeholder filled from [params] (prompts, all model
     * selections, all parameters), uploaded [images] referenced, unused reference image nodes removed,
     * node edits from the Workflow Editor and bypassed nodes applied, and the LoRA chains injected.
     *
     * The single preparation for all screens: any workflow can use any placeholder, so nothing here
     * depends on the screen. Returns null if the workflow doesn't exist.
     */
    fun prepareWorkflow(
        workflowId: String,
        params: GenerationParams,
        images: WorkflowImages = WorkflowImages()
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing workflow ${Obfuscator.workflowName(workflow.name)} (${workflow.type})")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(params.positivePrompt)}, steps: ${params.steps}, cfg: ${params.cfg}, " +
            "sampler: ${params.sampler}, scheduler: ${params.scheduler}")
        ModelSlot.entries.forEach { slot ->
            params.selection(slot).takeIf { it.isNotEmpty() }?.let {
                DebugLogger.d(TAG, "${slot.name}: ${Obfuscator.modelName(it)}")
            }
        }
        images.source?.let { DebugLogger.d(TAG, "Source image: ${Obfuscator.filename(it)}") }

        // Values shown on subgraph instances are inputs there, as the fields and edits name them
        var json = Subgraphs.exposeJson(workflow.jsonContent)

        // Prompts and models
        json = json.replace("{{positive_prompt}}", escapeForJson(params.positivePrompt))
        json = json.replace("{{negative_prompt}}", escapeForJson(params.negativePrompt))
        ModelSlot.entries.forEach { slot ->
            json = json.replace("{{${slot.placeholder}}}", escapeForJson(params.selection(slot)))
        }

        // Uploaded images, as placeholders or as the templates' fixed file names
        images.source?.let { name ->
            val annotated = "${escapeForJson(name)} [input]"
            json = json.replace("{{image_filename}}", annotated)
            json = json.replace("uploaded_image.png [input]", annotated)
        }
        images.reference1?.let { name ->
            val annotated = "${escapeForJson(name)} [input]"
            json = json.replace("{{reference_image_1}}", annotated)
            json = json.replace("reference_image_1.png [input]", annotated)
        }
        images.reference2?.let { name ->
            val annotated = "${escapeForJson(name)} [input]"
            json = json.replace("{{reference_image_2}}", annotated)
            json = json.replace("reference_image_2.png [input]", annotated)
        }
        images.video?.let { name ->
            json = json.replace("{{video_filename}}", "${escapeForJson(name)} [input]")
        }

        // Parameters
        json = replaceCommonPlaceholders(
            json = json,
            seed = if (params.randomSeed) (0..999999999999).random() else (params.seed.toLongOrNull() ?: 0),
            steps = params.steps.toIntOrNull(),
            cfg = params.cfg.toFloatOrNull(),
            samplerName = params.sampler,
            scheduler = params.scheduler,
            denoise = params.denoise.toFloatOrNull(),
            batchSize = params.batchSize.toIntOrNull(),
            width = params.width.toIntOrNull(),
            height = params.height.toIntOrNull(),
            megapixels = params.megapixels.toFloatOrNull(),
            length = params.length.toIntOrNull(),
            frameRate = params.fps.toIntOrNull(),
            upscaleMethod = params.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = params.scaleBy.toFloatOrNull(),
            stopAtClipLayer = params.stopAtClipLayer.toIntOrNull()
        )

        // Custom fields: each value into every value it fills, as its type
        json = CustomFields.apply(json, workflow.customFields, params.customValues)

        json = applyNodeAttributeEdits(json, workflow.id)
        // From here on the nodes that run: subgraphs flattened, their values back inside
        json = Subgraphs.flattenJson(json)
        json = removeUnusedReferenceImages(json, images)
        json = applyBypassedNodes(json)

        // LoRA chains: the main chain after the model loader, the high/low noise chains after a
        // dual-model workflow's own LoRAs (no change for workflows without that structure)
        if (params.loraChain.isNotEmpty()) {
            json = injectLoraChain(json, params.loraChain, workflow.type)
        }
        if (params.highnoiseLoraChain.isNotEmpty()) {
            json = injectAdditionalVideoLoras(json, params.highnoiseLoraChain, isHighNoise = true)
        }
        if (params.lownoiseLoraChain.isNotEmpty()) {
            json = injectAdditionalVideoLoras(json, params.lownoiseLoraChain, isHighNoise = false)
        }
        return json
    }

    /**
     * Remove reference image nodes (LoadImage with a reference image placeholder) that got no image,
     * together with the inputs connected to them, so the workflow runs without the optional references.
     */
    private fun removeUnusedReferenceImages(processedJson: String, images: WorkflowImages): String {
        if (!processedJson.contains("reference_image_")) return processedJson
        return try {
            val json = JSONObject(processedJson)
            val nodes = json.optJSONObject("nodes") ?: return processedJson

            val nodesToRemove = mutableSetOf<String>()
            for (nodeId in nodes.keys()) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                if (node.optString("class_type") != "LoadImage") continue
                val imageName = node.optJSONObject("inputs")?.optString("image", "") ?: continue
                val isRef1 = imageName == "{{reference_image_1}}" || imageName == "reference_image_1.png [input]"
                val isRef2 = imageName == "{{reference_image_2}}" || imageName == "reference_image_2.png [input]"
                if ((isRef1 && images.reference1 == null) || (isRef2 && images.reference2 == null)) {
                    nodesToRemove.add(nodeId)
                }
            }
            if (nodesToRemove.isEmpty()) return processedJson
            nodesToRemove.forEach { nodes.remove(it) }

            // Connections are [nodeId, outputIndex] arrays
            for (nodeId in nodes.keys()) {
                val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                val connected = inputs.keys().asSequence().filter { key ->
                    val value = inputs.opt(key)
                    value is JSONArray && value.length() == 2 && value.optString(0) in nodesToRemove
                }.toList()
                connected.forEach { inputs.remove(it) }
            }
            json.toString()
        } catch (e: Exception) {
            processedJson
        }
    }

    /**
     * Check if a value is a template placeholder (e.g., "{{sampler_name}}")
     * These should not be extracted as default values.
     */
    private fun isPlaceholder(value: Any?): Boolean {
        return value is String && value.startsWith("{{") && value.endsWith("}}")
    }

}
