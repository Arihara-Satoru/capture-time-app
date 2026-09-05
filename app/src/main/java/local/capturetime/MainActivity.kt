package local.capturetime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import local.capturetime.exif.ExifGateway
import local.capturetime.media.MediaStoreGateway
import local.capturetime.model.ImageFormat
import local.capturetime.model.PhotoRecord
import local.capturetime.operation.SafePhotoProcessor
import local.capturetime.operation.SessionLogger
import local.capturetime.scan.PhotoScanner
import local.capturetime.scan.ScanSnapshotStore
import local.capturetime.settings.TimeRuleConfig
import local.capturetime.time.CaptureTimeParser
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var mediaStore: MediaStoreGateway
    private lateinit var exif: ExifGateway
    private lateinit var scanner: PhotoScanner
    private lateinit var processor: SafePhotoProcessor
    private lateinit var timeRule: TimeRuleConfig
    private lateinit var snapshotStore: ScanSnapshotStore
    private lateinit var adapter: PhotoAdapter
    private var records: List<PhotoRecord> = emptyList()
    private var selected: PhotoRecord? = null
    private var jpegTrialPassed = false
    private val unlockedFormats = mutableSetOf<ImageFormat>()
    private val completedPaths = mutableSetOf<String>()
    private var lastSession: File? = null
    private var settingsOpened = false
    private var resultSource = "上次扫描"

    private val scanButton by lazy { findViewById<Button>(R.id.scanButton) }
    private val galleryButton by lazy { findViewById<Button>(R.id.galleryButton) }
    private val trialButton by lazy { findViewById<Button>(R.id.trialButton) }
    private val batchButton by lazy { findViewById<Button>(R.id.batchButton) }
    private val scanProgress by lazy { findViewById<ProgressBar>(R.id.scanProgress) }
    private val scanSummary by lazy { findViewById<TextView>(R.id.scanSummary) }
    private val batchStatus by lazy { findViewById<TextView>(R.id.batchStatus) }
    private val resultText by lazy { findViewById<TextView>(R.id.resultText) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)
        mediaStore = MediaStoreGateway(this)
        exif = ExifGateway()
        reloadTimeRule()
        snapshotStore = ScanSnapshotStore(this)
        adapter = PhotoAdapter { record -> selected = record; updateActions() }
        findViewById<RecyclerView>(R.id.photoList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openAppSettings()
        }
        galleryButton.setOnClickListener { openGallery() }
        scanButton.setOnClickListener { scanAllPhotos() }
        trialButton.setOnClickListener { confirmTrial() }
        batchButton.setOnClickListener { confirmBatch() }
        loadSavedScan()
        updatePermissionState()
        if (state == null && !hasStorageAccess()) showPermissionPrompt()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (settingsOpened) {
            settingsOpened = false
            val savedRule = TimeRuleConfig.load(this)
            if (savedRule != timeRule) {
                reloadTimeRule(savedRule)
                if (hasStorageAccess() && records.isNotEmpty()) {
                    val files = records.map { it.file }
                    runScan("正在按新规则刷新当前照片...", action = { scanner.scan(files) })
                }
            }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun updatePermissionState() {
        val granted = hasStorageAccess()
        scanButton.isEnabled = granted
        galleryButton.isEnabled = granted
        updateActions()
    }

    private fun showPermissionPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要照片存储权限")
            .setMessage("全局扫描和原地修正照片需要“所有文件访问权限”。授权入口已集中到设置页，未授权时应用不会读取或修改照片。")
            .setNegativeButton("暂不授权", null)
            .setPositiveButton("前往应用设置") { _, _ -> openAppSettings() }
            .show()
    }

    private fun openAppSettings() {
        settingsOpened = true
        startActivity(Intent(this, local.capturetime.settings.SettingsActivity::class.java))
    }

    private fun loadSavedScan() {
        val saved = snapshotStore.load()
        if (saved.isEmpty()) return
        records = saved
        renderRecords(false)
        resultText.text = "已读取上次扫描记录。照片未被读取或写入。请选择相册导入或全局扫描以获取最新状态。"
    }

    private fun scanAllPhotos() {
        if (!ensurePermission()) return
        resultSource = "全局扫描"
        runScan("正在全局扫描照片，请保持应用打开...", action = { scanner.scan() })
    }

    private fun openGallery() {
        if (!ensurePermission()) return
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_PHOTOS) }
            .onFailure { showError("无法打开系统相册：${it.message}") }
    }

    @Deprecated("Uses the platform photo picker result API for Android 11 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_PHOTOS || resultCode != RESULT_OK) return
        val uris = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip -> repeat(clip.itemCount) { add(clip.getItemAt(it).uri) } }
        }.distinct()
        val files = uris.mapNotNull(mediaStore::resolveFile).distinctBy { it.absolutePath.lowercase() }
        if (files.isEmpty()) {
            showError("所选照片不是可原地修改的本机相册项目，请选择保存在设备上的照片")
            return
        }
        resultSource = "相册导入"
        val unresolved = uris.size - files.size
        runScan(
            "正在读取所选照片...",
            action = { scanner.scan(files) },
            suffix = { if (unresolved > 0) "；另有 $unresolved 张云端或不可解析照片已跳过" else "" }
        )
    }

    private fun runScan(
        message: String,
        action: () -> List<PhotoRecord>,
        suffix: (List<PhotoRecord>) -> String = { "" }
    ) {
        setBusy(true, message)
        executor.execute {
            val result = runCatching(action)
            result.onSuccess { snapshotStore.save(it) }
            runOnUiThread {
                setBusy(false, "")
                result.onSuccess {
                    records = it; selected = null; jpegTrialPassed = false
                    unlockedFormats.clear(); completedPaths.clear(); adapter.clearSelection(); renderRecords(true)
                    resultText.append(suffix(it))
                }.onFailure { showError("扫描失败：${it.message}") }
            }
        }
    }

    private fun renderRecords(fresh: Boolean) {
        val candidates = records.filter { it.candidate }
        adapter.submitList(candidates)
        scanSummary.text = "$resultSource · 已检查 ${records.size} 张 · 候选 ${candidates.size} 张"
        if (fresh) resultText.text = if (records.isEmpty()) "范围内没有可识别图片。" else "扫描记录已保存到应用本机空间。请选择候选进行单张试运行。"
        updateActions()
    }

    private fun confirmTrial() {
        val record = selected ?: return
        MaterialAlertDialogBuilder(this).setTitle("确认单张试运行")
            .setMessage("将创建全新会话并备份：\n${record.file.absolutePath}\n\n规则目标时间：${CaptureTimeParser.formatDisplay(record.targetCaptureTime)}\n${record.reason}\n失败将立即恢复。是否继续？")
            .setNegativeButton("取消", null).setPositiveButton("确认写入") { _, _ -> runSession(listOf(record), true) }.show()
    }

    private fun confirmBatch() {
        val processable = records.filter { it.file.absolutePath !in completedPaths && it.candidate && it.safeForTrial && (it.format == ImageFormat.JPEG || it.format in unlockedFormats) }
        MaterialAlertDialogBuilder(this).setTitle("批量执行风险确认")
            .setMessage("预计处理：${processable.size} 张\n其余项目将记录为跳过。\n\n每张均独立执行备份、哈希、EXIF、媒体扫描及恢复链路。操作不会改名、删除或修改添加时间。")
            .setNegativeButton("取消", null).setPositiveButton("确认批量写入") { _, _ -> runSession(processable, false) }.show()
    }

    private fun runSession(selectedRecords: List<PhotoRecord>, trial: Boolean) {
        if (!ensurePermission()) return
        setBusy(true, if (trial) "正在执行单张安全链路..." else "正在逐张执行批量安全链路...")
        executor.execute {
            val outcome = runCatching {
                val session = SessionLogger.create(Environment.getExternalStorageDirectory(), records)
                val paths = selectedRecords.mapTo(hashSetOf()) { it.file.absolutePath }
                records.filterNot { it.file.absolutePath in paths }.forEach { session.logSkipped(it, if (it.candidate) "本次未纳入执行" else it.reason) }
                val results = selectedRecords.map { processor.process(it, session).also(session::logResult) }
                session to results
            }
            runOnUiThread {
                setBusy(false, "")
                outcome.onSuccess { (session, results) ->
                    lastSession = session.directory
                    if (trial && results.singleOrNull()?.success == true) {
                        unlockedFormats += results.single().record.format
                        if (results.single().record.format == ImageFormat.JPEG) jpegTrialPassed = true
                    }
                    results.filter { it.success }.forEach { completedPaths += it.record.file.absolutePath }
                    val success = results.count { it.success }; val restored = results.count { it.restored }
                    resultText.text = "本次成功 $success，恢复 $restored，失败 ${results.size - success}。\n会话：${session.directory.absolutePath}\n\n未触碰 .globalTrash；未修改文件名和添加时间；未删除照片。"
                    updateActions()
                }.onFailure { showError("无法执行：${it.message}") }
            }
        }
    }

    private fun showLogs() {
        val directory = lastSession ?: return
        val summary = File(directory, "summary.json").takeIf { it.isFile }?.readText().orEmpty()
        MaterialAlertDialogBuilder(this).setTitle("最近会话日志").setMessage("$summary\n\n目录：${directory.absolutePath}\n包含 planned.tsv、changed.tsv、skipped.tsv、restored.tsv。")
            .setPositiveButton("关闭", null).show()
    }

    private fun updateActions() {
        val granted = hasStorageAccess()
        trialButton.isEnabled = granted && selected?.candidate == true && selected?.safeForTrial == true
        batchButton.isEnabled = granted && jpegTrialPassed && records.any { it.file.absolutePath !in completedPaths && it.candidate && it.safeForTrial && (it.format == ImageFormat.JPEG || it.format in unlockedFormats) }
        batchStatus.text = when { !jpegTrialPassed -> "需先完成一张 JPEG 试运行"; batchButton.isEnabled -> "JPEG 安全链路已通过，可批量确认"; else -> "当前没有可批量处理的候选" }
    }

    private fun setBusy(busy: Boolean, message: String) {
        scanProgress.visibility = if (busy) View.VISIBLE else View.GONE
        scanButton.isEnabled = !busy && hasStorageAccess()
        galleryButton.isEnabled = !busy && hasStorageAccess()
        trialButton.isEnabled = false; batchButton.isEnabled = false
        if (message.isNotBlank()) resultText.text = message
        if (!busy) updateActions()
    }

    private fun ensurePermission(): Boolean {
        if (hasStorageAccess()) return true
        showPermissionPrompt()
        updatePermissionState()
        return false
    }
    private fun showError(message: String) { resultText.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun hasStorageAccess(): Boolean {
        val mediaPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return Environment.isExternalStorageManager() && checkSelfPermission(mediaPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun reloadTimeRule(rule: TimeRuleConfig = TimeRuleConfig.load(this)) {
        timeRule = rule
        scanner = PhotoScanner(mediaStore, exif, timeRule)
        processor = SafePhotoProcessor(this, exif, mediaStore, timeRule)
    }

    companion object {
        private const val REQUEST_PICK_PHOTOS = 30
    }
}
