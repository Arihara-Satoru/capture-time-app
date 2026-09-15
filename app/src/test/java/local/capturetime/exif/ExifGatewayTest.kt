package local.capturetime.exif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExifGatewayTest {
    @Test fun detectsWhenOnlyExifDateFieldsNeedSynchronizing() {
        val target = Instant.parse("2019-11-29T11:23:42Z")
        val gateway = ExifGateway()

        assertTrue(gateway.needsSync(ExifTimes("2019:11:29 19:23:42", null, "2026:09:02 15:13:14"), target))
        assertFalse(gateway.needsSync(ExifTimes("2019:11:29 19:23:42", "2019:11:29 19:23:42", "2019:11:29 19:23:42"), target))
    }
}
