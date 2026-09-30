package local.capturetime.settings

import local.capturetime.exif.ExifTimes
import local.capturetime.model.MediaSnapshot
import local.capturetime.time.CaptureTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeRuleConfigTest {
    private val early = Instant.parse("2024-01-01T00:00:00Z")
    private val late = Instant.parse("2024-02-01T00:00:00Z")

    @Test fun selectsEarliestConfiguredAvailableField() {
        val rule = TimeRuleConfig(sourceFields = setOf(TimeField.FILENAME, TimeField.MEDIA_DATE_ADDED))
        assertEquals(early, rule.selectTarget(mapOf(TimeField.FILENAME to late, TimeField.MEDIA_DATE_ADDED to early)))
    }

    @Test fun selectsLatestConfiguredAvailableField() {
        val rule = TimeRuleConfig(TimeSelection.LATEST, setOf(TimeField.FILENAME, TimeField.MEDIA_DATE_ADDED))
        assertEquals(late, rule.selectTarget(mapOf(TimeField.FILENAME to late, TimeField.MEDIA_DATE_ADDED to early)))
    }

    @Test fun screenshotMidnightUsesSameDayFilenameTimeAndRepairsDestinations() {
        val rule = TimeRuleConfig(toleranceSeconds = 3600)
        val midnight = Instant.ofEpochMilli(1512057600000L)
        val filename = CaptureTimeParser.parseFilename("Screenshot_2017-12-01-18-36-57")!!
        val raw = ExifTimes("2017:12:01 00:00:00", "2017:12:01 00:00:00", "2017:12:01 00:00:00")
        val values = rule.values(raw, MediaSnapshot(6923, midnight, late, late.epochSecond), filename, midnight)
        assertEquals(Instant.ofEpochMilli(1512124617000L), rule.selectTarget(values))
        assertEquals(TimeRuleConfig.DEFAULT_DESTINATIONS, rule.fieldsNeedingChange(values, filename, raw).toSet())
        assertTrue(rule.needsChange(midnight, midnight.plusSeconds(30 * 60)))
    }

    @Test fun onlySameDaySelectedTimesDisqualifyMidnight() {
        val rule = TimeRuleConfig(sourceFields = setOf(TimeField.CURRENT_CAPTURE, TimeField.FILENAME))
        val midnight = Instant.parse("2024-01-01T16:00:00Z")
        val values = mapOf(TimeField.CURRENT_CAPTURE to midnight, TimeField.FILENAME to midnight.plusSeconds(3600))
        assertEquals(midnight.plusSeconds(3600), rule.selectTarget(values))
        assertEquals(midnight, rule.selectTarget(values + (TimeField.FILENAME to midnight.plusSeconds(86400 + 3600))))
        assertEquals(midnight, rule.copy(sourceFields = setOf(TimeField.CURRENT_CAPTURE)).selectTarget(values))
        assertEquals(midnight, rule.selectTarget(mapOf(TimeField.CURRENT_CAPTURE to midnight)))
    }

    @Test fun midnightRuleUsesConfiguredZoneAndPreservesTimeSelection() {
        val midnight = Instant.parse("2024-01-01T16:00:00Z")
        val first = midnight.plusSeconds(3600)
        val last = midnight.plusSeconds(7200)
        val values = mapOf(TimeField.CURRENT_CAPTURE to midnight, TimeField.FILENAME to first, TimeField.MEDIA_DATE_TAKEN to last)
        val rule = TimeRuleConfig(sourceFields = values.keys)
        assertEquals(first, rule.selectTarget(values))
        assertEquals(last, rule.copy(selection = TimeSelection.LATEST).selectTarget(values))
        assertEquals(midnight, rule.copy(zone = ZoneId.of("UTC")).selectTarget(values))
        assertEquals(first, rule.selectTarget(values + (TimeField.CURRENT_CAPTURE to midnight.plusMillis(979))))
        assertEquals(first, rule.selectTarget(values + (TimeField.CURRENT_CAPTURE to midnight.plusSeconds(30))))
    }

    @Test fun selectsMmexportFilenameTimeWhenItIsEarliestDefaultSource() {
        val filename = CaptureTimeParser.parseFilename("mmexport1577440495004")
        val media = MediaSnapshot(
            1,
            Instant.parse("2020-01-01T00:00:00Z"),
            Instant.parse("2021-01-01T00:00:00Z"),
            1_609_459_200
        )
        val values = TimeRuleConfig().values(
            ExifTimes("2020:01:01 08:00:00", null, null),
            media,
            filename,
            Instant.parse("2022-01-01T00:00:00Z")
        )

        assertEquals(Instant.ofEpochMilli(1_577_440_495_004), filename)
        assertEquals(filename, TimeRuleConfig().selectTarget(values))
    }

    @Test fun ignoresFieldsThatWereNotSelectedAndReturnsNullWhenUnavailable() {
        val rule = TimeRuleConfig(sourceFields = setOf(TimeField.FILENAME))
        assertNull(rule.selectTarget(mapOf(TimeField.MEDIA_DATE_ADDED to early)))
    }

    @Test fun appliesToleranceInEitherTimeDirectionButAlwaysFillsMissingFields() {
        val rule = TimeRuleConfig(toleranceSeconds = 60)
        assertFalse(rule.needsChange(early.plusSeconds(60), early))
        assertFalse(rule.needsChange(early.minusSeconds(60), early))
        assertTrue(rule.needsChange(early.plusSeconds(61), early))
        assertTrue(rule.needsChange(null, early))
    }

    @Test fun positiveToleranceIgnoresMissingDestinationFieldsWhenCurrentCaptureIsClose() {
        val rule = TimeRuleConfig(
            destinationFields = setOf(TimeField.EXIF_ORIGINAL, TimeField.EXIF_DIGITIZED, TimeField.FILE_MODIFIED),
            toleranceSeconds = 86_400
        )
        val values = mapOf(
            TimeField.CURRENT_CAPTURE to early.plusSeconds(15),
            TimeField.EXIF_ORIGINAL to null,
            TimeField.EXIF_DIGITIZED to null,
            TimeField.FILE_MODIFIED to early.plusSeconds(10)
        )

        assertTrue(rule.fieldsNeedingChange(values, early, ExifTimes(null, null, null)).isEmpty())
    }

    @Test fun missingDestinationFieldsStillChangeWithoutToleranceOrReliableCurrentCapture() {
        val destinations = setOf(TimeField.EXIF_ORIGINAL)
        val values = mapOf(TimeField.CURRENT_CAPTURE to early, TimeField.EXIF_ORIGINAL to null)

        assertEquals(
            listOf(TimeField.EXIF_ORIGINAL),
            TimeRuleConfig(destinationFields = destinations)
                .fieldsNeedingChange(values, early, ExifTimes(null, null, null))
        )
        assertEquals(
            listOf(TimeField.EXIF_ORIGINAL),
            TimeRuleConfig(destinationFields = destinations, toleranceSeconds = 60)
                .fieldsNeedingChange(values + (TimeField.CURRENT_CAPTURE to null), early, ExifTimes(null, null, null))
        )
    }

    @Test fun invalidExifIsRepairedEvenWhenCurrentCaptureIsWithinTolerance() {
        val rule = TimeRuleConfig(
            destinationFields = setOf(TimeField.EXIF_ORIGINAL),
            toleranceSeconds = 60
        )
        val values = mapOf(TimeField.CURRENT_CAPTURE to early, TimeField.EXIF_ORIGINAL to null)

        assertEquals(
            listOf(TimeField.EXIF_ORIGINAL),
            rule.fieldsNeedingChange(values, early, ExifTimes("2024:01:01 00:00:00", null, null, originalOffset = "invalid"))
        )
    }

    @Test fun offsetWithoutDateIsInvalidForEveryExifDestination() {
        val cases = listOf(
            TimeField.EXIF_ORIGINAL to ExifTimes(null, null, null, originalOffset = "invalid"),
            TimeField.EXIF_DIGITIZED to ExifTimes(null, null, null, digitizedOffset = "invalid"),
            TimeField.EXIF_MODIFIED to ExifTimes(null, null, null, modifiedOffset = "invalid")
        )

        cases.forEach { (field, exif) ->
            val rule = TimeRuleConfig(destinationFields = setOf(field), toleranceSeconds = 60)
            val values = mapOf(TimeField.CURRENT_CAPTURE to early, field to null)
            assertEquals(listOf(field), rule.fieldsNeedingChange(values, early, exif))
        }
    }

    @Test fun existingDestinationOutsideToleranceStillChanges() {
        val rule = TimeRuleConfig(
            destinationFields = setOf(TimeField.EXIF_ORIGINAL),
            toleranceSeconds = 60
        )
        val values = mapOf(
            TimeField.CURRENT_CAPTURE to early,
            TimeField.EXIF_ORIGINAL to early.plusSeconds(61)
        )

        assertEquals(listOf(TimeField.EXIF_ORIGINAL), rule.fieldsNeedingChange(values, early, null))
    }

    @Test fun missingOriginalOffsetIsRepairedWhenMediaScannerDisagrees() {
        val rule = TimeRuleConfig(toleranceSeconds = 3600)
        val samples = listOf(
            Triple("2019:03:16 19:25:42", 1552717542979L, 1552735542000L),
            Triple("2023:02:10 12:05:21", 1676030721907L, 1676001921000L)
        )
        samples.forEach { (date, mediaMillis, targetMillis) ->
            val raw = ExifTimes(date, date, date)
            val target = Instant.ofEpochMilli(targetMillis)
            val media = MediaSnapshot(1, Instant.ofEpochMilli(mediaMillis), target, target.epochSecond)
            val values = rule.values(raw, media, null, target)
            assertEquals(target, rule.selectTarget(values))
            assertTrue(TimeField.EXIF_ORIGINAL in rule.fieldsNeedingChange(values, target, raw))
            assertFalse(TimeField.EXIF_ORIGINAL in rule.fieldsNeedingChange(
                values, target, raw.copy(originalOffset = "+08:00")))
        }
    }

    @Test fun missingExifChangesWhenCurrentCaptureIsOutsideTolerance() {
        val rule = TimeRuleConfig(
            destinationFields = setOf(TimeField.EXIF_ORIGINAL),
            toleranceSeconds = 60
        )
        val values = mapOf(
            TimeField.CURRENT_CAPTURE to early.plusSeconds(61),
            TimeField.EXIF_ORIGINAL to null
        )

        assertEquals(
            listOf(TimeField.EXIF_ORIGINAL),
            rule.fieldsNeedingChange(values, early, ExifTimes(null, null, null))
        )
    }

    @Test fun matchesObservedWxAndMmexportCandidateDecisions() {
        val rule = TimeRuleConfig(toleranceSeconds = 90_000)
        val wxFilename = Instant.ofEpochMilli(1_789_389_096_764)
        val wxCurrent = Instant.ofEpochMilli(1_789_389_096_000)
        val wxValues = rule.values(
            ExifTimes(null, null, null),
            MediaSnapshot(32_206, wxCurrent, wxCurrent, wxCurrent.epochSecond),
            wxFilename,
            wxCurrent
        )
        val wxTarget = rule.selectTarget(wxValues)!!
        assertEquals(wxCurrent, wxTarget)
        assertTrue(rule.fieldsNeedingChange(wxValues, wxTarget, ExifTimes(null, null, null)).isEmpty())

        val mmFilename = Instant.ofEpochMilli(1_577_440_495_004)
        val mmCurrent = Instant.ofEpochMilli(1_577_440_495_000)
        val mmExif = ExifTimes(
            "2019:12:27 17:54:55",
            "2019:12:27 17:54:55",
            "2019:12:27 17:54:55"
        )
        val mmValues = rule.values(
            mmExif,
            MediaSnapshot(7_284, mmCurrent, Instant.ofEpochSecond(1_788_401_085), 1_788_401_085),
            mmFilename,
            mmCurrent
        )
        val mmTarget = rule.selectTarget(mmValues)!!
        assertEquals(mmCurrent, mmTarget)
        assertTrue(rule.fieldsNeedingChange(mmValues, mmTarget, mmExif).isEmpty())
    }
}
