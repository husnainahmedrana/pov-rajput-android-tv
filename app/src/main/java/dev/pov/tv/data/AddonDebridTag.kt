package dev.pov.tv.data

import java.util.Locale

/**
 * The debrid service behind a ready-made link from an add-on that already holds the viewer's debrid key (Torrentio,
 * Comet, MediaFusion, StremThru, Debridio…), read from the tag those add-ons put in the link's name:
 * "[RD+]", "[TB⚡]", "[TB download]", "⚡ TB", "⏳ RD", or the service's full name. [cached] is true for "+"/"⚡",
 * false for "download"/"⬇"/"⏳" (the add-on has to fetch it first), null when the tag doesn't say.
 */
object AddonDebridTag {
    data class Tag(val service: String, val cached: Boolean?)

    private val SHORT = mapOf("RD" to "realdebrid", "TB" to "torbox", "AD" to "alldebrid", "PM" to "premiumize",
        "DL" to "debridlink", "OC" to "offcloud")
    private const val CODES = "RD|TB|AD|PM|DL|OC"
    /** "[RD+]", "[TB⚡]", "[RD download]", "[PM ⏳]": a short code at the start of a bracket. */
    private val BRACKET = Regex("\\[\\s*($CODES)\\b([^\\]]{0,20})\\]", RegexOption.IGNORE_CASE)
    /** "⚡ TB", "⏳RD", "⬇️ AD": a status mark right before the code (MediaFusion style). */
    private val MARK_BEFORE = Regex("(⚡|⏳|⬇)\\uFE0F?\\s*($CODES)(?![A-Za-z])")
    /** "TB ⚡", "RD+": the code right before a status mark. */
    private val MARK_AFTER = Regex("(?<![A-Za-z])($CODES)\\s*(\\+|⚡|⏳|⬇)")
    private val FULL = listOf(Regex("(?i)real[ -]?debrid") to "realdebrid", Regex("(?i)torbox") to "torbox",
        Regex("(?i)all[ -]?debrid") to "alldebrid", Regex("(?i)premiumize") to "premiumize",
        Regex("(?i)debrid[ -]?link") to "debridlink", Regex("(?i)offcloud") to "offcloud")

    private fun cachedFrom(mark: String): Boolean? {
        val lower = mark.lowercase(Locale.ROOT)
        return when {
            "+" in mark || "⚡" in mark -> true
            "download" in lower || "⬇" in mark || "⏳" in mark || "uncached" in lower -> false
            else -> null
        }
    }

    /** The tag in a link's [name] (the add-on's short label), or null when it names no debrid service. */
    fun of(name: String): Tag? {
        BRACKET.find(name)?.let { match -> return Tag(SHORT.getValue(match.groupValues[1].uppercase(Locale.ROOT)), cachedFrom(match.groupValues[2])) }
        MARK_BEFORE.find(name)?.let { match -> return Tag(SHORT.getValue(match.groupValues[2]), cachedFrom(match.groupValues[1])) }
        MARK_AFTER.find(name)?.let { match -> return Tag(SHORT.getValue(match.groupValues[1]), cachedFrom(match.groupValues[2])) }
        val service = FULL.firstOrNull { it.first.containsMatchIn(name) }?.second ?: return null
        return Tag(service, when {
            "⚡" in name || "+" in name -> true
            "⏳" in name || "⬇" in name || Regex("(?i)download|uncached").containsMatchIn(name) -> false
            else -> null
        })
    }
}
