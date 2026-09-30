package local.capturetime.time

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object CaptureTimeParser {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val exifFormatter = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val outputFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(zone)
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
    private val filenamePatterns = listOf(
        Regex("(?<!\\d)(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})(?!\\d)") to
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss"),
        Regex("(?<!\\d)(\\d{8}_\\d{6})(?!\\d)") to
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss"),
        Regex("(?<!\\d)(\\d{8}-\\d{6})(?!\\d)") to
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
    )
    private val epochMillisPattern = Regex("(?<!\\d)(\\d{13})(?!\\d)")
    private val cameraTimestampPrefix = Regex("(?i)(?:^|[^A-Za-z0-9])(?:IMG|MVIMG)_$")
    private val earliestFilenameTime = Instant.parse("2000-01-01T00:00:00Z")
    private val latestFilenameTime = Instant.parse("2100-01-01T00:00:00Z")

    private data class FilenameTimeMatch(val range: IntRange, val time: Instant)

    @JvmOverloads fun parseExif(value: String?, offset: String? = null, timeZone: ZoneId = zone): Instant? {
        if (offset.isNullOrBlank()) return parseLocal(value, exifFormatter, timeZone)
        if (value.isNullOrBlank() || !Regex("[+-]\\d{2}:\\d{2}").matches(offset.trim())) return null
        return try {
            LocalDateTime.parse(value.trim(), exifFormatter)
                .toInstant(java.time.ZoneOffset.of(offset.trim()))
        } catch (_: DateTimeException) {
            null
        }
    }

    @JvmOverloads fun formatExifOffset(value: Instant, timeZone: ZoneId = zone): String = value.atZone(timeZone).offset.id

    @JvmOverloads fun parseFilename(filenameWithoutExtension: String, timeZone: ZoneId = zone): Instant? {
        return parsedFilenameTimes(filenameWithoutExtension, timeZone).singleOrNull()
    }

    @JvmOverloads fun hasAmbiguousFilenameTime(filenameWithoutExtension: String, timeZone: ZoneId = zone): Boolean {
        return parsedFilenameTimes(filenameWithoutExtension, timeZone).size > 1
    }

    private fun parsedFilenameTimes(filenameWithoutExtension: String, timeZone: ZoneId): List<Instant> {
        val formatted = filenamePatterns.flatMap { (regex, formatter) ->
            regex.findAll(filenameWithoutExtension).mapNotNull { match ->
                parseLocal(match.groupValues[1], formatter, timeZone)?.let { time ->
                    FilenameTimeMatch(match.range, time)
                }
            }
        }
        val epochMillis = epochMillisPattern.findAll(filenameWithoutExtension).mapNotNull { match ->
            val isCameraCopySuffix = formatted.any { formattedMatch ->
                formattedMatch.range.last + 2 == match.range.first &&
                    filenameWithoutExtension[formattedMatch.range.last + 1] == '_' &&
                    cameraTimestampPrefix.containsMatchIn(
                        filenameWithoutExtension.substring(0, formattedMatch.range.first)
                    )
            }
            if (isCameraCopySuffix) return@mapNotNull null
            match.groupValues[1].toLongOrNull()?.let(Instant::ofEpochMilli)
                ?.takeIf { !it.isBefore(earliestFilenameTime) && it.isBefore(latestFilenameTime) }
        }
        return (formatted.map { it.time } + epochMillis).distinct().toList()
    }

    @JvmOverloads fun formatExif(value: Instant, timeZone: ZoneId = zone): String = outputFormatter.withZone(timeZone).format(value)
    @JvmOverloads fun formatDisplay(value: Instant?, timeZone: ZoneId = zone): String =
        value?.let { displayFormatter.withZone(timeZone).format(it) } ?: "-"

    private fun parseLocal(value: String?, formatter: DateTimeFormatter, timeZone: ZoneId): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(value.trim(), formatter.withResolverStyle(ResolverStyle.STRICT))
                .atZone(timeZone).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }
}
