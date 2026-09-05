package local.capturetime.duplicate

import java.util.Locale
import kotlin.math.abs

object DuplicateRules {
    private val safeHexCopy = Regex("^((?:IMG|MVIMG)_\\d{8}_\\d{6})_([0-9A-Fa-f]{6})$", RegexOption.IGNORE_CASE)
    private val safeVideoHexCopy = Regex("^(VID_\\d{8}_\\d{6})_([0-9A-Fa-f]{6})$", RegexOption.IGNORE_CASE)
    private val numericCopy = Regex("^(.+)_([0-9]{13})$")
    private val bracketCopy = Regex("^(.+?)\\s*\\(([0-9]+)\\)$")

    fun findCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        val result = linkedMapOf<String, DuplicateCandidate>()
        assets.groupBy { directoryKey(it) }.values.forEach { directory ->
            findScreenshotCandidates(directory).forEach { result[it.delete.file.absolutePath] = it }
            findImageCandidates(directory.filter { it.kind == MediaKind.IMAGE }).forEach {
                result.putIfAbsent(it.delete.file.absolutePath, it)
            }
            findVideoCandidates(directory.filter { it.kind == MediaKind.VIDEO }).forEach {
                result.putIfAbsent(it.delete.file.absolutePath, it)
            }
        }
        return result.values.sortedBy { it.delete.file.absolutePath.lowercase(Locale.ROOT) }
    }

    private fun findScreenshotCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        return assets.asSequence()
            .filter { it.kind == MediaKind.IMAGE && stem(it).startsWith("Screenshot_", ignoreCase = true) }
            .groupBy { stem(it).lowercase(Locale.ROOT) }
            .values
            .flatMap { group ->
                val png = group.filter { extension(it) == "png" }.maxByOrNull { it.size } ?: return@flatMap emptyList()
                group.filter { extension(it) in setOf("jpg", "jpeg") }.map {
                    DuplicateCandidate(it, png, "同名 Screenshot 保留 PNG，处理 JPG")
                }
            }
    }

    private fun findImageCandidates(assets: List<DuplicateAsset>): List<DuplicateCandidate> {
        return assets.groupBy { extension(it) }.values.flatMap { sameExtension ->
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
        }
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
            val retained = sameResolution.maxByOrNull { it.size } ?: return@forEach
            sameResolution.filter { it.size < retained.size }.forEach { smaller ->
                candidates.putIfAbsent(
                    smaller.file.absolutePath,
                    DuplicateCandidate(smaller, retained, "同扩展名、同分辨率，保留实际字节数更大的文件")
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
            if (copyAsset.width == original.width && copyAsset.height == original.height &&
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

    private fun directoryKey(asset: DuplicateAsset) = asset.file.parentFile?.absolutePath?.lowercase(Locale.ROOT).orEmpty()
    private fun stem(asset: DuplicateAsset) = asset.file.nameWithoutExtension
    private fun extension(asset: DuplicateAsset) = asset.file.extension.lowercase(Locale.ROOT)

    private data class CopyName(val base: String, val type: CopyType)
    private enum class CopyType { HEX, NUMERIC, BRACKET }
}
