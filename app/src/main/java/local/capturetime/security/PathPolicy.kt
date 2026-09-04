package local.capturetime.security

import java.io.File

object PathPolicy {
    private val excludedExact = setOf(".globaltrash", ".thumbnails")

    fun isExcluded(file: File): Boolean = file.invariantSeparatorsPath
        .split('/')
        .any { segment ->
            val lower = segment.lowercase()
            lower in excludedExact || lower.startsWith(".trashed-")
        }

    fun isSafeFile(file: File, roots: List<File>): Boolean {
        if (!file.isFile || isExcluded(file)) return false
        return try {
            val canonical = file.canonicalFile
            if (canonical.path != file.absoluteFile.path) return false
            roots.any { root ->
                val rootPath = root.canonicalFile.toPath()
                canonical.toPath().startsWith(rootPath)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun relativeStoragePath(file: File, storageRoot: File): String? = try {
        val rootPath = storageRoot.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (filePath.startsWith(rootPath)) rootPath.relativize(filePath).toString() else null
    } catch (_: Exception) {
        null
    }
}
