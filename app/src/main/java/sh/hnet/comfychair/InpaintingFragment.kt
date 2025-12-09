package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * InpaintingFragment - Inpainting screen for ComfyUI
 * Allows users to select a source image, paint a mask, and generate inpainted images
 */
class InpaintingFragment : Fragment(), MainContainerActivity.GenerationStateListener {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var viewModeToggle: MaterialButtonToggleGroup

    // Source image view elements
    private lateinit var sourceImageContainer: View
    private lateinit var sourceImageView: ImageView
    private lateinit var maskOverlayView: ImageView
    private lateinit var sourcePlaceholderIcon: ImageView

    // Preview elements
    private lateinit var previewContainer: View
    private lateinit var previewImageView: ImageView
    private lateinit var previewPlaceholderIcon: ImageView
    private lateinit var progressOverlay: View
    private lateinit var progressBar: android.widget.ProgressBar

    private lateinit var imageInstructionText: android.widget.TextView
    private lateinit var promptInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var promptInput: TextInputEditText
    private lateinit var generateButton: Button
    private lateinit var settingsButton: Button

    // Modal bottom sheet for configuration
    private var configBottomSheet: BottomSheetDialog? = null
    private lateinit var configModeToggle: MaterialButtonToggleGroup
    // Checkpoint mode UI elements
    private lateinit var workflowDropdown: AutoCompleteTextView
    private lateinit var checkpointDropdown: AutoCompleteTextView
    private lateinit var megapixelsInput: TextInputEditText
    private lateinit var stepsInput: TextInputEditText
    // Checkpoint mode TextInputLayout wrappers for validation
    private lateinit var megapixelsInputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var stepsInputLayout: com.google.android.material.textfield.TextInputLayout
    // UNET mode UI elements
    private lateinit var unetWorkflowDropdown: AutoCompleteTextView
    private lateinit var unetDropdown: AutoCompleteTextView
    private lateinit var vaeDropdown: AutoCompleteTextView
    private lateinit var clipDropdown: AutoCompleteTextView
    private lateinit var unetStepsInput: TextInputEditText
    // UNET mode TextInputLayout wrappers for validation
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

    // Source image and mask
    private var sourceImageBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var currentPreviewBitmap: Bitmap? = null

    // Track if generation is in progress
    private var isGenerating = false
    private var currentPromptId: String? = null

    // Track if data has been fetched from server
    private var dataFetched = false

    // Track which view is showing (source or preview)
    private var isShowingSource = true

    // Filename for persisting images
    private val LAST_PREVIEW_FILENAME = "inpainting_last_preview.png"
    private val LAST_SOURCE_FILENAME = "inpainting_last_source.png"
    private val LAST_MASK_FILENAME = "inpainting_last_mask.png"

    // Activity result launcher for picking source image
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { loadSourceImage(it) }
    }

    // Activity result launcher for "Save as..."
    private val saveImageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { saveImageToUri(it) }
    }

    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"

        // SharedPreferences constants
        private const val PREFS_NAME = "InpaintingFragmentPrefs"
        private const val PREF_IS_CHECKPOINT_MODE = "isCheckpointMode"
        private const val PREF_PROMPT = "prompt"

        // Checkpoint mode preferences
        private const val PREF_CHECKPOINT_WORKFLOW = "checkpointWorkflow"
        private const val PREF_CHECKPOINT_MODEL = "checkpointModel"
        private const val PREF_CHECKPOINT_MEGAPIXELS = "checkpointMegapixels"
        private const val PREF_CHECKPOINT_STEPS = "checkpointSteps"

        // UNET mode preferences
        private const val PREF_UNET_WORKFLOW = "unetWorkflow"
        private const val PREF_UNET_MODEL = "unetModel"
        private const val PREF_UNET_VAE = "unetVAE"
        private const val PREF_UNET_CLIP = "unetCLIP"
        private const val PREF_UNET_STEPS = "unetSteps"

        fun newInstance(hostname: String, port: Int): InpaintingFragment {
            val fragment = InpaintingFragment()
            val args = Bundle()
            args.putString(ARG_HOSTNAME, hostname)
            args.putInt(ARG_PORT, port)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        return inflater.inflate(R.layout.fragment_inpainting, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, 0)
            insets
        }

        val activity = requireActivity() as MainContainerActivity
        comfyUIClient = activity.getComfyUIClient()

        workflowManager = WorkflowManager(requireContext())

        initializeViews(view)
        initializeConfigBottomSheet()
        setupDropdowns()
        setupButtonListeners()
        setupImageViewListeners()
        setupViewModeToggle()

        restoreLastImages()
        loadConfiguration()
        setupAutoSaveListeners()
        fetchServerData()
    }

    private fun fetchServerData() {
        val activity = requireActivity() as MainContainerActivity
        activity.onConnectionReady {
            if (!dataFetched) {
                println("InpaintingFragment: Connection ready, fetching server data...")
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
        val activity = requireActivity() as MainContainerActivity
        activity.setGenerationStateListener(this)
    }

    override fun onPause() {
        super.onPause()
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
            // Switch to preview tab when generation starts
            if (isShowingSource) {
                viewModeToggle.check(R.id.previewButton)
            }
        } else {
            resetGenerateButton()
            hideProgressOverlay()
        }
    }

    override fun onProgressUpdate(current: Int, max: Int) {
        updateProgress(current, max)
    }

    override fun onPreviewImage(bitmap: Bitmap) {
        // Display preview image during generation
        if (isAdded && view != null) {
            previewImageView.setImageBitmap(bitmap)
            previewPlaceholderIcon.visibility = View.GONE
        }
    }

    override fun onImageGenerated(promptId: String) {
        println("InpaintingFragment: Image generated for prompt: $promptId")
        if (isAdded && view != null) {
            fetchGeneratedImage(promptId)
        }
    }

    override fun onGenerationError(message: String) {
        println("InpaintingFragment: Generation error: $message")
        resetGenerateButton()
        hideProgressOverlay()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        viewModeToggle = view.findViewById(R.id.viewModeToggle)

        // Source image views
        sourceImageContainer = view.findViewById(R.id.sourceImageContainer)
        sourceImageView = view.findViewById(R.id.sourceImageView)
        maskOverlayView = view.findViewById(R.id.maskOverlayView)
        sourcePlaceholderIcon = view.findViewById(R.id.sourcePlaceholderIcon)

        // Preview views
        previewContainer = view.findViewById(R.id.previewContainer)
        previewImageView = view.findViewById(R.id.previewImageView)
        previewPlaceholderIcon = view.findViewById(R.id.previewPlaceholderIcon)
        progressOverlay = view.findViewById(R.id.progressOverlay)
        progressBar = view.findViewById(R.id.progressBar)

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

    private fun setupViewModeToggle() {
        viewModeToggle.check(R.id.sourceImageButton)

        viewModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.sourceImageButton -> {
                        isShowingSource = true
                        sourceImageContainer.visibility = View.VISIBLE
                        previewContainer.visibility = View.GONE
                        imageInstructionText.text = getString(R.string.long_press_hint)
                    }
                    R.id.previewButton -> {
                        isShowingSource = false
                        sourceImageContainer.visibility = View.GONE
                        previewContainer.visibility = View.VISIBLE
                        imageInstructionText.text = getString(R.string.tap_image_hint)
                    }
                }
            }
        }
    }

    private fun initializeConfigBottomSheet() {
        configBottomSheet = BottomSheetDialog(requireContext())

        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_inpainting_config, null)
        configBottomSheet?.setContentView(bottomSheetView)

        // Initialize checkpoint mode UI elements
        workflowDropdown = bottomSheetView.findViewById(R.id.workflowDropdown)
        checkpointDropdown = bottomSheetView.findViewById(R.id.checkpointDropdown)
        megapixelsInput = bottomSheetView.findViewById(R.id.megapixelsInput)
        stepsInput = bottomSheetView.findViewById(R.id.stepsInput)
        // Initialize checkpoint mode TextInputLayout wrappers for validation
        megapixelsInputLayout = bottomSheetView.findViewById(R.id.megapixelsInputLayout)
        stepsInputLayout = bottomSheetView.findViewById(R.id.stepsInputLayout)

        // Initialize UNET mode UI elements
        unetWorkflowDropdown = bottomSheetView.findViewById(R.id.unetWorkflowDropdown)
        unetDropdown = bottomSheetView.findViewById(R.id.unetDropdown)
        vaeDropdown = bottomSheetView.findViewById(R.id.vaeDropdown)
        clipDropdown = bottomSheetView.findViewById(R.id.clipDropdown)
        unetStepsInput = bottomSheetView.findViewById(R.id.unetStepsInput)
        // Initialize UNET mode TextInputLayout wrappers for validation
        unetStepsInputLayout = bottomSheetView.findViewById(R.id.unetStepsInputLayout)

        // Set up segmented button toggle
        configModeToggle = bottomSheetView.findViewById(R.id.configModeToggle)
        val checkpointContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.checkpointContent)
        val unetContent = bottomSheetView.findViewById<android.widget.LinearLayout>(R.id.unetContent)

        configModeToggle.check(R.id.checkpointModeButton)

        configModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.checkpointModeButton -> {
                        checkpointContent.visibility = View.VISIBLE
                        unetContent.visibility = View.GONE
                        isCheckpointMode = true
                        saveConfiguration()
                    }
                    R.id.unetModeButton -> {
                        checkpointContent.visibility = View.GONE
                        unetContent.visibility = View.VISIBLE
                        isCheckpointMode = false
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
        // Megapixels validation
        megapixelsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().trim()
                megapixelsInputLayout.error = when {
                    value.isEmpty() -> null
                    else -> {
                        val floatValue = value.toFloatOrNull()
                        if (floatValue == null || floatValue < 0.1f || floatValue > 8.3f) {
                            getString(R.string.error_invalid_megapixels)
                        } else {
                            null
                        }
                    }
                }
            }
        })

        // Steps validation (checkpoint mode)
        stepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().trim()
                stepsInputLayout.error = when {
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

        // Steps validation (UNET mode)
        unetStepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().trim()
                unetStepsInputLayout.error = when {
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

    private fun showConfigBottomSheet() {
        configBottomSheet?.show()
    }

    private fun setupDropdowns() {
        // Setup inpainting checkpoint workflows dropdown
        val checkpointWorkflowNames = workflowManager.getInpaintingCheckpointWorkflowNames()
        val checkpointWorkflowAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            checkpointWorkflowNames
        )
        workflowDropdown.setAdapter(checkpointWorkflowAdapter)
        if (checkpointWorkflowNames.isNotEmpty()) {
            workflowDropdown.setText(checkpointWorkflowNames[0], false)
        }

        // Setup inpainting UNET workflows dropdown
        val unetWorkflowNames = workflowManager.getInpaintingUNETWorkflowNames()
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
        // Source image long press for options menu
        sourceImageView.setOnLongClickListener {
            showSourceImageOptions()
            true
        }

        // Preview image tap to view fullscreen
        previewImageView.setOnClickListener {
            currentPreviewBitmap?.let { bitmap ->
                showFullscreenImageViewer(bitmap)
            }
        }

        // Preview image long press for save options
        previewImageView.setOnLongClickListener {
            currentPreviewBitmap?.let {
                showSaveOptions()
                true
            } ?: false
        }
    }

    private fun showSourceImageOptions() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_source_image, null)
        bottomSheetDialog.setContentView(view)

        val uploadOption = view.findViewById<View>(R.id.uploadSourceImage)
        val editMaskOption = view.findViewById<View>(R.id.editMask)
        val clearMaskOption = view.findViewById<View>(R.id.clearMask)

        // Show edit/clear mask options only if source image exists
        if (sourceImageBitmap != null) {
            editMaskOption.visibility = View.VISIBLE
            clearMaskOption.visibility = View.VISIBLE
        }

        uploadOption.setOnClickListener {
            bottomSheetDialog.dismiss()
            pickSourceImage()
        }

        editMaskOption.setOnClickListener {
            bottomSheetDialog.dismiss()
            showMaskEditor()
        }

        clearMaskOption.setOnClickListener {
            bottomSheetDialog.dismiss()
            clearMask()
        }

        bottomSheetDialog.show()
    }

    private fun pickSourceImage() {
        pickImageLauncher.launch("image/*")
    }

    private fun loadSourceImage(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                sourceImageBitmap = bitmap
                sourceImageView.setImageBitmap(bitmap)
                sourcePlaceholderIcon.visibility = View.GONE

                // Clear existing mask when new image is loaded
                maskBitmap = null
                maskOverlayView.setImageBitmap(null)

                // Save source image
                saveSourceImage(bitmap)

                println("Source image loaded: ${bitmap.width}x${bitmap.height}")
            }
        } catch (e: Exception) {
            println("Failed to load source image: ${e.message}")
            Toast.makeText(requireContext(), R.string.error_failed_load_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMaskEditor() {
        val sourceBitmap = sourceImageBitmap ?: run {
            Toast.makeText(requireContext(), getString(R.string.no_source_image), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_mask_editor)

        val toolbar = dialog.findViewById<MaterialToolbar>(R.id.maskEditorToolbar)
        val backgroundImage = dialog.findViewById<ImageView>(R.id.backgroundImage)
        val maskPaintView = dialog.findViewById<MaskPaintView>(R.id.maskPaintView)
        val brushSizeSlider = dialog.findViewById<Slider>(R.id.brushSizeSlider)
        val clearMaskButton = dialog.findViewById<Button>(R.id.clearMaskButton)
        val invertMaskButton = dialog.findViewById<Button>(R.id.invertMaskButton)
        val doneButton = dialog.findViewById<Button>(R.id.doneButton)

        // Set up background image
        backgroundImage.setImageBitmap(sourceBitmap)

        // Set up mask paint view
        maskPaintView.setSourceImageSize(sourceBitmap.width, sourceBitmap.height)

        // Restore existing mask if any
        maskBitmap?.let { maskPaintView.setMaskBitmap(it) }

        // Brush size slider
        brushSizeSlider.addOnChangeListener { _, value, _ ->
            maskPaintView.brushSize = value
        }

        // Clear mask button
        clearMaskButton.setOnClickListener {
            maskPaintView.clearMask()
        }

        // Invert mask button
        invertMaskButton.setOnClickListener {
            maskPaintView.invertMask()
        }

        // Done button - save mask and close
        doneButton.setOnClickListener {
            val newMask = maskPaintView.getMaskBitmap()
            if (newMask != null) {
                maskBitmap = newMask
                updateMaskOverlay()
                saveMaskImage(newMask)
            }
            maskPaintView.recycle()
            dialog.dismiss()
        }

        // Close button (X)
        toolbar.setNavigationOnClickListener {
            maskPaintView.recycle()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateMaskOverlay() {
        val mask = maskBitmap ?: return

        // Create a semi-transparent red overlay from the mask
        val overlayBitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlayBitmap)

        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)

        for (i in pixels.indices) {
            val brightness = Color.red(pixels[i])
            if (brightness > 128) {
                // White area (inpaint) -> semi-transparent red
                pixels[i] = Color.argb(128, 255, 0, 0)
            } else {
                // Black area -> transparent
                pixels[i] = Color.TRANSPARENT
            }
        }

        overlayBitmap.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        maskOverlayView.setImageBitmap(overlayBitmap)
    }

    private fun clearMask() {
        maskBitmap = null
        maskOverlayView.setImageBitmap(null)
        deleteMaskImage()
    }

    private fun cancelImageGeneration() {
        val activity = requireActivity() as MainContainerActivity
        activity.cancelGeneration { success ->
            if (success) {
                println("InpaintingFragment: Generation canceled successfully")
            } else {
                println("InpaintingFragment: Failed to cancel generation")
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
        megapixelsInputLayout.error = null
        stepsInputLayout.error = null
        unetStepsInputLayout.error = null

        if (isCheckpointMode) {
            // Validate checkpoint mode fields
            val megapixels = megapixelsInput.text.toString().toFloatOrNull()
            if (megapixels == null || megapixels < 0.1f || megapixels > 8.3f) {
                megapixelsInputLayout.error = getString(R.string.error_invalid_megapixels)
                isValid = false
            }

            val steps = stepsInput.text.toString().toIntOrNull()
            if (steps == null || steps !in 1..255) {
                stepsInputLayout.error = getString(R.string.error_invalid_steps)
                isValid = false
            }
        } else {
            // Validate UNET mode fields
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
        // Validate inputs
        if (sourceImageBitmap == null) {
            Toast.makeText(requireContext(), getString(R.string.no_source_image), Toast.LENGTH_SHORT).show()
            return
        }

        if (maskBitmap == null || !hasMaskContent()) {
            Toast.makeText(requireContext(), R.string.paint_mask_hint, Toast.LENGTH_SHORT).show()
            return
        }

        // Validate configuration before starting
        if (!validateConfiguration()) {
            return
        }

        // Clear preview image before starting generation
        currentPreviewBitmap = null
        previewImageView.setImageDrawable(null)
        previewPlaceholderIcon.visibility = View.VISIBLE

        val prompt = promptInput.text.toString()

        // Combine source image with mask (RGBA where A is mask)
        val combinedImage = createImageWithMask()
        if (combinedImage == null) {
            Toast.makeText(requireContext(), R.string.error_failed_prepare_image, Toast.LENGTH_SHORT).show()
            return
        }

        // Convert to PNG bytes
        val outputStream = ByteArrayOutputStream()
        combinedImage.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val imageBytes = outputStream.toByteArray()

        // Generate unique filename
        val filename = "comfychair_inpaint_${System.currentTimeMillis()}.png"

        // Upload image to server
        Toast.makeText(requireContext(), R.string.uploading_image, Toast.LENGTH_SHORT).show()

        comfyUIClient.uploadImage(imageBytes, filename) { success, uploadedFilename, errorMessage ->
            activity?.runOnUiThread {
                if (success && uploadedFilename != null) {
                    println("InpaintingFragment: Image uploaded: $uploadedFilename")
                    submitInpaintingWorkflow(prompt, uploadedFilename)
                } else {
                    println("InpaintingFragment: Upload failed: $errorMessage")
                    Toast.makeText(requireContext(), getString(R.string.error_failed_upload_image, errorMessage), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasMaskContent(): Boolean {
        val mask = maskBitmap ?: return false
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)

        for (pixel in pixels) {
            if (Color.red(pixel) > 128) {
                return true
            }
        }
        return false
    }

    private fun createImageWithMask(): Bitmap? {
        val source = sourceImageBitmap ?: return null
        val mask = maskBitmap ?: return null

        // Create RGBA image where alpha channel contains the mask
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw source image
        canvas.drawBitmap(source, 0f, 0f, null)

        // Scale mask if needed
        val scaledMask = if (mask.width != source.width || mask.height != source.height) {
            Bitmap.createScaledBitmap(mask, source.width, source.height, true)
        } else {
            mask
        }

        // Combine RGB from source with mask as alpha
        val sourcePixels = IntArray(source.width * source.height)
        val maskPixels = IntArray(source.width * source.height)
        result.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        scaledMask.getPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)

        for (i in sourcePixels.indices) {
            val r = Color.red(sourcePixels[i])
            val g = Color.green(sourcePixels[i])
            val b = Color.blue(sourcePixels[i])
            // Mask logic for ComfyUI:
            // - LoadImage extracts mask from alpha: transparent (alpha=0) becomes WHITE (1.0) in mask
            // - White mask = area to inpaint
            // - So painted areas (maskValue=255) need alpha=0 to become white in ComfyUI mask
            val maskValue = Color.red(maskPixels[i])
            // Painted area (white=255) -> alpha=0 (transparent) -> white in ComfyUI mask (inpaint)
            // Unpainted area (black=0) -> alpha=255 (opaque) -> black in ComfyUI mask (keep)
            val alpha = 255 - maskValue
            sourcePixels[i] = Color.argb(alpha, r, g, b)
        }

        result.setPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        if (scaledMask != mask) {
            scaledMask.recycle()
        }

        return result
    }

    private fun submitInpaintingWorkflow(prompt: String, imageFilename: String) {
        val workflowJson = if (isCheckpointMode) {
            val workflowName = workflowDropdown.text.toString()
            val checkpoint = checkpointDropdown.text.toString()
            val megapixels = megapixelsInput.text.toString().toFloatOrNull() ?: 1.0f
            val steps = stepsInput.text.toString().toIntOrNull() ?: 20

            println("InpaintingFragment: Starting inpainting with:")
            println("  Mode: Checkpoint")
            println("  Prompt: $prompt")
            println("  Workflow: $workflowName")
            println("  Checkpoint: $checkpoint")
            println("  Megapixels: $megapixels")
            println("  Steps: $steps")
            println("  Image: $imageFilename")

            workflowManager.prepareInpaintingWorkflow(
                workflowName = workflowName,
                prompt = prompt,
                checkpoint = checkpoint,
                megapixels = megapixels,
                steps = steps,
                imageFilename = imageFilename
            )
        } else {
            val workflowName = unetWorkflowDropdown.text.toString()
            val unet = unetDropdown.text.toString()
            val vae = vaeDropdown.text.toString()
            val clip = clipDropdown.text.toString()
            val steps = unetStepsInput.text.toString().toIntOrNull() ?: 9

            println("InpaintingFragment: Starting inpainting with:")
            println("  Mode: UNET")
            println("  Prompt: $prompt")
            println("  Workflow: $workflowName")
            println("  UNET: $unet")
            println("  VAE: $vae")
            println("  CLIP: $clip")
            println("  Steps: $steps")
            println("  Image: $imageFilename")

            workflowManager.prepareInpaintingWorkflow(
                workflowName = workflowName,
                prompt = prompt,
                unet = unet,
                vae = vae,
                clip = clip,
                steps = steps,
                imageFilename = imageFilename
            )
        }

        if (workflowJson != null) {
            val activity = requireActivity() as MainContainerActivity
            activity.startGeneration(workflowJson) { success, promptId, errorMessage ->
                if (success && promptId != null) {
                    println("InpaintingFragment: Workflow submitted successfully. Prompt ID: $promptId")
                } else {
                    println("InpaintingFragment: Failed to submit workflow: $errorMessage")
                }
            }
        } else {
            println("InpaintingFragment: Error: Could not load workflow")
            Toast.makeText(requireContext(), R.string.error_failed_load_workflow, Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchCheckpoints() {
        comfyUIClient.fetchCheckpoints { checkpoints ->
            activity?.runOnUiThread {
                availableCheckpoints.clear()
                availableCheckpoints.addAll(checkpoints)
                updateCheckpointDropdown()
                restoreCheckpointConfiguration()
            }
        }
    }

    private fun fetchUNETs() {
        comfyUIClient.fetchUNETs { unets ->
            activity?.runOnUiThread {
                availableUNETs.clear()
                availableUNETs.addAll(unets)
                updateUNETDropdown()
                restoreUNETConfiguration()
            }
        }
    }

    private fun fetchVAEs() {
        comfyUIClient.fetchVAEs { vaes ->
            activity?.runOnUiThread {
                availableVAEs.clear()
                availableVAEs.addAll(vaes)
                updateVAEDropdown()
                restoreVAEConfiguration()
            }
        }
    }

    private fun fetchCLIPs() {
        comfyUIClient.fetchCLIPs { clips ->
            activity?.runOnUiThread {
                availableCLIPs.clear()
                availableCLIPs.addAll(clips)
                updateCLIPDropdown()
                restoreCLIPConfiguration()
            }
        }
    }

    private fun fetchGeneratedImage(promptId: String?) {
        if (promptId == null) return

        comfyUIClient.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            comfyUIClient.fetchImage(filename, subfolder, type) { bitmap ->
                                activity?.runOnUiThread {
                                    if (isAdded && view != null) {
                                        displayPreviewImage(bitmap)
                                        (requireActivity() as MainContainerActivity).completeGeneration()
                                    }
                                }
                            }
                            return@fetchHistory
                        }
                    }

                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), R.string.error_no_images_found, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        (activity as? MainContainerActivity)?.completeGeneration()
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), R.string.error_failed_fetch_image, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                activity?.runOnUiThread {
                    (activity as? MainContainerActivity)?.completeGeneration()
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_fetch_history, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun displayPreviewImage(bitmap: Bitmap?) {
        if (!isAdded || view == null) return

        if (bitmap != null) {
            currentPreviewBitmap = bitmap
            previewImageView.setImageBitmap(bitmap)
            previewPlaceholderIcon.visibility = View.GONE

            // Switch to preview tab
            viewModeToggle.check(R.id.previewButton)

            savePreviewImage(bitmap)
        }
    }

    private fun showProgressOverlay() {
        progressOverlay.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.max = 100
    }

    private fun hideProgressOverlay() {
        progressOverlay.visibility = View.GONE
    }

    private fun setGeneratingState() {
        isGenerating = true
        generateButton.text = getString(R.string.button_cancel_generation)
        generateButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cancel_red)
        )
    }

    private fun resetGenerateButton() {
        isGenerating = false
        generateButton.text = getString(R.string.button_generate)
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        generateButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
    }

    private fun updateProgress(current: Int, max: Int) {
        activity?.runOnUiThread {
            progressBar.max = max
            progressBar.progress = current
        }
    }

    // Image persistence methods

    private fun saveSourceImage(bitmap: Bitmap) {
        try {
            requireContext().openFileOutput(LAST_SOURCE_FILENAME, Context.MODE_PRIVATE).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: Exception) {
            println("Failed to save source image: ${e.message}")
        }
    }

    private fun saveMaskImage(bitmap: Bitmap) {
        try {
            requireContext().openFileOutput(LAST_MASK_FILENAME, Context.MODE_PRIVATE).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: Exception) {
            println("Failed to save mask image: ${e.message}")
        }
    }

    private fun deleteMaskImage() {
        try {
            requireContext().deleteFile(LAST_MASK_FILENAME)
        } catch (e: Exception) {
            println("Failed to delete mask image: ${e.message}")
        }
    }

    private fun savePreviewImage(bitmap: Bitmap) {
        try {
            requireContext().openFileOutput(LAST_PREVIEW_FILENAME, Context.MODE_PRIVATE).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: Exception) {
            println("Failed to save preview image: ${e.message}")
        }
    }

    private fun restoreLastImages() {
        // Restore source image
        try {
            val sourceFile = requireContext().getFileStreamPath(LAST_SOURCE_FILENAME)
            if (sourceFile.exists()) {
                requireContext().openFileInput(LAST_SOURCE_FILENAME).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        sourceImageBitmap = bitmap
                        sourceImageView.setImageBitmap(bitmap)
                        sourcePlaceholderIcon.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to restore source image: ${e.message}")
        }

        // Restore mask
        try {
            val maskFile = requireContext().getFileStreamPath(LAST_MASK_FILENAME)
            if (maskFile.exists()) {
                requireContext().openFileInput(LAST_MASK_FILENAME).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        maskBitmap = bitmap
                        updateMaskOverlay()
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to restore mask: ${e.message}")
        }

        // Restore preview
        try {
            val previewFile = requireContext().getFileStreamPath(LAST_PREVIEW_FILENAME)
            if (previewFile.exists()) {
                requireContext().openFileInput(LAST_PREVIEW_FILENAME).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        currentPreviewBitmap = bitmap
                        previewImageView.setImageBitmap(bitmap)
                        previewPlaceholderIcon.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to restore preview: ${e.message}")
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
        val bitmap = currentPreviewBitmap ?: return

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
                Toast.makeText(requireContext(), R.string.image_saved_to_gallery, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(requireContext(), R.string.failed_save_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAsFile() {
        val filename = "ComfyChair_${System.currentTimeMillis()}.png"
        saveImageLauncher.launch(filename)
    }

    private fun saveImageToUri(uri: Uri) {
        val bitmap = currentPreviewBitmap ?: return

        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: IOException) {
            Toast.makeText(requireContext(), R.string.failed_save_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage() {
        val bitmap = currentPreviewBitmap ?: return

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

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_image)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.failed_share_image, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAutoSaveListeners() {
        promptInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveConfiguration()
            }
        })

        workflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        checkpointDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        megapixelsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { saveConfiguration() }
        })
        stepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { saveConfiguration() }
        })

        unetWorkflowDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        unetDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        vaeDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        clipDropdown.setOnItemClickListener { _, _, _, _ -> saveConfiguration() }
        unetStepsInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { saveConfiguration() }
        })
    }

    private fun saveConfiguration() {
        if (isLoadingConfiguration) return

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(PREF_IS_CHECKPOINT_MODE, isCheckpointMode)
                putString(PREF_PROMPT, promptInput.text.toString())

                putString(PREF_CHECKPOINT_WORKFLOW, workflowDropdown.text.toString())
                putString(PREF_CHECKPOINT_MODEL, checkpointDropdown.text.toString())
                putString(PREF_CHECKPOINT_MEGAPIXELS, megapixelsInput.text.toString())
                putString(PREF_CHECKPOINT_STEPS, stepsInput.text.toString())

                putString(PREF_UNET_WORKFLOW, unetWorkflowDropdown.text.toString())
                putString(PREF_UNET_MODEL, unetDropdown.text.toString())
                putString(PREF_UNET_VAE, vaeDropdown.text.toString())
                putString(PREF_UNET_CLIP, clipDropdown.text.toString())
                putString(PREF_UNET_STEPS, unetStepsInput.text.toString())

                apply()
            }
        } catch (e: Exception) {
            println("Failed to save configuration: ${e.message}")
        }
    }

    private fun loadConfiguration() {
        isLoadingConfiguration = true

        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val savedMode = prefs.getBoolean(PREF_IS_CHECKPOINT_MODE, true)
            isCheckpointMode = savedMode

            if (savedMode) {
                configModeToggle.check(R.id.checkpointModeButton)
            } else {
                configModeToggle.check(R.id.unetModeButton)
            }

            prefs.getString(PREF_PROMPT, "")?.let {
                if (it.isNotEmpty()) promptInput.setText(it)
            }

            prefs.getString(PREF_CHECKPOINT_WORKFLOW, null)?.let { workflowDropdown.setText(it, false) }
            prefs.getString(PREF_CHECKPOINT_MEGAPIXELS, null)?.let { megapixelsInput.setText(it) }
            prefs.getString(PREF_CHECKPOINT_STEPS, null)?.let { stepsInput.setText(it) }

            prefs.getString(PREF_UNET_WORKFLOW, null)?.let { unetWorkflowDropdown.setText(it, false) }
            prefs.getString(PREF_UNET_STEPS, null)?.let { unetStepsInput.setText(it) }
        } catch (e: Exception) {
            println("Failed to load configuration: ${e.message}")
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 500)
        }
    }

    private fun restoreCheckpointConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_CHECKPOINT_MODEL, null)?.let { savedModel ->
                if (availableCheckpoints.contains(savedModel)) {
                    checkpointDropdown.setText(savedModel, false)
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    private fun restoreUNETConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_MODEL, null)?.let { savedModel ->
                if (availableUNETs.contains(savedModel)) {
                    unetDropdown.setText(savedModel, false)
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    private fun restoreVAEConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_VAE, null)?.let { savedVAE ->
                if (availableVAEs.contains(savedVAE)) {
                    vaeDropdown.setText(savedVAE, false)
                }
            }
        } finally {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isLoadingConfiguration = false
            }, 100)
        }
    }

    private fun restoreCLIPConfiguration() {
        isLoadingConfiguration = true
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_UNET_CLIP, null)?.let { savedCLIP ->
                if (availableCLIPs.contains(savedCLIP)) {
                    clipDropdown.setText(savedCLIP, false)
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
        currentPreviewBitmap = null
    }
}
