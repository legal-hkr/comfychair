package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.IOException

/**
 * TextToImageFragment - Main screen for interacting with ComfyUI
 */
class TextToImageFragment : Fragment(), MainContainerActivity.GenerationStateListener {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var imagePreview: ImageView
    private lateinit var placeholderIcon: ImageView
    private lateinit var promptInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var promptInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var settingsButton: Button
    // Progress overlay elements
    private lateinit var progressOverlay: View
    private lateinit var progressBar: android.widget.ProgressBar

    // Modal bottom sheet for configuration
    private var configBottomSheet: BottomSheetDialog? = null
    private lateinit var configModeToggle: com.google.android.material.button.MaterialButtonToggleGroup
    // Checkpoint mode UI elements
    private lateinit var workflowDropdown: AutoCompleteTextView
    private lateinit var checkpointDropdown: AutoCompleteTextView
    private lateinit var widthInput: TextInputEditText
    private lateinit var heightInput: TextInputEditText
    private lateinit var stepsInput: TextInputEditText
    // Checkpoint mode TextInputLayout wrappers for validation
    private lateinit var widthInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var heightInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var stepsInputLayout: com.google.android.material.textfield.TextInputLayout
    // UNET mode UI elements
    private lateinit var unetWorkflowDropdown: AutoCompleteTextView
    private lateinit var unetDropdown: AutoCompleteTextView
    private lateinit var vaeDropdown: AutoCompleteTextView
    private lateinit var clipDropdown: AutoCompleteTextView
    private lateinit var unetWidthInput: TextInputEditText
    private lateinit var unetHeightInput: TextInputEditText
    private lateinit var unetStepsInput: TextInputEditText
    // UNET mode TextInputLayout wrappers for validation
    private lateinit var unetWidthInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var unetHeightInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var unetStepsInputLayout: com.google.android.material.textfield.TextInputLayout

    // Workflow manager
    private lateinit var workflowManager: WorkflowManager

    // ComfyUI client - obtained from activity
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // Configuration values
    private val availableCheckpoints = mutableListOf<String>()
    private val availableUNETs = mutableListOf<String>()
    private val availableVAEs = mutableListOf<String>()
    private val availableCLIPs = mutableListOf<String>()
    // Track which mode is currently active
    private var isCheckpointMode = true
    // Flag to prevent auto-save during initialization
    private var isLoadingConfiguration = false

    // Store current generated image
    private var currentBitmap: Bitmap? = null

    // Track if generation is in progress (synced from activity)
    private var isGenerating = false
    private var currentPromptId: String? = null

    // Track if data has been fetched from server
    private var dataFetched = false

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
        private const val PREFS_NAME = "TextToImageFragmentPrefs"
        private const val PREF_IS_CHECKPOINT_MODE = "isCheckpointMode"
        private const val PREF_PROMPT = "prompt"

        // Checkpoint mode preferences
        private const val PREF_CHECKPOINT_WORKFLOW = "checkpointWorkflow"
        private const val PREF_CHECKPOINT_MODEL = "checkpointModel"
        private const val PREF_CHECKPOINT_WIDTH = "checkpointWidth"
        private const val PREF_CHECKPOINT_HEIGHT = "checkpointHeight"
        private const val PREF_CHECKPOINT_STEPS = "checkpointSteps"

        // UNET mode preferences
        private const val PREF_UNET_WORKFLOW = "unetWorkflow"
        private const val PREF_UNET_MODEL = "unetModel"
        private const val PREF_UNET_VAE = "unetVAE"
        private const val PREF_UNET_CLIP = "unetCLIP"
        private const val PREF_UNET_WIDTH = "unetWidth"
        private const val PREF_UNET_HEIGHT = "unetHeight"
        private const val PREF_UNET_STEPS = "unetSteps"

        fun newInstance(hostname: String, port: Int): TextToImageFragment {
            val fragment = TextToImageFragment()
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
        return inflater.inflate(R.layout.fragment_text_to_image, container, false)
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

        // Get ComfyUI client from activity
        val activity = requireActivity() as MainContainerActivity
        comfyUIClient = activity.getComfyUIClient()

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

        // Restore last generated image if available
        restoreLastGeneratedImage()

        // Load saved configuration (after all UI elements are initialized)
        loadConfiguration()

        // Setup auto-save listeners
        setupAutoSaveListeners()

        // Fetch server data only when connection is ready
        fetchServerData()
    }

    /**
     * Fetch server data (checkpoints, UNETs, VAEs, CLIPs) when connection is ready
     */
    private fun fetchServerData() {
        val activity = requireActivity() as MainContainerActivity
        activity.onConnectionReady {
            if (!dataFetched) {
                println("TextToImageFragment: Connection ready, fetching server data...")
                activity.runOnUiThread {
                    fetchCheckpoints()
                    fetchUNETs()
                    fetchVAEs()
                    fetchCLIPs()
                    dataFetched = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register as generation state listener when fragment becomes visible
        val activity = requireActivity() as MainContainerActivity
        activity.setGenerationStateListener(this)
    }

    override fun onPause() {
        super.onPause()
        // Unregister listener when fragment is no longer visible
        val activity = requireActivity() as MainContainerActivity
        activity.setGenerationStateListener(null)
    }

    // Implementation of GenerationStateListener interface

    override fun onGenerationStateChanged(isGenerating: Boolean, promptId: String?) {
        this.isGenerating = isGenerating
        this.currentPromptId = promptId

        if (isGenerating) {
            setGeneratingState()
            showProgressOverlay()
        } else {
            resetGenerateButton()
            hideProgressOverlay()
        }
    }

    override fun onProgressUpdate(current: Int, max: Int) {
        updateProgress(current, max)
    }

    override fun onImageGenerated(promptId: String) {
        println("TextToImageFragment: Image generated for prompt: $promptId")
        // Only fetch if fragment is still added and view is created
        if (isAdded && view != null) {
            fetchGeneratedImage(promptId)
        } else {
            println("TextToImageFragment: Fragment not in valid state to fetch image")
        }
    }

    override fun onGenerationError(message: String) {
        println("TextToImageFragment: Generation error: $message")
        resetGenerateButton()
        hideProgressOverlay()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        imagePreview = view.findViewById(R.id.imagePreview)
        placeholderIcon = view.findViewById(R.id.placeholderIcon)
        promptInputLayout = view.findViewById(R.id.promptInputLayout)
        promptInput = view.findViewById(R.id.promptInput)
        generateButton = view.findViewById(R.id.generateButton)
        settingsButton = view.findViewById(R.id.settingsButton)
        // Progress overlay elements
        progressOverlay = view.findViewById(R.id.progressOverlay)
        progressBar = view.findViewById(R.id.progressBar)

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
        // Initialize checkpoint mode TextInputLayout wrappers for validation
        widthInputLayout = bottomSheetView.findViewById(R.id.widthInputLayout)
        heightInputLayout = bottomSheetView.findViewById(R.id.heightInputLayout)
        stepsInputLayout = bottomSheetView.findViewById(R.id.stepsInputLayout)

        // Initialize UNET mode UI elements
        unetWorkflowDropdown = bottomSheetView.findViewById(R.id.unetWorkflowDropdown)
        unetDropdown = bottomSheetView.findViewById(R.id.unetDropdown)
        vaeDropdown = bottomSheetView.findViewById(R.id.vaeDropdown)
        clipDropdown = bottomSheetView.findViewById(R.id.clipDropdown)
        unetWidthInput = bottomSheetView.findViewById(R.id.unetWidthInput)
        unetHeightInput = bottomSheetView.findViewById(R.id.unetHeightInput)
        unetStepsInput = bottomSheetView.findViewById(R.id.unetStepsInput)
        // Initialize UNET mode TextInputLayout wrappers for validation
        unetWidthInputLayout = bottomSheetView.findViewById(R.id.unetWidthInputLayout)
        unetHeightInputLayout = bottomSheetView.findViewById(R.id.unetHeightInputLayout)
        unetStepsInputLayout = bottomSheetView.findViewById(R.id.unetStepsInputLayout)

        // Set up segmented button toggle
        configModeToggle = bottomSheetView.findViewById(R.id.configModeToggle)
        val checkpointContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.checkpointContent)
        val unetContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.unetContent)

        // Set initial mode (will be updated by loadConfiguration later)
        configModeToggle.check(R.id.checkpointModeButton)

        configModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.checkpointModeButton -> {
                        checkpointContent.visibility = View.VISIBLE
                        unetContent.visibility = View.GONE
                        isCheckpointMode = true
                        // Save configuration when mode changes
                        saveConfiguration()
                    }
                    R.id.unetModeButton -> {
                        checkpointContent.visibility = View.GONE
                        unetContent.visibility = View.VISIBLE
                        isCheckpointMode = false
                        // Save configuration when mode changes
                        saveConfiguration()
                    }
                }
            }
        }

        // Set up live validation for input fields
        setupLiveValidation()
    }

    /**
     * Set up live input validation for configuration fields
     * Validates as user types and shows errors in real-time
     */
    private fun setupLiveValidation() {
        // Helper function to validate dimension (width/height)
        fun validateDimension(input: TextInputEditText, layout: com.google.android.material.textfield.TextInputLayout, errorStringId: Int) {
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val value = s.toString().trim()
                    layout.error = when {
                        value.isEmpty() -> null
                        else -> {
                            val intValue = value.toIntOrNull()
                            if (intValue == null || intValue !in 1..4096) {
                                getString(errorStringId)
                            } else {
                                null
                            }
                        }
                    }
                }
            })
        }

        // Helper function to validate steps
        fun validateSteps(input: TextInputEditText, layout: com.google.android.material.textfield.TextInputLayout) {
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val value = s.toString().trim()
                    layout.error = when {
                        value.isEmpty() -> null
                        else -> {
                            val intValue = value.toIntOrNull()
                            if (intValue == null || intValue !in 1..255) {
                                getString(R.string.error_invalid_steps)
                            } else {
                                null
                            }
                        }
                    }
                }
            })
        }

        // Checkpoint mode validation
        validateDimension(widthInput, widthInputLayout, R.string.error_invalid_width)
        validateDimension(heightInput, heightInputLayout, R.string.error_invalid_height)
        validateSteps(stepsInput, stepsInputLayout)

        // UNET mode validation
        validateDimension(unetWidthInput, unetWidthInputLayout, R.string.error_invalid_width)
        validateDimension(unetHeightInput, unetHeightInputLayout, R.string.error_invalid_height)
        validateSteps(unetStepsInput, unetStepsInputLayout)
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

        // Setup UNET workflows dropdown
        val unetWorkflowNames = workflowManager.getUNETWorkflowNames()
        val unetWorkflowAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            unetWorkflowNames
        )
        unetWorkflowDropdown.setAdapter(unetWorkflowAdapter)
        if (unetWorkflowNames.isNotEmpty()) {
            unetWorkflowDropdown.setText(unetWorkflowNames[0], false)
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

    private fun updateUNETDropdown() {
        val unetAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableUNETs
        )
        unetDropdown.setAdapter(unetAdapter)
        if (availableUNETs.isNotEmpty()) {
            unetDropdown.setText(availableUNETs[0], false)
        }
    }

    private fun updateVAEDropdown() {
        val vaeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableVAEs
        )
        vaeDropdown.setAdapter(vaeAdapter)
        if (availableVAEs.isNotEmpty()) {
            vaeDropdown.setText(availableVAEs[0], false)
        }
    }

    private fun updateCLIPDropdown() {
        val clipAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableCLIPs
        )
        clipDropdown.setAdapter(clipAdapter)
        if (availableCLIPs.isNotEmpty()) {
            clipDropdown.setText(availableCLIPs[0], false)
        }
    }

    private fun setupButtonListeners() {
        generateButton.setOnClickListener {
            hideKeyboard()
            if (isGenerating) {
                cancelImageGeneration()
            } else {
                startImageGeneration()
            }
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

    private fun cancelImageGeneration() {
        println("TextToImageFragment: Canceling image generation...")
        val activity = requireActivity() as MainContainerActivity
        activity.cancelGeneration { success ->
            if (success) {
                println("TextToImageFragment: Generation canceled successfully")
            } else {
                println("TextToImageFragment: Failed to cancel generation")
            }
        }
    }

    /**
     * Validate configuration inputs before starting generation
     * @return true if all inputs are valid, false otherwise
     */
    private fun validateConfiguration(): Boolean {
        var isValid = true

        // Clear previous errors
        widthInputLayout.error = null
        heightInputLayout.error = null
        stepsInputLayout.error = null
        unetWidthInputLayout.error = null
        unetHeightInputLayout.error = null
        unetStepsInputLayout.error = null

        if (isCheckpointMode) {
            // Validate checkpoint mode fields
            val width = widthInput.text.toString().toIntOrNull()
            if (width == null || width !in 1..4096) {
                widthInputLayout.error = getString(R.string.error_invalid_width)
                isValid = false
            }

            val height = heightInput.text.toString().toIntOrNull()
            if (height == null || height !in 1..4096) {
                heightInputLayout.error = getString(R.string.error_invalid_height)
                isValid = false
            }

            val steps = stepsInput.text.toString().toIntOrNull()
            if (steps == null || steps !in 1..255) {
                stepsInputLayout.error = getString(R.string.error_invalid_steps)
                isValid = false
            }
        } else {
            // Validate UNET mode fields
            val width = unetWidthInput.text.toString().toIntOrNull()
            if (width == null || width !in 1..4096) {
                unetWidthInputLayout.error = getString(R.string.error_invalid_width)
                isValid = false
            }

            val height = unetHeightInput.text.toString().toIntOrNull()
            if (height == null || height !in 1..4096) {
                unetHeightInputLayout.error = getString(R.string.error_invalid_height)
                isValid = false
            }

            val steps = unetStepsInput.text.toString().toIntOrNull()
            if (steps == null || steps !in 1..255) {
                unetStepsInputLayout.error = getString(R.string.error_invalid_steps)
                isValid = false
            }
        }

        // Show the config bottom sheet if validation failed so user can see errors
        if (!isValid) {
            showConfigBottomSheet()
        }

        return isValid
    }

    private fun startImageGeneration() {
        // Validate configuration before starting
        if (!validateConfiguration()) {
            return
        }

        val prompt = promptInput.text.toString()

        val workflowJson = if (isCheckpointMode) {
            // Checkpoint mode - use checkpoint parameters
            val workflowName = workflowDropdown.text.toString()
            val checkpoint = checkpointDropdown.text.toString()
            val width = widthInput.text.toString().toIntOrNull() ?: 1024
            val height = heightInput.text.toString().toIntOrNull() ?: 1024
            val steps = stepsInput.text.toString().toIntOrNull() ?: 9

            println("TextToImageFragment: Starting generation with:")
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
            // UNET mode - use UNET parameters
            val workflowName = unetWorkflowDropdown.text.toString()
            val unet = unetDropdown.text.toString()
            val vae = vaeDropdown.text.toString()
            val clip = clipDropdown.text.toString()
            val width = unetWidthInput.text.toString().toIntOrNull() ?: 1024
            val height = unetHeightInput.text.toString().toIntOrNull() ?: 1024
            val steps = unetStepsInput.text.toString().toIntOrNull() ?: 9

            println("TextToImageFragment: Starting generation with:")
            println("  Mode: UNET")
            println("  Prompt: $prompt")
            println("  Workflow: $workflowName")
            println("  UNET: $unet")
            println("  VAE: $vae")
            println("  CLIP: $clip")
            println("  Size: ${width}x${height}")
            println("  Steps: $steps")

            workflowManager.prepareWorkflow(
                workflowName = workflowName,
                prompt = prompt,
                unet = unet,
                vae = vae,
                clip = clip,
                width = width,
                height = height,
                steps = steps
            )
        }

        if (workflowJson != null) {
            val activity = requireActivity() as MainContainerActivity
            activity.startGeneration(workflowJson) { success, promptId, errorMessage ->
                if (success && promptId != null) {
                    println("TextToImageFragment: Workflow submitted successfully. Prompt ID: $promptId")
                    // UI will be updated by onGenerationStateChanged callback
                } else {
                    println("TextToImageFragment: Failed to submit workflow: $errorMessage")
                    // Error already shown by activity via Toast
                }
            }
        } else {
            println("TextToImageFragment: Error: Could not load workflow")
            Toast.makeText(requireContext(), "Failed to load workflow", Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchCheckpoints() {
        println("TextToImageFragment: Fetching checkpoints from server...")
        comfyUIClient.fetchCheckpoints { checkpoints ->
            println("TextToImageFragment: Received ${checkpoints.size} checkpoints")
            checkpoints.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableCheckpoints.clear()
                availableCheckpoints.addAll(checkpoints)
                println("TextToImageFragment: Updated availableCheckpoints list, size=${availableCheckpoints.size}")
                updateCheckpointDropdown()
                println("TextToImageFragment: Checkpoint dropdown updated")
                // Restore saved checkpoint model after populating
                restoreCheckpointConfiguration()
            }
        }
    }

    private fun fetchUNETs() {
        println("TextToImageFragment: Fetching UNETs from server...")
        comfyUIClient.fetchUNETs { unets ->
            println("TextToImageFragment: Received ${unets.size} UNETs")
            unets.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableUNETs.clear()
                availableUNETs.addAll(unets)
                println("TextToImageFragment: Updated availableUNETs list, size=${availableUNETs.size}")
                updateUNETDropdown()
                println("TextToImageFragment: UNET dropdown updated")
                // Restore saved UNET model after populating
                restoreUNETConfiguration()
            }
        }
    }

    private fun fetchVAEs() {
        println("TextToImageFragment: Fetching VAEs from server...")
        comfyUIClient.fetchVAEs { vaes ->
            println("TextToImageFragment: Received ${vaes.size} VAEs")
            vaes.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableVAEs.clear()
                availableVAEs.addAll(vaes)
                println("TextToImageFragment: Updated availableVAEs list, size=${availableVAEs.size}")
                updateVAEDropdown()
                println("TextToImageFragment: VAE dropdown updated")
                // Restore saved VAE after populating
                restoreVAEConfiguration()
            }
        }
    }

    private fun fetchCLIPs() {
        println("TextToImageFragment: Fetching CLIPs from server...")
        comfyUIClient.fetchCLIPs { clips ->
            println("TextToImageFragment: Received ${clips.size} CLIPs")
            clips.forEach { println("  - $it") }

            activity?.runOnUiThread {
                availableCLIPs.clear()
                availableCLIPs.addAll(clips)
                println("TextToImageFragment: Updated availableCLIPs list, size=${availableCLIPs.size}")
                updateCLIPDropdown()
                println("TextToImageFragment: CLIP dropdown updated")
                // Restore saved CLIP after populating
                restoreCLIPConfiguration()
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
                                    // Verify fragment is still in valid state
                                    if (isAdded && view != null) {
                                        displayImage(bitmap)
                                        // Notify activity that generation is complete
                                        (requireActivity() as MainContainerActivity).completeGeneration()
                                    } else {
                                        println("fetchGeneratedImage: Fragment no longer valid, skipping image display")
                                        (activity as? MainContainerActivity)?.completeGeneration()
                                    }
                                }
                            }
                            return@fetchHistory
                        }
                    }

                    println("fetchGeneratedImage: No images found in any node output")
                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), "No images found in generation output", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    println("fetchGeneratedImage: Failed to parse history: ${e.message}")
                    e.printStackTrace()
                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), "Failed to fetch generated image", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                println("fetchGeneratedImage: Failed to fetch history - historyJson is null")
                activity?.runOnUiThread {
                    (activity as? MainContainerActivity)?.completeGeneration()
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Failed to fetch generation history", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun displayImage(bitmap: Bitmap?) {
        // Ensure view is still valid
        if (!isAdded || view == null) {
            println("displayImage: Fragment not in valid state")
            return
        }

        if (bitmap != null) {
            currentBitmap = bitmap
            imagePreview.background = null
            imagePreview.setImageBitmap(bitmap)
            placeholderIcon.visibility = View.GONE

            // Save image to internal storage for persistence
            saveLastGeneratedImage(bitmap)
        } else {
            println("Failed to load image")
        }
    }

    /**
     * Show progress overlay with initial state
     */
    private fun showProgressOverlay() {
        progressOverlay.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.max = 100
    }

    /**
     * Hide progress overlay
     */
    private fun hideProgressOverlay() {
        progressOverlay.visibility = View.GONE
    }

    /**
     * Update Generate button to show "generating" state
     */
    private fun setGeneratingState() {
        isGenerating = true
        generateButton.text = "Cancel generation"
        // Set red background tint for Material button
        generateButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cancel_red)
        )
    }

    /**
     * Reset Generate button to normal state
     */
    private fun resetGenerateButton() {
        isGenerating = false
        generateButton.text = getString(R.string.button_generate)
        // Reset to default Material button style
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        generateButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
    }

    /**
     * Update progress bar with current progress
     */
    private fun updateProgress(current: Int, max: Int) {
        activity?.runOnUiThread {
            progressBar.max = max
            progressBar.progress = current
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

        // UNET mode listeners
        unetWorkflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        unetDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        vaeDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        clipDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        unetWidthInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        unetHeightInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })
        unetStepsInput.addTextChangedListener(object : android.text.TextWatcher {
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

                // Save UNET mode configuration
                val unetWorkflow = unetWorkflowDropdown.text.toString()
                val unetModel = unetDropdown.text.toString()
                val unetVAE = vaeDropdown.text.toString()
                val unetCLIP = clipDropdown.text.toString()
                val unetWidth = unetWidthInput.text.toString()
                val unetHeight = unetHeightInput.text.toString()
                val unetSteps = unetStepsInput.text.toString()

                putString(PREF_UNET_WORKFLOW, unetWorkflow)
                putString(PREF_UNET_MODEL, unetModel)
                putString(PREF_UNET_VAE, unetVAE)
                putString(PREF_UNET_CLIP, unetCLIP)
                putString(PREF_UNET_WIDTH, unetWidth)
                putString(PREF_UNET_HEIGHT, unetHeight)
                putString(PREF_UNET_STEPS, unetSteps)

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
                configModeToggle.check(R.id.unetModeButton)
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

            prefs.getString(PREF_UNET_WORKFLOW, null)?.let {
                unetWorkflowDropdown.setText(it, false)
                println("Loaded UNET workflow: $it")
            }
            prefs.getString(PREF_UNET_WIDTH, null)?.let {
                unetWidthInput.setText(it)
                println("Loaded UNET width: $it")
            }
            prefs.getString(PREF_UNET_HEIGHT, null)?.let {
                unetHeightInput.setText(it)
                println("Loaded UNET height: $it")
            }
            prefs.getString(PREF_UNET_STEPS, null)?.let {
                unetStepsInput.setText(it)
                println("Loaded UNET steps: $it")
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
     * Restore UNET model selection after server data is loaded
     */
    private fun restoreUNETConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_MODEL, null)?.let { savedModel ->
                if (availableUNETs.contains(savedModel)) {
                    unetDropdown.setText(savedModel, false)
                    println("Restored UNET model: $savedModel")
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    /**
     * Restore VAE selection after server data is loaded
     */
    private fun restoreVAEConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_VAE, null)?.let { savedVAE ->
                if (availableVAEs.contains(savedVAE)) {
                    vaeDropdown.setText(savedVAE, false)
                    println("Restored VAE: $savedVAE")
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    /**
     * Restore CLIP selection after server data is loaded
     */
    private fun restoreCLIPConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_CLIP, null)?.let { savedCLIP ->
                if (availableCLIPs.contains(savedCLIP)) {
                    clipDropdown.setText(savedCLIP, false)
                    println("Restored CLIP: $savedCLIP")
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

        // Don't shutdown the client - it's managed by the activity
        configBottomSheet?.dismiss()
        // Don't recycle bitmap - it's persisted to storage and will be restored
        // Just clear the reference
        currentBitmap = null
    }
}

