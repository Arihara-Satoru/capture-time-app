package local.capturetime.operation

import local.capturetime.time.CaptureTimeParser

internal object MediaScanExpectation {
    // A rescan can replace a stale database value even when DateTimeOriginal was not edited.
    fun dateTaken(actualExifOriginal: String?, previousTakenMillis: Long?): Long? =
        CaptureTimeParser.parseExif(actualExifOriginal)?.toEpochMilli() ?: previousTakenMillis
}
