package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException

/**
 * QueryFragment - Main screen for interacting with ComfyUI
 */
class QueryFragment : Fragment() {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var imagePreview: ImageView
    private lateinit var placeholderIcon: ImageView
    private lateinit var imageInstructionText: android.widget.TextView
    private lateinit var promptInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var promptInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var settingsButton: Button

    // Modal bottom sheet for configuration
    private var configBottomSheet: BottomSheetDialog? = null
    private lateinit var configModeToggle: com.google.android.material.button.MaterialButtonToggleGroup
    // Checkpoint mode UI elements
    private lateinit var workflowDropdown: AutoCompleteTextView
    private lateinit var checkpointDropdown: AutoCompleteTextView
    private lateinit var widthInput: TextInputEditText
    private lateinit var heightInput: TextInputEditText
    private lateinit var stepsInput: TextInputEditText
    // Diffusers mode UI elements
    private lateinit var diffusersWorkflowDropdown: AutoCompleteTextView
    private lateinit var diffuserDropdown: AutoCompleteTextView
    private lateinit var diffusersWidthInput: TextInputEditText
    private lateinit var diffusersHeightInput: TextInputEditText
    private lateinit var diffusersStepsInput: TextInputEditText

    // Workflow manager
    private lateinit var workflowManager: WorkflowManager

    // ComfyUI client
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // Configuration values
    private val availableCheckpoints = mutableListOf<String>()
    private val availableDiffusers = mutableListOf<String>()
    // Track which mode is currently active
    private var isCheckpointMode = true
    // Flag to prevent auto-save during initialization
    private var isLoadingConfiguration = false

    // Track current prompt ID
    private var currentPromptId: String? = null

    // Store current generated image
    private var currentBitmap: Bitmap? = null

    // Filename for persisting last generated image
    private val LAST_IMAGE_FILENAME = "last_generated_image.png"

    // Activity result launcher for "Save as..."
    private val saveImageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { saveImageToUri(it) }
    }

    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"

        // SharedPreferences constants for configuration preservation
        private const val PREFS_NAME = "QueryFragmentPrefs"
        private const val PREF_IS_CHECKPOINT_MODE = "isCheckpointMode"
        private const val PREF_PROMPT = "prompt"

        // Checkpoint mode preferences
        private const val PREF_CHECKPOINT_WORKFLOW = "checkpointWorkflow"
        private const val PREF_CHECKPOINT_MODEL = "checkpointModel"
        private const val PREF_CHECKPOINT_WIDTH = "checkpointWidth"
        private const val PREF_CHECKPOINT_HEIGHT = "checkpointHeight"
        private const val PREF_CHECKPOINT_STEPS = "checkpointSteps"

        // Diffusers mode preferences
        private const val PREF_DIFFUSERS_WORKFLOW = "diffusersWorkflow"
        private const val PREF_DIFFUSERS_MODEL = "diffusersModel"
        private const val PREF_DIFFUSERS_WIDTH = "diffusersWidth"
        private const val PREF_DIFFUSERS_HEIGHT = "diffusersHeight"
        private const val PREF_DIFFUSERS_STEPS = "diffusersSteps"

        fun newInstance(hostname: String, port: Int): QueryFragment {
            val fragment = QueryFragment()
            val args = Bundle()
            args.putString(ARG_HOSTNAME, hostname)
            args.putInt(ARG_PORT, port)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get connection information from arguments
        arguments?.let {
            hostname = it.getString(ARG_HOSTNAME) ?: ""
            port = it.getInt(ARG_PORT, 8188)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_query, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets - only apply padding for system bars
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Don't apply any padding - the layout will handle resizing naturally
            v.setPadding(0, 0, 0, 0)

            insets
        }

        // Initialize ComfyUI client
        comfyUIClient = ComfyUIClient(hostname, port)

        // Initialize workflow manager
        workflowManager = WorkflowManager(requireContext())

        // Initialize UI components
        initializeViews(view)

        // Initialize configuration bottom sheet
        initializeConfigBottomSheet()

        // Setup dropdowns
        setupDropdowns()

        // Setup button listeners
        setupButtonListeners()

        // Setup image view listeners
        setupImageViewListeners()

        // Connect to server
        connectToServer()

        // Restore last generated image if available
        restoreLastGeneratedImage()

        // Load saved configuration (after all UI elements are initialized)
        loadConfiguration()

        // Setup auto-save listeners
        setupAutoSaveListeners()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        imagePreview = view.findViewById(R.id.imagePreview)
        placeholderIcon = view.findViewById(R.id.placeholderIcon)
        imageInstructionText = view.findViewById(R.id.imageInstructionText)
        promptInputLayout = view.findViewById(R.id.promptInputLayout)
        promptInput = view.findViewById(R.id.promptInput)
        generateButton = view.findViewById(R.id.generateButton)
        settingsButton = view.findViewById(R.id.settingsButton)

        setupTopAppBar()
    }

    private fun setupTopAppBar() {
        topAppBar.setNavigationOnClickListener {
            requireActivity().finish()
        }
    }

    private fun initializeConfigBottomSheet() {
        configBottomSheet = BottomSheetDialog(requireContext())

        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_config, null)
        configBottomSheet?.setContentView(bottomSheetView)

        // Initialize checkpoint mode UI elements
        workflowDropdown = bottomSheetView.findViewById(R.id.workflowDropdown)
        checkpointDropdown = bottomSheetView.findViewById(R.id.checkpointDropdown)
        widthInput = bottomSheetView.findViewById(R.id.widthInput)
        heightInput = bottomSheetView.findViewById(R.id.heightInput)
        stepsInput = bottomSheetView.findViewById(R.id.stepsInput)

        // Initialize diffusers mode UI elements
        diffusersWorkflowDropdown = bottomSheetView.findViewById(R.id.diffusersWorkflowDropdown)
        diffuserDropdown = bottomSheetView.findViewById(R.id.diffuserDropdown)
        diffusersWidthInput = bottomSheetView.findViewById(R.id.diffusersWidthInput)
        diffusersHeightInput = bottomSheetView.findViewById(R.id.diffusersHeightInput)
        diffusersStepsInput = bottomSheetView.findViewById(R.id.diffusersStepsInput)

        // Set up segmented button toggle
        configModeToggle = bottomSheetView.findViewById(R.id.configModeToggle)
        val checkpointContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.checkpointContent)
        val diffusersContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.diffusersContent)

        // Set initial mode (will be updated by loadConfiguration later)
        configModeToggle.check(R.id.checkpointModeButton)

        configModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.checkpointModeButton -> {
                        checkpointContent.visibility = View.VISIBLE
                        diffusersContent.visibility = View.GONE
                        isCheckpointMode = true
                        // Save configuration when mode changes
                        saveConfiguration()
                    }
                    R.id.diffusersModeButton -> {
                        checkpointContent.visibility = View.GONE
                        diffusersContent.visibility = View.VISIBLE
                        isCheckpointMode = false
                        // Save configuration when mode changes
                        saveConfiguration()
                    }
                }
            }
        }
    }

    private fun showConfigBottomSheet() {
        configBottomSheet?.show()
    }

    private fun setupDropdowns() {
        // Setup checkpoint workflows dropdown
        val checkpointWorkflowNames = workflowManager.getCheckpointWorkflowNames()
        val checkpointWorkflowAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            checkpointWorkflowNames
        )
        workflowDropdown.setAdapter(checkpointWorkflowAdapter)
        if (checkpointWorkflowNames.isNotEmpty()) {
            workflowDropdown.setText(checkpointWorkflowNames[0], false)
        }

        // Setup diffusers workflows dropdown
        val diffusersWorkflowNames = workflowManager.getDiffusersWorkflowNames()
        val diffusersWorkflowAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            diffusersWorkflowNames
        )
        diffusersWorkflowDropdown.setAdapter(diffusersWorkflowAdapter)
        if (diffusersWorkflowNames.isNotEmpty()) {
            diffusersWorkflowDropdown.setText(diffusersWorkflowNames[0], false)
        }
    }

    private fun updateCheckpointDropdown() {
        val checkpointAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableCheckpoints
        )
        checkpointDropdown.setAdapter(checkpointAdapter)
        if (availableCheckpoints.isNotEmpty()) {
            checkpointDropdown.setText(availableCheckpoints[0], false)
        }
    }

    private fun updateDiffusersDropdown() {
        val diffusersAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableDiffusers
        )
        diffuserDropdown.setAdapter(diffusersAdapter)
        if (availableDiffusers.isNotEmpty()) {
            diffuserDropdown.setText(availableDiffusers[0], false)
        }
    }

    private fun setupButtonListeners() {
        generateButton.setOnClickListener {
            hideKeyboard()
            startImageGeneration()
        }

        settingsButton.setOnClickListener {
            showConfigBottomSheet()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity?.currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun setupImageViewListeners() {
        imagePreview.setOnClickListener {
            currentBitmap?.let { bitmap ->
                showFullscreenImageViewer(bitmap)
            }
        }

        imagePreview.setOnLongClickListener {
            currentBitmap?.let {
                showSaveOptions()
                true
            } ?: false
        }
    }

    private fun startImageGeneration() {
        val prompt = promptInput.text.toString()

        generateButton.isEnabled = false
        generateButton.text = getString(R.string.button_generating)

        val workflowJson = if (isCheckpointMode) {
            // Checkpoint mode - use checkpoint parameters
            val workflowName = workflowDropdown.text.toString()
            val checkpoint = checkpointDropdown.text.toString()
            val width = widthInput.text.toString().toIntOrNull() ?: 1024
            val height = heightInput.text.toString().toIntOrNull() ?: 1024
            val steps = stepsInput.text.toString().toIntOrNull() ?: 9

            println("Starting generation with:")
            println("  Mode: Checkpoint")
            println("  Prompt: $prompt")
            println("  Workflow: $workflowName")
            println("  Checkpoint: $checkpoint")
            println("  Size: ${width}x${height}")
            println("  Steps: $steps")

            workflowManager.prepareWorkflow(
                workflowName = workflowName,
                prompt = prompt,
                checkpoint = checkpoint,
                width = width,
                height = height,
                steps = steps
            )
        } else {
            // Diffusers mode - use diffuser parameters
            val workflowName = diffusersWorkflowDropdown.text.toString()
            val diffuser = diffuserDropdown.text.toString()
            val width = diffusersWidthInput.text.toString().toIntOrNull() ?: 1024
            val height = diffusersHeightInput.text.toString().toIntOrNull() ?: 1024
            val steps = diffusersStepsInput.text.toString().toIntOrNull() ?: 9

            println("Starting generation with:")
            println("  Mode: Diffusers")
            println("  Prompt: $prompt")
            println("  Workflow: $workflowName")
            println("  Diffuser: $diffuser")
            println("  Size: ${width}x${height}")
            println("  Steps: $steps")

            workflowManager.prepareWorkflow(
                workflowName = workflowName,
                prompt = prompt,
                diffuser = diffuser,
                width = width,
                height = height,
                steps = steps
            )
        }

        if (workflowJson != null) {
            comfyUIClient.submitPrompt(workflowJson) { success, promptId, errorMessage ->
                activity?.runOnUiThread {
                    if (success && promptId != null) {
                        println("Workflow submitted successfully. Prompt ID: $promptId")
                        currentPromptId = promptId
                    } else {
                        println("Failed to submit workflow: $errorMessage")
                        generateButton.isEnabled = true
                        generateButton.text = getString(R.string.button_generate)
                    }
                }
            }
        } else {
            println("Error: Could not load workflow")
            generateButton.isEnabled = true
            generateButton.text = getString(R.string.button_generate)
        }
    }

    private fun connectToServer() {
        println("QueryFragment: Connecting to ComfyUI server at $hostname:$port")
        comfyUIClient.testConnection { success, errorMessage, certIssue ->
            if (success) {
                println("QueryFragment: Connection successful! Base URL: ${comfyUIClient.getBaseUrl()}")
                activity?.runOnUiThread {
                    openWebSocketConnection()
                    fetchCheckpoints()
                    fetchDiffusers()
                }
            } else {
                println("QueryFragment: Failed to connect to server: $errorMessage")
            }
        }
    }

    private fun openWebSocketConnection() {
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("WebSocket connected to ComfyUI server")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                println("WebSocket message: $text")

                try {
                    val message = JSONObject(text)
                    val messageType = message.optString("type")

                    println("Message type: $messageType")

                    when (messageType) {
                        "executing" -> {
                            val data = message.optJSONObject("data")
                            val promptId = data?.optString("prompt_id")
                            val isComplete = data?.isNull("node") == true

                            println("Executing: node=${if (isComplete) "null (complete)" else data?.optString("node")}, promptId=$promptId, currentPromptId=$currentPromptId")

                            if (isComplete && promptId == currentPromptId) {
                                println("Generation complete for prompt: $promptId")
                                fetchGeneratedImage(promptId)
                            }
                        }
                        "progress" -> {
                            val data = message.optJSONObject("data")
                            val value = data?.optInt("value", 0)
                            val max = data?.optInt("max", 0)
                            println("Progress: $value/$max")
                        }
                        "execution_error" -> {
                            activity?.runOnUiThread {
                                generateButton.isEnabled = true
                                generateButton.text = getString(R.string.button_generate)
                            }
                            println("Execution error: $text")
                        }
                        "status" -> {
                            val data = message.optJSONObject("data")
                            val status = data?.optJSONObject("status")
                            val execInfo = status?.optJSONObject("exec_info")
                            val queueRemaining = execInfo?.optInt("queue_remaining", -1)

                            println("Status: queue_remaining=$queueRemaining")

                            if (queueRemaining == 0 && currentPromptId != null) {
                                println("Queue empty, attempting to fetch image for prompt: $currentPromptId")
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    fetchGeneratedImage(currentPromptId)
                                }, 1000)
                            }
                        }
                        else -> {
                            println("Unknown message type: $messageType")
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to parse WebSocket message: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("WebSocket failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("WebSocket closed: $code - $reason")
            }
        }

        comfyUIClient.openWebSocket(webSocketListener)
    }

    private fun fetchCheckpoints() {
        println("QueryFragment: Fetching checkpoints from server...")
        comfyUIClient.fetchCheckpoints { checkpoints ->
            println("QueryFragment: Received ${checkpoints.size} checkpoints")
            checkpoints.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableCheckpoints.clear()
                availableCheckpoints.addAll(checkpoints)
                println("QueryFragment: Updated availableCheckpoints list, size=${availableCheckpoints.size}")
                updateCheckpointDropdown()
                println("QueryFragment: Checkpoint dropdown updated")
                // Restore saved checkpoint model after populating
                restoreCheckpointConfiguration()
            }
        }
    }

    private fun fetchDiffusers() {
        println("QueryFragment: Fetching diffusers from server...")
        comfyUIClient.fetchDiffusers { diffusers ->
            println("QueryFragment: Received ${diffusers.size} diffusers")
            diffusers.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableDiffusers.clear()
                availableDiffusers.addAll(diffusers)
                println("QueryFragment: Updated availableDiffusers list, size=${availableDiffusers.size}")
                updateDiffusersDropdown()
                println("QueryFragment: Diffusers dropdown updated")
                // Restore saved diffuser model after populating
                restoreDiffusersConfiguration()
            }
        }
    }

    private fun fetchGeneratedImage(promptId: String?) {
        if (promptId == null) {
            println("fetchGeneratedImage: promptId is null!")
            return
        }

        println("fetchGeneratedImage: Fetching history for prompt $promptId")

        comfyUIClient.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                println("fetchGeneratedImage: Received history JSON")
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    println("fetchGeneratedImage: promptData=${promptData != null}, outputs=${outputs != null}")

                    outputs?.keys()?.forEach { nodeId ->
                        println("fetchGeneratedImage: Checking node $nodeId")
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        println("fetchGeneratedImage: Node $nodeId has ${images?.length() ?: 0} images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            println("fetchGeneratedImage: Fetching image - filename=$filename, subfolder=$subfolder, type=$type")

                            comfyUIClient.fetchImage(filename, subfolder, type) { bitmap ->
                                println("fetchGeneratedImage: Received bitmap: ${bitmap != null}")
                                activity?.runOnUiThread {
                                    displayImage(bitmap)
                                    generateButton.isEnabled = true
                                    generateButton.text = getString(R.string.button_generate)
                                }
                            }
                            return@fetchHistory
                        }
                    }

                    println("fetchGeneratedImage: No images found in any node output")
                    activity?.runOnUiThread {
                        generateButton.isEnabled = true
                        generateButton.text = getString(R.string.button_generate)
                    }
                } catch (e: Exception) {
                    println("fetchGeneratedImage: Failed to parse history: ${e.message}")
                    e.printStackTrace()
                    activity?.runOnUiThread {
                        generateButton.isEnabled = true
                        generateButton.text = getString(R.string.button_generate)
                    }
                }
            } else {
                println("fetchGeneratedImage: Failed to fetch history - historyJson is null")
                activity?.runOnUiThread {
                    generateButton.isEnabled = true
                    generateButton.text = getString(R.string.button_generate)
                }
            }
        }
    }

    private fun displayImage(bitmap: Bitmap?) {
        if (bitmap != null) {
            currentBitmap = bitmap
            imagePreview.background = null
            imagePreview.setImageBitmap(bitmap)
            placeholderIcon.visibility = View.GONE
            imageInstructionText.visibility = View.VISIBLE

            // Save image to internal storage for persistence
            saveLastGeneratedImage(bitmap)
        } else {
            println("Failed to load image")
        }
    }

    /**
     * Save the last generated image to internal storage
     * This allows the image to persist when fragment is destroyed and recreated
     */
    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        try {
            requireContext().openFileOutput(LAST_IMAGE_FILENAME, Context.MODE_PRIVATE).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            println("Saved last generated image to internal storage")
        } catch (e: Exception) {
            println("Failed to save last generated image: ${e.message}")
        }
    }

    /**
     * Restore the last generated image from internal storage
     * Called when fragment is created to restore previously generated image
     */
    private fun restoreLastGeneratedImage() {
        try {
            val file = requireContext().getFileStreamPath(LAST_IMAGE_FILENAME)
            if (file.exists()) {
                requireContext().openFileInput(LAST_IMAGE_FILENAME).use { inputStream ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        displayImage(bitmap)
                        println("Restored last generated image from internal storage")
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to restore last generated image: ${e.message}")
        }
    }

    private fun showFullscreenImageViewer(bitmap: Bitmap) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_fullscreen_image)

        val photoView = dialog.findViewById<PhotoView>(R.id.fullscreenImageView)
        photoView.setImageBitmap(bitmap)

        photoView.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showSaveOptions() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_save_options, null)
        bottomSheetDialog.setContentView(view)

        view.findViewById<View>(R.id.saveToGallery).setOnClickListener {
            saveToGallery()
            bottomSheetDialog.dismiss()
        }

        view.findViewById<View>(R.id.saveAs).setOnClickListener {
            saveAsFile()
            bottomSheetDialog.dismiss()
        }

        view.findViewById<View>(R.id.share).setOnClickListener {
            shareImage()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun saveToGallery() {
        val bitmap = currentBitmap ?: return

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ComfyChair")
        }

        val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                requireContext().contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                Toast.makeText(requireContext(), "Image stored in gallery", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                println("Failed to save to gallery: ${e.message}")
                Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAsFile() {
        val filename = "ComfyChair_${System.currentTimeMillis()}.png"
        saveImageLauncher.launch(filename)
    }

    private fun saveImageToUri(uri: Uri) {
        val bitmap = currentBitmap ?: return

        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: IOException) {
            println("Failed to save image: ${e.message}")
            Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage() {
        val bitmap = currentBitmap ?: return

        val cachePath = requireContext().cacheDir
        val filename = "share_image_${System.currentTimeMillis()}.png"
        val file = java.io.File(cachePath, filename)

        try {
            file.outputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share image"))
        } catch (e: Exception) {
            println("Failed to share image: ${e.message}")
            Toast.makeText(requireContext(), "Failed to share image", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Setup listeners to auto-save configuration when values change
     */
    private fun setupAutoSaveListeners() {
        // Prompt text changes
        promptInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })

        // Checkpoint mode listeners
        workflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        checkpointDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        widthInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        heightInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        stepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })

        // Diffusers mode listeners
        diffusersWorkflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        diffuserDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        diffusersWidthInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        diffusersHeightInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        diffusersStepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
    }

    /**
     * Save current configuration to SharedPreferences
     * Called when configuration values change
     */
    private fun saveConfiguration() {
        // Don't save during initialization to avoid overwriting saved values
        if (isLoadingConfiguration) {
            println("Skipping save during configuration load")
            return
        }

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                // Save mode
                putBoolean(PREF_IS_CHECKPOINT_MODE, isCheckpointMode)

                // Save prompt
                val prompt = promptInput.text.toString()
                putString(PREF_PROMPT, prompt)

                // Save checkpoint mode configuration
                val checkpointWorkflow = workflowDropdown.text.toString()
                val checkpointModel = checkpointDropdown.text.toString()
                val checkpointWidth = widthInput.text.toString()
                val checkpointHeight = heightInput.text.toString()
                val checkpointSteps = stepsInput.text.toString()

                putString(PREF_CHECKPOINT_WORKFLOW, checkpointWorkflow)
                putString(PREF_CHECKPOINT_MODEL, checkpointModel)
                putString(PREF_CHECKPOINT_WIDTH, checkpointWidth)
                putString(PREF_CHECKPOINT_HEIGHT, checkpointHeight)
                putString(PREF_CHECKPOINT_STEPS, checkpointSteps)

                // Save diffusers mode configuration
                val diffusersWorkflow = diffusersWorkflowDropdown.text.toString()
                val diffusersModel = diffuserDropdown.text.toString()
                val diffusersWidth = diffusersWidthInput.text.toString()
                val diffusersHeight = diffusersHeightInput.text.toString()
                val diffusersSteps = diffusersStepsInput.text.toString()

                putString(PREF_DIFFUSERS_WORKFLOW, diffusersWorkflow)
                putString(PREF_DIFFUSERS_MODEL, diffusersModel)
                putString(PREF_DIFFUSERS_WIDTH, diffusersWidth)
                putString(PREF_DIFFUSERS_HEIGHT, diffusersHeight)
                putString(PREF_DIFFUSERS_STEPS, diffusersSteps)

                apply()
            }

            println("Configuration saved successfully")
        } catch (e: Exception) {
            println("Failed to save configuration: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load saved configuration from SharedPreferences
     * Called when fragment is created to restore previous configuration
     */
    private fun loadConfiguration() {
        // Set flag to prevent auto-save during load
        isLoadingConfiguration = true

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            println("Loading configuration from SharedPreferences...")

            // Restore mode
            val savedMode = prefs.getBoolean(PREF_IS_CHECKPOINT_MODE, true)
            isCheckpointMode = savedMode
            println("Loaded mode: $savedMode (checkpoint=$isCheckpointMode)")

            // Update toggle to reflect saved mode
            if (savedMode) {
                configModeToggle.check(R.id.checkpointModeButton)
            } else {
                configModeToggle.check(R.id.diffusersModeButton)
            }

            // Restore prompt
            val savedPrompt = prefs.getString(PREF_PROMPT, "")
            if (!savedPrompt.isNullOrEmpty()) {
                promptInput.setText(savedPrompt)
                println("Loaded prompt: $savedPrompt")
            }

            // Restore workflow and parameters (models will be restored after server data arrives)
            prefs.getString(PREF_CHECKPOINT_WORKFLOW, null)?.let {
                workflowDropdown.setText(it, false)
                println("Loaded checkpoint workflow: $it")
            }
            prefs.getString(PREF_CHECKPOINT_WIDTH, null)?.let {
                widthInput.setText(it)
                println("Loaded checkpoint width: $it")
            }
            prefs.getString(PREF_CHECKPOINT_HEIGHT, null)?.let {
                heightInput.setText(it)
                println("Loaded checkpoint height: $it")
            }
            prefs.getString(PREF_CHECKPOINT_STEPS, null)?.let {
                stepsInput.setText(it)
                println("Loaded checkpoint steps: $it")
            }

            prefs.getString(PREF_DIFFUSERS_WORKFLOW, null)?.let {
                diffusersWorkflowDropdown.setText(it, false)
                println("Loaded diffusers workflow: $it")
            }
            prefs.getString(PREF_DIFFUSERS_WIDTH, null)?.let {
                diffusersWidthInput.setText(it)
                println("Loaded diffusers width: $it")
            }
            prefs.getString(PREF_DIFFUSERS_HEIGHT, null)?.let {
                diffusersHeightInput.setText(it)
                println("Loaded diffusers height: $it")
            }
            prefs.getString(PREF_DIFFUSERS_STEPS, null)?.let {
                diffusersStepsInput.setText(it)
                println("Loaded diffusers steps: $it")
            }

            println("Configuration loading completed")
        } catch (e: Exception) {
            println("Failed to load configuration: ${e.message}")
            e.printStackTrace()
        } finally {
            // Clear the flag after a short delay to allow UI to settle
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
                println("Configuration loading flag cleared - auto-save now enabled")
            }, 500)
        }
    }

    /**
     * Restore checkpoint model selection after server data is loaded
     */
    private fun restoreCheckpointConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_CHECKPOINT_MODEL, null)?.let { savedModel ->
                if (availableCheckpoints.contains(savedModel)) {
                    checkpointDropdown.setText(savedModel, false)
                    println("Restored checkpoint model: $savedModel")
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    /**
     * Restore diffuser model selection after server data is loaded
     */
    private fun restoreDiffusersConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_DIFFUSERS_MODEL, null)?.let { savedModel ->
                if (availableDiffusers.contains(savedModel)) {
                    diffuserDropdown.setText(savedModel, false)
                    println("Restored diffuser model: $savedModel")
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Save configuration before destroying view
        saveConfiguration()

        comfyUIClient.shutdown()
        configBottomSheet?.dismiss()
        // Don't recycle bitmap - it's persisted to storage and will be restored
        // Just clear the reference
        currentBitmap = null
    }
}


