package local.capturetime.duplicate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DuplicateRulesTest {
    @Test fun `hex suffix only applies to safe timestamp names`() {
        val unsafe = listOf(image("/DCIM/foo.jpg", 100), image("/DCIM/foo_abcdef.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(unsafe).isEmpty())

        val safe = listOf(image("/DCIM/IMG_20260101_120000.jpg", 100), image("/DCIM/IMG_20260101_120000_abcdef.jpg", 90))
        assertEquals(listOf("IMG_20260101_120000_abcdef.jpg"), DuplicateRules.findCandidates(safe).map { it.delete.file.name })
    }

    @Test fun `ordinary timestamp is not treated as a hex suffix`() {
        val files = listOf(image("/DCIM/IMG_20260101.jpg", 100), image("/DCIM/IMG_20260101_120000.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
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

    @Test fun `equal byte sizes are not removed`() {
        val files = listOf(image("/DCIM/IMG_20260101_120000.jpg", 100), image("/DCIM/IMG_20260101_120000_abcdef.jpg", 100))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
    }

    @Test fun `higher resolution wins only with close aspect and five percent gap`() {
        val files = listOf(
            image("/DCIM/IMG_20260101_120000.jpg", 80, 1000, 1000),
            image("/DCIM/IMG_20260101_120000_abcdef.jpg", 100, 1100, 1100)
        )
        assertEquals("IMG_20260101_120000.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `same screenshot name keeps png`() {
        val files = listOf(image("/Pictures/Screenshot_demo.png", 80), image("/Pictures/Screenshot_demo.jpg", 100))
        assertEquals("Screenshot_demo.jpg", DuplicateRules.findCandidates(files).single().delete.file.name)
    }

    @Test fun `video requires exact metadata and keeps original`() {
        val original = video("/DCIM/VID_20260101_120000.mp4", 100, 5000)
        val copy = video("/DCIM/VID_20260101_120000_abcdef.mp4", 100, 5000)
        assertEquals(copy.file.name, DuplicateRules.findCandidates(listOf(original, copy)).single().delete.file.name)
        assertTrue(DuplicateRules.findCandidates(listOf(original, copy.copy(durationMillis = 5001))).isEmpty())
    }

    @Test fun `never compares across folders`() {
        val files = listOf(image("/DCIM/A/IMG_20260101_120000.jpg", 100), image("/DCIM/B/IMG_20260101_120000_abcdef.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
    }

    private fun image(path: String, size: Long, width: Int = 1000, height: Int = 1000) =
        DuplicateAsset(File(path), MediaKind.IMAGE, width, height, size = size)

    private fun video(path: String, size: Long, duration: Long) =
        DuplicateAsset(File(path), MediaKind.VIDEO, 1920, 1080, duration, size)
}
