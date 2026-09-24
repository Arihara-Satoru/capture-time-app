package local.capturetime.duplicate

import java.util.Locale
import kotlin.math.abs
import java.io.File

object DuplicateRules {
    private val safeHexCopy = Regex("^((?:IMG|MVIMG)_\\d{8}_\\d{6})_([0-9A-Fa-f]{6})$", RegexOption.IGNORE_CASE)
    private val safeVideoHexCopy = Regex("^(VID_\\d{8}_\\d{6})_([0-9A-Fa-f]{6})$", RegexOption.IGNORE_CASE)
    private val numericCopy = Regex("^(.+)_([0-9]{13})$")
    private val hexCopy = Regex("^(.+)_([0-9A-Fa-f]{6})$")
    private val bracketCopy = Regex("^(.+?)\\s*\\(([0-9]+)\\)$")

    fun findCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        val result = linkedMapOf<String, DuplicateCandidate>()
        assets.groupBy { directoryKey(it) }.values.forEach { directory ->
            findImageCandidates(directory.filter { it.kind == MediaKind.IMAGE }).forEach {
                result.putIfAbsent(it.delete.file.absolutePath, it)
            }
            findVideoCandidates(directory.filter { it.kind == MediaKind.VIDEO }).forEach {
                result.putIfAbsent(it.delete.file.absolutePath, it)
            }
        }
        return result.values.sortedBy { it.delete.file.absolutePath.lowercase(Locale.ROOT) }
    }

    fun hasSelectionConflict(candidates: List<DuplicateCandidate>): Boolean {
        val deletePaths = candidates.map { normalizePath(it.delete.file.absolutePath) }
        if (deletePaths.toSet().size != deletePaths.size) return true
        return candidates.any { normalizePath(it.retained.file.absolutePath) in deletePaths }
    }

    fun hasMatchingContent(candidate: DuplicateCandidate): Boolean =
        candidate.delete.sha256.isNotBlank() && candidate.delete.sha256 == candidate.retained.sha256

    fun isEligibleCandidate(candidate: DuplicateCandidate): Boolean =
        hasMatchingContent(candidate) ||
            (candidate.matchedByNameRule && isNameRulePair(candidate))

    private fun findImageCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        val prefixCandidates = findUnderscoreCandidates(assets)
        val prefixPairs = prefixCandidates.mapTo(hashSetOf<Set<String>>()) {
            setOf(it.delete.file.absolutePath, it.retained.file.absolutePath)
        }
        val (suffixCandidates, suffixGroupPaths) = findNumericHexCandidates(assets)
        val existingCandidates = assets.groupBy { extension(it) }.values.flatMap { sameExtension ->
            val byStem = sameExtension.associateBy { stem(it).lowercase(Locale.ROOT) }
            val grouped = linkedMapOf<String, MutableList<DuplicateAsset>>()
            sameExtension.forEach { asset ->
                val copy = copyName(stem(asset))
                val base = when (copy?.type) {
                    CopyType.HEX -> copy.base
                    CopyType.NUMERIC -> copy.base.takeIf { base ->
                        byStem.containsKey(base.lowercase(Locale.ROOT)) || sameExtension.any {
                            val other = copyName(stem(it))
                            other?.type == CopyType.HEX && (
                                other.base.equals(base, true) ||
                                    other.base.equals(base.substringBeforeLast('_'), true)
                                )
                        }
                    }
                    CopyType.BRACKET -> copy.base.takeIf { byStem.containsKey(it.lowercase(Locale.ROOT)) }
                    null -> null
                } ?: return@forEach
                // A numeric-only copy has no safe base by itself; it may join the
                // timestamped hex-copy group only when both variants coexist.
                val copyName = copy ?: return@forEach
                val groupKey = if (copyName.type == CopyType.NUMERIC) {
                    sameExtension.firstOrNull { other ->
                        val otherCopy = copyName(stem(other))
                        otherCopy?.type == CopyType.HEX && (
                            otherCopy.base.equals(base, true) ||
                                otherCopy.base.equals(base.substringBeforeLast('_'), true)
                            )
                    }?.let { copyName(stem(it))?.base } ?: base
                } else base
                grouped.getOrPut(groupKey.lowercase(Locale.ROOT)) { mutableListOf() } += asset
                byStem[base.lowercase(Locale.ROOT)]?.let { original ->
                    if (original !in grouped.getValue(groupKey.lowercase(Locale.ROOT))) grouped.getValue(groupKey.lowercase(Locale.ROOT)) += original
                }
            }
            grouped.values.flatMap(::compareImageGroup)
                .filterNot {
                    setOf(it.delete.file.absolutePath, it.retained.file.absolutePath) in prefixPairs ||
                        (it.delete.file.absolutePath in suffixGroupPaths &&
                            it.retained.file.absolutePath in suffixGroupPaths)
                }
        }
        return existingCandidates + prefixCandidates + suffixCandidates
    }

    private fun findUnderscoreCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        val byStem = assets.groupBy { stem(it).lowercase(Locale.ROOT) }
        return assets.mapNotNull { copy ->
            val copyStem = stem(copy)
            val underscore = copyStem.indexOf('_')
            if (underscore <= 0 || underscore == copyStem.lastIndex) return@mapNotNull null

            val prefix = copyStem.substring(0, underscore)
            val matches = byStem[prefix.lowercase(Locale.ROOT)].orEmpty()
                .filter { it.file.absolutePath != copy.file.absolutePath }
            val original = matches.firstOrNull { stem(it) == prefix && extension(it) == extension(copy) }
                ?: matches.filter { extension(it) == extension(copy) }.singleOrNull()
                ?: matches.singleOrNull()
                ?: return@mapNotNull null
            val deleteCopy = when {
                copy.pixels != original.pixels -> copy.pixels < original.pixels
                copy.size != original.size -> copy.size < original.size
                else -> true
            }
            val (delete, retained) = if (deleteCopy) copy to original else original to copy
            DuplicateCandidate(
                delete = delete,
                retained = retained,
                reason = "文件名前缀“$prefix”与同目录图片匹配；内容可能不同，默认保留像素较多或同分辨率下较大的文件，请比对后确认",
                matchedByNameRule = true
            )
        }
    }

    private fun findNumericHexCandidates(assets: List<DuplicateAsset>): Pair<List<DuplicateCandidate>, Set<String>> {
        val baseNames = assets.mapTo(hashSetOf()) { stem(it).lowercase(Locale.ROOT) }
        val groups = linkedMapOf<String, MutableList<Pair<DuplicateAsset, Boolean>>>()
        assets.forEach { asset ->
            val numeric = numericCopy.matchEntire(stem(asset))
            val hex = hexCopy.matchEntire(stem(asset))
            val (base, isHex) = when {
                numeric != null -> numeric.groupValues[1] to false
                hex != null -> hex.groupValues[1] to true
                else -> return@forEach
            }
            groups.getOrPut(base.lowercase(Locale.ROOT)) { mutableListOf() }.add(asset to isHex)
        }

        val candidates = mutableListOf<DuplicateCandidate>()
        val groupPaths = hashSetOf<String>()
        groups.forEach { (base, variants) ->
            val numeric = variants.filterNot { it.second }
            val hex = variants.filter { it.second }
            if (base in baseNames || numeric.size != 1 || hex.size != 1) return@forEach
            val pair = listOf(numeric.single().first, hex.single().first)
            groupPaths += pair.map { it.file.absolutePath }
            val retained = variants.maxWithOrNull(
                compareBy<Pair<DuplicateAsset, Boolean>> { it.first.pixels }
                    .thenBy { it.first.size }
                    .thenBy { if (it.second) 1 else 0 }
            )?.first ?: return@forEach
            val delete = pair.single { it.file.absolutePath != retained.file.absolutePath }
            candidates += DuplicateCandidate(
                delete = delete,
                retained = retained,
                reason = "同目录 13 位数字后缀与 6 位十六进制后缀共享基名；内容可能不同，请比对后确认",
                matchedByNameRule = true
            )
        }
        return candidates to groupPaths
    }

    private fun isNameRulePair(candidate: DuplicateCandidate): Boolean {
        if (candidate.delete.kind != MediaKind.IMAGE || candidate.retained.kind != MediaKind.IMAGE) return false
        if (directoryKey(candidate.delete) != directoryKey(candidate.retained)) return false
        return isPrefixPair(candidate.delete, candidate.retained) ||
            isPrefixPair(candidate.retained, candidate.delete) ||
            isNumericHexPair(candidate.delete, candidate.retained) ||
            isNumericHexPair(candidate.retained, candidate.delete)
    }

    private fun isPrefixPair(copy: DuplicateAsset, original: DuplicateAsset): Boolean {
        val copyStem = stem(copy)
        val underscore = copyStem.indexOf('_')
        return underscore > 0 && underscore < copyStem.lastIndex &&
            copyStem.substring(0, underscore).equals(stem(original), ignoreCase = true)
    }

    private fun isNumericHexPair(numeric: DuplicateAsset, hex: DuplicateAsset): Boolean {
        val numericBase = numericCopy.matchEntire(stem(numeric))?.groupValues?.get(1) ?: return false
        val hexBase = hexCopy.matchEntire(stem(hex))?.groupValues?.get(1) ?: return false
        return numericBase.equals(hexBase, ignoreCase = true)
    }

    private fun compareImageGroup(group: List<DuplicateAsset>): List<DuplicateCandidate> {
        val candidates = linkedMapOf<String, DuplicateCandidate>()
        group.forEach { lower ->
            val higher = group.filter { it !== lower && aspectDifference(it, lower) < 0.003 && it.pixels >= lower.pixels * 1.05 }
                .maxWithOrNull(compareBy<DuplicateAsset> { it.pixels }.thenBy { it.size })
            if (higher != null) {
                candidates[lower.file.absolutePath] = DuplicateCandidate(lower, higher, "已确认原名/副本关系，保留高分辨率版本")
            }
        }
        group.groupBy { it.width to it.height }.values.forEach { sameResolution ->
            val retained = sameResolution.maxWithOrNull(
                compareBy<DuplicateAsset> { it.size }.thenBy { if (copyName(stem(it)) == null) 1 else 0 }
            ) ?: return@forEach
            sameResolution.filter { it !== retained && it.size <= retained.size }.forEach { smaller ->
                candidates.putIfAbsent(
                    smaller.file.absolutePath,
                    DuplicateCandidate(smaller, retained, "同扩展名、同分辨率，最终仅保留 SHA-256 完全一致的副本")
                )
            }
        }
        return candidates.values.toList()
    }

    private fun findVideoCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        val byStemAndExtension = assets.associateBy { stem(it).lowercase(Locale.ROOT) to extension(it) }
        return assets.mapNotNull { copyAsset ->
            val match = safeVideoHexCopy.matchEntire(stem(copyAsset)) ?: return@mapNotNull null
            val original = byStemAndExtension[match.groupValues[1].lowercase(Locale.ROOT) to extension(copyAsset)] ?: return@mapNotNull null
            if (copyAsset.durationMillis > 0 && original.durationMillis > 0 &&
                copyAsset.width == original.width && copyAsset.height == original.height &&
                copyAsset.durationMillis == original.durationMillis && copyAsset.size == original.size
            ) DuplicateCandidate(copyAsset, original, "视频分辨率、时长和实际字节数完全一致，保留无后缀原名") else null
        }
    }

    private fun copyName(stem: String): CopyName? {
        safeHexCopy.matchEntire(stem)?.let { return CopyName(it.groupValues[1], CopyType.HEX) }
        numericCopy.matchEntire(stem)?.let { return CopyName(it.groupValues[1], CopyType.NUMERIC) }
        bracketCopy.matchEntire(stem)?.let { return CopyName(it.groupValues[1], CopyType.BRACKET) }
        return null
    }

    private fun aspectDifference(a: DuplicateAsset, b: DuplicateAsset): Double =
        abs(a.width.toDouble() / a.height - b.width.toDouble() / b.height)

    private fun directoryKey(asset: DuplicateAsset) = normalizePath(asset.file.parentFile?.absolutePath.orEmpty())
    private fun stem(asset: DuplicateAsset) = asset.file.nameWithoutExtension
    private fun extension(asset: DuplicateAsset) = asset.file.extension.lowercase(Locale.ROOT)

    private fun normalizePath(path: String): String {
        val slashPath = path.replace('\\', '/').let { value ->
            if (value.length >= 2 && value[0].isLetter() && value[1] == ':') value.substring(2) else value
        }
        val primaryPath = when {
            slashPath.equals("/sdcard", ignoreCase = true) -> "/storage/emulated/0"
            slashPath.startsWith("/sdcard/", ignoreCase = true) ->
                "/storage/emulated/0/" + slashPath.substring("/sdcard/".length)
            slashPath.equals("/storage/self/primary", ignoreCase = true) -> "/storage/emulated/0"
            slashPath.startsWith("/storage/self/primary/", ignoreCase = true) ->
                "/storage/emulated/0/" + slashPath.substring("/storage/self/primary/".length)
            else -> slashPath
        }
        val primary = File(primaryPath)
        return if (primary.exists()) {
            runCatching { primary.canonicalPath.replace('\\', '/').lowercase(Locale.ROOT) }
                .getOrDefault(primaryPath.lowercase(Locale.ROOT))
        } else {
            primaryPath.lowercase(Locale.ROOT)
        }
    }

    private data class CopyName(val base: String, val type: CopyType)
    private enum class CopyType { HEX, NUMERIC, BRACKET }
}
