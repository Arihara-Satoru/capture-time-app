package local.capturetime.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathPolicyTest {
    @Test fun excludesProtectedPathSegmentsCaseInsensitively() {
        assertTrue(PathPolicy.isExcluded(File("/sdcard/DCIM/.globalTrash/a.jpg")))
        assertTrue(PathPolicy.isExcluded(File("/sdcard/DCIM/.GLOBALTRASH/a.jpg")))
        assertTrue(PathPolicy.isExcluded(File("/sdcard/Pictures/.trashed-123-a.jpg")))
        assertTrue(PathPolicy.isExcluded(File("/sdcard/DCIM/.thumbnails/a.jpg")))
        assertFalse(PathPolicy.isExcluded(File("/sdcard/DCIM/Camera/a.jpg")))
    }
}
