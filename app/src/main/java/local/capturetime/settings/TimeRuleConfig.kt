package local.capturetime.settings

import android.content.Context
import local.capturetime.exif.ExifTimes
import local.capturetime.model.MediaSnapshot
import local.capturetime.time.CaptureTimeParser
import java.time.Instant
import kotlin.math.abs

enum class TimeSelection(val label: String) {
    EARLIEST("所选依据字段中的最早时间"),
    LATEST("所选依据字段中的最晚时间")
}

enum class TimeField(val label: String, val canRead: Boolean, val canWrite: Boolean) {
    CURRENT_CAPTURE("当前拍摄时间（优先 EXIF 原始，缺失时用 MediaStore）", true, false),
    EXIF_ORIGINAL("EXIF DateTimeOriginal（原始时间）", true, true),
    EXIF_DIGITIZED("EXIF DateTimeDigitized（数字化时间）", true, true),
    EXIF_MODIFIED("EXIF DateTime（修改时间）", true, true),
    MEDIA_DATE_TAKEN("MediaStore DATE_TAKEN（拍摄时间）", true, false),
    MEDIA_DATE_ADDED("MediaStore DATE_ADDED（添加时间）", true, false),
    FILENAME("文件名中的时间", true, false),
    FILE_MODIFIED("文件修改时间", true, true)
}

data class TimeRuleConfig(
    val selection: TimeSelection = TimeSelection.EARLIEST,
    val sourceFields: Set<TimeField> = DEFAULT_SOURCES,
    val destinationFields: Set<TimeField> = DEFAULT_DESTINATIONS,
    val toleranceSeconds: Long = 0
) {
    fun selectTarget(values: Map<TimeField, Instant?>): Instant? {
        val available = sourceFields.mapNotNull(values::get)
        return when (selection) {
            TimeSelection.EARLIEST -> available.minOrNull()
            TimeSelection.LATEST -> available.maxOrNull()
        }
    }

    fun needsChange(actual: Instant?, target: Instant): Boolean =
        actual == null || abs(actual.epochSecond - target.epochSecond) > toleranceSeconds

    fun values(
        exif: ExifTimes?,
        media: MediaSnapshot?,
        filenameTime: Instant?,
        fileModified: Instant
    ): Map<TimeField, Instant?> {
        val original = CaptureTimeParser.parseExif(exif?.original)
        return mapOf(
            TimeField.CURRENT_CAPTURE to (original ?: media?.dateTaken),
            TimeField.EXIF_ORIGINAL to original,
            TimeField.EXIF_DIGITIZED to CaptureTimeParser.parseExif(exif?.digitized),
            TimeField.EXIF_MODIFIED to CaptureTimeParser.parseExif(exif?.modified),
            TimeField.MEDIA_DATE_TAKEN to media?.dateTaken,
            TimeField.MEDIA_DATE_ADDED to media?.dateAdded,
            TimeField.FILENAME to filenameTime,
            TimeField.FILE_MODIFIED to fileModified
        )
    }

    companion object {
        val DEFAULT_SOURCES = setOf(TimeField.CURRENT_CAPTURE, TimeField.MEDIA_DATE_ADDED, TimeField.FILENAME)
        val DEFAULT_DESTINATIONS = setOf(
            TimeField.EXIF_ORIGINAL,
            TimeField.EXIF_DIGITIZED,
            TimeField.EXIF_MODIFIED,
            TimeField.FILE_MODIFIED
        )

        fun load(context: Context): TimeRuleConfig {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val selection = runCatching {
                TimeSelection.valueOf(prefs.getString("time_selection", null).orEmpty())
            }.getOrDefault(TimeSelection.EARLIEST)
            val sources = parseFields(prefs.getString("source_fields", null), DEFAULT_SOURCES).filterTo(mutableSetOf()) { it.canRead }
            val destinations = parseFields(prefs.getString("destination_fields", null), DEFAULT_DESTINATIONS).filterTo(mutableSetOf()) { it.canWrite }
            val tolerance = prefs.getInt("days", 0) * 86_400L + prefs.getInt("hours", 0) * 3_600L +
                prefs.getInt("minutes", 0) * 60L + prefs.getInt("seconds", 0)
            return TimeRuleConfig(selection, sources.ifEmpty { DEFAULT_SOURCES }, destinations.ifEmpty { DEFAULT_DESTINATIONS }, tolerance)
        }

        private fun parseFields(value: String?, fallback: Set<TimeField>): Set<TimeField> {
            if (value == null) return fallback
            return value.split(',').mapNotNullTo(mutableSetOf()) { name ->
                runCatching { TimeField.valueOf(name) }.getOrNull()
            }
        }
    }
}
