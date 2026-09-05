package local.capturetime.scan

import android.os.Environment
import local.capturetime.exif.ExifGateway
import local.capturetime.media.MediaStoreGateway
import local.capturetime.model.CaptureSource
import local.capturetime.model.ImageFormat
import local.capturetime.model.PhotoRecord
import local.capturetime.security.PathPolicy
import local.capturetime.settings.TimeField
import local.capturetime.settings.TimeRuleConfig
import local.capturetime.time.CaptureTimeParser
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.Locale

class PhotoScanner(
    private val mediaStore: MediaStoreGateway,
    private val exif: ExifGateway,
    private val rule: TimeRuleConfig = TimeRuleConfig()
) {
    fun scan(): List<PhotoRecord> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = resolveRoots(storage)
        val mediaIndex = mediaStore.queryAll()
        return roots.asSequence()
            .flatMap { walkFiles(it) }
            .filter { looksLikeImageName(it.name) }
            .distinctBy { it.absolutePath.lowercase(Locale.ROOT) }
            .map { inspect(it, roots, mediaIndex[it.absolutePath.lowercase()]) }
            .sortedBy { it.file.absolutePath.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun inspect(file: File, roots: List<File>, indexedMedia: local.capturetime.model.MediaSnapshot?): PhotoRecord {
        val format = detectFormat(file)
        if (!PathPolicy.isSafeFile(file, roots)) return skipped(file, format, "路径不安全或位于排除目录")
        if (format == ImageFormat.OTHER) return skipped(file, format, "扩展名与文件签名不匹配或格式不支持")

        val rawExif = runCatching { exif.readRaw(file) }.getOrNull()
        val exifTime = CaptureTimeParser.parseExif(rawExif?.original)
        val media = indexedMedia
        if (media?.rawDateAddedSeconds == null) {
            return skipped(file, format, "缺少可核验的 MediaStore DATE_ADDED，无法证明添加时间不变", exifTime, media)
        }
        val current = exifTime ?: media?.dateTaken
        val source = when {
            exifTime != null -> CaptureSource.EXIF
            media?.dateTaken != null -> CaptureSource.MEDIASTORE
            else -> null
        }
        val stem = file.name.substringBeforeLast('.', file.name)
        if (TimeField.FILENAME in rule.sourceFields && CaptureTimeParser.hasAmbiguousFilenameTime(stem)) {
            return skipped(file, format, "文件名包含多个不同的有效时间", exifTime, media)
        }
        val filenameTime = CaptureTimeParser.parseFilename(stem)
        val values = rule.values(rawExif, media, filenameTime, Instant.ofEpochMilli(file.lastModified()))
        val target = rule.selectTarget(values)
            ?: return skipped(file, format, "所选依据字段均缺少有效时间", exifTime, media, filenameTime)
        val changedFields = rule.destinationFields.filter { field -> rule.needsChange(values[field], target) }
        val candidate = changedFields.isNotEmpty()
        val reason = when {
            !file.canWrite() -> "文件不可写"
            candidate && format == ImageFormat.JPEG -> "将修改：${changedFields.joinToString("、") { it.label }}"
            candidate -> "需先完成本次预览的单张格式能力测试；将修改：${changedFields.joinToString("、") { it.label }}"
            else -> "所选修改字段与目标时间一致或在忽略误差内"
        }
        return PhotoRecord(
            file, format, exifTime, media, filenameTime, current, source, target,
            candidate, candidate && file.canWrite(), reason
        )
    }

    private fun skipped(
        file: File,
        format: ImageFormat,
        reason: String,
        exifTime: Instant? = null,
        media: local.capturetime.model.MediaSnapshot? = null,
        filenameTime: Instant? = null
    ) = PhotoRecord(file, format, exifTime, media, filenameTime, exifTime ?: media?.dateTaken,
        if (exifTime != null) CaptureSource.EXIF else if (media?.dateTaken != null) CaptureSource.MEDIASTORE else null,
        null, false, false, reason)

    private fun resolveRoots(storage: File): List<File> {
        val children = storage.listFiles().orEmpty()
        val dcim = children.filter { it.isDirectory && it.name.equals("DCIM", ignoreCase = true) }
        val pictures = children.filter { it.isDirectory && it.name.equals("Pictures", ignoreCase = true) }
        val download = children.firstOrNull { it.isDirectory && it.name.equals("Download", ignoreCase = true) }
        val miShare = download?.listFiles()?.filter { it.isDirectory && it.name.equals("MiShare", ignoreCase = true) }.orEmpty()
        return (dcim + pictures + miShare).distinctBy { runCatching { it.canonicalPath.lowercase() }.getOrDefault(it.absolutePath.lowercase()) }
    }

    private fun walkFiles(root: File): Sequence<File> = sequence {
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (PathPolicy.isExcluded(current)) continue
            current.listFiles()?.forEach { child ->
                if (PathPolicy.isExcluded(child)) return@forEach
                if (child.isDirectory && runCatching { child.canonicalPath == child.absoluteFile.path }.getOrDefault(false)) {
                    pending.add(child)
                } else if (child.isFile) yield(child)
            }
        }
    }

    private fun looksLikeImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "heic", "heif", "png", "webp", "gif", "bmp", "dng")

    private fun detectFormat(file: File): ImageFormat {
        val extension = file.extension.lowercase()
        val header = ByteArray(16)
        val count = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(0)
        if (count < 12) return ImageFormat.OTHER
        val jpeg = header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()
        val png = header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val ftyp = String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
        val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
        val heic = ftyp && brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
        return when {
            extension in setOf("jpg", "jpeg") && jpeg -> ImageFormat.JPEG
            extension == "png" && png -> ImageFormat.PNG
            extension in setOf("heic", "heif") && heic -> ImageFormat.HEIC
            else -> ImageFormat.OTHER
        }
    }
}
