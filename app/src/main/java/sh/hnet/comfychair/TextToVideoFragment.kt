package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.IOException

/**
 * TextToVideoFragment - Main screen for video generation with ComfyUI
 */
class TextToVideoFragment : Fragment(), MainContainerActivity.GenerationStateListener {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var videoPreview: TextureView
    private lateinit var previewImage: ImageView
    private lateinit var placeholderBackground: View
    private lateinit var placeholderIcon: ImageView
    // MediaPlayer for video playback with TextureView
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var promptInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var promptInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var settingsButton: Button
    // Progress overlay elements
    private lateinit var progressOverlay: View
    private lateinit var progressBar: android.widget.ProgressBar

    // Modal bottom sheet for configuration
    private var configBottomSheet: BottomSheetDialog? = null
    // UI elements for video config
    private lateinit var workflowDropdown: AutoCompleteTextView
    private lateinit var highnoiseUnetDropdown: AutoCompleteTextView
    private lateinit var lownoiseUnetDropdown: AutoCompleteTextView
    private lateinit var highnoiseLoraDropdown: AutoCompleteTextView
    private lateinit var lownoiseLoraDropdown: AutoCompleteTextView
    private lateinit var vaeDropdown: AutoCompleteTextView
    private lateinit var clipDropdown: AutoCompleteTextView
    private lateinit var widthInput: TextInputEditText
    private lateinit var heightInput: TextInputEditText
    private lateinit var lengthInput: TextInputEditText
    private lateinit var fpsInput: TextInputEditText
    // TextInputLayout wrappers for validation
    private lateinit var widthInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var heightInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var lengthInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var fpsInputLayout: com.google.android.material.textfield.TextInputLayout

    // Workflow manager
    private lateinit var workflowManager: WorkflowManager

    // ComfyUI client - obtained from activity
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // Configuration values
    private val availableUNETs = mutableListOf<String>()
    private val availableLoRAs = mutableListOf<String>()
    private val availableVAEs = mutableListOf<String>()
    private val availableCLIPs = mutableListOf<String>()
    // Flag to prevent auto-save during initialization
    private var isLoadingConfiguration = false

    // Store current generated video path
    private var currentVideoPath: String? = null

    // Track if generation is in progress (synced from activity)
    private var isGenerating = false
    private var currentPromptId: String? = null

    // Track if data has been fetched from server
    private var dataFetched = false

    // Filename for persisting last generated video
    private val LAST_VIDEO_FILENAME = "last_generated_video.mp4"

    // Activity result launcher for "Save as..."
    private val saveVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        uri?.let { saveVideoToUri(it) }
    }

    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"

        // SharedPreferences constants for configuration preservation
        private const val PREFS_NAME = "TextToVideoFragmentPrefs"
        private const val PREF_PROMPT = "prompt"
        private const val PREF_WORKFLOW = "workflow"
        private const val PREF_HIGHNOISE_UNET = "highnoiseUnet"
        private const val PREF_LOWNOISE_UNET = "lownoiseUnet"
        private const val PREF_HIGHNOISE_LORA = "highnoiseLora"
        private const val PREF_LOWNOISE_LORA = "lownoiseLora"
        private const val PREF_VAE = "vae"
        private const val PREF_CLIP = "clip"
        private const val PREF_WIDTH = "width"
        private const val PREF_HEIGHT = "height"
        private const val PREF_LENGTH = "length"
        private const val PREF_FPS = "fps"

        fun newInstance(hostname: String, port: Int): TextToVideoFragment {
            val fragment = TextToVideoFragment()
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
        return inflater.inflate(R.layout.fragment_text_to_video, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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

        // Setup video view listeners
        setupVideoViewListeners()

        // Restore last generated video if available
        restoreLastGeneratedVideo()

        // Load saved configuration
        loadConfiguration()

        // Setup auto-save listeners
        setupAutoSaveListeners()

        // Fetch server data only when connection is ready
        fetchServerData()
    }

    /**
     * Fetch server data (UNETs, LoRAs, VAEs, CLIPs) when connection is ready
     */
    private fun fetchServerData() {
        val activity = requireActivity() as MainContainerActivity
        activity.onConnectionReady {
            if (!dataFetched) {
                println("TextToVideoFragment: Connection ready, fetching server data...")
                activity.runOnUiThread {
                    fetchUNETs()
                    fetchLoRAs()
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

        // Resume video playback if we have a video
        if (currentVideoPath != null && mediaPlayer != null) {
            try {
                mediaPlayer?.start()
            } catch (e: Exception) {
                println("Failed to resume video: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister listener when fragment is no longer visible
        val activity = requireActivity() as MainContainerActivity
        activity.setGenerationStateListener(null)

        // Pause video playback
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            println("Failed to pause video: ${e.message}")
        }
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

    override fun onPreviewImage(bitmap: Bitmap) {
        // Display preview image during video generation
        if (isAdded && view != null) {
            previewImage.setImageBitmap(bitmap)
            previewImage.visibility = View.VISIBLE
            placeholderBackground.visibility = View.GONE
            placeholderIcon.visibility = View.GONE
        }
    }

    override fun onImageGenerated(promptId: String) {
        println("TextToVideoFragment: Video generated for prompt: $promptId")
        // Only fetch if fragment is still added and view is created
        if (isAdded && view != null) {
            fetchGeneratedVideo(promptId)
        } else {
            println("TextToVideoFragment: Fragment not in valid state to fetch video")
        }
    }

    override fun onGenerationError(message: String) {
        println("TextToVideoFragment: Generation error: $message")
        resetGenerateButton()
        hideProgressOverlay()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        videoPreview = view.findViewById(R.id.videoPreview)
        previewImage = view.findViewById(R.id.previewImage)
        placeholderBackground = view.findViewById(R.id.placeholderBackground)
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

        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_video_config, null)
        configBottomSheet?.setContentView(bottomSheetView)

        // Initialize UI elements
        workflowDropdown = bottomSheetView.findViewById(R.id.workflowDropdown)
        highnoiseUnetDropdown = bottomSheetView.findViewById(R.id.highnoiseUnetDropdown)
        lownoiseUnetDropdown = bottomSheetView.findViewById(R.id.lownoiseUnetDropdown)
        highnoiseLoraDropdown = bottomSheetView.findViewById(R.id.highnoiseLoraDropdown)
        lownoiseLoraDropdown = bottomSheetView.findViewById(R.id.lownoiseLoraDropdown)
        vaeDropdown = bottomSheetView.findViewById(R.id.vaeDropdown)
        clipDropdown = bottomSheetView.findViewById(R.id.clipDropdown)
        widthInput = bottomSheetView.findViewById(R.id.widthInput)
        heightInput = bottomSheetView.findViewById(R.id.heightInput)
        lengthInput = bottomSheetView.findViewById(R.id.lengthInput)
        fpsInput = bottomSheetView.findViewById(R.id.fpsInput)
        // Initialize TextInputLayout wrappers for validation
        widthInputLayout = bottomSheetView.findViewById(R.id.widthInputLayout)
        heightInputLayout = bottomSheetView.findViewById(R.id.heightInputLayout)
        lengthInputLayout = bottomSheetView.findViewById(R.id.lengthInputLayout)
        fpsInputLayout = bottomSheetView.findViewById(R.id.fpsInputLayout)

        // Set up live validation
        setupLiveValidation()
    }

    /**
     * Set up live input validation for configuration fields
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

        // Validate length - must be (n * 4 + 1) where n >= 0 and result <= 129
        // Valid values: 1, 5, 9, 13, 17, 21, 25, 29, 33, ..., 125, 129
        lengthInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().trim()
                lengthInputLayout.error = when {
                    value.isEmpty() -> null
                    else -> {
                        val intValue = value.toIntOrNull()
                        if (intValue == null || intValue < 1 || intValue > 129 || (intValue - 1) % 4 != 0) {
                            getString(R.string.error_invalid_length)
                        } else {
                            null
                        }
                    }
                }
            }
        })

        // Dimension validation
        validateDimension(widthInput, widthInputLayout, R.string.error_invalid_width)
        validateDimension(heightInput, heightInputLayout, R.string.error_invalid_height)

        // Validate FPS - must be between 1 and 120
        fpsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().trim()
                fpsInputLayout.error = when {
                    value.isEmpty() -> null
                    else -> {
                        val intValue = value.toIntOrNull()
                        if (intValue == null || intValue < 1 || intValue > 120) {
                            getString(R.string.error_invalid_fps)
                        } else {
                            null
                        }
                    }
                }
            }
        })
    }

    private fun showConfigBottomSheet() {
        configBottomSheet?.show()
    }

    private fun setupDropdowns() {
        // Setup video workflows dropdown
        val videoWorkflowNames = workflowManager.getVideoUNETWorkflowNames()
        val workflowAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            videoWorkflowNames
        )
        workflowDropdown.setAdapter(workflowAdapter)
        if (videoWorkflowNames.isNotEmpty()) {
            workflowDropdown.setText(videoWorkflowNames[0], false)
        }
    }

    private fun updateUNETDropdowns() {
        val unetAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableUNETs
        )
        highnoiseUnetDropdown.setAdapter(unetAdapter)
        lownoiseUnetDropdown.setAdapter(unetAdapter)
        if (availableUNETs.isNotEmpty()) {
            highnoiseUnetDropdown.setText(availableUNETs[0], false)
            lownoiseUnetDropdown.setText(availableUNETs[0], false)
        }
    }

    private fun updateLoRADropdowns() {
        val loraAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            availableLoRAs
        )
        highnoiseLoraDropdown.setAdapter(loraAdapter)
        lownoiseLoraDropdown.setAdapter(loraAdapter)
        if (availableLoRAs.isNotEmpty()) {
            highnoiseLoraDropdown.setText(availableLoRAs[0], false)
            lownoiseLoraDropdown.setText(availableLoRAs[0], false)
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
                cancelVideoGeneration()
            } else {
                startVideoGeneration()
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

    private fun setupVideoViewListeners() {
        // Set up TextureView surface texture listener for video playback
        videoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                // If we have a video path and no active player, start playback
                currentVideoPath?.let { path ->
                    if (mediaPlayer == null) {
                        playVideo(path)
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Update the transform matrix when size changes
                mediaPlayer?.let { mp ->
                    updateTextureViewTransform(mp.videoWidth, mp.videoHeight)
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // Just release the media player, don't change visibility
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                } catch (e: Exception) {
                    println("Error releasing MediaPlayer: ${e.message}")
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // No-op
            }
        }

        videoPreview.setOnClickListener {
            currentVideoPath?.let { path ->
                showFullscreenVideoViewer(path)
            }
        }

        videoPreview.setOnLongClickListener {
            currentVideoPath?.let {
                showSaveOptions()
                true
            } ?: false
        }
    }

    /**
     * Play video using MediaPlayer with TextureView
     */
    private fun playVideo(videoPath: String) {
        // Stop any existing player but don't hide TextureView yet
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            println("Error releasing MediaPlayer: ${e.message}")
        }

        if (!videoPreview.isAvailable) {
            println("TextureView not available yet, will play when ready")
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setSurface(Surface(videoPreview.surfaceTexture))
                setDataSource(videoPath)
                isLooping = true
                setOnPreparedListener { mp ->
                    // Hide all overlays to show the video
                    previewImage.visibility = View.GONE
                    placeholderBackground.visibility = View.GONE
                    placeholderIcon.visibility = View.GONE
                    updateTextureViewTransform(mp.videoWidth, mp.videoHeight)
                    mp.start()
                }
                setOnErrorListener { _, what, extra ->
                    println("MediaPlayer error: what=$what, extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            println("Failed to play video: ${e.message}")
        }
    }

    /**
     * Apply center-crop transform to the TextureView
     */
    private fun updateTextureViewTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return

        val viewWidth = videoPreview.width.toFloat()
        val viewHeight = videoPreview.height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewWidth / viewHeight

        val scaleX: Float
        val scaleY: Float

        if (videoAspect > viewAspect) {
            // Video is wider than view - scale to fill height, crop width
            scaleY = 1f
            scaleX = videoAspect / viewAspect
        } else {
            // Video is taller than view - scale to fill width, crop height
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        videoPreview.setTransform(matrix)
    }

    /**
     * Release MediaPlayer resources and show placeholder background
     */
    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            println("Error releasing MediaPlayer: ${e.message}")
        }

        // Show placeholder background to cover the TextureView's black surface
        placeholderBackground.visibility = View.VISIBLE
    }

    private fun cancelVideoGeneration() {
        println("TextToVideoFragment: Canceling video generation...")
        val activity = requireActivity() as MainContainerActivity
        activity.cancelGeneration { success ->
            if (success) {
                println("TextToVideoFragment: Generation canceled successfully")
            } else {
                println("TextToVideoFragment: Failed to cancel generation")
            }
        }
    }

    /**
     * Validate configuration inputs before starting generation
     */
    private fun validateConfiguration(): Boolean {
        var isValid = true

        // Clear previous errors
        widthInputLayout.error = null
        heightInputLayout.error = null
        lengthInputLayout.error = null
        fpsInputLayout.error = null

        // Validate width
        val width = widthInput.text.toString().toIntOrNull()
        if (width == null || width !in 1..4096) {
            widthInputLayout.error = getString(R.string.error_invalid_width)
            isValid = false
        }

        // Validate height
        val height = heightInput.text.toString().toIntOrNull()
        if (height == null || height !in 1..4096) {
            heightInputLayout.error = getString(R.string.error_invalid_height)
            isValid = false
        }

        // Validate length - must be (n * 4 + 1) where n >= 0 and result <= 129
        val length = lengthInput.text.toString().toIntOrNull()
        if (length == null || length < 1 || length > 129 || (length - 1) % 4 != 0) {
            lengthInputLayout.error = getString(R.string.error_invalid_length)
            isValid = false
        }

        // Validate FPS - must be between 1 and 120
        val fps = fpsInput.text.toString().toIntOrNull()
        if (fps == null || fps < 1 || fps > 120) {
            fpsInputLayout.error = getString(R.string.error_invalid_fps)
            isValid = false
        }

        // Show the config bottom sheet if validation failed
        if (!isValid) {
            showConfigBottomSheet()
        }

        return isValid
    }

    private fun startVideoGeneration() {
        // Validate configuration before starting
        if (!validateConfiguration()) {
            return
        }

        // Clear preview video and image before starting generation
        releaseMediaPlayer()
        previewImage.setImageBitmap(null)
        previewImage.visibility = View.GONE
        placeholderBackground.visibility = View.VISIBLE
        placeholderIcon.visibility = View.VISIBLE

        val prompt = promptInput.text.toString()
        val workflowName = workflowDropdown.text.toString()
        val highnoiseUnet = highnoiseUnetDropdown.text.toString()
        val lownoiseUnet = lownoiseUnetDropdown.text.toString()
        val highnoiseLora = highnoiseLoraDropdown.text.toString()
        val lownoiseLora = lownoiseLoraDropdown.text.toString()
        val vae = vaeDropdown.text.toString()
        val clip = clipDropdown.text.toString()
        val width = widthInput.text.toString().toIntOrNull() ?: 512
        val height = heightInput.text.toString().toIntOrNull() ?: 512
        val length = lengthInput.text.toString().toIntOrNull() ?: 33
        val fps = fpsInput.text.toString().toIntOrNull() ?: 16

        println("TextToVideoFragment: Starting generation with:")
        println("  Prompt: $prompt")
        println("  Workflow: $workflowName")
        println("  High Noise UNET: $highnoiseUnet")
        println("  Low Noise UNET: $lownoiseUnet")
        println("  High Noise LoRA: $highnoiseLora")
        println("  Low Noise LoRA: $lownoiseLora")
        println("  VAE: $vae")
        println("  CLIP: $clip")
        println("  Size: ${width}x${height}")
        println("  Length: $length")
        println("  FPS: $fps")

        val workflowJson = workflowManager.prepareVideoWorkflow(
            workflowName = workflowName,
            prompt = prompt,
            highnoiseUnet = highnoiseUnet,
            lownoiseUnet = lownoiseUnet,
            highnoiseLora = highnoiseLora,
            lownoiseLora = lownoiseLora,
            vae = vae,
            clip = clip,
            width = width,
            height = height,
            length = length,
            fps = fps
        )

        if (workflowJson != null) {
            val activity = requireActivity() as MainContainerActivity
            activity.startGeneration(workflowJson) { success, promptId, errorMessage ->
                if (success && promptId != null) {
                    println("TextToVideoFragment: Workflow submitted successfully. Prompt ID: $promptId")
                } else {
                    println("TextToVideoFragment: Failed to submit workflow: $errorMessage")
                }
            }
        } else {
            println("TextToVideoFragment: Error: Could not load workflow")
            Toast.makeText(requireContext(), R.string.error_failed_load_workflow, Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchUNETs() {
        println("TextToVideoFragment: Fetching UNETs from server...")
        comfyUIClient.fetchUNETs { unets ->
            println("TextToVideoFragment: Received ${unets.size} UNETs")

            activity?.runOnUiThread {
                availableUNETs.clear()
                availableUNETs.addAll(unets)
                updateUNETDropdowns()
                restoreUNETConfiguration()
            }
        }
    }

    private fun fetchLoRAs() {
        println("TextToVideoFragment: Fetching LoRAs from server...")
        comfyUIClient.fetchLoRAs { loras ->
            println("TextToVideoFragment: Received ${loras.size} LoRAs")

            activity?.runOnUiThread {
                availableLoRAs.clear()
                availableLoRAs.addAll(loras)
                updateLoRADropdowns()
                restoreLoRAConfiguration()
            }
        }
    }

    private fun fetchVAEs() {
        println("TextToVideoFragment: Fetching VAEs from server...")
        comfyUIClient.fetchVAEs { vaes ->
            println("TextToVideoFragment: Received ${vaes.size} VAEs")

            activity?.runOnUiThread {
                availableVAEs.clear()
                availableVAEs.addAll(vaes)
                updateVAEDropdown()
                restoreVAEConfiguration()
            }
        }
    }

    private fun fetchCLIPs() {
        println("TextToVideoFragment: Fetching CLIPs from server...")
        comfyUIClient.fetchCLIPs { clips ->
            println("TextToVideoFragment: Received ${clips.size} CLIPs")

            activity?.runOnUiThread {
                availableCLIPs.clear()
                availableCLIPs.addAll(clips)
                updateCLIPDropdown()
                restoreCLIPConfiguration()
            }
        }
    }

    private fun fetchGeneratedVideo(promptId: String?) {
        if (promptId == null) {
            println("fetchGeneratedVideo: promptId is null!")
            return
        }

        println("fetchGeneratedVideo: Fetching history for prompt $promptId")

        comfyUIClient.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                println("fetchGeneratedVideo: Received history JSON")
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)

                        // Debug: print all keys in the node output
                        println("fetchGeneratedVideo: Node $nodeId has keys: ${nodeOutput.keys().asSequence().toList()}")

                        // Check for videos array first
                        var videos = nodeOutput.optJSONArray("videos")

                        // If no videos, check for gifs array (some ComfyUI video nodes output as gifs)
                        if (videos == null || videos.length() == 0) {
                            videos = nodeOutput.optJSONArray("gifs")
                        }

                        // If still no videos, check for video files in images array
                        // Some video nodes (like CreateVideo) output to images array with animated=true
                        if (videos == null || videos.length() == 0) {
                            val images = nodeOutput.optJSONArray("images")
                            if (images != null && images.length() > 0) {
                                val imageInfo = images.getJSONObject(0)
                                val filename = imageInfo.optString("filename")
                                println("fetchGeneratedVideo: Node $nodeId images[0] = $imageInfo")
                                // Check if this is actually a video file
                                if (filename.lowercase().let {
                                    it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".gif") || it.endsWith(".avi")
                                }) {
                                    println("fetchGeneratedVideo: Found video file in images array: $filename")
                                    videos = images
                                }
                            }
                        }

                        if (videos != null && videos.length() > 0) {
                            val videoInfo = videos.getJSONObject(0)
                            val filename = videoInfo.optString("filename")
                            val subfolder = videoInfo.optString("subfolder", "")
                            val type = videoInfo.optString("type", "output")

                            println("fetchGeneratedVideo: Fetching video - filename=$filename, subfolder=$subfolder, type=$type")

                            comfyUIClient.fetchVideo(filename, subfolder, type) { videoBytes ->
                                activity?.runOnUiThread {
                                    if (isAdded && view != null) {
                                        displayVideo(videoBytes)
                                        (requireActivity() as MainContainerActivity).completeGeneration()
                                    } else {
                                        (activity as? MainContainerActivity)?.completeGeneration()
                                    }
                                }
                            }
                            return@fetchHistory
                        }
                    }

                    println("fetchGeneratedVideo: No videos found in any node output")
                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), R.string.error_no_videos_found, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    println("fetchGeneratedVideo: Failed to parse history: ${e.message}")
                    e.printStackTrace()
                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), R.string.error_failed_fetch_video, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                println("fetchGeneratedVideo: Failed to fetch history - historyJson is null")
                activity?.runOnUiThread {
                    (activity as? MainContainerActivity)?.completeGeneration()
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_fetch_history, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun displayVideo(videoBytes: ByteArray?) {
        if (!isAdded || view == null) {
            println("displayVideo: Fragment not in valid state")
            return
        }

        if (videoBytes != null) {
            try {
                // Save video to internal storage
                val videoFile = File(requireContext().filesDir, LAST_VIDEO_FILENAME)
                videoFile.writeBytes(videoBytes)
                currentVideoPath = videoFile.absolutePath

                // Play the video using MediaPlayer with TextureView
                playVideo(currentVideoPath!!)

                println("Video saved and playing: $currentVideoPath")
            } catch (e: Exception) {
                println("Failed to save/display video: ${e.message}")
                Toast.makeText(requireContext(), R.string.error_failed_display_video, Toast.LENGTH_LONG).show()
            }
        } else {
            println("Failed to load video - bytes are null")
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
        generateButton.text = getString(R.string.button_cancel_generation)
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
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        generateButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
    }

    /**
     * Update progress bar with current progress
     */
    private fun updateProgress(current: Int, max: Int) {
        // Only update progress for meaningful progress reports (more than 1 step)
        // Single-step progress reports (max=1) are typically node transitions
        if (max > 1) {
            activity?.runOnUiThread {
                progressBar.max = max
                progressBar.progress = current
            }
        }
    }

    /**
     * Restore the last generated video from internal storage
     */
    private fun restoreLastGeneratedVideo() {
        try {
            val videoFile = File(requireContext().filesDir, LAST_VIDEO_FILENAME)
            if (videoFile.exists()) {
                currentVideoPath = videoFile.absolutePath
                // Video will start playing when TextureView surface becomes available
                // (handled in surfaceTextureListener)
                println("Restored last generated video path from internal storage")
            }
        } catch (e: Exception) {
            println("Failed to restore last generated video: ${e.message}")
        }
    }

    private fun showFullscreenVideoViewer(videoPath: String) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_fullscreen_video)

        val fullscreenVideoView = dialog.findViewById<VideoView>(R.id.fullscreenVideoView)
        fullscreenVideoView.setVideoPath(videoPath)

        // Add media controller for video controls
        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(fullscreenVideoView)
        fullscreenVideoView.setMediaController(mediaController)

        fullscreenVideoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.start()
        }

        // Tap to dismiss
        fullscreenVideoView.setOnClickListener {
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
            shareVideo()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun saveToGallery() {
        val videoPath = currentVideoPath ?: return
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ComfyChair")
        }

        val uri = requireContext().contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                requireContext().contentResolver.openOutputStream(it)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Toast.makeText(requireContext(), R.string.video_saved_to_gallery, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                println("Failed to save to gallery: ${e.message}")
                Toast.makeText(requireContext(), R.string.failed_save_video, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAsFile() {
        val filename = "ComfyChair_${System.currentTimeMillis()}.mp4"
        saveVideoLauncher.launch(filename)
    }

    private fun saveVideoToUri(uri: Uri) {
        val videoPath = currentVideoPath ?: return
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return

        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                videoFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: IOException) {
            println("Failed to save video: ${e.message}")
            Toast.makeText(requireContext(), R.string.failed_save_video, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideo() {
        val videoPath = currentVideoPath ?: return
        val videoFile = File(videoPath)
        if (!videoFile.exists()) return

        try {
            val cachePath = requireContext().cacheDir
            val filename = "share_video_${System.currentTimeMillis()}.mp4"
            val cacheFile = File(cachePath, filename)
            videoFile.copyTo(cacheFile, overwrite = true)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().applicationContext.packageName}.fileprovider",
                cacheFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_video)))
        } catch (e: Exception) {
            println("Failed to share video: ${e.message}")
            Toast.makeText(requireContext(), R.string.failed_share_video, Toast.LENGTH_SHORT).show()
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

        // Dropdown listeners
        workflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        highnoiseUnetDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        lownoiseUnetDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        highnoiseLoraDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        lownoiseLoraDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        vaeDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        clipDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }

        // Input field listeners
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        }
        widthInput.addTextChangedListener(textWatcher)
        heightInput.addTextChangedListener(textWatcher)
        lengthInput.addTextChangedListener(textWatcher)
        fpsInput.addTextChangedListener(textWatcher)
    }

    /**
     * Save current configuration to SharedPreferences
     */
    private fun saveConfiguration() {
        if (isLoadingConfiguration) {
            return
        }

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(PREF_PROMPT, promptInput.text.toString())
                putString(PREF_WORKFLOW, workflowDropdown.text.toString())
                putString(PREF_HIGHNOISE_UNET, highnoiseUnetDropdown.text.toString())
                putString(PREF_LOWNOISE_UNET, lownoiseUnetDropdown.text.toString())
                putString(PREF_HIGHNOISE_LORA, highnoiseLoraDropdown.text.toString())
                putString(PREF_LOWNOISE_LORA, lownoiseLoraDropdown.text.toString())
                putString(PREF_VAE, vaeDropdown.text.toString())
                putString(PREF_CLIP, clipDropdown.text.toString())
                putString(PREF_WIDTH, widthInput.text.toString())
                putString(PREF_HEIGHT, heightInput.text.toString())
                putString(PREF_LENGTH, lengthInput.text.toString())
                putString(PREF_FPS, fpsInput.text.toString())
                apply()
            }
        } catch (e: Exception) {
            println("Failed to save configuration: ${e.message}")
        }
    }

    /**
     * Load saved configuration from SharedPreferences
     */
    private fun loadConfiguration() {
        isLoadingConfiguration = true

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            prefs.getString(PREF_PROMPT, null)?.let {
                promptInput.setText(it)
            }
            prefs.getString(PREF_WORKFLOW, null)?.let {
                workflowDropdown.setText(it, false)
            }
            prefs.getString(PREF_WIDTH, null)?.let {
                widthInput.setText(it)
            }
            prefs.getString(PREF_HEIGHT, null)?.let {
                heightInput.setText(it)
            }
            prefs.getString(PREF_LENGTH, null)?.let {
                lengthInput.setText(it)
            }
            prefs.getString(PREF_FPS, null)?.let {
                fpsInput.setText(it)
            }
        } catch (e: Exception) {
            println("Failed to load configuration: ${e.message}")
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 500)
        }
    }

    /**
     * Restore UNET selections after server data is loaded
     */
    private fun restoreUNETConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_HIGHNOISE_UNET, null)?.let { saved ->
                if (availableUNETs.contains(saved)) {
                    highnoiseUnetDropdown.setText(saved, false)
                }
            }
            prefs.getString(PREF_LOWNOISE_UNET, null)?.let { saved ->
                if (availableUNETs.contains(saved)) {
                    lownoiseUnetDropdown.setText(saved, false)
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    /**
     * Restore LoRA selections after server data is loaded
     */
    private fun restoreLoRAConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_HIGHNOISE_LORA, null)?.let { saved ->
                if (availableLoRAs.contains(saved)) {
                    highnoiseLoraDropdown.setText(saved, false)
                }
            }
            prefs.getString(PREF_LOWNOISE_LORA, null)?.let { saved ->
                if (availableLoRAs.contains(saved)) {
                    lownoiseLoraDropdown.setText(saved, false)
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
            prefs.getString(PREF_VAE, null)?.let { saved ->
                if (availableVAEs.contains(saved)) {
                    vaeDropdown.setText(saved, false)
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
            prefs.getString(PREF_CLIP, null)?.let { saved ->
                if (availableCLIPs.contains(saved)) {
                    clipDropdown.setText(saved, false)
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
        saveConfiguration()
        configBottomSheet?.dismiss()
        releaseMediaPlayer()
        currentVideoPath = null
    }
}
