package local.capturetime.duplicate

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
    val reason: String
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
    val failures: List<String>
)

data class MediaDetails(
    val kind: MediaKind,
    val width: Int,
    val height: Int,
    val durationMillis: Long = 0
)
