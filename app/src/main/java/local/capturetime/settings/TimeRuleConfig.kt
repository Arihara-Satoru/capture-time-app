package local.capturetime.settings

import android.content.Context
import local.capturetime.exif.ExifTimes
import local.capturetime.model.MediaSnapshot
import local.capturetime.time.CaptureTimeParser
import java.time.Instant
import java.time.ZoneId
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
    val toleranceSeconds: Long = 0,
    val zone: ZoneId = CaptureTimeParser.zone
) {
    fun selectTarget(values: Map<TimeField, Instant?>): Instant? {
        val available = sourceFields.mapNotNull(values::get).map { it.atZone(zone) }
        // ponytail: 00:00 is treated as date-only when another selected source gives a time that day.
        val timedDates = available.filter { it.hour != 0 || it.minute != 0 }.mapTo(hashSetOf()) { it.toLocalDate() }
        val candidates = available.filter {
            it.hour != 0 || it.minute != 0 || it.toLocalDate() !in timedDates
        }.map { it.toInstant() }
        return when (selection) {
            TimeSelection.EARLIEST -> candidates.minOrNull()
            TimeSelection.LATEST -> candidates.maxOrNull()
        }
    }

    fun needsChange(actual: Instant?, target: Instant): Boolean {
        if (actual == null) return true
        val current = actual.atZone(zone)
        val desired = target.atZone(zone)
        return (current.hour == 0 && current.minute == 0 && (desired.hour != 0 || desired.minute != 0) &&
            current.toLocalDate() == desired.toLocalDate()) ||
            abs(actual.epochSecond - target.epochSecond) > toleranceSeconds
    }

    fun fieldsNeedingChange(
        values: Map<TimeField, Instant?>,
        target: Instant,
        exif: ExifTimes?
    ): List<TimeField> {
        val currentCapture = values[TimeField.CURRENT_CAPTURE]
        return destinationFields.filter { field ->
            val actual = values[field]
            when {
                actual != null -> needsChange(actual, target) ||
                    (field == TimeField.EXIF_ORIGINAL && exif?.originalOffset.isNullOrBlank() &&
                        values[TimeField.MEDIA_DATE_TAKEN]?.let { needsChange(it, actual) } == true)
                !field.isMissingIn(exif) -> true
                toleranceSeconds == 0L -> true
                currentCapture == null -> true
                else -> needsChange(currentCapture, target)
            }
        }
    }

    private fun TimeField.isMissingIn(exif: ExifTimes?): Boolean = exif != null && when (this) {
        TimeField.EXIF_ORIGINAL -> exif.original.isNullOrBlank() && exif.originalOffset.isNullOrBlank()
        TimeField.EXIF_DIGITIZED -> exif.digitized.isNullOrBlank() && exif.digitizedOffset.isNullOrBlank()
        TimeField.EXIF_MODIFIED -> exif.modified.isNullOrBlank() && exif.modifiedOffset.isNullOrBlank()
        else -> false
    }

    fun values(
        exif: ExifTimes?,
        media: MediaSnapshot?,
        filenameTime: Instant?,
        fileModified: Instant
    ): Map<TimeField, Instant?> {
        val original = CaptureTimeParser.parseExif(exif?.original, exif?.originalOffset, zone)
        return mapOf(
            TimeField.CURRENT_CAPTURE to (original ?: media?.dateTaken),
            TimeField.EXIF_ORIGINAL to original,
            TimeField.EXIF_DIGITIZED to CaptureTimeParser.parseExif(exif?.digitized, exif?.digitizedOffset, zone),
            TimeField.EXIF_MODIFIED to CaptureTimeParser.parseExif(exif?.modified, exif?.modifiedOffset, zone),
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
            val zone = runCatching { prefs.getString("time_zone", null)?.let(ZoneId::of) ?: CaptureTimeParser.zone }
                .getOrDefault(CaptureTimeParser.zone)
            return TimeRuleConfig(selection, sources.ifEmpty { DEFAULT_SOURCES }, destinations.ifEmpty { DEFAULT_DESTINATIONS }, tolerance, zone)
        }

        private fun parseFields(value: String?, fallback: Set<TimeField>): Set<TimeField> {
            if (value == null) return fallback
            return value.split(',').mapNotNullTo(mutableSetOf()) { name ->
                runCatching { TimeField.valueOf(name) }.getOrNull()
            }
        }
    }
}
