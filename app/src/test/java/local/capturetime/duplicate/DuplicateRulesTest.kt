package local.capturetime.duplicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DuplicateRulesTest {
    @Test fun `underscore suffix matches same directory prefix and hex suffix remains supported`() {
        val withoutPrefix = listOf(image("/DCIM/foo_abcdef.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(withoutPrefix).isEmpty())

        val generic = listOf(image("/DCIM/foo.jpg", 100), image("/DCIM/foo_abcdef.jpg", 90))
        assertEquals("foo_abcdef.jpg", DuplicateRules.findCandidates(generic).single().delete.file.name)

        val safe = listOf(image("/DCIM/IMG_20260101_120000.jpg", 100), image("/DCIM/IMG_20260101_120000_abcdef.jpg", 90))
        assertEquals(listOf("IMG_20260101_120000_abcdef.jpg"), DuplicateRules.findCandidates(safe).map { it.delete.file.name })
    }

    @Test fun `ordinary timestamp suffix sharing a base is a candidate`() {
        val files = listOf(image("/DCIM/IMG_20260101.jpg", 100), image("/DCIM/IMG_20260101_120000.jpg", 90))
        assertEquals("IMG_20260101_120000.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `numeric and bracket copies require original`() {
        val noOriginal = listOf(image("/DCIM/a_1234567890123.jpg", 90), image("/DCIM/a (1).jpg", 80))
        assertTrue(DuplicateRules.findCandidates(noOriginal).isEmpty())
        val withOriginal = noOriginal + image("/DCIM/a.jpg", 100)
        assertEquals(setOf("a_1234567890123.jpg", "a (1).jpg"), DuplicateRules.findCandidates(withOriginal).map { it.delete.file.name }.toSet())
    }

    @Test fun `numeric copy can compare with hex copy when original is absent`() {
        val files = listOf(
            image("/DCIM/IMG_20260101_120000_1234567890123.jpg", 90),
            image("/DCIM/IMG_20260101_120000_abcdef.jpg", 100)
        )
        assertEquals("IMG_20260101_120000_1234567890123.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `numeric camera copy with rotated display dimensions is detected`() {
        val files = listOf(
            image("/DCIM/Camera/IMG_20221203_174311.jpg", 125706, 1440, 1080),
            image("/DCIM/Camera/IMG_20221203_174311_1788422703200.jpg", 326015, 1920, 1440)
        )
        assertEquals(
            "IMG_20221203_174311.jpg",
            DuplicateRules.findCandidates(files).single().delete.file.name
        )
    }

    @Test fun `equal byte size copy remains eligible for hash filtering`() {
        val files = listOf(image("/DCIM/IMG_20260101_120000.jpg", 100), image("/DCIM/IMG_20260101_120000_abcdef.jpg", 100))
        assertEquals("IMG_20260101_120000_abcdef.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `higher resolution wins only with close aspect and five percent gap`() {
        val files = listOf(
            image("/DCIM/IMG_20260101_120000.jpg", 80, 1000, 1000),
            image("/DCIM/IMG_20260101_120000_abcdef.jpg", 100, 1100, 1100)
        )
        assertEquals("IMG_20260101_120000.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `same screenshot base across formats is a candidate`() {
        val files = listOf(image("/Pictures/Screenshot_demo.png", 80), image("/Pictures/Screenshot_demo.jpg", 100))
        assertEquals("Screenshot_demo.png", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `video requires exact metadata and keeps original`() {
        val original = video("/DCIM/VID_20260101_120000.mp4", 100, 5000)
        val copy = video("/DCIM/VID_20260101_120000_abcdef.mp4", 100, 5000)
        assertEquals(copy.file.name, DuplicateRules.findCandidates(listOf(original, copy)).single().delete.file.name)
        assertTrue(DuplicateRules.findCandidates(listOf(original, copy.copy(durationMillis = 5001))).isEmpty())
        assertTrue(DuplicateRules.findCandidates(listOf(original.copy(durationMillis = 0), copy.copy(durationMillis = 0))).isEmpty())
    }

    @Test fun `never compares across folders`() {
        val files = listOf(image("/DCIM/A/IMG_20260101_120000.jpg", 100), image("/DCIM/B/IMG_20260101_120000_abcdef.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
    }

    @Test fun `normalizes primary storage path aliases before grouping`() {
        val files = listOf(
            image("/sdcard/DCIM/IMG_20260101_120000.jpg", 100),
            image("/storage/emulated/0/DCIM/IMG_20260101_120000_abcdef.jpg", 90)
        )
        assertEquals("IMG_20260101_120000_abcdef.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `rejects retained file that is also selected for deletion`() {
        val a = image("/DCIM/A.jpg", 80)
        val b = image("/DCIM/B.jpg", 90)
        val c = image("/DCIM/C.jpg", 100)
        assertTrue(DuplicateRules.hasSelectionConflict(listOf(
            DuplicateCandidate(a, b, "A to B"),
            DuplicateCandidate(b, c, "B to C")
        )))
    }

    @Test fun `accepts independent retained files`() {
        val a = image("/DCIM/A.jpg", 80)
        val b = image("/DCIM/B.jpg", 90)
        val c = image("/DCIM/C.jpg", 70)
        val d = image("/DCIM/D.jpg", 100)
        assertTrue(!DuplicateRules.hasSelectionConflict(listOf(
            DuplicateCandidate(a, b, "A to B"),
            DuplicateCandidate(c, d, "C to D")
        )))
    }

    @Test fun `strict candidate requires matching nonblank hashes`() {
        val delete = image("/DCIM/A.jpg", 100)
        val retained = image("/DCIM/B.jpg", 100)
        assertTrue(!DuplicateRules.hasMatchingContent(DuplicateCandidate(delete, retained, "test")))
        assertTrue(!DuplicateRules.hasMatchingContent(DuplicateCandidate(
            delete.copy(sha256 = "aaa"), retained.copy(sha256 = "bbb"), "test"
        )))
        assertTrue(DuplicateRules.hasMatchingContent(DuplicateCandidate(
            delete.copy(sha256 = "aaa"), retained.copy(sha256 = "aaa"), "test"
        )))
    }

    private fun image(path: String, size: Long, width: Int = 1000, height: Int = 1000) =
        DuplicateAsset(File(path), MediaKind.IMAGE, width, height, size = size)

    private fun video(path: String, size: Long, duration: Long) =
        DuplicateAsset(File(path), MediaKind.VIDEO, 1920, 1080, duration, size)
}
