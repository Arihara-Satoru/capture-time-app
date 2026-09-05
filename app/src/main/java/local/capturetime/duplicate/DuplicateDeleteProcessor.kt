package local.capturetime.duplicate

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import local.capturetime.media.MediaStoreGateway
import local.capturetime.security.PathPolicy
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

        candidates.forEach { candidate ->
            val error = runCatching { deleteOne(candidate, storage, session, log) }.exceptionOrNull()
            if (error == null) deleted++ else failures += "${candidate.delete.file.absolutePath}：${error.message ?: "处理失败"}"
        }

        val rows = log.readLines(Charsets.UTF_8).drop(1).count { it.isNotBlank() }
        if (rows != deleted) failures += "deleted.tsv 清单行数核验失败：记录 $rows，成功 $deleted"
        return DuplicateDeleteResult(session, deleted, candidates.size - deleted, failures)
    }

    private fun deleteOne(candidate: DuplicateCandidate, storage: File, session: File, log: File) {
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
        scanDeleted(target)
        require(!target.exists()) { "删除后原路径仍存在" }
        require(retained.isFile) { "删除后保留文件不存在" }
        require(backup.isFile) { "删除后备份文件不存在" }
        writeLine(log, listOf(target.absolutePath, retained.absolutePath, backup.absolutePath).joinToString("\t", transform = ::cell))
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
        latch.await(15, TimeUnit.SECONDS)
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
