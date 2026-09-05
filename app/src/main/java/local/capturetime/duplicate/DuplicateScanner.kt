package local.capturetime.duplicate

import android.os.Environment
import local.capturetime.media.MediaStoreGateway
import local.capturetime.security.PathPolicy
import java.io.File
import java.util.Locale

class DuplicateScanner(private val mediaStore: MediaStoreGateway) {
    fun scan(): DuplicateScanResult {
        val roots = resolveRoots(Environment.getExternalStorageDirectory())
        val realFiles = roots.asSequence()
            .flatMap(::walkFiles)
            .filter { supported(it) }
            .distinctBy { it.absolutePath.lowercase(Locale.ROOT) }
            .filter { PathPolicy.isSafeFile(it, roots) }
            .toList()

        // File-system discovery is intentionally completed before consulting MediaStore.
        val media = mediaStore.queryDuplicateDetails(realFiles)
        val assets = realFiles.mapNotNull { file ->
            val details = media[file.absolutePath.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            if (details.width <= 0 || details.height <= 0) return@mapNotNull null
            DuplicateAsset(file, details.kind, details.width, details.height, details.durationMillis, file.length())
        }
        val candidates = DuplicateRules.findCandidates(assets)
        val hashCache = HashMap<String, String>()
        val hashedCandidates = candidates.map { candidate ->
            candidate.copy(
                delete = candidate.delete.withHash(hashCache),
                retained = candidate.retained.withHash(hashCache)
            )
        }
        return DuplicateScanResult(realFiles.size, assets.size, hashedCandidates)
    }

    private fun DuplicateAsset.withHash(cache: MutableMap<String, String>) = copy(
        sha256 = cache.getOrPut(file.absolutePath.lowercase(Locale.ROOT)) { FileVerification.sha256(file) }
    )

    private fun resolveRoots(storage: File): List<File> {
        val children = storage.listFiles().orEmpty()
        val dcim = children.filter { it.isDirectory && it.name.equals("DCIM", true) }
        val pictures = children.filter { it.isDirectory && it.name.equals("Pictures", true) }
        val download = children.firstOrNull { it.isDirectory && it.name.equals("Download", true) }
        val miShare = download?.listFiles()?.filter { it.isDirectory && it.name.equals("MiShare", true) }.orEmpty()
        return (dcim + pictures + miShare).distinctBy {
            runCatching { it.canonicalPath.lowercase(Locale.ROOT) }.getOrDefault(it.absolutePath.lowercase(Locale.ROOT))
        }
    }

    private fun walkFiles(root: File): Sequence<File> = sequence {
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (PathPolicy.isExcluded(current)) continue
            current.listFiles()?.forEach { child ->
                if (PathPolicy.isExcluded(child)) return@forEach
                if (child.isDirectory && runCatching { child.canonicalPath == child.absoluteFile.path }.getOrDefault(false)) {
                    pending.add(child)
                } else if (child.isFile) yield(child)
            }
        }
    }

    private fun supported(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in
        setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "mp4", "mov", "mkv", "3gp", "webm")
}
