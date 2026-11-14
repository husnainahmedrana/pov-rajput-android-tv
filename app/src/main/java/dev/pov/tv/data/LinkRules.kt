package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONArray
import org.json.JSONObject
import dev.pov.tv.UiText
import java.util.Locale

/**
 * The viewer's link rules: what moves a link up (the Preferred row in Link sorting) and what hides it (Link filters).
 * Everything matches the link's release name only; nothing is downloaded or resolved to apply them.
 */
object LinkRules {
    /** A ready-made switch: an id, its name, a short example of what it matches, and the pattern itself. */
    data class Preset(val id: String, private val name: String, private val sample: String, val pattern: Regex) {
        val label: String get() = UiText.of(name)
        val example: String get() = UiText.of(sample)
    }
    /** One group of trusted release groups (Remux, WEB-DL…), each switched on or off on its own. */
    data class GroupSet(val id: String, private val name: String, val groups: List<String>, val onByDefault: Boolean = true) {
        val label: String get() = UiText.of(name)
    }

    private fun words(vararg values: String) = Regex("(?i)(?<![a-z0-9])(?:${values.joinToString("|")})(?![a-z0-9])")

    val PREFER: List<Preset> = listOf(
        Preset("remux", "Remux / Blu-ray", "REMUX, BluRay, BDRip", words("remux", "bdremux", "blu-?ray", "bdrip", "bd-?rip")),
        Preset("webdl", "WEB-DL over WEBRip", "WEB-DL, WEB", words("web[ ._-]?dl", "webdl")),
        Preset("hevc", "HEVC / x265", "HEVC, x265, H.265", words("hevc", "x265", "h[ .]?265")),
        Preset("tenbit", "10-bit", "10bit, Hi10", words("10[ ._-]?bits?", "hi10p?")),
        Preset("dv", "Dolby Vision", "DV, DoVi", words("dv", "dovi", "dolby[ ._-]?vision")),
        Preset("hdr", "HDR", "HDR10, HDR10+", words("hdr10\\+?", "hdr10plus", "hdr", "hlg")),
        Preset("atmos", "Atmos", "Needs passthrough", words("atmos")),
        Preset("lossless", "Lossless audio", "TrueHD, DTS-HD MA", words("truehd", "true[ ._-]hd", "dts[ ._-]?hd([ ._-]?ma)?", "dts[ ._-]?x", "flac", "l?pcm")),
        Preset("proper", "Proper / Repack", "PROPER, REPACK", words("proper", "repack", "rerip")),
    )
    const val GROUPS = "groups"
    /**
     * Prefer switches this device can't use (set by the app at start): Dolby Vision without a DV decoder, HDR without
     * 10-bit HEVC, Atmos without passthrough. They stay on in Settings but don't move links, so auto-play never
     * favours a file the device can't show or hear as intended.
     */
    @Volatile var unsupportedPrefer: Set<String> = emptySet()

    /** Every group list, trusted and hidden, by id. */
    fun groupSet(id: String): GroupSet? = (GROUP_SETS + HIDE_GROUP_SETS).firstOrNull { it.id == id }

    val GROUP_SETS: List<GroupSet> = listOf(
        GroupSet("remux1", "Remux · top tier", listOf("FraMeSToR", "BLURANiUM", "BMF", "3L", "BiZKiT", "CiNEPHiLES", "PmP", "WiLDCAT", "ZQ"), onByDefault = false),
        GroupSet("remux2", "Remux · second tier", listOf("EPSiLON", "decibeL", "HiFi", "KRaLiMaRKo", "NCmt", "playBD", "SiCFoI", "SURFINBIRD", "TEPES"), onByDefault = false),
        GroupSet("bluray", "Blu-ray encodes", listOf("CtrlHD", "D-Z0N3", "DON", "EbP", "TayTO", "NTb", "HiDt", "SA89", "c0kE", "CRiSC", "VietHD")),
        GroupSet("web1", "WEB-DL · top tier", listOf("FLUX", "NTb", "CMRG", "HONE", "KiNGS", "NTG", "SiC", "APEX", "PAXA", "ABBIE", "GNOME", "CRFW", "TEPES")),
        GroupSet("web2", "WEB-DL · second tier", listOf("playWEB", "SMURF", "dB", "MZABI", "PHOENiX", "monkee", "TOMMY", "XEBEC")),
        GroupSet("hevc", "Small HEVC", listOf("PSA", "Tigole", "QxR", "TAoE", "Joy", "UTR")),
        GroupSet("anime", "Anime", listOf("SubsPlease", "Erai-raws", "VARYG", "ASW", "Judas")),
    )

    val HIDE: List<Preset> = listOf(
        Preset("threed", "Hide 3D", "3D, SBS, Half-OU", words("3d", "h?sbs", "half[ ._-]?(sbs|ou)", "h-?ou")),
        Preset("upscale", "Hide upscales", "Upscaled, AI upscale", words("upscaled?", "ai[ ._-]?upscaled?", "upscale")),
        Preset("hardsub", "Hide hardcoded subs", "HC, HardSub", words("hc", "hardsubs?", "hardsubbed", "hard[ ._-]?coded")),
        Preset("sample", "Hide samples and trailers", "Sample, Trailer, Extras", words("sample", "trailer", "teaser", "extras", "featurette")),
        Preset("dubbed", "Hide dubbed audio", "Dubbed, Dual-dub", words("dubbed", "dub", "dual[ ._-]?dub")),
        Preset("remux", "Hide Remux", "REMUX, BDRemux (very large files)", words("remux", "bdremux")),
        Preset("hdtv", "Hide TV recordings", "HDTV, TVRip, PDTV", words("hdtv", "tvrip", "pdtv", "dsr", "satrip")),
        Preset("av1", "Hide AV1", "AV1 (older TVs can't decode it)", words("av1")),
        Preset("x264", "Hide x264 / AVC", "x264, H.264, AVC (bigger files than HEVC)", words("x264", "h[ .]?264", "avc")),
    )
    const val HIDE_GROUPS = "hidegroups"
    /** Quality groups that can be switched off in Link filters, by the link's known height. */
    val QUALITIES = listOf("4k" to "4K", "1080" to "1080p", "720" to "720p", "sd" to "576p, 480p and lower", "unknown" to "Unknown quality")
    fun qualityKey(height: Int?): String = when {
        height == null || height <= 0 -> "unknown"
        height >= 2160 -> "4k"; height >= 1080 -> "1080"; height >= 720 -> "720"; else -> "sd"
    }
    /** Release groups to hide; the switch is off by default, each list is on once it is switched on. */
    val HIDE_GROUP_SETS: List<GroupSet> = listOf(
        GroupSet("lq", "Low-quality encoders", listOf("YIFY", "YTS", "aXXo", "EVO", "mSD", "nSD", "mHD", "nHD", "d3g", "MeGusta",
            "GalaxyRG", "GalaxyTV", "TGALL", "Pahe", "ShAaNiG", "MkvCage", "HDHub4u", "TamilRockers", "Ganool")),
    )
    /** Not a name pattern: links the device can't play because they're Dolby Vision only (checked in the app). */
    const val HIDE_DV_ONLY = "dvonly"

    private val extension = Regex("(?i)\\.(mkv|mp4|m4v|avi|ts|m2ts|mov|wmv|webm)$")
    private val tagAtEnd = Regex("-([A-Za-z0-9][A-Za-z0-9]{0,24})(?:\\s*[\\[(][^\\])]{1,30}[\\])])*\\s*$")
    private val bracketAtStart = Regex("^\\s*\\[([^\\]]{2,30})]")
    private val lastParenthesis = Regex("\\(([^()]*)\\)\\s*$")

    /**
     * The release group of a link name, e.g. "Movie.2024.1080p.WEB-DL.H.264-FLUX.mkv" → FLUX, "[SubsPlease] Show - 01" →
     * SubsPlease, "Movie (2020) (1080p BluRay x265 Tigole)" → Tigole. Null when the name doesn't end in one.
     */
    fun releaseGroup(name: String): String? {
        val line = name.lineSequence().firstOrNull().orEmpty().trim().replace(extension, "")
        bracketAtStart.find(line)?.let { return it.groupValues[1].trim() }
        tagAtEnd.find(line)?.let { return it.groupValues[1] }
        return lastParenthesis.find(line)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.takeIf { it.length in 2..24 && it.any(Char::isLetter) }
    }

    private val yearToken = Regex("^(19|20)\\d{2}$")
    private val separators = Regex("[\\s._\\-\\[\\](){}+,]+")

    /**
     * True when a movie link names a different year than the movie: "Street.Fighter.1994.1080p" for Street Fighter
     * (2026) is the older film with the same name. Numbers in the title itself (1917, Blade Runner 2049) are skipped,
     * a year either side is allowed (a late-December film is often tagged with the next year), and a name with no
     * year is kept.
     */
    fun otherYear(name: String, title: String, year: Int): Boolean {
        val own = title.lowercase(Locale.ROOT).split(separators).filter { it.isNotBlank() }.toSet()
        val tokens = name.lineSequence().firstOrNull().orEmpty().lowercase(Locale.ROOT).split(separators)
        val release = tokens.firstOrNull { yearToken.matches(it) && it !in own }?.toIntOrNull() ?: return false
        return kotlin.math.abs(release - year) > 1
    }

    /** A whole word or phrase, any case: "PSA" matches "x265-PSA" but not "PSALM". */
    fun wordPattern(word: String) = Regex("(?i)(?<![a-z0-9])${Regex.escape(word.trim())}(?![a-z0-9])")

    /** A viewer's own pattern, or null with the reason when it isn't valid. */
    fun pattern(text: String): Result<Regex?> {
        val clean = text.trim()
        if (clean.length > MAX_PATTERN) return Result.failure(IllegalArgumentException(L10n.tr(R.string.t_keep_the_pattern_under_x_characters, "Keep the pattern under %1\$s characters.", MAX_PATTERN)))
        if (clean.isEmpty()) return Result.success(null)
        return runCatching { Regex(clean, RegexOption.IGNORE_CASE) as Regex? }.recoverCatching { error ->
            throw IllegalArgumentException(L10n.tr(R.string.t_this_pattern_isn_t_valid_x, "This pattern isn't valid: %1\$s", error.message?.lineSequence()?.firstOrNull().orEmpty().take(80)))
        }
    }

    const val MAX_PATTERN = 200
    const val MAX_WORDS = 30

    /** The saved rules, read and written as one JSON object. */
    data class Rules(
        val preferWords: List<String> = emptyList(),
        val preferPresets: Set<String> = emptySet(),
        val groupSets: Set<String> = GROUP_SETS.filter { it.onByDefault }.map { it.id }.toSet(),
        val customGroups: Map<String, List<String>> = emptyMap(),
        val preferPattern: String = "",
        val hidePresets: Set<String> = setOf("sample"),
        val hideWords: List<String> = emptyList(),
        val hidePattern: String = "",
        val hideGroupSets: Set<String> = HIDE_GROUP_SETS.filter { it.onByDefault }.map { it.id }.toSet(),
        /** Movies: hide links naming another year (an older film with the same name). On by default. */
        val hideOtherYears: Boolean = true,
        /** Groups switched off in a list (lower-case), kept in the list but ignored. */
        val disabledGroups: Map<String, Set<String>> = emptyMap(),
        /** Qualities switched off in Link filters (keys of [QUALITIES]). */
        val hiddenQualities: Set<String> = emptySet(),
        /** Switches the viewer changed (preset ids), so a later default change leaves their choice alone. */
        val touched: Set<String> = emptySet(),
    ) {
        /** How many Hide rules are on, for the Link rules summary ([badCopies]: the app's "Hide bad copies" switch). */
        fun hideCount(badCopies: Boolean) = (if (badCopies) 1 else 0) + hidePresets.size + hideWords.size +
            (if (hidePattern.isNotBlank()) 1 else 0) + (if (hideOtherYears) 1 else 0)
        fun hidesQuality(height: Int?) = hiddenQualities.isNotEmpty() && qualityKey(height) in hiddenQualities
        fun groups(set: GroupSet) = customGroups[set.id] ?: set.groups
        fun groupOn(set: GroupSet, group: String) = group.lowercase(Locale.ROOT) !in disabledGroups[set.id].orEmpty()
        fun activeGroups(set: GroupSet) = groups(set).filter { groupOn(set, it) }
        /** Your words only (the first rules in Preferred), for "Your words beat cached links". */
        fun matchesOwnWord(name: String) = preferMatchers.any { it.containsMatchIn(name) }
        private val preferRegex by lazy { pattern(preferPattern).getOrNull() }
        private val hideRegex by lazy { pattern(hidePattern).getOrNull() }
        private val preferMatchers by lazy { preferWords.map(::wordPattern) }
        private val hideMatchers by lazy { hideWords.map(::wordPattern) }
        /** Enabled groups in rank order, lower-cased, best first. */
        private val trusted by lazy {
            if (GROUPS !in preferPresets) emptyList()
            else GROUP_SETS.filter { it.id in groupSets }.flatMap(::activeGroups).map { it.lowercase(Locale.ROOT) }.distinct()
        }
        val preferActive: Boolean get() = preferWords.isNotEmpty() || preferPresets.isNotEmpty() || preferRegex != null
        private val hiddenGroups by lazy {
            if (HIDE_GROUPS !in hidePresets) emptySet()
            else HIDE_GROUP_SETS.filter { it.id in hideGroupSets }.flatMap(::activeGroups).map { it.lowercase(Locale.ROOT) }.toSet()
        }
        val hideActive: Boolean get() = hidePresets.any { it != HIDE_DV_ONLY } || hideWords.isNotEmpty() || hideRegex != null

        /**
         * How strongly a link is preferred: lower is better, [Int.MAX_VALUE] when nothing matches. The viewer's own
         * words come first (in their order), then the switches (trusted groups ranked by their place in the list),
         * then the custom pattern.
         */
        fun rank(name: String): Int {
            preferMatchers.forEachIndexed { index, matcher -> if (matcher.containsMatchIn(name)) return index }
            var base = MAX_WORDS
            for (preset in PREFER) {
                if (preset.id in preferPresets && preset.id !in unsupportedPrefer && preset.pattern.containsMatchIn(name)) return base
                base++
            }
            if (trusted.isNotEmpty()) releaseGroup(name)?.lowercase(Locale.ROOT)?.let { group ->
                trusted.indexOf(group).takeIf { it >= 0 }?.let { return base + it }
            }
            base += 1000
            if (preferRegex?.containsMatchIn(name) == true) return base
            return Int.MAX_VALUE
        }
        fun preferred(name: String) = rank(name) != Int.MAX_VALUE

        /** True when a link name is hidden by a filter switch, a hide word or the hide pattern. */
        fun hides(name: String): Boolean = HIDE.any { it.id in hidePresets && it.pattern.containsMatchIn(name) } ||
            hideMatchers.any { it.containsMatchIn(name) } || hideRegex?.containsMatchIn(name) == true ||
            (hiddenGroups.isNotEmpty() && releaseGroup(name)?.lowercase(Locale.ROOT) in hiddenGroups)

        fun toJson(): String = JSONObject()
            .put("preferWords", JSONArray(preferWords)).put("preferPresets", JSONArray(preferPresets.toList()))
            .put("groupSets", JSONArray(groupSets.toList()))
            .put("customGroups", JSONObject().apply { customGroups.forEach { (id, list) -> put(id, JSONArray(list)) } })
            .put("preferPattern", preferPattern).put("hidePresets", JSONArray(hidePresets.toList()))
            .put("hideWords", JSONArray(hideWords)).put("hidePattern", hidePattern)
            .put("hideGroupSets", JSONArray(hideGroupSets.toList())).put("hideOtherYears", hideOtherYears)
            .put("disabledGroups", JSONObject().apply { disabledGroups.forEach { (id, names) -> put(id, JSONArray(names.toList())) } })
            .put("hiddenQualities", JSONArray(hiddenQualities.toList()))
            .put("touched", JSONArray(touched.toList())).put("v", VERSION).toString()

        companion object {
            /** 2: separate Dolby Vision / HDR and Atmos / lossless switches; samples and trailers hidden by default. */
            const val VERSION = 2
            fun fromJson(raw: String?): Rules {
                if (raw.isNullOrBlank()) return Rules()
                return runCatching {
                    val json = JSONObject(raw)
                    fun list(name: String) = json.optJSONArray(name)?.let { array -> (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank) }
                    val old = json.optInt("v", 1) < 2
                    val touched = list("touched").orEmpty().toSet()
                    val savedPrefer = list("preferPresets").orEmpty().toSet()
                    val savedHide = list("hidePresets").orEmpty().toSet() - "lowres"
                    Rules(
                        preferWords = list("preferWords").orEmpty().take(MAX_WORDS),
                        // The combined "HDR / Dolby Vision" and "Atmos / lossless audio" switches became two each.
                        preferPresets = savedPrefer + (if (old && "hdr" in savedPrefer) setOf("dv") else emptySet()) +
                            (if (old && "lossless" in savedPrefer) setOf("atmos") else emptySet()),
                        touched = touched,
                        groupSets = list("groupSets")?.toSet() ?: Rules().groupSets,
                        customGroups = json.optJSONObject("customGroups")?.let { custom ->
                            custom.keys().asSequence().associateWith { id -> custom.optJSONArray(id)?.let { array -> (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank) }.orEmpty() }
                        }.orEmpty(),
                        preferPattern = json.optString("preferPattern"),
                        // Samples and trailers are hidden by default since version 2, unless the viewer changed that switch.
                        hidePresets = savedHide + (if (old && "hide:sample" !in touched) setOf("sample") else emptySet()),
                        // The older "Hide 720p and lower" switch became the 720p and SD quality switches.
                        hiddenQualities = list("hiddenQualities").orEmpty().toSet() +
                            (if ("lowres" in list("hidePresets").orEmpty()) setOf("720", "sd") else emptySet()),
                        hideWords = list("hideWords").orEmpty().take(MAX_WORDS),
                        hidePattern = json.optString("hidePattern"),
                        hideGroupSets = list("hideGroupSets")?.toSet() ?: Rules().hideGroupSets,
                        hideOtherYears = json.optBoolean("hideOtherYears", true),
                        disabledGroups = json.optJSONObject("disabledGroups")?.let { off ->
                            off.keys().asSequence().associateWith { id -> off.optJSONArray(id)?.let { array -> (0 until array.length()).map { array.optString(it).trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet() }.orEmpty() }
                        }.orEmpty(),
                    )
                }.getOrDefault(Rules())
            }
        }
    }
}
