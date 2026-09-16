package local.capturetime.exif

import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import local.capturetime.time.CaptureTimeParser
import local.capturetime.settings.TimeField
import java.io.File
import java.io.FileDescriptor
import java.time.Instant

data class ExifTimes(
    val original: String?, val digitized: String?, val modified: String?,
    val originalOffset: String? = null, val digitizedOffset: String? = null, val modifiedOffset: String? = null
)

class ExifGateway {
    fun readOriginal(file: File): Instant? = try {
        val raw = readRaw(file)
        CaptureTimeParser.parseExif(raw.original, raw.originalOffset)
    } catch (_: Exception) {
        null
    }

    fun readRaw(file: File): ExifTimes {
        return readTimes(ExifInterface(file))
    }

    fun readRaw(descriptor: FileDescriptor): ExifTimes {
        rewind(descriptor)
        return try {
            readTimes(ExifInterface(descriptor))
        } finally {
            rewind(descriptor)
        }
    }

    private fun readTimes(exif: ExifInterface): ExifTimes {
        return ExifTimes(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_DATETIME),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        )
    }

    fun write(file: File, target: Instant, fields: Set<TimeField>) {
        JpegStructure.validate(file)
        writeAttributes(ExifInterface(file), target, fields)
    }

    fun write(descriptor: FileDescriptor, target: Instant, fields: Set<TimeField>) {
        JpegStructure.validate(descriptor)
        rewind(descriptor)
        try {
            writeAttributes(ExifInterface(descriptor), target, fields)
        } finally {
            rewind(descriptor)
        }
    }

    private fun writeAttributes(exif: ExifInterface, target: Instant, fields: Set<TimeField>) {
        val value = CaptureTimeParser.formatExif(target)
        val offset = CaptureTimeParser.formatExifOffset(target)
        exif.apply {
            if (TimeField.EXIF_ORIGINAL in fields) {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, value)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            }
            if (TimeField.EXIF_DIGITIZED in fields) {
                setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, value)
                setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
            }
            if (TimeField.EXIF_MODIFIED in fields) {
                setAttribute(ExifInterface.TAG_DATETIME, value)
                setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            }
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
        return verifyTimes(readRaw(file), target, fields)
    }

    fun verify(descriptor: FileDescriptor, target: Instant, fields: Set<TimeField>): Boolean {
        return verifyTimes(readRaw(descriptor), target, fields)
    }

    private fun verifyTimes(actual: ExifTimes, target: Instant, fields: Set<TimeField>): Boolean {
        val expected = CaptureTimeParser.formatExif(target)
        val offset = CaptureTimeParser.formatExifOffset(target)
        return (TimeField.EXIF_ORIGINAL !in fields || (actual.original == expected && actual.originalOffset == offset)) &&
            (TimeField.EXIF_DIGITIZED !in fields || (actual.digitized == expected && actual.digitizedOffset == offset)) &&
            (TimeField.EXIF_MODIFIED !in fields || (actual.modified == expected && actual.modifiedOffset == offset))
    }

    fun needsSync(actual: ExifTimes?, target: Instant): Boolean {
        if (actual == null) return false
        val expected = Instant.ofEpochSecond(target.epochSecond)
        return CaptureTimeParser.parseExif(actual.original, actual.originalOffset) != expected ||
            CaptureTimeParser.parseExif(actual.digitized, actual.digitizedOffset) != expected ||
            CaptureTimeParser.parseExif(actual.modified, actual.modifiedOffset) != expected
    }

    private fun rewind(descriptor: FileDescriptor) {
        Os.lseek(descriptor, 0, OsConstants.SEEK_SET)
    }
}
