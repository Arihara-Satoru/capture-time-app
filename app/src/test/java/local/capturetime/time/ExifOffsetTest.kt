package local.capturetime.time

import local.capturetime.exif.ExifGateway
import local.capturetime.exif.ExifTimes
import local.capturetime.operation.MediaScanExpectation
import local.capturetime.settings.TimeField
import local.capturetime.settings.TimeRuleConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ExifOffsetTest {
    @Test fun deviceSampleMatchesScannerWithSubseconds() {
        val expected = MediaScanExpectation.dateTaken("2025:05:04 21:29:03", null, "+09:45")
        assertEquals(1746359043000L, expected)
        assertTrue(MediaScanExpectation.matchesTaken(1746359043710L, expected, true))
    }

    @Test fun eachRuleFieldUsesItsOwnOffset() {
        val raw = ExifTimes("2025:05:04 21:29:03", "2025:05:04 21:29:03", "2025:05:04 21:29:03",
            "+09:45", "+08:00", "-04:00")
        val values = TimeRuleConfig().values(raw, null, null, Instant.EPOCH)
        assertEquals(Instant.parse("2025-05-04T11:44:03Z"), values[TimeField.EXIF_ORIGINAL])
        assertEquals(values[TimeField.EXIF_ORIGINAL], values[TimeField.CURRENT_CAPTURE])
        assertEquals(Instant.parse("2025-05-04T13:29:03Z"), values[TimeField.EXIF_DIGITIZED])
        assertEquals(Instant.parse("2025-05-05T01:29:03Z"), values[TimeField.EXIF_MODIFIED])
    }

    @Test fun writtenTimeAndOffsetRepresentTheTargetInstant() {
        val target = Instant.parse("2025-05-04T11:44:03Z")
        assertEquals("2025:05:04 19:44:03", CaptureTimeParser.formatExif(target))
        assertEquals("+08:00", CaptureTimeParser.formatExifOffset(target))
        assertEquals(target, CaptureTimeParser.parseExif(CaptureTimeParser.formatExif(target), CaptureTimeParser.formatExifOffset(target)))
    }

    @Test fun missingOffsetKeepsLegacyZoneButMalformedOffsetIsNotGuessed() {
        assertEquals(Instant.parse("2025-05-04T13:29:03Z"), CaptureTimeParser.parseExif("2025:05:04 21:29:03"))
        assertNull(CaptureTimeParser.parseExif("2025:05:04 21:29:03", "+25:00"))
        assertNull(CaptureTimeParser.parseExif("2025:05:04 21:29:03", "invalid"))
    }

    @Test fun unchangedForeignOffsetCanAlreadyRepresentTarget() {
        val target = Instant.parse("2025-05-04T11:44:03Z")
        val raw = ExifTimes("2025:05:04 21:29:03", "2025:05:04 21:29:03", "2025:05:04 21:29:03",
            "+09:45", "+09:45", "+09:45")
        assertFalse(ExifGateway().needsSync(raw, target))
        val rule = TimeRuleConfig(sourceFields = setOf(TimeField.EXIF_ORIGINAL))
        val values = rule.values(raw, null, null, Instant.EPOCH)
        assertEquals(target, rule.selectTarget(values))
        assertFalse(rule.needsChange(values[TimeField.EXIF_ORIGINAL], target))
    }
}
