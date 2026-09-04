package local.capturetime.operation

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import local.capturetime.exif.ExifGateway
import local.capturetime.media.MediaStoreGateway
import local.capturetime.model.PhotoRecord
import local.capturetime.model.ProcessResult
import local.capturetime.security.PathPolicy
import local.capturetime.time.CaptureTimeParser
import local.capturetime.time.PlanningRules
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SafePhotoProcessor(
    private val context: Context,
    private val exif: ExifGateway,
    private val mediaStore: MediaStoreGateway,
    private val toleranceSeconds: Long = 0
) {
    fun process(record: PhotoRecord, session: SessionLogger): ProcessResult {
        val target = record.targetCaptureTime
            ?: return failure(record, "目标时间缺失")
        if (!Environment.isExternalStorageManager()) return failure(record, "所有文件访问权限已撤销")
        if (!record.file.isFile || !record.file.canWrite()) return failure(record, "原文件不存在或不可写")

        val storage = Environment.getExternalStorageDirectory()
        val roots = allowedRoots(storage)
        if (!PathPolicy.isSafeFile(record.file, roots)) return failure(record, "写入前路径安全校验失败")
        val relative = PathPolicy.relativeStoragePath(record.file, storage)
            ?: return failure(record, "原路径不在共享存储内")
        val backup = File(session.directory, relative)
        val beforeHash: String
        val originalExif = runCatching { exif.readRaw(record.file) }.getOrNull()
        val originalMedia = runCatching { mediaStore.query(record.file) }.getOrNull()
            ?: return failure(record, "写入前 MediaStore 记录已消失")
        if (originalMedia.rawDateAddedSeconds == null) return failure(record, "写入前 DATE_ADDED 已不可核验")
        val originalModifiedMillis = record.file.lastModified()
        val currentExifTime = runCatching { exif.readOriginal(record.file) }.getOrNull()
        val currentCapture = currentExifTime ?: originalMedia?.dateTaken
            ?: return failure(record, "写入前已缺少当前拍摄时间")
        val stem = record.file.name.substringBeforeLast('.', record.file.name)
        if (CaptureTimeParser.hasAmbiguousFilenameTime(stem)) return failure(record, "写入前文件名时间存在歧义")
        val recalculatedTarget = PlanningRules.target(currentCapture, originalMedia?.dateAdded, CaptureTimeParser.parseFilename(stem))
        if (recalculatedTarget != target || !PlanningRules.isCandidate(currentCapture, target, toleranceSeconds)) {
            return failure(record, "预览后时间状态已变化，已安全跳过")
        }
        var modified = false
        var exifVerification = "未执行"
        var mediaVerification = "未执行"

        try {
            val parent = requireNotNull(backup.parentFile) { "备份目录无效" }
            require(parent.isDirectory || parent.mkdirs()) { "无法创建备份目录" }
            require(!backup.exists()) { "备份路径已存在，拒绝覆盖" }
            copyAndSync(record.file, backup)
            beforeHash = sha256(record.file)
            require(record.file.length() == backup.length() && beforeHash == sha256(backup)) { "备份完整性校验失败" }

            exif.writeAll(record.file, target)
            modified = true
            exifVerification = if (exif.verifyAll(record.file, target)) "通过" else "失败"
            require(exifVerification == "通过") { "EXIF 三字段核验失败" }

            // HyperOS may derive DATE_TAKEN from filesystem mtime after EXIF save.
            require(record.file.setLastModified(target.toEpochMilli())) { "无法设置扫描用文件修改时间" }
            val scannedUri = scan(record.file)
            require(scannedUri != null) { "媒体扫描超时或失败" }
            val expectedMillis = target.epochSecond * 1000
            val verified = waitForMedia(record.file, scannedUri, expectedMillis, originalMedia.rawDateAddedSeconds)
            mediaVerification = if (verified) "通过（扫描回调 URI）" else mediaDiagnostic(record.file, scannedUri, expectedMillis, originalMedia.rawDateAddedSeconds)
            require(verified) { "MediaStore DATE_TAKEN 或 DATE_ADDED 核验失败" }

            return ProcessResult(record, true, false, false, "成功", backup.absolutePath, exifVerification, mediaVerification, "无需恢复")
        } catch (error: Exception) {
            if (!modified) return failure(record, error.message ?: "备份阶段失败", backup.takeIf { it.exists() }?.absolutePath, true)
            val restore = restore(record.file, backup, originalExif, originalMedia?.dateTaken?.toEpochMilli(), originalMedia?.rawDateAddedSeconds, originalModifiedMillis)
            return ProcessResult(
                record, false, true, true, error.message ?: "处理失败", backup.absolutePath,
                exifVerification, mediaVerification, restore
            )
        }
    }

    private fun restore(file: File, backup: File, originalExif: local.capturetime.exif.ExifTimes?, oldTaken: Long?, oldAdded: Long?, oldModifiedMillis: Long): String {
        if (!backup.isFile) return "失败：备份不存在"
        return try {
            copyAndSync(backup, file)
            require(file.length() == backup.length() && sha256(file) == sha256(backup)) { "恢复内容哈希不一致" }
            require(file.setLastModified(oldModifiedMillis)) { "无法恢复原文件修改时间" }
            val scannedUri = scan(file)
            require(scannedUri != null) { "恢复后的媒体扫描失败" }
            val raw = exif.readRaw(file)
            require(originalExif == null || raw == originalExif) { "恢复后的 EXIF 不一致" }
            val mediaOk = waitForMedia(file, scannedUri, oldTaken, oldAdded)
            if (mediaOk) "通过：文件、EXIF 与 MediaStore 已恢复" else "失败：文件及 EXIF 已恢复，但 MediaStore 恢复核验失败"
        } catch (error: Exception) {
            "失败：${error.message}"
        }
    }

    private fun waitForMedia(file: File, scanUri: android.net.Uri?, expectedTaken: Long?, expectedAdded: Long?): Boolean {
        repeat(30) {
            val current = runCatching { scanUri?.let { mediaStore.query(it, file) } }.getOrNull()
                ?: runCatching { mediaStore.query(file) }.getOrNull()
            val takenOk = expectedTaken == null || current?.dateTaken?.toEpochMilli() == expectedTaken
            val addedOk = expectedAdded == null || current?.rawDateAddedSeconds == expectedAdded
            if (current != null && takenOk && addedOk) return true
            Thread.sleep(500)
        }
        return false
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
        return if (latch.await(15, TimeUnit.SECONDS)) result else null
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun allowedRoots(storage: File): List<File> {
        val top = storage.listFiles().orEmpty()
        val dcim = top.filter { it.isDirectory && it.name.equals("DCIM", true) }
        val pictures = top.filter { it.isDirectory && it.name.equals("Pictures", true) }
        val download = top.firstOrNull { it.isDirectory && it.name.equals("Download", true) }
        val miShare = download?.listFiles()?.filter { it.isDirectory && it.name.equals("MiShare", true) }.orEmpty()
        return dcim + pictures + miShare
    }

    private fun failure(record: PhotoRecord, reason: String, backup: String? = null, isFailure: Boolean = false) =
        ProcessResult(record, false, false, isFailure, reason, backup, "未执行", "未执行", "未修改原文件")
}
