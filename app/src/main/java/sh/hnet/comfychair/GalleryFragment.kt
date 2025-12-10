package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.IOException

/**
 * GalleryFragment - Displays a gallery of all generated images and videos
 */
class GalleryFragment : Fragment() {

    // UI references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var galleryGrid: RecyclerView

    // ComfyUI client
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // Gallery adapter
    private lateinit var galleryAdapter: GalleryAdapter

    // Gallery items list
    private val galleryItems = mutableListOf<GalleryItem>()

    // Store current selected item for save operations
    private var currentBitmap: Bitmap? = null
    private var currentVideoItem: GalleryItem? = null

    // Activity result launcher for "Save as..." image
    private val saveImageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { saveImageToUri(it) }
    }

    // Activity result launcher for "Save as..." video
    private val saveVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        uri?.let { saveVideoToUri(it) }
    }

    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"

        fun newInstance(hostname: String, port: Int): GalleryFragment {
            val fragment = GalleryFragment()
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
        return inflater.inflate(R.layout.fragment_gallery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            v.setPadding(0, 0, 0, 0)

            val mainContent = view.findViewById<android.widget.LinearLayout>(R.id.mainContent)
            val bottomPadding = if (imeInsets.bottom > 0) 0 else systemBars.bottom
            mainContent.setPadding(0, 0, 0, bottomPadding)

            WindowInsetsCompat.CONSUMED
        }

        // Initialize ComfyUI client
        comfyUIClient = ComfyUIClient(hostname, port)

        // Initialize UI components
        initializeViews(view)

        // Set up RecyclerView
        setupRecyclerView()

        // Set up pull-to-refresh
        setupSwipeRefresh()

        // Set up top app bar
        setupTopAppBar()

        // Load gallery
        loadGallery()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        galleryGrid = view.findViewById(R.id.galleryGrid)
    }

    private fun setupRecyclerView() {
        galleryAdapter = GalleryAdapter(
            items = galleryItems,
            onItemClick = { galleryItem ->
                when (galleryItem.type) {
                    GalleryItemType.IMAGE -> showFullscreenImageViewer(galleryItem.bitmap)
                    GalleryItemType.VIDEO -> showFullscreenVideoViewer(galleryItem)
                }
            },
            onItemLongClick = { galleryItem ->
                when (galleryItem.type) {
                    GalleryItemType.IMAGE -> {
                        currentBitmap = galleryItem.bitmap
                        currentVideoItem = null
                        showSaveOptions()
                    }
                    GalleryItemType.VIDEO -> {
                        currentBitmap = null
                        currentVideoItem = galleryItem
                        showVideoSaveOptions()
                    }
                }
            },
            onDeleteClick = { galleryItem ->
                deleteHistoryItem(galleryItem.promptId)
            }
        )

        galleryGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        galleryGrid.adapter = galleryAdapter
    }

    private fun deleteHistoryItem(promptId: String) {
        comfyUIClient.deleteHistoryItem(promptId) { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(requireContext(), R.string.history_item_deleted_success, Toast.LENGTH_SHORT).show()
                    loadGallery()
                } else {
                    Toast.makeText(requireContext(), R.string.history_item_deleted_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            loadGallery()
        }
    }

    private fun setupTopAppBar() {
        // Back button - navigate to previous fragment
        topAppBar.setNavigationOnClickListener {
            val activity = requireActivity() as? MainContainerActivity
            if (activity?.navigateBack() != true) {
                // No history - finish the activity
                activity?.finish()
            }
        }

        // Menu item click handler
        topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_generation -> {
                    // Already in generation mode, do nothing
                    true
                }
                R.id.action_settings -> {
                    (requireActivity() as? MainContainerActivity)?.openSettings()
                    true
                }
                R.id.action_logout -> {
                    (requireActivity() as? MainContainerActivity)?.logout()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadGallery() {
        swipeRefresh.isRefreshing = true

        comfyUIClient.testConnection { success, errorMessage, _ ->
            if (success) {
                comfyUIClient.fetchAllHistory { historyJson ->
                    if (historyJson != null) {
                        parseHistoryAndLoadImages(historyJson)
                    } else {
                        activity?.runOnUiThread {
                            swipeRefresh.isRefreshing = false
                        }
                    }
                }
            } else {
                println("Failed to connect: $errorMessage")
                activity?.runOnUiThread {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun parseHistoryAndLoadImages(historyJson: org.json.JSONObject) {
        // Map to store items with their order index
        val itemsWithIndex = mutableListOf<Pair<Int, GalleryItem>>()

        try {
            val promptIds = historyJson.keys()
            val promptIdList = mutableListOf<String>()
            while (promptIds.hasNext()) {
                promptIdList.add(promptIds.next())
            }

            var loadedCount = 0
            val totalCount = promptIdList.size

            if (totalCount == 0) {
                activity?.runOnUiThread {
                    swipeRefresh.isRefreshing = false
                }
                return
            }

            promptIdList.forEachIndexed { index, promptId ->
                val promptData = historyJson.optJSONObject(promptId)
                val outputs = promptData?.optJSONObject("outputs")

                var foundMedia = false
                outputs?.keys()?.forEach { nodeId ->
                    val nodeOutput = outputs.getJSONObject(nodeId)

                    // Check for images first
                    val images = nodeOutput.optJSONArray("images")
                    if (images != null && images.length() > 0 && !foundMedia) {
                        foundMedia = true
                        val imageInfo = images.getJSONObject(0)
                        val filename = imageInfo.optString("filename")
                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        // Check if this is actually a video file based on extension
                        val isVideoFile = filename.lowercase().let {
                            it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".gif") || it.endsWith(".avi")
                        }

                        if (isVideoFile) {
                            // Treat as video
                            comfyUIClient.fetchVideo(filename, subfolder, type) { videoBytes ->
                                synchronized(itemsWithIndex) {
                                    if (videoBytes != null) {
                                        val thumbnail = extractVideoThumbnail(videoBytes)
                                        itemsWithIndex.add(Pair(index, GalleryItem(
                                            promptId = promptId,
                                            filename = filename,
                                            bitmap = thumbnail,
                                            type = GalleryItemType.VIDEO,
                                            subfolder = subfolder,
                                            outputType = type
                                        )))
                                    }

                                    loadedCount++
                                    if (loadedCount == totalCount) {
                                        updateGalleryUI(itemsWithIndex)
                                    }
                                }
                            }
                        } else {
                            // Treat as image
                            comfyUIClient.fetchImage(filename, subfolder, type) { bitmap ->
                                synchronized(itemsWithIndex) {
                                    if (bitmap != null) {
                                        itemsWithIndex.add(Pair(index, GalleryItem(
                                            promptId = promptId,
                                            filename = filename,
                                            bitmap = bitmap,
                                            type = GalleryItemType.IMAGE,
                                            subfolder = subfolder,
                                            outputType = type
                                        )))
                                    }

                                    loadedCount++
                                    if (loadedCount == totalCount) {
                                        updateGalleryUI(itemsWithIndex)
                                    }
                                }
                            }
                        }
                    }

                    // Check for videos if no images found (also check gifs array)
                    var videos = nodeOutput.optJSONArray("videos")
                    if (videos == null || videos.length() == 0) {
                        videos = nodeOutput.optJSONArray("gifs")
                    }
                    if (videos != null && videos.length() > 0 && !foundMedia) {
                        foundMedia = true
                        val videoInfo = videos.getJSONObject(0)
                        val filename = videoInfo.optString("filename")
                        val subfolder = videoInfo.optString("subfolder", "")
                        val type = videoInfo.optString("type", "output")

                        // Fetch video to extract thumbnail
                        comfyUIClient.fetchVideo(filename, subfolder, type) { videoBytes ->
                            synchronized(itemsWithIndex) {
                                if (videoBytes != null) {
                                    val thumbnail = extractVideoThumbnail(videoBytes)
                                    itemsWithIndex.add(Pair(index, GalleryItem(
                                        promptId = promptId,
                                        filename = filename,
                                        bitmap = thumbnail,
                                        type = GalleryItemType.VIDEO,
                                        subfolder = subfolder,
                                        outputType = type
                                    )))
                                }

                                loadedCount++
                                if (loadedCount == totalCount) {
                                    updateGalleryUI(itemsWithIndex)
                                }
                            }
                        }
                    }
                }

                if (!foundMedia) {
                    synchronized(itemsWithIndex) {
                        loadedCount++
                        if (loadedCount == totalCount) {
                            updateGalleryUI(itemsWithIndex)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to parse history: ${e.message}")
            e.printStackTrace()
            activity?.runOnUiThread {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateGalleryUI(itemsWithIndex: List<Pair<Int, GalleryItem>>) {
        activity?.runOnUiThread {
            galleryItems.clear()
            // Sort by index (descending) to ensure newest to oldest
            val sortedItems = itemsWithIndex
                .sortedByDescending { it.first }
                .map { it.second }
            galleryItems.addAll(sortedItems)
            galleryAdapter.notifyDataSetChanged()
            swipeRefresh.isRefreshing = false
        }
    }

    private fun extractVideoThumbnail(videoBytes: ByteArray): Bitmap {
        // Check if fragment is attached before accessing context
        if (!isAdded || context == null) {
            return createVideoPlaceholderBitmap()
        }

        var tempFile: File? = null
        var retriever: MediaMetadataRetriever? = null
        return try {
            // Save video to temporary file to extract thumbnail
            val cacheDir = context?.cacheDir ?: return createVideoPlaceholderBitmap()
            tempFile = File.createTempFile("thumb_", ".mp4", cacheDir)
            tempFile.writeBytes(videoBytes)

            retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            // If bitmap extraction fails, return placeholder
            bitmap ?: createVideoPlaceholderBitmap()
        } catch (e: Exception) {
            println("Failed to extract video thumbnail: ${e.message}")
            e.printStackTrace()
            // Return a placeholder bitmap for videos that can't have thumbnails extracted
            createVideoPlaceholderBitmap()
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
            try {
                tempFile?.delete()
            } catch (e: Exception) {
                // Ignore delete errors
            }
        }
    }

    private fun createVideoPlaceholderBitmap(): Bitmap {
        // Create a simple gray placeholder bitmap for videos without thumbnails
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)
        return bitmap
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

    private fun showFullscreenVideoViewer(item: GalleryItem) {
        // Fetch video and display in fullscreen
        comfyUIClient.fetchVideo(item.filename, item.subfolder, item.outputType) { videoBytes ->
            activity?.runOnUiThread {
                if (videoBytes != null && isAdded && context != null) {
                    try {
                        // Save video to temp file for playback
                        val tempFile = File.createTempFile("playback_", ".mp4", requireContext().cacheDir)
                        tempFile.writeBytes(videoBytes)

                        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        dialog.setContentView(R.layout.dialog_fullscreen_video)

                        val videoView = dialog.findViewById<VideoView>(R.id.fullscreenVideoView)
                        videoView.setVideoPath(tempFile.absolutePath)

                        val mediaController = MediaController(requireContext())
                        mediaController.setAnchorView(videoView)
                        videoView.setMediaController(mediaController)

                        videoView.setOnPreparedListener { mp ->
                            mp.isLooping = true
                            mp.start()
                        }

                        videoView.setOnClickListener {
                            dialog.dismiss()
                            tempFile.delete()
                        }

                        dialog.setOnDismissListener {
                            tempFile.delete()
                        }

                        dialog.show()
                    } catch (e: Exception) {
                        println("Failed to show video: ${e.message}")
                        Toast.makeText(requireContext(), R.string.error_failed_play_video, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_load_video, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
                Toast.makeText(requireContext(), R.string.image_saved_to_gallery, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                println("Failed to save to gallery: ${e.message}")
                Toast.makeText(requireContext(), R.string.failed_save_image, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), R.string.failed_save_image, Toast.LENGTH_SHORT).show()
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

            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_image)))
        } catch (e: Exception) {
            println("Failed to share image: ${e.message}")
            Toast.makeText(requireContext(), R.string.failed_share_image, Toast.LENGTH_SHORT).show()
        }
    }

    // Video save methods

    private fun showVideoSaveOptions() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_save_options, null)
        bottomSheetDialog.setContentView(view)

        view.findViewById<View>(R.id.saveToGallery).setOnClickListener {
            saveVideoToGallery()
            bottomSheetDialog.dismiss()
        }

        view.findViewById<View>(R.id.saveAs).setOnClickListener {
            saveVideoAsFile()
            bottomSheetDialog.dismiss()
        }

        view.findViewById<View>(R.id.share).setOnClickListener {
            shareVideo()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun saveVideoToGallery() {
        val item = currentVideoItem ?: return

        comfyUIClient.fetchVideo(item.filename, item.subfolder, item.outputType) { videoBytes ->
            activity?.runOnUiThread {
                if (videoBytes != null && isAdded && context != null) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ComfyChair")
                    }

                    val uri = requireContext().contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        try {
                            requireContext().contentResolver.openOutputStream(it)?.use { outputStream ->
                                outputStream.write(videoBytes)
                            }
                            Toast.makeText(requireContext(), R.string.video_saved_to_gallery, Toast.LENGTH_SHORT).show()
                        } catch (e: IOException) {
                            println("Failed to save video to gallery: ${e.message}")
                            Toast.makeText(requireContext(), R.string.failed_save_video, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_download_video, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun saveVideoAsFile() {
        val filename = "ComfyChair_${System.currentTimeMillis()}.mp4"
        saveVideoLauncher.launch(filename)
    }

    private fun saveVideoToUri(uri: Uri) {
        val item = currentVideoItem ?: return

        comfyUIClient.fetchVideo(item.filename, item.subfolder, item.outputType) { videoBytes ->
            activity?.runOnUiThread {
                if (videoBytes != null && isAdded && context != null) {
                    try {
                        requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(videoBytes)
                        }
                    } catch (e: IOException) {
                        println("Failed to save video: ${e.message}")
                        Toast.makeText(requireContext(), R.string.failed_save_video, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_download_video, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun shareVideo() {
        val item = currentVideoItem ?: return

        comfyUIClient.fetchVideo(item.filename, item.subfolder, item.outputType) { videoBytes ->
            activity?.runOnUiThread {
                if (videoBytes != null && isAdded && context != null) {
                    try {
                        val cachePath = requireContext().cacheDir
                        val filename = "share_video_${System.currentTimeMillis()}.mp4"
                        val file = File(cachePath, filename)
                        file.writeBytes(videoBytes)

                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().applicationContext.packageName}.fileprovider",
                            file
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
                } else {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), R.string.error_failed_download_video, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        comfyUIClient.shutdown()
        currentBitmap = null
        currentVideoItem = null
        galleryItems.forEach { it.bitmap.recycle() }
        galleryItems.clear()
    }
}
