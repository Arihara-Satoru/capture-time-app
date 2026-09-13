package local.capturetime.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaScanExpectationTest {
    @Test fun fileModifiedOnlyUsesExistingExifInsteadOfStaleDatabaseTime() {
        assertEquals(1661432354000L, MediaScanExpectation.dateTaken("2022:08:25 20:59:14", 1762863324000L))
    }

    @Test fun digitizedOnlyUsesUnchangedOriginalExif() {
        assertEquals(1712919453000L, MediaScanExpectation.dateTaken("2024:04:12 18:57:33", 1758363138000L))
    }

    @Test fun readsActualExifRatherThanAssumingRuleTargetWasWritten() {
        // The original field can be excluded or skipped by the configured tolerance.
        assertEquals(1712919455000L, MediaScanExpectation.dateTaken("2024:04:12 18:57:35", 1758363138000L))
    }

    @Test fun missingOrInvalidExifKeepsDatabaseFallback() {
        assertEquals(1758363138000L, MediaScanExpectation.dateTaken(null, 1758363138000L))
        assertEquals(1758363138000L, MediaScanExpectation.dateTaken("invalid", 1758363138000L))
        assertNull(MediaScanExpectation.dateTaken(null, null))
    }

    @Test fun validExifWorksWithoutPreviousDatabaseTime() {
        assertEquals(1661432354000L, MediaScanExpectation.dateTaken("2022:08:25 20:59:14", null))
    }
}
