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
import androidx.appcompat.app.AlertDialog
import local.capturetime.exif.ExifGateway
import local.capturetime.duplicate.DuplicateCandidate
import local.capturetime.duplicate.DuplicateDeleteConfirmation
import local.capturetime.duplicate.DuplicateDeletePreparation
import local.capturetime.duplicate.DuplicateDeleteProcessor
import local.capturetime.duplicate.DuplicateScanner
import local.capturetime.media.MediaStoreGateway
import local.capturetime.model.ImageFormat
import local.capturetime.model.PhotoRecord
import local.capturetime.operation.BackupOperationGuard
import local.capturetime.operation.SafePhotoProcessor
import local.capturetime.operation.SessionLogger
import local.capturetime.scan.PhotoScanner
import local.capturetime.scan.ScanSnapshotStore
import local.capturetime.settings.TimeRuleConfig
import local.capturetime.time.CaptureTimeParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private var pendingDuplicateDelete: DuplicateDeletePreparation? = null
    private var duplicateDeleteStateBlocked = false
    private var duplicatePendingStateUnreadable = false
    private var processingDialog: AlertDialog? = null

    private val scanButton by lazy { findViewById<Button>(R.id.scanButton) }
    private val galleryButton by lazy { findViewById<Button>(R.id.galleryButton) }
    private val trialButton by lazy { findViewById<Button>(R.id.trialButton) }
    private val batchButton by lazy { findViewById<Button>(R.id.batchButton) }
    private val scanProgress by lazy { findViewById<ProgressBar>(R.id.scanProgress) }
    private val scanSummary by lazy { findViewById<TextView>(R.id.scanSummary) }
    private val accessStatus by lazy { findViewById<TextView>(R.id.accessStatus) }
    private val resultText by lazy { findViewById<TextView>(R.id.resultText) }
    private val duplicateScanButton by lazy { findViewById<Button>(R.id.duplicateScanButton) }
    private val duplicateDeleteButton by lazy { findViewById<Button>(R.id.duplicateDeleteButton) }
    private val duplicateProgress by lazy { findViewById<ProgressBar>(R.id.duplicateProgress) }
    private val duplicateSummary by lazy { findViewById<TextView>(R.id.duplicateSummary) }
    private val duplicateResult by lazy { findViewById<TextView>(R.id.duplicateResult) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        mediaStore = MediaStoreGateway(applicationContext)
        duplicateScanner = DuplicateScanner(mediaStore)
        duplicateProcessor = DuplicateDeleteProcessor(applicationContext, mediaStore)
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
        findViewById<Button>(R.id.galleryRootEntry).setOnClickListener {
            startActivity(Intent(this, local.capturetime.gallery.GalleryRepairActivity::class.java))
        }
        duplicateScanButton.setOnClickListener { scanDuplicates() }
        duplicateDeleteButton.setOnClickListener {
            if (duplicateOperationActive.get()) {
                showDuplicateError("重复清理仍在准备或核验，请稍候")
                return@setOnClickListener
            }
            refreshPendingDuplicateDeleteState()
            when {
                pendingDuplicateDelete != null -> confirmPendingDuplicateDeleteRecovery(requireNotNull(pendingDuplicateDelete))
                duplicateDeleteStateBlocked -> showBlockedDuplicateDeleteState()
                else -> confirmDuplicateDelete()
            }
        }
        findViewById<BottomNavigationView>(R.id.bottomNavigation).setOnItemSelectedListener { item ->
            val captureSelected = item.itemId == R.id.navigationCaptureTime
            findViewById<View>(R.id.captureTimePage).visibility = if (captureSelected) View.VISIBLE else View.GONE
            findViewById<View>(R.id.duplicatePhotoPage).visibility = if (captureSelected) View.GONE else View.VISIBLE
            findViewById<MaterialToolbar>(R.id.mainToolbar).title =
                 if (captureSelected) "拍摄时间" else "重复照片"
            true
        }
        findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.navigationCaptureTime
        loadSavedScan()
        updatePermissionState()
        refreshPendingDuplicateDeleteState()
        pendingDuplicateDelete?.let {
            if (it.confirmation == DuplicateDeleteConfirmation.CONFIRMED) {
                duplicateResult.text = "发现系统已删除但尚未完成核验的会话，正在继续核验..."
                verifyPreparedDuplicateDeletion(it)
            } else {
                duplicateResult.text = "发现结果未知的系统删除会话。应用不会据此再次删除；可点击下方按钮只读核验当前文件状态。\n会话：${it.sessionDirectory.absolutePath}"
                updateDuplicateActions()
            }
        }
        if (pendingDuplicateDelete == null && duplicateDeleteStateBlocked) {
            duplicateResult.text = if (duplicatePendingStateUnreadable) {
                "待处理的删除状态无法读取。为避免覆盖恢复信息，新的重复删除和备份清理已停用；请点击下方按钮处理。"
            } else {
                "发现中断的删除准备。原件不会被自动删除；请点击下方按钮处理。"
            }
            updateDuplicateActions()
        }
        if (BackupOperationGuard.hasCaptureRecoveryState(applicationContext)) {
            showStatus("发现上次中断的拍摄时间会话。备份清理和新的写入/删除已锁定，请先检查备份。")
            if (state == null) showCaptureRecoveryPrompt()
        }
        refreshDuplicateOperationButtonWhenIdle()
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
        accessStatus.text = if (granted) "权限已授予" else "需要照片与所有文件访问权限"
        accessStatus.setTextColor(getColor(if (granted) R.color.permission_granted else R.color.permission_missing))
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
        showStatus("已读取上次扫描记录。照片未被读取或写入。请选择相册导入或全局扫描以获取最新状态。")
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
        if (requestCode == REQUEST_DELETE_DUPLICATES) {
            finishDuplicateDelete(resultCode == RESULT_OK)
            return
        }
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
        val filenameTimes = records.count { it.filenameTime != null }
        adapter.submitList(candidates)
        scanSummary.text = "$resultSource · 已检查 ${records.size} 张 · 文件名时间 $filenameTimes 张 · 候选 ${candidates.size} 张"
        if (fresh) showStatus(if (records.isEmpty()) "范围内没有可识别图片。" else "扫描记录已保存到应用本机空间。请选择候选进行单张试运行。")
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
        if (BackupOperationGuard.hasCaptureRecoveryState(applicationContext)) {
            showCaptureRecoveryPrompt()
            return
        }
        showProcessingDialog(if (trial) "正在安全试运行" else "正在批量执行", if (trial) "正在备份、修改并核验照片，请稍候…" else "正在逐张备份、修改并核验照片，请勿退出应用…")
        setBusy(true, if (trial) "正在执行单张安全链路..." else "正在逐张执行批量安全链路...")
        executor.execute {
            val outcome = runCatching {
                val started = android.os.SystemClock.elapsedRealtime()
                runOnUiThread { processingDialog?.setMessage("正在批量写入执行计划与跳过清单…") }
                var session: SessionLogger? = null
                var completed = false
                try {
                    session = BackupOperationGuard.beginCapture(
                        applicationContext,
                        { SessionLogger.create(Environment.getExternalStorageDirectory(), records) },
                        SessionLogger::directory
                    )
                    val paths = selectedRecords.mapTo(hashSetOf()) { it.file.absolutePath }
                    session.logUnselected(records, paths)
                    var successCount = 0
                    val results = selectedRecords.mapIndexed { index, record ->
                        val result = processor.process(record, session) { stage ->
                            val seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000
                            val successes = successCount
                            runOnUiThread {
                                processingDialog?.setMessage("已完成 $index / ${selectedRecords.size} · 成功 $successes\n已用时 ${seconds / 60} 分 ${seconds % 60} 秒\n${record.file.name}\n当前阶段：$stage")
                            }
                        }
                        session.logResult(result)
                        if (result.success) successCount++
                        result
                    }
                    completed = true
                    session to results
                } finally {
                    session?.let { BackupOperationGuard.endCapture(applicationContext, it.directory, completed) }
                }
            }
            runOnUiThread {
                dismissProcessingDialog()
                setBusy(false, "")
                outcome.onSuccess { (session, results) ->
                    lastSession = session.directory
                    if (trial && results.singleOrNull()?.success == true) {
                        unlockedFormats += results.single().record.format
                        if (results.single().record.format == ImageFormat.JPEG) jpegTrialPassed = true
                    }
                    val successfulPaths = results.filter { it.success }.mapTo(hashSetOf()) { it.record.file.absolutePath }
                    results.filter { it.success }.forEach { completedPaths += it.record.file.absolutePath }
                    if (successfulPaths.isNotEmpty()) {
                        records = records.filterNot { it.file.absolutePath in successfulPaths }
                        snapshotStore.save(records)
                        selected = null
                        adapter.clearSelection()
                        renderRecords(false)
                    }
                    val success = results.count { it.success }; val restored = results.count { it.restored }
                    showStatus("本次成功 $success，恢复 $restored，失败 ${results.size - success}。已成功修改的照片已从候选预览移除。")
                    updateActions()
                    showSessionLog(session.directory, success, restored, results.size - success)
                }.onFailure {
                    showError("无法执行：${it.message}")
                    if (BackupOperationGuard.hasCaptureRecoveryState(applicationContext)) showCaptureRecoveryPrompt()
                }
            }
        }
    }

    private fun showCaptureRecoveryPrompt() {
        val session = BackupOperationGuard.captureRecoverySession(applicationContext) ?: "状态文件无法读取"
        MaterialAlertDialogBuilder(this)
            .setTitle("检查中断的拍摄时间会话")
            .setMessage("上次操作可能在写入、恢复或日志落盘期间中断。为保护备份，新的照片写入、重复删除和备份清理已锁定。请先检查原文件及会话备份；只有确认不再需要应用保留该恢复状态时才解除锁定。\n\n会话：$session")
            .setNegativeButton("保留锁定", null)
            .setPositiveButton("仅解除状态锁") { _, _ ->
                if (BackupOperationGuard.acknowledgeCaptureRecovery(applicationContext)) {
                    showStatus("已解除中断状态锁；照片、备份和日志均未改动。")
                    updateActions()
                    updateDuplicateActions()
                } else {
                    showError("拍摄时间操作仍在进行，不能解除状态锁")
                }
            }
            .show()
    }

    private fun showLogs() {
        val directory = lastSession ?: return
        val summary = File(directory, "summary.json").takeIf { it.isFile }?.readText().orEmpty()
        MaterialAlertDialogBuilder(this).setTitle("最近会话日志").setMessage("$summary\n\n目录：${directory.absolutePath}\n包含 planned.tsv、changed.tsv、skipped.tsv、restored.tsv。")
            .setPositiveButton("关闭", null).show()
    }

    private fun showSessionLog(directory: File, success: Int, restored: Int, failed: Int) {
        val summary = File(directory, "summary.json").takeIf { it.isFile }?.readText().orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle("执行完成 · 会话日志")
            .setMessage("成功 $success · 恢复 $restored · 失败 $failed\n\n$summary\n\n目录：${directory.absolutePath}\n包含 planned.tsv、changed.tsv、skipped.tsv、restored.tsv。")
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showProcessingDialog(title: String, message: String) {
        processingDialog?.dismiss()
        processingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setCancelable(false)
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }

    private fun dismissProcessingDialog() {
        processingDialog?.dismiss()
        processingDialog = null
    }

    private fun scanDuplicates() {
        if (!ensurePermission()) return
        duplicateCandidates = emptyList()
        duplicateAdapter.submitList(emptyList())
        duplicateSummary.text = "正在重新扫描"
        setDuplicateBusy(true, "正在先枚举真实文件，再核对 MediaStore 尺寸...")
        executor.execute {
            val result = runCatching { duplicateScanner.scan() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setDuplicateBusy(false)
                result.onSuccess { scan ->
                    duplicateCandidates = scan.candidates
                    duplicateAdapter.submitList(scan.candidates)
                    val bytes = scan.candidates.sumOf { it.delete.size }
                    duplicateSummary.text = "真实文件 ${scan.realFiles} · MediaStore 有效 ${scan.mediaFiles} · 候选 ${scan.candidates.size}"
                    duplicateResult.text = if (scan.candidates.isEmpty()) {
                        "没有符合规则的重复项。"
                    } else "候选 ${scan.candidates.size} 项，预计释放 ${formatBytes(bytes)}。"
                }.onFailure { showDuplicateError("重复项扫描失败：${it.message}") }
            }
        }
    }

    private fun confirmDuplicateDelete() {
        if (BackupOperationGuard.hasCaptureRecoveryState(applicationContext)) {
            showCaptureRecoveryPrompt()
            return
        }
        val selectedCandidates = duplicateAdapter.selected()
        if (selectedCandidates.isEmpty()) return
        val bytes = selectedCandidates.sumOf { it.delete.size }
        MaterialAlertDialogBuilder(this)
            .setTitle("确认备份并删除？")
            .setMessage("将处理 ${selectedCandidates.size} 个候选，约 ${formatBytes(bytes)}。\n\n文件名规则只表示同目录文件名关联，两张图的内容可能不同；请先点“比对”确认待处理项。执行前会分别重新核验两张图，并按原始相对路径备份待处理文件、校验备份 SHA-256，随后显示 Android 系统删除确认。已有 .temp 备份绝不删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行") { _, _ -> deleteDuplicates(selectedCandidates) }
            .show()
    }

    private fun deleteDuplicates(candidates: List<DuplicateCandidate>) {
        if (!ensurePermission()) return
        refreshPendingDuplicateDeleteState()
        pendingDuplicateDelete?.let {
            pendingDuplicateDelete = it
            updateDuplicateActions()
            showDuplicateError("存在尚未处理完的删除会话，请先核验或清除其待处理状态")
            return
        }
        if (duplicateDeleteStateBlocked) {
            updateDuplicateActions()
            showDuplicateError("删除状态仍被锁定，请先处理上次中断或损坏的状态")
            return
        }
        if (!duplicateOperationActive.compareAndSet(false, true)) {
            showDuplicateError("另一项重复清理操作仍在进行，请稍候")
            return
        }
        pendingDuplicateDelete = null
        setDuplicateBusy(true, "正在逐项复核并备份，尚未删除原件...")
        val processor = duplicateProcessor
        val activity = WeakReference(this)
        duplicateExecutor.execute {
            val result = runCatching { processor.prepare(candidates) }
            duplicateOperationActive.set(false)
            val current = activity.get() ?: return@execute
            current.runOnUiThread {
                if (current.isDestroyed) return@runOnUiThread
                result.onSuccess(current::requestSystemDuplicateDelete)
                    .onFailure {
                        current.setDuplicateBusy(false)
                        current.showDuplicateError("无法准备重复清理：${it.message}")
                    }
            }
        }
    }

    private fun requestSystemDuplicateDelete(preparation: DuplicateDeletePreparation) {
        if (preparation.prepared.isEmpty()) {
            setDuplicateBusy(false)
            duplicateResult.text = buildString {
                append("没有项目通过删除前复核，原件均未删除。\n会话：")
                    .append(preparation.sessionDirectory.absolutePath)
                if (preparation.failures.isNotEmpty()) append("\n\n").append(preparation.failures.joinToString("\n"))
            }
            return
        }
        pendingDuplicateDelete = preparation
        updateDuplicateActions()
        duplicateResult.text = "已安全备份 ${preparation.prepared.size} 项，正在等待 Android 系统删除确认；取消不会删除原件。"
        runCatching {
            val uris = preparation.prepared.flatMap { it.mediaUris }.distinct()
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            startIntentSenderForResult(request.intentSender, REQUEST_DELETE_DUPLICATES, null, 0, 0, 0)
        }.onFailure {
            pendingDuplicateDelete = null
            duplicateProcessor.clearPending(preparation)
            refreshPendingDuplicateDeleteState()
            setDuplicateBusy(false)
            showDuplicateError("无法打开系统删除确认，原件均未删除：${it.message}")
        }
    }

    private fun finishDuplicateDelete(confirmed: Boolean) {
        val preparation = pendingDuplicateDelete ?: duplicateProcessor.loadPending()
        pendingDuplicateDelete = null
        if (preparation == null) {
            setDuplicateBusy(false)
            showDuplicateError("删除确认状态已丢失，请重新扫描；不会自动再次删除")
            return
        }
        if (!confirmed) {
            duplicateProcessor.clearPending(preparation)
            refreshPendingDuplicateDeleteState()
            setDuplicateBusy(false)
            duplicateResult.text = "已取消系统删除，原件未由本次操作删除。删除前备份保留在：${preparation.sessionDirectory.absolutePath}"
            return
        }
        val confirmedPreparation = runCatching {
            duplicateProcessor.updateConfirmation(preparation, DuplicateDeleteConfirmation.CONFIRMED)
        }.getOrElse {
            preparation.copy(confirmation = DuplicateDeleteConfirmation.CONFIRMED)
        }
        pendingDuplicateDelete = confirmedPreparation
        duplicateCandidates = emptyList()
        duplicateAdapter.submitList(emptyList())
        duplicateSummary.text = "系统已确认删除，正在核验并重新扫描"
        duplicateResult.text = "系统删除已确认，正在核验原路径、媒体库记录、保留文件和备份..."
        verifyPreparedDuplicateDeletion(confirmedPreparation)
    }

    private fun confirmPendingDuplicateDeleteRecovery(preparation: DuplicateDeletePreparation) {
        val confirmed = preparation.confirmation == DuplicateDeleteConfirmation.CONFIRMED
        MaterialAlertDialogBuilder(this)
            .setTitle(if (confirmed) "重新核验删除结果？" else "核验中断的删除会话？")
            .setMessage(if (confirmed) {
                "系统确认结果已保存，但上次核验未全部通过。重新核验不会再次删除任何文件。\n\n会话：${preparation.sessionDirectory.absolutePath}"
            } else {
                "应用没有收到或持久化本次系统确认结果。只读核验不会再次删除任何文件；若原路径已消失，只能记录当前状态，不能证明由本次系统确认删除。\n\n会话：${preparation.sessionDirectory.absolutePath}"
            })
            .setNegativeButton("暂不处理", null)
            .setNeutralButton("清除待处理状态") { _, _ ->
                if (duplicateOperationActive.get()) {
                    showDuplicateError("核验仍在进行，不能清除待处理状态")
                    return@setNeutralButton
                }
                duplicateProcessor.clearPending(preparation)
                refreshPendingDuplicateDeleteState()
                duplicateResult.text = "已清除待处理状态；原文件和会话备份均未改动。"
                updateDuplicateActions()
            }
            .setPositiveButton("只读核验") { _, _ ->
                val recovery = if (confirmed) preparation else runCatching {
                    duplicateProcessor.updateConfirmation(preparation, DuplicateDeleteConfirmation.UNKNOWN)
                }.getOrElse { preparation.copy(confirmation = DuplicateDeleteConfirmation.UNKNOWN) }
                pendingDuplicateDelete = recovery
                verifyPreparedDuplicateDeletion(recovery)
            }
            .show()
    }

    private fun refreshPendingDuplicateDeleteState() {
        val hasPendingState = duplicateProcessor.hasPendingState()
        pendingDuplicateDelete = duplicateProcessor.loadPending()
        duplicatePendingStateUnreadable = hasPendingState && pendingDuplicateDelete == null
        duplicateDeleteStateBlocked = duplicateProcessor.hasBlockingState()
    }

    private fun showBlockedDuplicateDeleteState() {
        if (duplicateOperationActive.get()) {
            showDuplicateError("重复清理仍在准备或核验，请稍候")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (duplicatePendingStateUnreadable) "删除状态无法读取" else "删除准备已中断")
            .setMessage("应用将保持停用新的重复删除和备份清理。只有确认不再需要恢复该待处理操作时，才清除私有状态锁；共享存储中的照片、备份和日志不会被删除。")
            .setNegativeButton("保留锁定", null)
            .setPositiveButton("仅清除状态锁") { _, _ ->
                duplicateProcessor.clearPending()
                refreshPendingDuplicateDeleteState()
                duplicateResult.text = "已清除中断状态锁；照片、会话备份和日志均未改动。"
                updateDuplicateActions()
            }
            .show()
    }

    private fun verifyPreparedDuplicateDeletion(preparation: DuplicateDeletePreparation) {
        if (!duplicateOperationActive.compareAndSet(false, true)) {
            duplicateResult.text = "另一项重复清理操作仍在进行，请稍候后重新核验。"
            updateDuplicateActions()
            return
        }
        setDuplicateBusy(true)
        val processor = duplicateProcessor
        val scanner = duplicateScanner
        val activity = WeakReference(this)
        duplicateExecutor.execute {
            var fullyVerified = false
            val result = try {
                runCatching {
                    val outcome = processor.verifyDeletion(preparation)
                    fullyVerified = outcome.deleted == preparation.prepared.size && outcome.verified == outcome.deleted
                    if (fullyVerified) processor.clearPending(preparation)
                    outcome
                }
            } finally {
                duplicateOperationActive.set(false)
            }
            val rescan = result.getOrNull()?.let { runCatching { scanner.scan() } }
            val current = activity.get() ?: return@execute
            current.runOnUiThread {
                if (current.isDestroyed) return@runOnUiThread
                if (fullyVerified) current.refreshPendingDuplicateDeleteState()
                current.setDuplicateBusy(false)
                result.onSuccess { outcome ->
                    val confirmed = preparation.confirmation == DuplicateDeleteConfirmation.CONFIRMED
                    val deletedLabel = if (confirmed) "系统确认后原路径已消失" else "观察到原路径已消失"
                    rescan?.onSuccess { scan ->
                        current.duplicateCandidates = scan.candidates
                        current.duplicateAdapter.submitList(scan.candidates)
                        current.duplicateSummary.text = "复扫后候选 ${scan.candidates.size} · $deletedLabel ${outcome.deleted} · 核验通过 ${outcome.verified}"
                    }
                    current.duplicateResult.text = buildString {
                        append(deletedLabel).append(' ').append(outcome.deleted).append("，核验通过 ").append(outcome.verified)
                        append("，待确认 ").append(outcome.deleted - outcome.verified).append("，未删除 ").append(outcome.skipped)
                        append("。\n会话：").append(outcome.sessionDirectory.absolutePath)
                        append("\n日志：prepared.tsv 记录删除前备份，deleted.tsv 记录核验时观察到原路径已消失的项目及确认状态。")
                        if (outcome.failures.isNotEmpty()) append("\n\n").append(outcome.failures.joinToString("\n"))
                        rescan?.exceptionOrNull()?.let { append("\n\n删除完成，但剩余文件复扫失败：").append(it.message) }
                    }
                }.onFailure { current.showDuplicateError("系统删除后的核验失败：${it.message}") }
            }
        }
    }

    private fun updateDuplicateActions() {
        if (BackupOperationGuard.hasGalleryState(applicationContext)) {
            duplicateDeleteButton.isEnabled = false
            duplicateDeleteButton.text = "小米相册修复待核验"
            return
        }
        if (BackupOperationGuard.hasCaptureRecoveryState(applicationContext)) {
            duplicateDeleteButton.isEnabled = false
            duplicateDeleteButton.text = "拍摄时间会话待检查"
            return
        }
        if (duplicateOperationActive.get()) {
            duplicateDeleteButton.isEnabled = false
            duplicateDeleteButton.text = "重复清理处理中"
            return
        }
        if (duplicateDeleteStateBlocked && pendingDuplicateDelete == null) {
            duplicateDeleteButton.isEnabled = hasStorageAccess() && duplicateProgress.visibility != View.VISIBLE
            duplicateDeleteButton.text = if (duplicatePendingStateUnreadable) {
                "处理损坏的删除状态"
            } else {
                "处理中断的删除准备"
            }
            return
        }
        pendingDuplicateDelete?.let {
            duplicateDeleteButton.isEnabled = hasStorageAccess() && duplicateProgress.visibility != View.VISIBLE
            duplicateDeleteButton.text = if (it.confirmation == DuplicateDeleteConfirmation.CONFIRMED) {
                "重新核验删除结果"
            } else {
                "核验中断的删除会话"
            }
            return
        }
        val selected = duplicateAdapter.selected()
        duplicateDeleteButton.isEnabled = hasStorageAccess() && selected.isNotEmpty() && duplicateProgress.visibility != View.VISIBLE
        duplicateDeleteButton.text = if (selected.isEmpty()) {
            "备份并删除已勾选项"
        } else {
            "备份并删除 ${selected.size} 项（约 ${formatBytes(selected.sumOf { it.delete.size })}）"
        }
    }

    private fun refreshDuplicateOperationButtonWhenIdle() {
        if (!duplicateOperationActive.get()) {
            refreshPendingDuplicateDeleteState()
            updateDuplicateActions()
            return
        }
        duplicateDeleteButton.postDelayed({
            if (!isFinishing && !isDestroyed) refreshDuplicateOperationButtonWhenIdle()
        }, 500)
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
        val recoveryBlocked = BackupOperationGuard.hasCaptureRecoveryState(applicationContext) || BackupOperationGuard.hasGalleryState(applicationContext)
        findViewById<Button>(R.id.galleryRootEntry).text = if (BackupOperationGuard.hasGalleryState(applicationContext))
            "核验小米相册修复 · Root" else "小米相册时间修复 · Root"
        trialButton.isEnabled = granted && !recoveryBlocked && selected?.candidate == true && selected?.safeForTrial == true
        batchButton.isEnabled = granted && !recoveryBlocked && jpegTrialPassed && records.any { it.file.absolutePath !in completedPaths && it.candidate && it.safeForTrial && (it.format == ImageFormat.JPEG || it.format in unlockedFormats) }
    }

    private fun setBusy(busy: Boolean, message: String) {
        scanProgress.visibility = if (busy) View.VISIBLE else View.GONE
        scanButton.isEnabled = !busy && hasStorageAccess()
        galleryButton.isEnabled = !busy && hasStorageAccess()
        trialButton.isEnabled = false; batchButton.isEnabled = false
        if (message.isNotBlank()) showStatus(message)
        if (!busy) updateActions()
    }

    private fun ensurePermission(): Boolean {
        if (hasStorageAccess()) return true
        showPermissionPrompt()
        updatePermissionState()
        return false
    }
    private fun showStatus(message: String) { resultText.text = message; resultText.visibility = View.VISIBLE }
    private fun showError(message: String) { showStatus(message); Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

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
        private const val REQUEST_DELETE_DUPLICATES = 31
        private val duplicateOperationActive = AtomicBoolean(false)
        private val duplicateExecutor = Executors.newSingleThreadExecutor()
    }
}
