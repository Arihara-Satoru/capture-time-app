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
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PhotoScanner(
    private val mediaStore: MediaStoreGateway,
    private val exif: ExifGateway,
    private val rule: TimeRuleConfig = TimeRuleConfig()
) {
    private val inspectionThreads = Executors.newFixedThreadPool(availableInspectionThreads())

    fun scan(): List<PhotoRecord> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = resolveRoots(storage)
        val mediaIndex = mediaStore.queryAll()
        val files = roots.asSequence()
            .flatMap { walkFiles(it) }
            .filter { looksLikeImageName(it.name) }
            .distinctBy { it.absolutePath.lowercase(Locale.ROOT) }
            .toList()
        return inspectInParallel(files, roots) { file -> mediaIndex[mediaStore.pathKey(file)] }
    }

    fun scan(files: List<File>): List<PhotoRecord> {
        val storage = Environment.getExternalStorageDirectory()
        val uniqueFiles = files.asSequence()
            .distinctBy { it.absolutePath.lowercase(Locale.ROOT) }
            .toList()
        // Avoid one ContentResolver query per photo when importing a selection.
        val mediaIndex = mediaStore.queryAll()
        return inspectInParallel(uniqueFiles, listOf(storage)) { file -> mediaIndex[mediaStore.pathKey(file)] }
    }

    private fun inspectInParallel(
        files: List<File>,
        roots: List<File>,
        mediaFor: (File) -> local.capturetime.model.MediaSnapshot?
    ): List<PhotoRecord> {
        val tasks = files.map { file -> Callable { inspect(file, roots, mediaFor(file)) } }
        return inspectionThreads.invokeAll(tasks).map { it.get() }
            .sortedBy { it.file.absolutePath.lowercase(Locale.ROOT) }
    }

    private fun inspect(file: File, roots: List<File>, indexedMedia: local.capturetime.model.MediaSnapshot?): PhotoRecord {
        if (!PathPolicy.isSafeFile(file, roots)) return skipped(file, ImageFormat.OTHER, "路径不安全或位于排除目录")
        val inspection = ImageExtension.inspect(file)
        val format = inspection.format
        if (format == ImageFormat.OTHER) return skipped(file, format,
            if (inspection.correction != null) "实际为 ${inspection.correction.uppercase(Locale.ROOT)} 图片，建议将后缀改为 .${inspection.correction}"
            else "扩展名与文件签名不匹配或格式不支持", extensionCorrection = inspection.correction)

        val rawExif = runCatching { exif.readRaw(file) }.getOrNull()
        val exifTime = CaptureTimeParser.parseExif(rawExif?.original, rawExif?.originalOffset)
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
        val changedFields = rule.fieldsNeedingChange(values, target, rawExif)
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
        filenameTime: Instant? = null,
        extensionCorrection: String? = null
    ) = PhotoRecord(file, format, exifTime, media, filenameTime, exifTime ?: media?.dateTaken,
        if (exifTime != null) CaptureSource.EXIF else if (media?.dateTaken != null) CaptureSource.MEDIASTORE else null,
        null, false, false, reason, extensionCorrection)

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

    private fun availableInspectionThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
}
