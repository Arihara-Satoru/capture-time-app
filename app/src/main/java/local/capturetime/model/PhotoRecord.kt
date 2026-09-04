package local.capturetime.model

import java.io.File
import java.time.Instant

enum class ImageFormat(val label: String) {
    JPEG("JPEG"), HEIC("HEIC/HEIF"), PNG("PNG"), OTHER("其他")
}

enum class CaptureSource(val label: String) {
    EXIF("EXIF DateTimeOriginal"), MEDIASTORE("MediaStore DATE_TAKEN")
}

data class MediaSnapshot(
    val id: Long,
    val dateTaken: Instant?,
    val dateAdded: Instant?,
    val rawDateAddedSeconds: Long?
)

data class PhotoRecord(
    val file: File,
    val format: ImageFormat,
    val exifOriginal: Instant?,
    val media: MediaSnapshot?,
    val filenameTime: Instant?,
    val currentCaptureTime: Instant?,
    val captureSource: CaptureSource?,
    val targetCaptureTime: Instant?,
    val candidate: Boolean,
    val safeForTrial: Boolean,
    val reason: String
)

data class ProcessResult(
    val record: PhotoRecord,
    val success: Boolean,
    val restored: Boolean,
    val failure: Boolean,
    val reason: String,
    val backupPath: String?,
    val exifVerification: String,
    val mediaStoreVerification: String,
    val restoreVerification: String
)
