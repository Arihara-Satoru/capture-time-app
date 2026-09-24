package local.capturetime.duplicate

import android.net.Uri
import java.io.File

enum class MediaKind { IMAGE, VIDEO }

data class DuplicateAsset(
    val file: File,
    val kind: MediaKind,
    val width: Int,
    val height: Int,
    val durationMillis: Long = 0,
    val size: Long = file.length(),
    val sha256: String = ""
) {
    val pixels: Long get() = width.toLong() * height
}

data class DuplicateCandidate(
    val delete: DuplicateAsset,
    val retained: DuplicateAsset,
    val reason: String,
    val matchedByUnderscorePrefix: Boolean = false
)

data class DuplicateScanResult(
    val realFiles: Int,
    val mediaFiles: Int,
    val candidates: List<DuplicateCandidate>
)

data class DuplicateDeleteResult(
    val sessionDirectory: File,
    val deleted: Int,
    val skipped: Int,
    val failures: List<String>,
    val verified: Int
)

data class PreparedDuplicateDelete(
    val delete: DuplicateAsset,
    val retained: DuplicateAsset,
    val backup: File,
    val mediaUris: List<Uri>
)

enum class DuplicateDeleteConfirmation {
    AWAITING_CONFIRMATION,
    CONFIRMED,
    UNKNOWN
}

data class DuplicateDeletePreparation(
    val sessionDirectory: File,
    val requested: Int,
    val prepared: List<PreparedDuplicateDelete>,
    val failures: List<String>,
    val confirmation: DuplicateDeleteConfirmation = DuplicateDeleteConfirmation.AWAITING_CONFIRMATION
)

data class MediaDetails(
    val kind: MediaKind,
    val width: Int,
    val height: Int,
    val durationMillis: Long = 0
)
