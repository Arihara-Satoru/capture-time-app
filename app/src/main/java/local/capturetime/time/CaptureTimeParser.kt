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
    private val earliestFilenameTime = Instant.parse("2000-01-01T00:00:00Z")
    private val latestFilenameTime = Instant.parse("2100-01-01T00:00:00Z")

    fun parseExif(value: String?): Instant? = parseLocal(value, exifFormatter)

    fun parseFilename(filenameWithoutExtension: String): Instant? {
        return parsedFilenameTimes(filenameWithoutExtension).singleOrNull()
    }

    fun hasAmbiguousFilenameTime(filenameWithoutExtension: String): Boolean {
        return parsedFilenameTimes(filenameWithoutExtension).size > 1
    }

    private fun parsedFilenameTimes(filenameWithoutExtension: String): List<Instant> {
        val formatted = filenamePatterns.flatMap { (regex, formatter) ->
            regex.findAll(filenameWithoutExtension).mapNotNull { parseLocal(it.groupValues[1], formatter) }
        }
        val epochMillis = epochMillisPattern.findAll(filenameWithoutExtension).mapNotNull { match ->
            match.groupValues[1].toLongOrNull()?.let(Instant::ofEpochMilli)
                ?.takeIf { !it.isBefore(earliestFilenameTime) && it.isBefore(latestFilenameTime) }
        }
        return (formatted + epochMillis).distinct().toList()
    }

    fun formatExif(value: Instant): String = outputFormatter.format(value)
    fun formatDisplay(value: Instant?): String = value?.let(displayFormatter::format) ?: "-"

    private fun parseLocal(value: String?, formatter: DateTimeFormatter): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(value.trim(), formatter.withResolverStyle(ResolverStyle.STRICT))
                .atZone(zone).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    }
}
