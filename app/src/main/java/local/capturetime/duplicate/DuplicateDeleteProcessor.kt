package local.capturetime.duplicate

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import local.capturetime.media.MediaStoreGateway
import local.capturetime.security.PathPolicy
import local.capturetime.operation.MediaWait
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DuplicateDeleteProcessor(
    private val context: Context,
    private val mediaStore: MediaStoreGateway
) {
    fun delete(candidates: List<DuplicateCandidate>): DuplicateDeleteResult {
        require(Environment.isExternalStorageManager()) { "所有文件访问权限已撤销" }
        val storage = Environment.getExternalStorageDirectory()
        val session = createSession(storage)
        val log = File(session, "deleted.tsv")
        writeLine(log, "original_path\tretained_path\tbackup_path")
        val failures = mutableListOf<String>()
        var deleted = 0
        var verified = 0

        candidates.forEach { candidate ->
            var removed = false
            val error = runCatching {
                deleteOne(candidate, storage, session, log) { removed = true; deleted++ }
            }.exceptionOrNull()
            if (error == null) verified++ else failures +=
                "${candidate.delete.file.absolutePath}：${if (removed) "已执行删除，后续核验或日志失败" else "未执行删除"}：${error.message ?: "处理失败"}"
        }

        runCatching {
            val rows = log.readLines(Charsets.UTF_8).drop(1).count { it.isNotBlank() }
            require(rows == deleted) { "记录 $rows，已执行删除 $deleted" }
        }.onFailure { failures += "deleted.tsv 清单行数核验失败：${it.message}" }
        return DuplicateDeleteResult(session, deleted, candidates.size - deleted, failures, verified)
    }

    private fun deleteOne(candidate: DuplicateCandidate, storage: File, session: File, log: File, onDeleted: () -> Unit) {
        val target = candidate.delete.file
        val retained = candidate.retained.file
        require(PathPolicy.isSafeFile(target, listOf(storage))) { "待删除路径不安全或文件已不存在" }
        require(PathPolicy.isSafeFile(retained, listOf(storage))) { "保留文件路径不安全或文件已不存在" }
        require(target.parentFile?.canonicalFile == retained.parentFile?.canonicalFile) { "文件已不在同一物理文件夹" }
        verifyUnchanged(candidate.delete)
        verifyUnchanged(candidate.retained)

        val relative = PathPolicy.relativeStoragePath(target, storage) ?: error("无法计算原始相对路径")
        val backup = File(session, relative)
        require(!backup.exists()) { "备份路径已存在，拒绝覆盖" }
        require(backup.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建备份目录" }
        copyAndSync(target, backup)
        require(FileVerification.contentEquals(target, backup)) { "备份与原件逐字节 cmp 核验失败" }
        require(FileVerification.sha256(backup) == candidate.delete.sha256) { "备份 SHA-256 核验失败" }
        require(target.delete()) { "删除原件失败" }
        onDeleted()
        // ponytail: record the completed deletion before fallible verification; a crash between unlink and logging still requires checking the backup.
        writeLine(log, listOf(target.absolutePath, retained.absolutePath, backup.absolutePath).joinToString("\t", transform = ::cell))
        scanDeleted(target)
        require(MediaWait.until(android.os.SystemClock::elapsedRealtime, Thread::sleep) {
            require(!target.exists()) { "删除后原路径重新出现，请检查相册同步或其他应用；未再次删除" }
            !mediaStore.containsDuplicatePath(target, candidate.delete.kind)
        }) { "原文件已删除，但系统媒体库记录仍存在，请稍后重新扫描" }
        require(!target.exists()) { "媒体库核验后原路径重新出现，请检查相册同步或其他应用；未再次删除" }
        require(retained.isFile) { "删除后保留文件不存在" }
        require(backup.isFile) { "删除后备份文件不存在" }
    }

    private fun verifyUnchanged(asset: DuplicateAsset) {
        require(asset.file.isFile && asset.file.length() == asset.size) { "文件大小已变化，请重新扫描" }
        val details = mediaStore.queryDuplicateDetails(asset.file, asset.kind) ?: error("MediaStore 记录或有效尺寸已消失")
        require(details.width == asset.width && details.height == asset.height) { "文件分辨率已变化，请重新扫描" }
        if (asset.kind == MediaKind.VIDEO) require(details.durationMillis == asset.durationMillis) { "视频时长已变化，请重新扫描" }
        require(FileVerification.sha256(asset.file) == asset.sha256) { "文件 SHA-256 已变化，请重新扫描" }
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun scanDeleted(file: File) {
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> latch.countDown() }
        require(latch.await(MediaWait.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "原文件已删除，但系统媒体扫描超时，尚未确认媒体库清理" }
    }

    @Synchronized private fun writeLine(file: File, value: String) {
        FileOutputStream(file, true).use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.append(value).append('\n')
                writer.flush()
                stream.fd.sync()
            }
        }
    }

    private fun cell(value: String) = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private fun createSession(storage: File): File {
        val temp = File(storage, ".temp")
        require(temp.isDirectory || (!temp.exists() && temp.mkdir())) { "无法创建 /sdcard/.temp" }
        repeat(3) {
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val directory = File(temp, "duplicate-cleanup-$stamp")
            if (directory.mkdir()) return directory
            Thread.sleep(1100)
        }
        error("无法创建唯一清理会话")
    }
}
