package local.capturetime.exif

import androidx.exifinterface.media.ExifInterface
import local.capturetime.time.CaptureTimeParser
import local.capturetime.settings.TimeField
import java.io.File
import java.time.Instant

data class ExifTimes(val original: String?, val digitized: String?, val modified: String?)

class ExifGateway {
    fun readOriginal(file: File): Instant? = try {
        CaptureTimeParser.parseExif(ExifInterface(file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    } catch (_: Exception) {
        null
    }

    fun readRaw(file: File): ExifTimes {
        val exif = ExifInterface(file)
        return ExifTimes(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME)
        )
    }

    fun write(file: File, target: Instant, fields: Set<TimeField>) {
        val value = CaptureTimeParser.formatExif(target)
        ExifInterface(file).apply {
            if (TimeField.EXIF_ORIGINAL in fields) setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, value)
            if (TimeField.EXIF_DIGITIZED in fields) setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, value)
            if (TimeField.EXIF_MODIFIED in fields) setAttribute(ExifInterface.TAG_DATETIME, value)
            saveAttributes()
        }
    }

    fun writeAll(file: File, target: Instant) = write(file, target, setOf(
        TimeField.EXIF_ORIGINAL, TimeField.EXIF_DIGITIZED, TimeField.EXIF_MODIFIED
    ))

    fun verifyAll(file: File, target: Instant): Boolean {
        return !needsSync(readRaw(file), target)
    }

    fun verify(file: File, target: Instant, fields: Set<TimeField>): Boolean {
        val actual = readRaw(file)
        val expected = CaptureTimeParser.formatExif(target)
        return (TimeField.EXIF_ORIGINAL !in fields || actual.original == expected) &&
            (TimeField.EXIF_DIGITIZED !in fields || actual.digitized == expected) &&
            (TimeField.EXIF_MODIFIED !in fields || actual.modified == expected)
    }

    fun needsSync(actual: ExifTimes?, target: Instant): Boolean {
        if (actual == null) return false
        val expected = CaptureTimeParser.formatExif(target)
        return actual.original != expected || actual.digitized != expected || actual.modified != expected
    }
}
