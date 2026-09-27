package local.capturetime.scan

import local.capturetime.model.ImageFormat
import local.capturetime.security.PathPolicy
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Locale

internal object ImageExtension {
    data class Inspection(val format: ImageFormat, val correction: String?)
    data class BatchResult(val renamed: Map<File, File>, val failures: List<Pair<File, String>>)

    fun inspect(file: File): Inspection {
        val header = ByteArray(16)
        val count = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(0)
        if (count < 12) return Inspection(ImageFormat.OTHER, null)
        val actual = when {
            header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> ImageFormat.JPEG
            header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> ImageFormat.PNG
            String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> ImageFormat.HEIC
            else -> ImageFormat.OTHER
        }
        val extension = file.extension.lowercase(Locale.ROOT)
        val matches = when (actual) {
            ImageFormat.JPEG -> extension in setOf("jpg", "jpeg")
            ImageFormat.PNG -> extension == "png"
            ImageFormat.HEIC -> extension in setOf("heic", "heif")
            ImageFormat.OTHER -> false
        }
        val correction = when (actual) {
            ImageFormat.JPEG -> "jpg"
            ImageFormat.PNG -> "png"
            ImageFormat.HEIC -> "heic"
            ImageFormat.OTHER -> null
        }
        return Inspection(if (matches) actual else ImageFormat.OTHER,
            correction.takeIf { !matches && extension in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng") })
    }

    fun correct(file: File, extension: String, storageRoot: File): File {
        require(PathPolicy.isSafeFile(file, listOf(storageRoot))) { "源文件路径不安全或文件已消失" }
        require(file.canWrite()) { "源文件不可写" }
        require(inspect(file).correction == extension) { "文件内容或后缀已变化，请重新扫描" }
        val target = File(requireNotNull(file.parentFile), "${file.nameWithoutExtension}.$extension")
        require(!target.exists()) { "目标文件已存在，拒绝覆盖：${target.name}" }
        // ponytail: same-directory move avoids copying image bytes; a failed media rescan is reported separately.
        Files.move(file.toPath(), target.toPath())
        return target
    }

    fun correctAll(files: List<Pair<File, String>>, storageRoot: File): BatchResult {
        val renamed = linkedMapOf<File, File>()
        val failures = mutableListOf<Pair<File, String>>()
        files.forEach { (file, extension) ->
            runCatching { correct(file, extension, storageRoot) }
                .onSuccess { renamed[file] = it }
                .onFailure { failures += file to (it.message ?: "改名失败") }
        }
        return BatchResult(renamed, failures)
    }
}
