package sh.hnet.comfychair

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Enum representing the type of gallery item
 */
enum class GalleryItemType {
    IMAGE,
    VIDEO
}

/**
 * Data class representing a gallery item (image or video)
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val bitmap: Bitmap,
    val type: GalleryItemType = GalleryItemType.IMAGE,
    val subfolder: String = "",
    val outputType: String = "output"
)

/**
 * RecyclerView adapter for displaying gallery items in a grid
 */
class GalleryAdapter(
    private val items: List<GalleryItem>,
    private val onItemClick: (GalleryItem) -> Unit,
    private val onItemLongClick: (GalleryItem) -> Unit,
    private val onDeleteClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.thumbnailImage)
        val videoIndicator: ImageView = view.findViewById(R.id.videoIndicator)
        val deleteButton: ImageView = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_thumbnail, parent, false)
        return GalleryViewHolder(view)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        val item = items[position]
        holder.imageView.setImageBitmap(item.bitmap)

        // Show/hide video indicator based on item type
        holder.videoIndicator.visibility = if (item.type == GalleryItemType.VIDEO) {
            View.VISIBLE
        } else {
            View.GONE
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
