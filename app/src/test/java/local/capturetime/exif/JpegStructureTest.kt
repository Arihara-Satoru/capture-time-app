package local.capturetime.exif

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class JpegStructureTest {
    @get:Rule val folder = TemporaryFolder()
    private val scan = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 63, 0, 12, 0xff, 0xd9)
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun check(data: ByteArray, invalid: Boolean) {
        val file = folder.newFile().apply { writeBytes(data) }
        if (invalid) {
            val error = assertThrows(IOException::class.java) { JpegStructure.validate(file) }
            assertEquals("JPEG 结构异常，无法写入", error.message)
        } else JpegStructure.validate(file)
        assertArrayEquals(data, file.readBytes())
    }

    @Test fun detectsCommentLengthShortBy34BytesAndAcceptsCorrectedLength() {
        val comment = ByteArray(918) { 32 }
        check(bytes(0xff, 0xd8, 0xff, 0xfe, 3, 118) + comment + scan, true)
        check(bytes(0xff, 0xd8, 0xff, 0xfe, 3, 152) + comment + scan, false)
    }

    @Test fun rejectsTruncationAndInvalidLengths() {
        listOf(bytes(0xff, 0xd8), bytes(0xff, 0xd8, 0xff, 0xe1, 0),
            bytes(0xff, 0xd8, 0xff, 0xe1, 0, 1), bytes(0xff, 0xd8, 0xff, 0xe1, 0, 20),
            bytes(0xff, 0xd8, 0xff, 0xda, 0, 2, 1)).forEach { check(it, true) }
    }

    @Test fun allowsMarkerFillAndDoesNotParseCompressedBytesAsHeaders() {
        check(bytes(0xff, 0xd8, 0xff, 0xff, 0xe0, 0, 2) + scan, false)
    }

    @Test fun otherFormatsAreLeftToExifLibrary() {
        check(bytes(0x89, 0x50, 0x4e, 0x47), false)
    }
}
