package local.capturetime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.media.ThumbnailUtils
import android.util.Size
import androidx.recyclerview.widget.RecyclerView
import local.capturetime.duplicate.DuplicateCandidate
import java.util.Locale

class DuplicateAdapter(
    private val onSelectionChanged: () -> Unit,
    private val onCompare: (DuplicateCandidate) -> Unit
) : RecyclerView.Adapter<DuplicateAdapter.Holder>() {
    private var items: List<DuplicateCandidate> = emptyList()
    private val selectedPaths = linkedSetOf<String>()

    fun submitList(value: List<DuplicateCandidate>) {
        items = value
        selectedPaths.clear()
        selectedPaths += value.map { it.delete.file.absolutePath }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selected(): List<DuplicateCandidate> = items.filter { it.delete.file.absolutePath in selectedPaths }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_duplicate, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val check = view.findViewById<CheckBox>(R.id.duplicateCheck)
        private val details = view.findViewById<TextView>(R.id.duplicateDetails)
        private val preview = view.findViewById<ImageView>(R.id.duplicatePreview)
        private val compare = view.findViewById<View>(R.id.duplicateCompare)

        fun bind(candidate: DuplicateCandidate) {
            val path = candidate.delete.file.absolutePath
            preview.tag = path
            preview.setImageResource(android.R.drawable.ic_menu_gallery)
            Thread {
                val bitmap = runCatching {
                    if (candidate.delete.kind == local.capturetime.duplicate.MediaKind.VIDEO) {
                        ThumbnailUtils.createVideoThumbnail(candidate.delete.file, Size(192, 192), null)
                    } else {
                        ThumbnailUtils.createImageThumbnail(candidate.delete.file, Size(192, 192), null)
                    }
                }.getOrNull()
                preview.post {
                    if (preview.tag == path && bitmap != null) preview.setImageBitmap(bitmap)
                }
            }.start()
            check.setOnCheckedChangeListener(null)
            check.isChecked = path in selectedPaths
            details.text = buildString {
                append("处理：").append(candidate.delete.file.name).append('\n')
                append("保留：").append(candidate.retained.file.name).append('\n')
                append("尺寸：").append(candidate.delete.width).append('×').append(candidate.delete.height)
                append(" · ").append(formatBytes(candidate.delete.size)).append('\n')
                append(candidate.reason).append('\n')
                append("目录：").append(candidate.delete.file.parent)
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPaths += path else selectedPaths -= path
                onSelectionChanged()
            }
            itemView.setOnClickListener { check.isChecked = !check.isChecked }
            compare.setOnClickListener { onCompare(candidate) }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    }
}
