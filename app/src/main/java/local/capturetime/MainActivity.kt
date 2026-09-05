package local.capturetime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
    private var autoScanRequested = false
    private var settingsOpened = false

    private val permissionStatus by lazy { findViewById<TextView>(R.id.permissionStatus) }
    private val scanButton by lazy { findViewById<Button>(R.id.scanButton) }
    private val trialButton by lazy { findViewById<Button>(R.id.trialButton) }
    private val batchButton by lazy { findViewById<Button>(R.id.batchButton) }
    private val scanProgress by lazy { findViewById<ProgressBar>(R.id.scanProgress) }
    private val scanSummary by lazy { findViewById<TextView>(R.id.scanSummary) }
    private val batchStatus by lazy { findViewById<TextView>(R.id.batchStatus) }
    private val resultText by lazy { findViewById<TextView>(R.id.resultText) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        findViewById<Button>(R.id.permissionButton).setOnClickListener { requestStorageAccess() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            settingsOpened = true
            startActivity(Intent(this, local.capturetime.settings.SettingsActivity::class.java))
        }
        scanButton.setOnClickListener { scanPreview() }
        trialButton.setOnClickListener { confirmTrial() }
        batchButton.setOnClickListener { confirmBatch() }
        loadSavedScan()
        updatePermissionState()
        if (settingsOpened && Environment.isExternalStorageManager()) {
            settingsOpened = false
            scanPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (settingsOpened) {
            settingsOpened = false
            val savedRule = TimeRuleConfig.load(this)
            if (savedRule != timeRule) {
                reloadTimeRule(savedRule)
                if (Environment.isExternalStorageManager()) scanPreview()
            }
        }
        if (autoScanRequested && Environment.isExternalStorageManager()) {
            autoScanRequested = false
            scanPreview()
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun requestStorageAccess() {
        autoScanRequested = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 20)
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    private fun updatePermissionState() {
        val granted = Environment.isExternalStorageManager()
        permissionStatus.text = if (granted) "存储权限已开启\n扫描和写入前仍会再次校验。" else "尚未开启所有文件访问权限\n未授权时不会执行任何写入。"
        scanButton.isEnabled = granted
        updateActions()
    }

    private fun loadSavedScan() {
        val saved = snapshotStore.load()
        if (saved.isEmpty()) return
        records = saved
        renderRecords(false)
        resultText.text = "已读取上次扫描记录。照片未被读取或写入。点击“重新扫描”获取最新状态。"
    }

    private fun scanPreview() {
        if (!ensurePermission()) return
        setBusy(true, "正在扫描，请保持应用打开...")
        executor.execute {
            val result = runCatching { scanner.scan() }
            result.onSuccess { snapshotStore.save(it) }
            runOnUiThread {
                setBusy(false, "")
                result.onSuccess {
                    records = it; selected = null; jpegTrialPassed = false
                    unlockedFormats.clear(); completedPaths.clear(); adapter.clearSelection(); renderRecords(true)
                }.onFailure { showError("扫描失败：${it.message}") }
            }
        }
    }

    private fun renderRecords(fresh: Boolean) {
        val candidates = records.filter { it.candidate }
        adapter.submitList(candidates)
        scanSummary.text = "上次扫描：共 ${records.size} 张；候选 ${candidates.size} 张。\n列表仅显示候选，滚动加载，不会一次创建全部控件。"
        if (fresh) resultText.text = if (records.isEmpty()) "范围内没有可识别图片。" else "扫描记录已保存到应用本机空间。请选择候选进行单张试运行。"
        updateActions()
    }

    private fun confirmTrial() {
        val record = selected ?: return
        AlertDialog.Builder(this).setTitle("确认单张试运行")
            .setMessage("将创建全新会话并备份：\n${record.file.absolutePath}\n\n规则目标时间：${CaptureTimeParser.formatDisplay(record.targetCaptureTime)}\n${record.reason}\n失败将立即恢复。是否继续？")
            .setNegativeButton("取消", null).setPositiveButton("确认写入") { _, _ -> runSession(listOf(record), true) }.show()
    }

    private fun confirmBatch() {
        val processable = records.filter { it.file.absolutePath !in completedPaths && it.candidate && it.safeForTrial && (it.format == ImageFormat.JPEG || it.format in unlockedFormats) }
        AlertDialog.Builder(this).setTitle("批量执行风险确认")
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
        AlertDialog.Builder(this).setTitle("最近会话日志").setMessage("$summary\n\n目录：${directory.absolutePath}\n包含 planned.tsv、changed.tsv、skipped.tsv、restored.tsv。")
            .setPositiveButton("关闭", null).show()
    }

    private fun updateActions() {
        val granted = Environment.isExternalStorageManager()
        trialButton.isEnabled = granted && selected?.candidate == true && selected?.safeForTrial == true
        batchButton.isEnabled = granted && jpegTrialPassed && records.any { it.file.absolutePath !in completedPaths && it.candidate && it.safeForTrial && (it.format == ImageFormat.JPEG || it.format in unlockedFormats) }
        batchStatus.text = when { !jpegTrialPassed -> "需先完成一张 JPEG 试运行"; batchButton.isEnabled -> "JPEG 安全链路已通过，可批量确认"; else -> "当前没有可批量处理的候选" }
    }

    private fun setBusy(busy: Boolean, message: String) {
        scanProgress.visibility = if (busy) View.VISIBLE else View.GONE
        scanButton.isEnabled = !busy && Environment.isExternalStorageManager()
        trialButton.isEnabled = false; batchButton.isEnabled = false
        if (message.isNotBlank()) resultText.text = message
        if (!busy) updateActions()
    }

    private fun ensurePermission(): Boolean { if (Environment.isExternalStorageManager()) return true; Toast.makeText(this, "未授予所有文件访问权限，已拒绝执行", Toast.LENGTH_LONG).show(); updatePermissionState(); return false }
    private fun showError(message: String) { resultText.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun reloadTimeRule(rule: TimeRuleConfig = TimeRuleConfig.load(this)) {
        timeRule = rule
        scanner = PhotoScanner(mediaStore, exif, timeRule)
        processor = SafePhotoProcessor(this, exif, mediaStore, timeRule)
    }
}
