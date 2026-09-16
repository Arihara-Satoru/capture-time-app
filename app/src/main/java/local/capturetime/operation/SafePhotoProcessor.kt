package local.capturetime.operation

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.system.Os
import local.capturetime.exif.ExifGateway
import local.capturetime.exif.JpegStructure
import local.capturetime.media.MediaStoreGateway
import local.capturetime.model.PhotoRecord
import local.capturetime.model.ProcessResult
import local.capturetime.security.PathPolicy
import local.capturetime.settings.TimeField
import local.capturetime.settings.TimeRuleConfig
import local.capturetime.time.CaptureTimeParser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SafePhotoProcessor(
    private val context: Context,
    private val exif: ExifGateway,
    private val mediaStore: MediaStoreGateway,
    private val rule: TimeRuleConfig = TimeRuleConfig()
) {
    fun process(record: PhotoRecord, session: SessionLogger, onStage: (String) -> Unit = {}): ProcessResult {
        val timings = mutableListOf<Pair<String, Long>>()
        var stage = "写入前校验"
        var started = System.nanoTime()
        fun nextStage(next: String) {
            val now = System.nanoTime()
            timings += stage to TimeUnit.NANOSECONDS.toMillis(now - started)
            stage = next
            started = now
            onStage(next)
        }
        onStage(stage)
        return try {
            processInternal(record, session, ::nextStage)
        } finally {
            timings += stage to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            // Diagnostic logging must not turn a completed write into an unreported failure.
            runCatching { session.logTimings(record, timings) }
        }
    }

    private fun processInternal(record: PhotoRecord, session: SessionLogger, onStage: (String) -> Unit): ProcessResult {
        val target = record.targetCaptureTime
            ?: return failure(record, "目标时间缺失")
        if (!Environment.isExternalStorageManager()) return failure(record, "所有文件访问权限已撤销")
        if (!record.file.isFile || !record.file.canWrite()) return failure(record, "原文件不存在或不可写")

        val storage = Environment.getExternalStorageDirectory()
        val roots = listOf(storage)
        if (!PathPolicy.isSafeFile(record.file, roots)) return failure(record, "写入前路径安全校验失败")
        val relative = PathPolicy.relativeStoragePath(record.file, storage)
            ?: return failure(record, "原路径不在共享存储内")
        val backup = File(session.directory, relative)
        val originalExif = runCatching { exif.readRaw(record.file) }.getOrNull()
        val originalMedia = runCatching { mediaStore.query(record.file) }.getOrNull()
            ?: return failure(record, "写入前 MediaStore 记录已消失")
        if (originalMedia.rawDateAddedSeconds == null) return failure(record, "写入前 DATE_ADDED 已不可核验")
        val originalModifiedMillis = record.file.lastModified()
        val stem = record.file.name.substringBeforeLast('.', record.file.name)
        if (TimeField.FILENAME in rule.sourceFields && CaptureTimeParser.hasAmbiguousFilenameTime(stem)) {
            return failure(record, "写入前文件名时间存在歧义")
        }
        val values = rule.values(originalExif, originalMedia, CaptureTimeParser.parseFilename(stem), java.time.Instant.ofEpochMilli(originalModifiedMillis))
        val recalculatedTarget = rule.selectTarget(values)
        val changedFields = rule.fieldsNeedingChange(values, target, originalExif).toSet()
        if (recalculatedTarget != target || changedFields.isEmpty()) {
            return failure(record, "预览后时间状态已变化，已安全跳过")
        }
        var modified = false
        var backupReceipt: VerifiedPhotoBackup.BackupReceipt? = null
        var exifVerification = "未执行"
        var mediaVerification = "未执行"

        try {
            val exifFields = changedFields.filterTo(mutableSetOf()) { it != TimeField.FILE_MODIFIED }
            if (exifFields.isNotEmpty()) {
                onStage("JPEG 结构检查")
                JpegStructure.validate(record.file)
            }
            onStage("备份与哈希校验")
            val parent = requireNotNull(backup.parentFile) { "备份目录无效" }
            require(parent.isDirectory || parent.mkdirs()) { "无法创建备份目录" }
            require(!backup.exists()) { "备份路径已存在，拒绝覆盖" }
            backupReceipt = VerifiedPhotoBackup.copyNewAndVerify(record.file, backup, session.directory)

            onStage("写入与核验 EXIF")
            val expectedModified = if (TimeField.FILE_MODIFIED in changedFields) target.toEpochMilli() else originalModifiedMillis
            val actualExif = VerifiedPhotoBackup.withVerifiedWritableFile(
                record.file,
                storage,
                backupReceipt.sourceIdentity
            ) { descriptor, descriptorFile ->
                // saveAttributes can fail after starting to replace the original file.
                modified = true
                if (exifFields.isNotEmpty()) exif.write(descriptor, target, exifFields)
                exifVerification = if (exifFields.isEmpty() || exif.verify(descriptor, target, exifFields)) "通过" else "失败"
                require(exifVerification == "通过") { "所选 EXIF 字段核验失败" }
                require(descriptorFile.setLastModified(expectedModified)) { "无法设置文件修改时间" }
                val stat = Os.fstat(descriptor)
                val modifiedMillis = stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000
                require(kotlin.math.abs(modifiedMillis - expectedModified) < 1000) { "文件修改时间核验失败" }
                exif.readRaw(descriptor)
            }
            val expectedMillis = MediaScanExpectation.dateTaken(
                actualExif.original, originalMedia.dateTaken?.toEpochMilli(), actualExif.originalOffset
            )
            onStage("系统媒体扫描")
            val scannedUri = scan(record.file)
            require(scannedUri != null) { "媒体扫描超时或失败" }
            onStage("MediaStore 时间核验")
            val verified = waitForMedia(record.file, scannedUri, expectedMillis, originalMedia.rawDateAddedSeconds,
                secondPrecision = CaptureTimeParser.parseExif(actualExif.original, actualExif.originalOffset) != null)
            mediaVerification = if (verified) "通过（扫描回调 URI）" else mediaDiagnostic(record.file, scannedUri, expectedMillis, originalMedia.rawDateAddedSeconds)
            require(verified) { "MediaStore DATE_TAKEN 或 DATE_ADDED 核验失败" }
            require(VerifiedPhotoBackup.hasIdentity(record.file, storage, backupReceipt.sourceIdentity)) {
                "写入后原路径文件身份已变化"
            }

            return ProcessResult(record, true, false, false, "成功", backup.absolutePath, exifVerification, mediaVerification, "无需恢复")
        } catch (error: Exception) {
            if (!modified) return failure(record, error.message ?: "备份阶段失败", backup.takeIf { it.exists() }?.absolutePath, true)
            onStage("失败恢复")
            val restore = restore(
                record.file,
                backup,
                session.directory,
                storage,
                requireNotNull(backupReceipt),
                originalExif,
                originalMedia.dateTaken?.toEpochMilli(),
                originalMedia.rawDateAddedSeconds,
                originalModifiedMillis
            )
            return ProcessResult(
                record, false, true, true, error.message ?: "处理失败", backup.absolutePath,
                exifVerification, mediaVerification, restore
            )
        }
    }

    private fun restore(
        file: File,
        backup: File,
        sessionRoot: File,
        storageRoot: File,
        backupReceipt: VerifiedPhotoBackup.BackupReceipt,
        originalExif: local.capturetime.exif.ExifTimes?,
        oldTaken: Long?,
        oldAdded: Long?,
        oldModifiedMillis: Long
    ): String {
        if (!backup.isFile) return "失败：备份不存在"
        return try {
            require(VerifiedPhotoBackup.sha256Hex(
                backup,
                sessionRoot,
                requireSingleLink = true
            ) == backupReceipt.backupSha256) { "恢复前备份 SHA-256 已变化" }
            VerifiedPhotoBackup.copyAndVerify(
                backup,
                file,
                sessionRoot,
                storageRoot,
                backupReceipt.sourceIdentity
            )
            val raw = VerifiedPhotoBackup.withVerifiedWritableFile(
                file,
                storageRoot,
                backupReceipt.sourceIdentity
            ) { descriptor, descriptorFile ->
                require(descriptorFile.setLastModified(oldModifiedMillis)) { "无法恢复原文件修改时间" }
                val stat = Os.fstat(descriptor)
                val modifiedMillis = stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000
                require(kotlin.math.abs(modifiedMillis - oldModifiedMillis) < 1000) { "恢复后的文件修改时间不一致" }
                exif.readRaw(descriptor)
            }
            val scannedUri = scan(file)
            require(scannedUri != null) { "恢复后的媒体扫描失败" }
            require(originalExif == null || raw == originalExif) { "恢复后的 EXIF 不一致" }
            val mediaOk = waitForMedia(file, scannedUri, oldTaken, oldAdded)
            require(VerifiedPhotoBackup.hasIdentity(file, storageRoot, backupReceipt.sourceIdentity)) {
                "恢复后原路径文件身份已变化"
            }
            if (mediaOk) "通过：文件、EXIF 与 MediaStore 已恢复" else "失败：文件及 EXIF 已恢复，但 MediaStore 恢复核验失败"
        } catch (error: Exception) {
            "失败：${error.message}"
        }
    }

    private fun waitForMedia(file: File, scanUri: android.net.Uri?, expectedTaken: Long?, expectedAdded: Long?, secondPrecision: Boolean = false): Boolean =
        MediaWait.until(android.os.SystemClock::elapsedRealtime, Thread::sleep) {
            val current = runCatching { scanUri?.let { mediaStore.query(it, file) } }.getOrNull()
                ?: runCatching { mediaStore.query(file) }.getOrNull()
            val takenOk = MediaScanExpectation.matchesTaken(current?.dateTaken?.toEpochMilli(), expectedTaken, secondPrecision)
            val addedOk = expectedAdded == null || current?.rawDateAddedSeconds == expectedAdded
            current != null && takenOk && addedOk
        }

    private fun mediaDiagnostic(file: File, scanUri: android.net.Uri?, expectedTaken: Long?, expectedAdded: Long?): String {
        val actual = runCatching { scanUri?.let { mediaStore.query(it, file) } }.getOrNull()
            ?: runCatching { mediaStore.query(file) }.getOrNull()
        return "失败；期望 DATE_TAKEN=${expectedTaken ?: "null"}, 实际=${actual?.dateTaken?.toEpochMilli() ?: "null"}; " +
            "期望 DATE_ADDED=${expectedAdded ?: "null"}, 实际=${actual?.rawDateAddedSeconds ?: "null"}"
    }

    private fun scan(file: File): android.net.Uri? {
        val latch = CountDownLatch(1)
        var result: android.net.Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
            result = uri
            latch.countDown()
        }
        return if (latch.await(MediaWait.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) result else null
    }

    private fun failure(record: PhotoRecord, reason: String, backup: String? = null, isFailure: Boolean = false) =
        ProcessResult(record, false, false, isFailure, reason, backup, "未执行", "未执行", "未修改原文件")
}
