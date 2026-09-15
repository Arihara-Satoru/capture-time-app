package local.capturetime.operation

import org.junit.Assert.*
import org.junit.Test

class MediaWaitTest {
    @Test fun waitsUntilDeletedMediaRowDisappears() {
        var now = 0L
        var queries = 0
        assertTrue(MediaWait.until({ now }, { now += it }) { ++queries >= 3 })
        assertEquals(3, queries)
        assertEquals(500L, now)
    }

    @Test fun queryFailureIsNotMistakenForDeletedMedia() {
        val failure = IllegalStateException("provider unavailable")
        try {
            MediaWait.until({ 0L }, { fail("unexpected retry") }) { throw failure }
            fail("query failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
    }

    @Test fun stopsAfterThreeSeconds() {
        var now = 0L
        assertFalse(MediaWait.until({ now }, { now += it }) { false })
        assertEquals(3000L, now)
    }

    @Test fun countsQueryTimeAgainstDeadline() {
        var now = 0L
        var queries = 0
        assertFalse(MediaWait.until({ now }, { now += it }) { queries++; now += 1000; false })
        assertEquals(3, queries)
        assertEquals(3500L, now) // A synchronous provider query cannot be preempted by the polling loop.
    }

    @Test fun doesNotResumePollingAfterLongSuspension() {
        var now = 0L
        var queries = 0
        assertFalse(MediaWait.until({ now }, { now += 120_000 }) { queries++; false })
        assertEquals(1, queries)
    }

    @Test fun immediateSuccessDoesNotSleep() {
        assertTrue(MediaWait.until({ 0L }, { fail("unexpected sleep") }) { true })
    }
}
