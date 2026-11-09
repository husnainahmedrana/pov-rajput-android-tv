package dev.pov.tv.data

import java.util.IdentityHashMap
import java.util.Locale

/** Language claims read from release names and provider text; nothing is probed until the viewer plays a link. */
object SourceLanguages {
    data class Detected(val audio: Set<String>, val subtitles: Set<String>, val multiAudio: Boolean)

    // Extra release-name spellings. Two-letter codes are too ambiguous on their own and only appear with a region.
    private val AUDIO_WORDS = mapOf(
        "en" to listOf("eng", "🇬🇧", "🇺🇸"),
        "pt" to listOf("por", "portugues", "brazilian", "pt[ ._-]?br", "pt[ ._-]?pt", "dublado", "dub[ ._-]?pt", "🇧🇷", "🇵🇹"),
        "es" to listOf("spa", "esp", "espanol", "castellano", "latino", "es[ ._-]?mx", "🇪🇸", "🇲🇽"),
        "fr" to listOf("fre", "fra", "vff", "vfq", "vfi", "vf2", "truefrench", "🇫🇷"),
        "de" to listOf("ger", "deu", "🇩🇪"),
        "it" to listOf("ita", "🇮🇹"),
        "hi" to listOf("hin", "🇮🇳"),
        "ur" to listOf("urd", "🇵🇰"),
        "ar" to listOf("ara", "🇸🇦", "🇪🇬", "🇦🇪"),
        "ru" to listOf("rus", "🇷🇺"),
        "ja" to listOf("jpn", "jap", "🇯🇵"),
        "ko" to listOf("kor", "🇰🇷"),
        "zh" to listOf("chi", "zho", "chs", "cht", "mandarin", "cantonese", "🇨🇳", "🇹🇼", "🇭🇰"),
        "tr" to listOf("tur", "🇹🇷"),
        "pl" to listOf("pol", "pldub", "🇵🇱"),
        "nl" to listOf("dut", "nld", "🇳🇱"),
        "ta" to emptyList(), "te" to emptyList(), "ml" to emptyList(), "bn" to emptyList(),
    )
    private val SUBTITLE_WORDS = mapOf("pt" to listOf("legendado", "legendas?"), "fr" to listOf("vostfr"), "es" to listOf("subtitulado"),
        // "ESub" / "E-Subs" (Indian releases): English subtitles.
        "en" to listOf("e[ ._-]?subs?"))
    private const val EDGE_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val EDGE_AFTER = "(?![\\p{L}\\p{N}])"
    // Providers such as Meteor print one line per kind: "🌐 🇮🇳" for audio and "💬 🇬🇧 🇮🇳" for subtitles.
    private val segments = Regex("\\n| · | \\| ")
    private val subtitleLine = Regex("(?i)^\\s*(?:💬|📝|sub(?:title)?s?\\s*:)")
    // StremThru labels audio 🎙️ when it also prints a 💬 subtitle line.
    private val audioLine = Regex("^\\s*(?:🌐|🌎|🌍|🗣|🔊|🎙)")
    private val subtitleBefore = Regex("(?i)(?:sub(?:title)?s?|💬|📝)[\\s:._\\-\\[(]{0,3}$")
    private val subtitleAfter = Regex("(?i)^[\\s._\\-\\])]{0,3}(?:sub(?:title)?s?|subbed|legendado)$EDGE_AFTER")
    private val multi = Regex("(?i)$EDGE_BEFORE(?:multi|dual)(?:[ ._-]?audio)?$EDGE_AFTER(?![ ._-]?sub)")
    private val vostfr = Regex("(?i)${EDGE_BEFORE}vostfr$EDGE_AFTER")
    /**
     * What may sit between two subtitle languages of one list, as in Torrentio's "Sub Rus Ukr Eng", "Sub (Rus Eng Ukr)"
     * or Comet's "sub ita.eng": separators and short codes this scan doesn't know (Ukr). A longer word such as
     * "Original" or "Multi" ends the list, so "Sub Rus Eng + Original Eng" still has English audio.
     */
    private val subtitleListGap = Regex("^(?:[\\s._,/+&()\\[\\]-]|(?<![\\p{L}])[A-Za-z]{2,3}(?![\\p{L}]))*$")
    private val subtitleWords = SUBTITLE_WORDS.mapValues { (_, words) -> Regex("(?i)$EDGE_BEFORE(?:${words.joinToString("|")})$EDGE_AFTER") }

    /** One alternation for every language, so each text is scanned once; the matching group names the language. */
    private class Scanner(val codes: List<String>, val regex: Regex)
    private val scanners = HashMap<Set<String>, Scanner>()

    private fun alternatives(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val names = listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale))
            .filter { it.length > 2 && !it.equals(code, true) }.map(Regex::escape)
        val (flags, words) = AUDIO_WORDS[code].orEmpty().partition { it.any(Character::isSurrogate) }
        val textual = (names + words).distinct().joinToString("|")
        return listOfNotNull(textual.takeIf { it.isNotEmpty() }?.let { "$EDGE_BEFORE(?:$it)$EDGE_AFTER" },
            flags.joinToString("|").takeIf { it.isNotEmpty() }).joinToString("|")
    }

    private fun scanner(extra: Collection<String>): Scanner {
        val codes = (AUDIO_WORDS.keys + extra).filter { it.isNotEmpty() && it != "off" }.toSet()
        return synchronized(scanners) {
            scanners.getOrPut(codes) {
                val ordered = codes.toList().filter { alternatives(it).isNotEmpty() }
                Scanner(ordered, Regex("(?i)" + ordered.mapIndexed { index, code -> "(?<l$index>${alternatives(code)})" }.joinToString("|")))
            }
        }
    }

    /**
     * Torrentio's flag line names every language in the file, subtitles too ("🇬🇧 / 🇯🇵 / 🇷🇺 / … 27 flags"), and
     * AIOStreams and Comet repeat such lists on their 🌎 line. A list this long is the original audio (its first
     * language) followed by subtitle tracks; short lists ("🇬🇧 / 🇪🇸") are dubs. Meteor's 🌐 line, which comes
     * with its own 💬 subtitle line, is real audio however long.
     */
    private const val LONG_LANGUAGE_LIST = 5
    /** Only flags and separators (Torrentio's unlabelled language line). */
    private fun flagsOnly(segment: String): Boolean {
        var index = 0
        while (index < segment.length) {
            val point = segment.codePointAt(index)
            if (point !in 0x1F1E6..0x1F1FF && !Character.isWhitespace(point) && point != '/'.code && point != ','.code && point != '|'.code) return false
            index += Character.charCount(point)
        }
        return true
    }

    fun detect(text: String, extra: Collection<String> = emptyList()): Detected {
        val sample = text.take(4000)
        val scan = scanner(extra)
        val audio = linkedSetOf<String>(); val subtitles = linkedSetOf<String>()
        val labelledSubtitles = sample.split(segments).any { subtitleLine.containsMatchIn(it) }
        for (segment in sample.split(segments)) {
            val kind = when { subtitleLine.containsMatchIn(segment) -> 1; audioLine.containsMatchIn(segment) -> 2; else -> 0 }
            val matches = scan.regex.findAll(segment).toList()
            val longList = matches.size >= LONG_LANGUAGE_LIST &&
                ((kind == 0 && flagsOnly(segment)) || (kind == 2 && !labelledSubtitles))
            var listEnd = -1
            for (match in matches) {
                val code = scan.codes[scan.codes.indices.first { match.groups["l$it"] != null }]
                val subtitle = when {
                    kind == 1 -> true
                    longList -> match !== matches.first()
                    kind == 2 -> false
                    else -> subtitleBefore.containsMatchIn(segment.substring(maxOf(0, match.range.first - 12), match.range.first)) ||
                        subtitleAfter.containsMatchIn(segment.substring(match.range.last + 1, minOf(segment.length, match.range.last + 13))) ||
                        (listEnd >= 0 && subtitleListGap.matches(segment.substring(listEnd, match.range.first)))
                }
                listEnd = if (subtitle && kind == 0) match.range.last + 1 else -1
                if (subtitle) subtitles += code else audio += code
            }
        }
        subtitleWords.forEach { (code, regex) -> if (regex.containsMatchIn(sample)) subtitles += code }
        // "VOSTFR" releases keep the original audio; French belongs to the subtitles only.
        if (vostfr.containsMatchIn(sample)) audio -= "fr"
        return Detected(audio, subtitles, multi.containsMatchIn(sample))
    }
}

/**
 * Puts links in the viewer's audio language first, then untagged links when the title's original language matches,
 * then multi-audio, other untagged and other-language links. Links with subtitles in the preferred language lead
 * within each group, and quality and size sort inside that.
 */
data class SourceLanguageOrder(val audio: String, val subtitle: String, val fallback: String = "", val second: String = "") {
    private val subtitleCode = subtitle.takeUnless { it == "off" }.orEmpty()
    val active: Boolean get() = audio.isNotEmpty() || subtitleCode.isNotEmpty()
    private val cache = IdentityHashMap<SourceItem, SourceLanguages.Detected>()

    fun detect(source: SourceItem): SourceLanguages.Detected = synchronized(cache) {
        cache.getOrPut(source) { SourceLanguages.detect("${source.title} · ${source.detail}", listOf(audio, subtitleCode, fallback, second)) }
    }

    /** True only for an explicit tag; untagged links never get a language badge. */
    fun audioMatch(source: SourceItem): Boolean = audio.isNotEmpty() && audio in detect(source).audio
    fun multiAudio(source: SourceItem): Boolean = audio.isNotEmpty() && !audioMatch(source) && detect(source).multiAudio
    fun subtitleMatch(source: SourceItem): Boolean = subtitleCode.isNotEmpty() && subtitleCode in detect(source).subtitles

    /** Untagged releases are usually the original version; the language most single-audio links carry stands in for it. */
    fun originalLanguage(sources: List<SourceItem>): String = sources.mapNotNull { detect(it).audio.singleOrNull() }
        .groupingBy { it }.eachCount().let { counts ->
            val top = counts.values.maxOrNull() ?: return "en"
            // A tie is no evidence of a foreign original; English is the usual one.
            counts.filterValues { it == top }.keys.let { if ("en" in it) "en" else it.first() }
        }

    private fun audioGroup(found: SourceLanguages.Detected, original: String): Int = when {
        audio.isEmpty() || audio in found.audio -> 0
        found.audio.isEmpty() && !found.multiAudio && original == audio -> 1
        found.multiAudio -> 2
        // The second audio language (tagged, or an untagged original in it) comes before English and the rest.
        second.isNotEmpty() && second != audio && (second in found.audio || (found.audio.isEmpty() && original == second)) -> 3
        // With a fallback set, links in it (tagged, or untagged originals in it) come before other languages.
        fallback.isNotEmpty() && (fallback in found.audio || (found.audio.isEmpty() && original == fallback)) -> 4
        found.audio.isEmpty() -> 5
        else -> 6
    }

    /** Sorts by [base], whose priority says where the language step sits (first by default). */
    fun sorted(sources: List<SourceItem>, base: SourceSortOrder): List<SourceItem> {
        if (!active) return base.sorted(sources)
        val original = originalLanguage(base.sorted(sources))
        val ranks = IdentityHashMap<SourceItem, Int>()
        fun rank(source: SourceItem): Int = ranks.getOrPut(source) {
            val found = detect(source)
            val subtitles = when {
                subtitleCode.isEmpty() || subtitleCode in found.subtitles -> 0
                fallback.isNotEmpty() && fallback in found.subtitles -> 1
                else -> 2
            }
            audioGroup(found, original) * 3 + subtitles
        }
        return base.sorted(sources, ::rank)
    }
}
