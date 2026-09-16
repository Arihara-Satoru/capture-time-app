package local.capturetime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupSessionRulesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun releaseAndDebugSessionsAreIsolated() {
        assertTrue(BackupSessionRules.isManagedName("capture-time-app-20260907-103000", debug = false))
        assertTrue(BackupSessionRules.isManagedName("duplicate-cleanup-20260907-103000", debug = false))
        assertFalse(BackupSessionRules.isManagedName("capture-time-app-debug-20260907-103000", debug = false))
        assertFalse(BackupSessionRules.isManagedName("duplicate-cleanup-debug-20260907-103000", debug = false))

        assertTrue(BackupSessionRules.isManagedName("capture-time-app-debug-20260907-103000", debug = true))
        assertTrue(BackupSessionRules.isManagedName("duplicate-cleanup-debug-20260907-103000", debug = true))
        assertFalse(BackupSessionRules.isManagedName("capture-time-app-20260907-103000", debug = true))
        assertFalse(BackupSessionRules.isManagedName("duplicate-cleanup-20260907-103000", debug = true))
    }

    @Test fun rejectsIncompleteOrUnknownSessionNames() {
        assertFalse(BackupSessionRules.isManagedName("capture-time-app-", debug = false))
        assertFalse(BackupSessionRules.isManagedName("duplicate-cleanup-", debug = false))
        assertFalse(BackupSessionRules.isManagedName("capture-time-app-20260907", debug = false))
        assertFalse(BackupSessionRules.isManagedName("another-app-backup-20260907", debug = false))
    }

    @Test fun createsBuildSpecificSessionNames() {
        assertEquals("capture-time-app-20260907-103000", BackupSessionRules.captureSessionName("20260907-103000", debug = false))
        assertEquals("capture-time-app-debug-20260907-103000", BackupSessionRules.captureSessionName("20260907-103000", debug = true))
        assertEquals("duplicate-cleanup-debug-20260907-103000", BackupSessionRules.duplicateSessionName("20260907-103000", debug = true))
    }

    @Test fun deletesOnlyRecognizedTempSession() {
        val storage = folder.newFolder("storage")
        val temp = File(storage, ".temp").apply { mkdir() }
        val session = File(temp, "capture-time-app-debug-20260907-103000").apply { mkdir() }
        File(session, "nested").apply { mkdir() }
        File(session, "nested/backup.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val unrelated = File(temp, "unrelated").apply { mkdir() }
        File(unrelated, "keep.txt").writeText("keep")

        assertEquals(3, BackupSessionRules.sessionSize(session, debug = true))
        assertTrue(BackupSessionRules.deleteManagedSession(session, debug = true))
        assertFalse(session.exists())
        assertTrue(File(unrelated, "keep.txt").isFile)
    }

    @Test fun rejectsManagedNameOutsideTempRoot() {
        val lookalike = File(folder.newFolder("other"), "capture-time-app-debug-20260907-103000").apply { mkdir() }
        assertFalse(BackupSessionRules.isManagedSession(lookalike, debug = true))
        assertFalse(BackupSessionRules.deleteManagedSession(lookalike, debug = true))
    }
}
