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
}
