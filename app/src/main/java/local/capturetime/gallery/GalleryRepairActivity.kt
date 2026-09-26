package local.capturetime.gallery

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import local.capturetime.R
import local.capturetime.operation.BackupOperationGuard
import local.capturetime.settings.TimeRuleConfig
import local.capturetime.time.CaptureTimeParser
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GalleryRepairActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val repair by lazy { GalleryRepair(applicationContext) }
    private val chosen = linkedSetOf<Long>()
    private var rows = emptyList<JSONObject>()
    private var busy = false
    private var includeAdded = true
    private var exportSession: File? = null
    private val listAdapter = CandidateAdapter()
    private val summary by lazy { findViewById<TextView>(R.id.galleryRootSummary) }
    private val status by lazy { findViewById<TextView>(R.id.galleryRootStatus) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_gallery_repair)
        exportSession = state?.getString("export_session")?.let(::File)
        includeAdded = state?.getBoolean("include_added", true) ?: true
        findViewById<RadioGroup>(R.id.galleryRootMode).apply {
            check(if (includeAdded) R.id.galleryRootModeBoth else R.id.galleryRootModeCapture)
            setOnCheckedChangeListener { _, checkedId ->
                includeAdded = checkedId == R.id.galleryRootModeBoth
                rows = emptyList(); chosen.clear(); listAdapter.notifyDataSetChanged()
                summary.text = "模式已切换。请重新检查相册时间。"
                updateActions()
            }
        }
        findViewById<MaterialToolbar>(R.id.galleryRootToolbar).setNavigationOnClickListener { if (!busy) finish() }
        findViewById<RecyclerView>(R.id.galleryRootList).apply {
            layoutManager = LinearLayoutManager(this@GalleryRepairActivity)
            adapter = listAdapter
            itemAnimator = null
        }
        findViewById<Button>(R.id.galleryRootScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.galleryRootRepair).setOnClickListener { confirmRepair() }
        findViewById<Button>(R.id.galleryRootRecovery).setOnClickListener {
            work("正在核验上次会话…", { repair.recover(::progress) }) { session -> showResult(session) }
        }
        findViewById<Button>(R.id.galleryRootSelectAll).setOnClickListener {
            if (chosen.size == rows.size) chosen.clear() else rows.forEach { chosen += it.getLong("_id") }
            listAdapter.notifyDataSetChanged()
            updateActions()
        }
        findViewById<Button>(R.id.galleryRootLogs).setOnClickListener { showLogs() }
        if (BackupOperationGuard.hasGalleryState(this)) {
            summary.text = "发现尚未完成核验的相册修复。请先核验上次会话，备份将继续保留。"
        } else {
            val tolerance = TimeRuleConfig.load(this).toleranceSeconds
            summary.text = "沿用设置：忽略 ${tolerance / 86400} 天 ${tolerance / 3600 % 24} 时 ${tolerance / 60 % 60} 分 ${tolerance % 60} 秒误差。检查完成后，勾选需要修复的照片。"
        }
        updateActions()
    }

    override fun onDestroy() { executor.shutdown(); listAdapter.close(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export_session", exportSession?.path)
        outState.putBoolean("include_added", includeAdded)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Legacy Activity navigation") override fun onBackPressed() {
        if (!busy) super.onBackPressed() else Toast.makeText(this, "正在处理，请等待核验完成", Toast.LENGTH_SHORT).show()
    }

    private fun scan() {
        rows = emptyList(); chosen.clear(); listAdapter.notifyDataSetChanged()
        val scanAdded = includeAdded
        work("正在请求 Root 并读取相册时间，照片较多时需要一些时间…", { repair.scan(TimeRuleConfig.load(applicationContext), scanAdded) }) { preview ->
            rows = preview.rows
            status.text = "Root 已授权 · 检查完成"
            findViewById<Button>(R.id.galleryRootScan).text = "重新检查"
            val rule = TimeRuleConfig.load(applicationContext)
            fun differs(row: JSONObject, field: String): Boolean = rule.needsChange(
                if (row.isNull(field)) null else Instant.ofEpochMilli(row.getLong(field)), Instant.ofEpochMilli(row.getLong("target")))
            val capture = rows.count { differs(it, "dateTaken") || differs(it, "mixedDateTime") }
            val modeSummary = if (scanAdded) "拍摄排序待修 $capture 张 · 添加排序待修 ${rows.count { differs(it, "dateModified") }} 张"
                else "拍摄排序待修 $capture 张 · 添加排序保持原值"
            summary.text = "已检查有本地路径 ${preview.inspected} 张 · 云端无原图 ${preview.cloudOnly} 张待核对\n$modeSummary\n" +
                if (rows.isEmpty()) "当前没有超过忽略误差、且文件名、EXIF、文件修改时间一致的待修复照片。" else "勾选照片后先备份，再按所选模式修复相册时间。"
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun confirmRepair() {
        val selected = rows.filter { it.getLong("_id") in chosen }
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("修复 ${selected.size} 张照片的相册时间？")
            .setMessage("将暂停小米相册、备份数据库，再${if (includeAdded) "统一拍摄和添加时间" else "只修拍摄时间，保留添加时间"}。完成后核验照片哈希并重新打开小米相册。\n\n备份保存在本应用中，可从“会话记录与备份”导出；卸载应用前请先导出。")
            .setNegativeButton("取消", null).setPositiveButton("备份并修复") { _, _ ->
                work("正在创建修复会话…", { repair.repair(selected, ::progress) }) { session ->
                    rows = rows.filterNot { it.getLong("_id") in chosen }
                    chosen.clear(); listAdapter.notifyDataSetChanged(); showResult(session)
                }
            }.show()
    }

    private fun showResult(session: File) {
        status.text = "Root 核验完成"
        summary.text = runCatching { JSONObject(File(session, "status.json").readText()).getString("message") }
            .getOrDefault("会话核验完成，记录和备份已保存。")
    }

    private fun progress(message: String) = runOnUiThread { if (!isDestroyed) summary.text = message }

    private fun <T> work(message: String, action: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        summary.text = message
        updateActions()
        executor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                result.onSuccess(done).onFailure {
                    summary.text = it.message ?: "操作未完成，请查看会话记录"
                    status.text = if (BackupOperationGuard.hasGalleryState(this)) "会话等待核验" else "检查未完成"
                }
                updateActions()
            }
        }
    }

    private fun updateActions() {
        val recovery = BackupOperationGuard.hasGalleryState(this)
        findViewById<RadioGroup>(R.id.galleryRootMode).apply {
            for (index in 0 until childCount) getChildAt(index).isEnabled = !busy && !recovery
        }
        findViewById<ProgressBar>(R.id.galleryRootProgress).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.galleryRootScan).isEnabled = !busy && !recovery
        findViewById<Button>(R.id.galleryRootSelectAll).apply {
            isEnabled = !busy && !recovery && rows.isNotEmpty()
            text = if (rows.isNotEmpty() && chosen.size == rows.size) "取消全选" else "全选"
        }
        findViewById<Button>(R.id.galleryRootRepair).apply {
            isEnabled = !busy && !recovery && chosen.isNotEmpty()
            text = if (chosen.isEmpty()) "修复所选时间" else "修复所选 ${chosen.size} 张"
        }
        findViewById<Button>(R.id.galleryRootRecovery).apply { visibility = if (recovery) View.VISIBLE else View.GONE; isEnabled = !busy }
        findViewById<Button>(R.id.galleryRootLogs).isEnabled = !busy
        listAdapter.notifyDataSetChanged()
    }

    private fun showLogs() {
        val sessions = File(filesDir, "gallery-repair").listFiles().orEmpty().filter(File::isDirectory).sortedByDescending(File::getName)
        if (sessions.isEmpty()) { Toast.makeText(this, "暂无 Root 修复会话", Toast.LENGTH_SHORT).show(); return }
        MaterialAlertDialogBuilder(this).setTitle("Root 修复会话")
            .setItems(sessions.map { session ->
                val message = runCatching { JSONObject(File(session, "status.json").readText()).getString("message") }.getOrDefault("待核验")
                "${CaptureTimeParser.formatDisplay(session.name.substringBefore('-').toLongOrNull()?.let(Instant::ofEpochMilli))} · $message"
            }.toTypedArray()) { _, index ->
                val session = sessions[index]
                MaterialAlertDialogBuilder(this).setTitle("会话记录与备份")
                    .setMessage("${File(session, "status.json").takeIf(File::isFile)?.readText().orEmpty()}\n\n备份目录：${session.path}\n\n导出的 ZIP 包含数据库备份、修复清单及照片哈希，可用于核验和恢复。")
                    .setNegativeButton("关闭", null).setPositiveButton("导出备份 ZIP") { _, _ ->
                        exportSession = session
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "xiaomi-gallery-${session.name}.zip")
                        }, 31)
                    }.show()
            }.setPositiveButton("关闭", null).show()
    }

    @Deprecated("Legacy Activity result") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 31 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val session = exportSession ?: return
        work("正在导出备份…", {
            ZipOutputStream(requireNotNull(contentResolver.openOutputStream(uri))).use { zip ->
                session.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == session.canonicalFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }) { summary.text = "备份 ZIP 已导出。" }
    }

    private inner class CandidateAdapter : RecyclerView.Adapter<CandidateAdapter.Holder>() {
        private val thumbnails = LruCache<String, Bitmap>(48)
        private val thumbnailExecutor = Executors.newFixedThreadPool(2)
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val preview: ImageView = view.findViewById(R.id.galleryCandidatePreview)
            val check: MaterialCheckBox = view.findViewById(R.id.galleryCandidateCheck)
        }
        fun close() = thumbnailExecutor.shutdownNow()
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_repair, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]; val id = row.getLong("_id")
            val file = File(row.getString("localFile"))
            val key = "${file.path}:${file.lastModified()}:${file.length()}"
            val cached = thumbnails.get(key)
            holder.preview.apply {
                tag = key
                contentDescription = "查看 ${file.name} 的照片预览"
                if (cached == null) setImageResource(android.R.drawable.ic_menu_gallery) else setImageBitmap(cached)
                setOnClickListener { showPreview(file) }
            }
            if (cached == null) thumbnailExecutor.execute {
                val bitmap = runCatching { ThumbnailUtils.createImageThumbnail(file, Size(184, 184), null) }.getOrNull()
                if (bitmap != null) {
                    thumbnails.put(key, bitmap)
                    holder.preview.post { if (holder.preview.tag == key) holder.preview.setImageBitmap(bitmap) }
                }
            }
            fun time(key: String) = CaptureTimeParser.formatDisplay(if (row.isNull(key)) null else Instant.ofEpochMilli(row.getLong(key)))
            holder.check.apply {
                setOnCheckedChangeListener(null)
                val captureOnly = row.optString("repairMode") == "capture"
                text = "${file.name}\n拍摄排序 ${time("dateTaken")}\n添加排序${if (captureOnly) "（保留）" else ""} ${time("dateModified")}\n${if (captureOnly) "建议拍摄时间" else "建议统一时间"} ${time("target")}\n${file.parent}"
                isChecked = id in chosen
                isEnabled = !busy && !BackupOperationGuard.hasGalleryState(this@GalleryRepairActivity)
                setOnCheckedChangeListener { _, checked -> if (checked) chosen.add(id) else chosen.remove(id); updateActions() }
            }
        }
    }

    private fun showPreview(file: File) {
        val image = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(android.R.drawable.ic_menu_gallery)
            contentDescription = "${file.name} 的放大预览"
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("照片预览").setMessage(file.name)
            .setView(image).setPositiveButton("关闭", null).show()
        executor.execute {
            val bitmap = runCatching { ThumbnailUtils.createImageThumbnail(file, Size(1080, 1080), null) }.getOrNull()
            image.post { if (dialog.isShowing && bitmap != null) image.setImageBitmap(bitmap) }
        }
    }
}
