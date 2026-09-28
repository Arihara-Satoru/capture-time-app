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

    @Test fun `ordinary timestamp is not treated as a hex suffix`() {
        val files = listOf(image("/DCIM/IMG_20260101.jpg", 100), image("/DCIM/IMG_20260101_120000.jpg", 90))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
    }

    @Test fun `screenshot hex copy with different bytes retains higher resolution`() {
        val base = "/Pictures/Gallery/owner/galgame/Screenshot_2023-07-02-21-33-08-904_org.tvp.kirikiri2_free_10309"
        val original = image("$base.jpg", 121947, 1728, 1080).copy(sha256 = "lower")
        val copy = image("${base}_17b2ad.jpg", 1259816, 2560, 1600).copy(sha256 = "higher")
        val candidate = DuplicateRules.findCandidates(listOf(original, copy)).single()
        assertEquals(original.file, candidate.delete.file)
        assertEquals(copy.file, candidate.retained.file)
        assertTrue(DuplicateRules.isEligibleCandidate(candidate))
        assertTrue(DuplicateRules.findCandidates(listOf(original, copy.copy(height = 1500))).isEmpty())
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

    @Test fun `two numeric screenshot copies need EXIF-only JPEG confirmation`() {
        val base = "/DCIM/Screenshots/Screenshot_2025-06-21-19-16-44-133_com.umetrip.android.msky.app"
        val older = image("${base}_1790516728935.jpg", 1643327, 1080, 2400).copy(sha256 = "older")
        val newer = image("${base}_1790518019938.jpg", 1643473, 1080, 2400).copy(sha256 = "newer")
        val candidate = DuplicateRules.findCandidates(listOf(older, newer)).single()
        assertEquals(older.file, candidate.delete.file)
        assertEquals(newer.file, candidate.retained.file)
        assertTrue(!DuplicateRules.isEligibleCandidate(candidate))
        assertTrue(DuplicateRules.isEligibleCandidate(candidate.copy(matchedByNameRule = true)))
        assertTrue(DuplicateRules.findCandidates(listOf(older, newer.copy(height = 2300))).isEmpty())
    }

    @Test fun `original screenshot and numeric copy need EXIF-only JPEG confirmation`() {
        val base = "/DCIM/Screenshots/Screenshot_2025-03-17-16-43-56-709_com.miui.home"
        val original = image("$base.jpg", 601207, 1080, 2400).copy(sha256 = "original")
        val copy = image("${base}_1790516699719.jpg", 601061, 1080, 2400).copy(sha256 = "copy")
        val candidate = DuplicateRules.findCandidates(listOf(original, copy)).single()
        assertEquals(copy.file, candidate.delete.file)
        assertEquals(original.file, candidate.retained.file)
        assertTrue(DuplicateRules.isNumericOriginalPair(candidate))
        assertTrue(!DuplicateRules.isEligibleCandidate(candidate))
        assertTrue(DuplicateRules.isEligibleCandidate(candidate.copy(matchedByNameRule = true)))
        assertTrue(DuplicateRules.findCandidates(listOf(original, copy.copy(height = 2300))).isEmpty())
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

    @Test fun `same screenshot name across formats is not auto deleted`() {
        val files = listOf(image("/Pictures/Screenshot_demo.png", 80), image("/Pictures/Screenshot_demo.jpg", 100))
        assertTrue(DuplicateRules.findCandidates(files).isEmpty())
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
