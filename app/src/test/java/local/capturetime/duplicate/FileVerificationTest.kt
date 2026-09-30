package local.capturetime.duplicate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileVerificationTest {
    @Test fun `JPEG comparison ignores EXIF but not image data`() {
        fun jpeg(exifValue: Byte, pixel: Byte, extraExif: ByteArray = byteArrayOf()) = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, (9 + extraExif.size).toByte(),
            69, 120, 105, 102, 0, 0, exifValue
        ) + extraExif + byteArrayOf(
            0xff.toByte(), 0xda.toByte(), 0, 2, pixel, 0xff.toByte(), 0xd9.toByte()
        )
        val first = Files.createTempFile("first", ".jpg").toFile()
        val second = Files.createTempFile("second", ".jpg").toFile()
        try {
            first.writeBytes(jpeg(1, 3))
            second.writeBytes(jpeg(2, 3, byteArrayOf(4, 5)))
            assertTrue(FileVerification.sameJpegExceptExif(first, second))
            assertEquals(FileVerification.jpegWithoutExifSize(first), FileVerification.jpegWithoutExifSize(second))
            assertEquals(FileVerification.jpegWithoutExifHash(first), FileVerification.jpegWithoutExifHash(second))
            second.writeBytes(jpeg(2, 4))
            assertFalse(FileVerification.sameJpegExceptExif(first, second))
            val video = byteArrayOf(0, 0, 0, 12, 102, 116, 121, 112, 109, 112, 52, 50)
            first.writeBytes(jpeg(1, 3) + video)
            second.writeBytes(jpeg(2, 3, byteArrayOf(4, 5)) + video)
            assertTrue(FileVerification.sameJpegExceptExif(first, second))
            assertEquals(FileVerification.jpegWithoutExifSize(first), FileVerification.jpegWithoutExifSize(second))
            second.appendBytes(byteArrayOf(1))
            assertFalse(FileVerification.sameJpegExceptExif(first, second))
        } finally {
            first.delete()
            second.delete()
        }
    }
}
