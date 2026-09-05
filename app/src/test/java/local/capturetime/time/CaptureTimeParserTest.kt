package local.capturetime.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CaptureTimeParserTest {
    @Test fun parsesSupportedFilenameFormatsInShanghai() {
        val expected = Instant.parse("2024-01-01T19:04:05Z")
        assertEquals(expected, CaptureTimeParser.parseFilename("IMG_2024-01-02-03-04-05_copy"))
        assertEquals(expected, CaptureTimeParser.parseFilename("IMG_20240102_030405"))
        assertEquals(expected, CaptureTimeParser.parseFilename("20240102-030405-photo"))
    }

    @Test fun parsesThirteenDigitUnixMilliseconds() {
        assertEquals(Instant.ofEpochMilli(1_534_600_669_491), CaptureTimeParser.parseFilename("1534600669491"))
        assertEquals(Instant.ofEpochMilli(1_534_600_669_491), CaptureTimeParser.parseFilename("IMG_1534600669491_copy"))
    }

    @Test fun rejectsInvalidDatesAndUnsupportedFormats() {
        assertNull(CaptureTimeParser.parseFilename("IMG_20240230_120000"))
        assertNull(CaptureTimeParser.parseFilename("IMG_2024_01_02_030405"))
        assertNull(CaptureTimeParser.parseFilename("9999999999999"))
        assertNull(CaptureTimeParser.parseExif("2024:13:01 00:00:00"))
    }

    @Test fun detectsDifferentValidTimesAsAmbiguous() {
        assertTrue(CaptureTimeParser.hasAmbiguousFilenameTime("20240102_030405--20240103-030405"))
        assertNull(CaptureTimeParser.parseFilename("20240102_030405--20240103-030405"))
        assertTrue(CaptureTimeParser.hasAmbiguousFilenameTime("20240102_030405--1704155045000"))
    }

    @Test fun formatsExifWithoutChangingShanghaiWallTime() {
        assertEquals("2024:01:02 03:04:05", CaptureTimeParser.formatExif(Instant.parse("2024-01-01T19:04:05Z")))
    }
}
