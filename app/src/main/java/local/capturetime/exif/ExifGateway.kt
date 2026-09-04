package local.capturetime.exif

import androidx.exifinterface.media.ExifInterface
import local.capturetime.time.CaptureTimeParser
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

    fun writeAll(file: File, target: Instant) {
        val value = CaptureTimeParser.formatExif(target)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, value)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, value)
            setAttribute(ExifInterface.TAG_DATETIME, value)
            saveAttributes()
        }
    }

    fun verifyAll(file: File, target: Instant): Boolean {
        val expected = CaptureTimeParser.formatExif(target)
        val actual = readRaw(file)
        return actual.original == expected && actual.digitized == expected && actual.modified == expected
    }
}
