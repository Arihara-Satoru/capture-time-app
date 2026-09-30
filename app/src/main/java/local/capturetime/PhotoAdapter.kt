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
import java.time.ZoneId
import java.util.concurrent.Executors

class PhotoAdapter(
    private val onSelected: (PhotoRecord) -> Unit,
    private val onPreview: (PhotoRecord) -> Unit,
    var zone: ZoneId = CaptureTimeParser.zone,
) : RecyclerView.Adapter<PhotoAdapter.Holder>() {
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
        private val title = view.findViewById<TextView>(R.id.itemTitle)
        private val format = view.findViewById<TextView>(R.id.itemFormat)
        private val target = view.findViewById<TextView>(R.id.itemTarget)
        private val path = view.findViewById<TextView>(R.id.itemPath)

        fun bind(record: PhotoRecord) {
            preview.tag = record.file.absolutePath
            preview.contentDescription = "放大查看 ${record.file.name}"
            preview.setOnClickListener { onPreview(record) }
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
            title.text = record.file.name
            format.text = if (record.extensionCorrection != null) " 后缀不符 · 建议 .${record.extensionCorrection} "
                else " ${record.format.label} · ${if (record.safeForTrial) "可安全试运行" else "需人工确认"} "
            target.text = if (record.extensionCorrection != null) record.reason
                else "目标时间 · ${CaptureTimeParser.formatDisplay(record.targetCaptureTime, zone)}"
            details.text = if (record.extensionCorrection != null) "改名后重新扫描，才会比较图片时间"
                else "当前时间 ${CaptureTimeParser.formatDisplay(record.currentCaptureTime, zone)}\n" +
                    "添加时间 ${CaptureTimeParser.formatDisplay(record.media?.dateAdded, zone)}\n" +
                    "文件名时间 ${CaptureTimeParser.formatDisplay(record.filenameTime, zone)}"
            path.text = record.file.parent ?: record.file.absolutePath
            itemView.contentDescription = "${record.file.name}，${record.reason}。${if (itemView.isSelected) "已选择" else "点击选择"}"
            itemView.setOnClickListener {
                selectedPath = record.file.absolutePath
                itemView.isSelected = true
                onSelected(record)
                notifyDataSetChanged()
            }
        }
    }
}
