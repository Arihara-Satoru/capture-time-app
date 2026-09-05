package local.capturetime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
}
