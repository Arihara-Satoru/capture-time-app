package local.capturetime.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanningRulesTest {
    @Test fun selectsEarliestAvailableTime() {
        val current = Instant.parse("2024-03-01T00:00:00Z")
        val added = Instant.parse("2024-02-01T00:00:00Z")
        val filename = Instant.parse("2024-01-01T00:00:00Z")
        assertEquals(filename, PlanningRules.target(current, added, filename))
    }

    @Test fun requiresStrictlyEarlierTarget() {
        val current = Instant.parse("2024-03-01T00:00:00Z")
        assertFalse(PlanningRules.isCandidate(current, current))
        assertTrue(PlanningRules.isCandidate(current, current.minusSeconds(1)))
    }

    @Test fun ignoresDifferencesWithinConfiguredTolerance() {
        val current = Instant.parse("2024-03-01T00:00:00Z")
        assertFalse(PlanningRules.isCandidate(current, current.minusSeconds(60), 60))
        assertTrue(PlanningRules.isCandidate(current, current.minusSeconds(61), 60))
    }
}
