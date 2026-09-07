package local.capturetime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSessionRulesTest {
    @Test fun includesBothKnownBackupSessionPrefixesOnlyWhenTheyHaveNames() {
        assertTrue(BackupSessionRules.isManagedName("capture-time-app-20260907-103000"))
        assertTrue(BackupSessionRules.isManagedName("duplicate-cleanup-20260907-103000"))
        assertFalse(BackupSessionRules.isManagedName("capture-time-app-"))
        assertFalse(BackupSessionRules.isManagedName("duplicate-cleanup-"))
        assertFalse(BackupSessionRules.isManagedName("another-app-backup-20260907"))
    }
}
