package local.capturetime.operation

import local.capturetime.time.CaptureTimeParser

internal object MediaScanExpectation {
    fun matchesTaken(actualMillis: Long?, expectedMillis: Long?, secondPrecision: Boolean): Boolean =
        expectedMillis == null || (actualMillis != null && if (secondPrecision) {
            Math.floorDiv(actualMillis, 1000L) == Math.floorDiv(expectedMillis, 1000L)
        } else actualMillis == expectedMillis)

    // A rescan can replace a stale database value even when DateTimeOriginal was not edited.
    fun dateTaken(actualExifOriginal: String?, previousTakenMillis: Long?): Long? =
        CaptureTimeParser.parseExif(actualExifOriginal)?.toEpochMilli() ?: previousTakenMillis
}
