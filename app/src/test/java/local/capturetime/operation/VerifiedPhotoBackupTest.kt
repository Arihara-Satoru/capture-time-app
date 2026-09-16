package local.capturetime.operation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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

    @Test fun createsNewBackupWithoutOverwriting() {
        val source = folder.newFile("source-new.jpg").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val session = folder.newFolder("session")
        val backup = File(session, "nested/backup.jpg")
        backup.parentFile!!.mkdirs()

        val receipt = VerifiedPhotoBackup.copyNewAndVerify(source, backup, session)

        assertArrayEquals(source.readBytes(), backup.readBytes())
        assertTrue(receipt.backupSha256 == VerifiedPhotoBackup.sha256Hex(backup, session, requireSingleLink = true))
        try {
            VerifiedPhotoBackup.copyNewAndVerify(source, backup, session)
            fail("Expected existing destination to be rejected")
        } catch (_: Exception) {
        }
    }

    @Test fun rejectsHardlinkedRestoreTargetWithoutChangingSource() {
        val bytes = byteArrayOf(5, 4, 3, 2, 1)
        val source = folder.newFile("hardlink-source.jpg").apply { writeBytes(bytes) }
        val destination = File(folder.root, "hardlink-target.jpg")
        Files.createLink(destination.toPath(), source.toPath())

        try {
            VerifiedPhotoBackup.copyAndVerify(source, destination)
            fail("Expected hardlinked destination to be rejected")
        } catch (_: Exception) {
        }

        assertArrayEquals(bytes, source.readBytes())
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test fun independentBackupCheckRejectsHardlink() {
        val source = folder.newFile("independent-source.jpg").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val linkedBackup = File(folder.root, "linked-backup.jpg")
        Files.createLink(linkedBackup.toPath(), source.toPath())

        org.junit.Assert.assertFalse(VerifiedPhotoBackup.contentMatches(
            source,
            linkedBackup,
            folder.root,
            folder.root
        ))
    }

    @Test fun backupReceiptDetectsLaterContentChange() {
        val source = folder.newFile("receipt-source.jpg").apply { writeBytes(byteArrayOf(2, 4, 6, 8)) }
        val session = folder.newFolder("receipt-session")
        val backup = File(session, "backup.jpg")
        val receipt = VerifiedPhotoBackup.copyNewAndVerify(source, backup, session)

        backup.writeBytes(byteArrayOf(8, 6, 4, 2))

        assertTrue(receipt.backupSha256 != VerifiedPhotoBackup.sha256Hex(backup, session, requireSingleLink = true))
    }
}
