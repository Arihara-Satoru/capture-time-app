package local.capturetime

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import local.capturetime.model.PhotoRecord
import local.capturetime.time.CaptureTimeParser
import android.media.ThumbnailUtils
import android.util.Size
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Executors

class PhotoAdapter(private val onSelected: (PhotoRecord) -> Unit) : RecyclerView.Adapter<PhotoAdapter.Holder>() {
    private var items: List<PhotoRecord> = emptyList()
    private var selectedPath: String? = null
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)

    fun submitList(value: List<PhotoRecord>) { items = value; notifyDataSetChanged() }
    fun clearSelection() { selectedPath = null; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val details = view.findViewById<TextView>(R.id.itemDetails)
        private val preview = view.findViewById<ImageView>(R.id.itemPreview)

        fun bind(record: PhotoRecord) {
            preview.tag = record.file.absolutePath
            preview.setImageResource(android.R.drawable.ic_menu_gallery)
            thumbnailExecutor.execute {
                val bitmap = runCatching {
                    ThumbnailUtils.createImageThumbnail(record.file, Size(184, 184), null)
                }.getOrNull()
                preview.post {
                    if (preview.tag == record.file.absolutePath && bitmap != null) preview.setImageBitmap(bitmap)
                }
            }
            itemView.isSelected = selectedPath == record.file.absolutePath
            details.text = buildString {
                append("文件名：").append(record.file.name).append('\n')
                append("格式：").append(record.format.label).append('\n')
                append("当前拍摄：").append(CaptureTimeParser.formatDisplay(record.currentCaptureTime)).append('\n')
                append("添加时间：").append(CaptureTimeParser.formatDisplay(record.media?.dateAdded)).append('\n')
                append("文件名时间：").append(CaptureTimeParser.formatDisplay(record.filenameTime)).append('\n')
                append("规则目标：").append(CaptureTimeParser.formatDisplay(record.targetCaptureTime)).append('\n')
                append("路径：").append(record.file.parent ?: record.file.absolutePath)
            }
            itemView.setOnClickListener {
                selectedPath = record.file.absolutePath
                itemView.isSelected = true
                onSelected(record)
                notifyDataSetChanged()
            }
        }
    }
}
