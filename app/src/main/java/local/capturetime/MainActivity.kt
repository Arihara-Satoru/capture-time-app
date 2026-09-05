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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import local.capturetime.exif.ExifGateway
import local.capturetime.duplicate.DuplicateCandidate
import local.capturetime.duplicate.DuplicateDeleteProcessor
import local.capturetime.duplicate.DuplicateScanner
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    private lateinit var duplicateScanner: DuplicateScanner
    private lateinit var duplicateProcessor: DuplicateDeleteProcessor
    private lateinit var duplicateAdapter: DuplicateAdapter
    private var records: List<PhotoRecord> = emptyList()
    private var selected: PhotoRecord? = null
    private var jpegTrialPassed = false
    private val unlockedFormats = mutableSetOf<ImageFormat>()
    private val completedPaths = mutableSetOf<String>()
    private var lastSession: File? = null
    private var settingsOpened = false
    private var resultSource = "上次扫描"
    private var duplicateCandidates: List<DuplicateCandidate> = emptyList()

    private val scanButton by lazy { findViewById<Button>(R.id.scanButton) }
    private val galleryButton by lazy { findViewById<Button>(R.id.galleryButton) }
    private val trialButton by lazy { findViewById<Button>(R.id.trialButton) }
    private val batchButton by lazy { findViewById<Button>(R.id.batchButton) }
    private val scanProgress by lazy { findViewById<ProgressBar>(R.id.scanProgress) }
    private val scanSummary by lazy { findViewById<TextView>(R.id.scanSummary) }
    private val batchStatus by lazy { findViewById<TextView>(R.id.batchStatus) }
    private val resultText by lazy { findViewById<TextView>(R.id.resultText) }
    private val duplicateScanButton by lazy { findViewById<Button>(R.id.duplicateScanButton) }
    private val duplicateDeleteButton by lazy { findViewById<Button>(R.id.duplicateDeleteButton) }
    private val duplicateProgress by lazy { findViewById<ProgressBar>(R.id.duplicateProgress) }
    private val duplicateSummary by lazy { findViewById<TextView>(R.id.duplicateSummary) }
    private val duplicateResult by lazy { findViewById<TextView>(R.id.duplicateResult) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)
        mediaStore = MediaStoreGateway(this)
        duplicateScanner = DuplicateScanner(mediaStore)
        duplicateProcessor = DuplicateDeleteProcessor(this, mediaStore)
        exif = ExifGateway()
        reloadTimeRule()
        snapshotStore = ScanSnapshotStore(this)
        adapter = PhotoAdapter { record -> selected = record; updateActions() }
        duplicateAdapter = DuplicateAdapter(::updateDuplicateActions, ::showDuplicateComparison)
        findViewById<RecyclerView>(R.id.photoList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        findViewById<RecyclerView>(R.id.duplicateList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = duplicateAdapter
            itemAnimator = null
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openAppSettings()
        }
        galleryButton.setOnClickListener { openGallery() }
        scanButton.setOnClickListener { scanAllPhotos() }
        trialButton.setOnClickListener { confirmTrial() }
        batchButton.setOnClickListener { confirmBatch() }
        duplicateScanButton.setOnClickListener { scanDuplicates() }
        duplicateDeleteButton.setOnClickListener { confirmDuplicateDelete() }
        findViewById<BottomNavigationView>(R.id.bottomNavigation).setOnItemSelectedListener { item ->
            val captureSelected = item.itemId == R.id.navigationCaptureTime
            findViewById<View>(R.id.captureTimePage).visibility = if (captureSelected) View.VISIBLE else View.GONE
            findViewById<View>(R.id.duplicatePhotoPage).visibility = if (captureSelected) View.GONE else View.VISIBLE
            findViewById<MaterialToolbar>(R.id.mainToolbar).title =
                if (captureSelected) "拍摄时间纠正" else "照片重复纠正"
            true
        }
        findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navigationCaptureTime
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
        duplicateScanButton.isEnabled = granted
        updateActions()
        updateDuplicateActions()
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

    private fun scanDuplicates() {
        if (!ensurePermission()) return
        setDuplicateBusy(true, "正在先枚举真实文件，再核对 MediaStore 尺寸...")
        executor.execute {
            val result = runCatching { duplicateScanner.scan() }
            runOnUiThread {
                setDuplicateBusy(false)
                result.onSuccess { scan ->
                    duplicateCandidates = scan.candidates
                    duplicateAdapter.submitList(scan.candidates)
                    val bytes = scan.candidates.sumOf { it.delete.size }
                    duplicateSummary.text = "真实文件 ${scan.realFiles} · MediaStore 有效 ${scan.mediaFiles} · 候选 ${scan.candidates.size}"
                    duplicateResult.text = if (scan.candidates.isEmpty()) {
                        "没有符合当前严格规则的可删项。视频仅在原名与六码副本的分辨率、时长和字节数完全一致时列出。"
                    } else "默认已勾选全部候选，预计释放 ${formatBytes(bytes)}。可逐项取消；执行前还会重新核验并建立新备份会话。"
                }.onFailure { showDuplicateError("重复项扫描失败：${it.message}") }
            }
        }
    }

    private fun confirmDuplicateDelete() {
        val selectedCandidates = duplicateAdapter.selected()
        if (selectedCandidates.isEmpty()) return
        val bytes = selectedCandidates.sumOf { it.delete.size }
        MaterialAlertDialogBuilder(this)
            .setTitle("确认备份并删除？")
            .setMessage("将处理 ${selectedCandidates.size} 个严格候选，约 ${formatBytes(bytes)}。\n\n每项先重新核验真实路径、尺寸/时长、实际大小和 SHA-256，再按原始相对路径复制到新的 /sdcard/.temp/duplicate-cleanup-* 会话；只有逐字节一致才删除原件。已有 .temp 备份绝不删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行") { _, _ -> deleteDuplicates(selectedCandidates) }
            .show()
    }

    private fun deleteDuplicates(candidates: List<DuplicateCandidate>) {
        if (!ensurePermission()) return
        setDuplicateBusy(true, "正在逐项复核、备份并删除...")
        executor.execute {
            val result = runCatching { duplicateProcessor.delete(candidates) }
            val rescan = result.getOrNull()?.let { runCatching { duplicateScanner.scan() } }
            runOnUiThread {
                setDuplicateBusy(false)
                result.onSuccess { outcome ->
                    rescan?.onSuccess { scan ->
                        duplicateCandidates = scan.candidates
                        duplicateAdapter.submitList(scan.candidates)
                        duplicateSummary.text = "复扫后候选 ${scan.candidates.size} · 本批删除 ${outcome.deleted} · 跳过 ${outcome.skipped}"
                    }
                    duplicateResult.text = buildString {
                        append("本批删除 ").append(outcome.deleted).append("，跳过 ").append(outcome.skipped).append("。\n会话：").append(outcome.sessionDirectory.absolutePath)
                        append("\n日志：deleted.tsv；原路径、保留文件和备份均已逐项核验。")
                        if (outcome.failures.isNotEmpty()) append("\n\n").append(outcome.failures.joinToString("\n"))
                        rescan?.exceptionOrNull()?.let { append("\n\n删除完成，但剩余文件复扫失败：").append(it.message) }
                    }
                }.onFailure { showDuplicateError("无法执行重复清理：${it.message}") }
            }
        }
    }

    private fun updateDuplicateActions() {
        duplicateDeleteButton.isEnabled = hasStorageAccess() && duplicateAdapter.selected().isNotEmpty() && duplicateProgress.visibility != View.VISIBLE
    }

    private fun showDuplicateComparison(candidate: DuplicateCandidate) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 0, 16, 0)
        }
        val deletePanel = comparisonPanel("待处理", candidate.delete.file, candidate.delete.width, candidate.delete.height, candidate.delete.size)
        val retainedPanel = comparisonPanel("保留", candidate.retained.file, candidate.retained.width, candidate.retained.height, candidate.retained.size)
        dialogView.addView(deletePanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dialogView.addView(Space(this), LinearLayout.LayoutParams(12, 1))
        dialogView.addView(retainedPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        MaterialAlertDialogBuilder(this)
            .setTitle("照片比对")
            .setMessage("请确认待处理照片确实是你要删除的副本。当前候选规则：${candidate.reason}")
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun comparisonPanel(label: String, file: File, width: Int, height: Int, size: Long): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = label
            textSize = 16f
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 180)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(android.R.drawable.ic_menu_gallery)
            contentDescription = "${label}照片预览"
        }
        val info = TextView(this).apply {
            text = "${file.name}\n${width}×${height} · ${formatBytes(size)}\n${file.parent}"
            textSize = 12f
        }
        panel.addView(title)
        panel.addView(image)
        panel.addView(info)
        Thread {
            val bitmap: Bitmap? = runCatching {
                if (file.extension.lowercase() in setOf("mp4", "mov", "mkv", "3gp", "webm")) {
                    ThumbnailUtils.createVideoThumbnail(file, Size(480, 480), null)
                } else ThumbnailUtils.createImageThumbnail(file, Size(480, 480), null)
            }.getOrNull()
            image.post { if (bitmap != null) image.setImageBitmap(bitmap) }
        }.start()
        return panel
    }

    private fun setDuplicateBusy(busy: Boolean, message: String = "") {
        duplicateProgress.visibility = if (busy) View.VISIBLE else View.GONE
        duplicateScanButton.isEnabled = !busy && hasStorageAccess()
        duplicateDeleteButton.isEnabled = false
        if (message.isNotBlank()) duplicateResult.text = message
        if (!busy) updateDuplicateActions()
    }

    private fun showDuplicateError(message: String) {
        duplicateResult.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.1f KB".format(bytes / 1024.0)
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
        val mediaGranted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return Environment.isExternalStorageManager() && mediaGranted
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
