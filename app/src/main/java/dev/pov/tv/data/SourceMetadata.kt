package dev.pov.tv.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Provider-reported metadata only; listing and sorting never resolve or download a stream. */
object SourceMetadata {
    private val resolution = Regex("(?i)(?<![a-z0-9])(4320|2160|1440|1080|720|576|480|360|240)[pi](?![a-z0-9])")
    private val dimensions = Regex("(?i)(?<!\\d)\\d{3,5}\\s*[x×]\\s*(4320|2160|1440|1080|720|576|480|360|240)(?!\\d)")
    private val size = Regex("(?i)(?<![a-z0-9.,+\\-])([0-9]+(?:[.,][0-9]+)*)\\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\\b")

    fun sizeBytes(stream: JSONObject): Long? {
        val hints = stream.optJSONObject("behaviorHints")
        for (value in listOf(hints?.opt("videoSize"), stream.opt("videoSize"), stream.opt("fileSize"), stream.opt("size"))) {
            positiveBytes(value)?.let { return it }
        }
        val text = stream.optString("description").ifBlank { stream.optString("title") }
        // A whole torrent/pack size must not be presented as the selected video's size.
        for (line in text.lineSequence()) {
            if (Regex("(?i)\\b(?:pack|torrent|total|season)\\s+size\\b").containsMatchIn(line)) continue
            val match = size.find(line) ?: continue
            val unit = match.groupValues[2].uppercase(Locale.ROOT)
            val power = when (unit.first()) { 'K' -> 1; 'M' -> 2; 'G' -> 3; 'T' -> 4; else -> 0 }
            val scale = BigDecimal(if (unit.contains('I')) 1024 else 1000).pow(power)
            try {
                val raw = match.groupValues[1]
                val normalized = if (raw.contains('.') || Regex("^\\d{1,3}(,\\d{3})+$").matches(raw)) raw.replace(",", "") else raw.replace(',', '.')
                // Human-readable metadata is already rounded (e.g. 2.42 GiB).
                val bytes = BigDecimal(normalized).multiply(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()
                if (bytes > 0) return bytes
            } catch (_: ArithmeticException) { /* Unknown is preferable to overflow or fabricated bytes. */ }
            catch (_: NumberFormatException) { /* Try another metadata line. */ }
        }
        return null
    }

    private fun positiveBytes(value: Any?): Long? = try {
        if (value !is Number && value !is String) null
        else BigDecimal(value.toString().trim()).longValueExact().takeIf { it > 0 }
    } catch (_: RuntimeException) { null }

    fun qualityHeight(stream: JSONObject): Int? {
        val hints = stream.optJSONObject("behaviorHints")
        val candidates = listOf(hints?.optString("filename").orEmpty(), stream.optString("resolution"), stream.optString("quality"),
            stream.optString("name"), stream.optString("description").ifBlank { stream.optString("title") }.lineSequence().firstOrNull().orEmpty())
        return candidates.firstNotNullOfOrNull(::qualityHeight)
    }

    fun qualityHeight(text: String): Int? {
        resolution.find(text)?.let { return it.groupValues[1].toInt() }
        dimensions.find(text)?.let { return it.groupValues[1].toInt() }
        if (Regex("(?i)(?<![a-z0-9])8k(?![a-z0-9])").containsMatchIn(text)) return 4320
        if (Regex("(?i)(?<![a-z0-9])(?:4k|uhd)(?![a-z0-9])").containsMatchIn(text)) return 2160
        if (Regex("(?i)(?<![a-z0-9])(?:fhd|full[ ._-]?hd)(?![a-z0-9])").containsMatchIn(text)) return 1080
        return text.trim().toIntOrNull()?.takeIf { it in setOf(4320, 2160, 1440, 1080, 720, 576, 480, 360, 240) }
    }

    fun qualityLabel(height: Int?): String = when (height?.takeIf { it > 0 }) { null -> "Quality unavailable"; 4320 -> "8K"; 2160 -> "4K"; else -> "${height}p" }
    fun sizeLabel(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "Size unavailable"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble(); var index = 0
        while (value >= 1000 && index < units.lastIndex) { value /= 1000; index++ }
        return if (index == 0) "$bytes B" else String.format(Locale.getDefault(), "%.2f %s", value, units[index])
    }
}

/**
 * How links are ordered: [priority] says which comes first (language, quality, size, in any order), and each keeps its
 * own direction. The language step only applies when a language rank is given (a preferred language is set).
 */
data class SourceSortOrder(val qualityDescending: Boolean = true, val sizeAscending: Boolean = true,
                           val priority: List<String> = DEFAULT_PRIORITY, val libraryFirst: Boolean = true,
                           /** The Preferred row (Link sorting): lower ranks first; null when the row is off or has no rules. */
                           val preferredRank: ((SourceItem) -> Int)? = null) {
    fun sorted(sources: List<SourceItem>, languageRank: ((SourceItem) -> Int)? = null): List<SourceItem> = sources.sortedWith { left, right ->
        var result = 0
        for (key in priority) {
            result = when (key) {
                // Links already in a library (AIOStreams, StreamNZB, AltMount) before the rest, when switched on.
                LIBRARY -> if (!libraryFirst) 0 else right.inLibrary.compareTo(left.inLibrary)
                LANGUAGE -> if (languageRank == null) 0 else languageRank(left).compareTo(languageRank(right))
                PREFERRED -> preferredRank?.let { rank -> rank(left).compareTo(rank(right)) } ?: 0
                QUALITY -> compareKnown(left.qualityHeight?.takeIf { it > 0 }, right.qualityHeight?.takeIf { it > 0 }, !qualityDescending)
                SIZE -> compareKnown(left.sizeBytes?.takeIf { it > 0 }, right.sizeBytes?.takeIf { it > 0 }, sizeAscending)
                else -> 0
            }
            if (result != 0) break
        }
        result
    }

    companion object {
        const val LIBRARY = "library"; const val LANGUAGE = "language"; const val QUALITY = "quality"; const val SIZE = "size"; const val PREFERRED = "preferred"
        val DEFAULT_PRIORITY = listOf(LIBRARY, LANGUAGE, QUALITY, PREFERRED, SIZE)
        /**
         * A saved order, repaired: each key once, unknown keys dropped, missing ones added at the end. Library joined
         * the order later; library links always came first before, so an order saved without it gets it on top.
         */
        fun priority(saved: String?): List<String> {
            val keys = saved.orEmpty().split(',').map(String::trim).filter { it in DEFAULT_PRIORITY }.distinct()
                .let { if (it.isNotEmpty() && LIBRARY !in it) listOf(LIBRARY) + it else it }
                // Preferred joined later still: an older order gets it just before file size (within a quality).
                .let { if (PREFERRED !in it && SIZE in it) it.toMutableList().apply { add(indexOf(SIZE), PREFERRED) } else it }
            return keys + DEFAULT_PRIORITY.filterNot { it in keys }
        }
    }
    private fun <T : Comparable<T>> compareKnown(left: T?, right: T?, ascending: Boolean): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        ascending -> left.compareTo(right)
        else -> right.compareTo(left)
    }
}
