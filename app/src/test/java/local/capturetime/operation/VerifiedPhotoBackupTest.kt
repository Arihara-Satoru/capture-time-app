package local.capturetime.operation

import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedPhotoBackupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun backsUpAcrossBufferBoundariesWithoutChangingSource() {
        val bytes = ByteArray(256 * 1024 * 3 + 137).also { java.util.Random(7).nextBytes(it) }
        val source = folder.newFile("photo.jpg").apply { writeBytes(bytes) }
        val backup = folder.newFile("backup.jpg")
        VerifiedPhotoBackup.copyAndVerify(source, backup)
        assertArrayEquals(bytes, source.readBytes())
        assertArrayEquals(bytes, backup.readBytes())
    }

    @Test fun restorationTruncatesLongerDamagedFile() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val backup = folder.newFile("backup.jpg").apply { writeBytes(bytes) }
        val damaged = folder.newFile("photo.jpg").apply { writeBytes(ByteArray(300_000)) }
        VerifiedPhotoBackup.copyAndVerify(backup, damaged)
        assertArrayEquals(bytes, damaged.readBytes())
    }
}
