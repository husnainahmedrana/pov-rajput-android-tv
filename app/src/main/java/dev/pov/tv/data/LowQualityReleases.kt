package dev.pov.tv.data

/**
 * Release names that mark theatre recordings and other early, poor copies: cam, telesync, telecine, screeners,
 * workprints and "HDRip" (on new films usually a cleaned-up cam or telesync). Matched as whole tags, so words that
 * merely contain them (such as "Scream" or "Catch") are kept.
 */
object LowQualityReleases {
    const val KEY = "sources.hide_low_quality"

    /** On unless the viewer turned it off. */
    fun hidden(store: SecureStore) = store.get(KEY) != "false"

    private const val SEP = "[ ._-]?"
    private val TERMS = listOf(
        "cam", "cam-rip", "hd-cam", "hq-cam", "new-cam",
        "ts", "ts-rip", "hd-ts", "hq-ts", "telesync", "pdvd", "pre-dvd", "pre-dvd-rip",
        "tc", "hd-tc", "telecine",
        "scr", "screener", "dvd-scr", "dvd-screener", "bd-scr", "web-scr",
        "workprint", "hd-rip", "hq-hd-rip",
    )
    private val TAG = Regex(
        "(?i)(?<![a-z0-9])(?:" + TERMS.sortedByDescending { it.length }.joinToString("|") { it.split('-').joinToString(SEP) } + ")(?![a-z0-9])"
    )
    // A ".ts" file extension is a container, not a telesync tag.
    private val EXTENSION = Regex("(?i)\\.(?:mkv|mp4|avi|m2ts|ts|webm)(?![a-z0-9])")

    fun matches(name: String): Boolean = TAG.containsMatchIn(EXTENSION.replace(name, " "))
    fun matches(source: SourceItem): Boolean = matches("${source.title} ${source.detail}".take(4000))
}
