package local.capturetime.operation

import local.capturetime.time.CaptureTimeParser
import java.time.ZoneId

internal object MediaScanExpectation {
    fun matchesTaken(actualMillis: Long?, expectedMillis: Long?, secondPrecision: Boolean): Boolean =
        expectedMillis == null || (actualMillis != null && if (secondPrecision) {
            Math.floorDiv(actualMillis, 1000L) == Math.floorDiv(expectedMillis, 1000L)
        } else actualMillis == expectedMillis)

    // A rescan can replace a stale database value even when DateTimeOriginal was not edited.
    fun dateTaken(actualExifOriginal: String?, previousTakenMillis: Long?, originalOffset: String? = null,
                  zone: ZoneId = CaptureTimeParser.zone): Long? =
        CaptureTimeParser.parseExif(actualExifOriginal, originalOffset, zone)?.toEpochMilli() ?: previousTakenMillis
}
