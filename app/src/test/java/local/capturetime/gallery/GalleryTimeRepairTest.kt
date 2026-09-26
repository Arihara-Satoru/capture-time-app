package local.capturetime.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GalleryTimeRepairTest {
    @Test fun selectsTheFieldsWrittenByEachMode() {
        assertTrue(GalleryTimeRepair.repairAdded("both"))
        assertFalse(GalleryTimeRepair.repairAdded("capture"))
        try {
            GalleryTimeRepair.repairAdded("unknown")
            fail("Unknown mode must not write the database")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsOnlyObservedGalleryCopySuffix() {
        val recorded = "mmexport1601462732439.jpg"
        assertTrue(GalleryTimeRepair.matchesFileName(recorded, recorded, 0))
        assertTrue(GalleryTimeRepair.matchesFileName(recorded, "mmexport1601462732439_c0f17c.jpg", 7))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded, "mmexport1601462732439_c0f17c.jpg", 0))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded, "mmexport1601462732439_c0f17z.jpg", 7))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded, "mmexport1601462732440_c0f17c.jpg", 7))
    }

    @Test fun acceptsGalleryUploadIdOnlyWhenDiskNameIsExactPrefix() {
        val actual = "Screenshot_2019-11-29-19-23-42-149_com.android.settings.jpg"
        val recorded = "Screenshot_2019-11-29-19-23-42-149_com.android.settings_1788421987481.jpg"
        assertTrue(GalleryTimeRepair.matchesFileName(recorded, actual, 0))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded, actual, 7))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded.replace("1788421987481", "178842198748"), actual, 0))
        assertFalse(GalleryTimeRepair.matchesFileName(recorded, actual.replace("settings", "other"), 0))
    }
}
