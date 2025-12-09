package sh.hnet.comfychair

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import java.io.IOException

/**
 * GalleryFragment - Displays a gallery of all generated images
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

    // Store current selected bitmap for save operations
    private var currentBitmap: Bitmap? = null

    // Activity result launcher for "Save as..."
    private val saveImageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri?.let { saveImageToUri(it) }
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
                showFullscreenImageViewer(galleryItem.bitmap)
            },
            onItemLongClick = { galleryItem ->
                currentBitmap = galleryItem.bitmap
                showSaveOptions()
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
        topAppBar.setNavigationOnClickListener {
            requireActivity().finish()
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

                var foundImage = false
                outputs?.keys()?.forEach { nodeId ->
                    val nodeOutput = outputs.getJSONObject(nodeId)
                    val images = nodeOutput.optJSONArray("images")

                    if (images != null && images.length() > 0 && !foundImage) {
                        foundImage = true
                        val imageInfo = images.getJSONObject(0)
                        val filename = imageInfo.optString("filename")
                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        comfyUIClient.fetchImage(filename, subfolder, type) { bitmap ->
                            synchronized(itemsWithIndex) {
                                if (bitmap != null) {
                                    itemsWithIndex.add(Pair(index, GalleryItem(promptId, filename, bitmap)))
                                }

                                loadedCount++
                                if (loadedCount == totalCount) {
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
                            }
                        }
                    }
                }

                if (!foundImage) {
                    synchronized(itemsWithIndex) {
                        loadedCount++
                        if (loadedCount == totalCount) {
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
                Toast.makeText(requireContext(), "Image saved to gallery", Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        comfyUIClient.shutdown()
        currentBitmap = null
        galleryItems.forEach { it.bitmap.recycle() }
        galleryItems.clear()
    }
}
