package dev.pov.tv

import android.content.Context
import java.util.Locale

/**
 * How a link search ends and what it starts on its own: the time limit, stopping after enough good links, and the
 * Auto-play mode. Kept on the device; per-add-on answer times are kept beside them for Source providers.
 */
internal class SourceSearchPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pov_source_search", Context.MODE_PRIVATE)
    private val speed = context.applicationContext.getSharedPreferences("pov_source_speed", Context.MODE_PRIVATE)

    /** Seconds to wait for add-ons before the list stops waiting (0 = wait for all). */
    var timeLimitSeconds: Int
        get() = prefs.getInt("time_limit", 8).takeIf { it in TIME_LIMIT_RANGE } ?: 8
        set(value) { require(value in TIME_LIMIT_RANGE); prefs.edit().putInt("time_limit", value).apply() }
    val timeLimitLabel: String get() = if (timeLimitSeconds == 0) L10n.tr(R.string.t_no_limit_2, "No limit") else L10n.tr(R.string.t_x_seconds, "%1\$s seconds", timeLimitSeconds)

    /** Stop waiting once this many good links (play at once, fit this device) are in (0 = off). */
    var stopAfter: Int
        get() = prefs.getInt("stop_after", 0).takeIf { it in STOP_AFTER_RANGE } ?: 0
        set(value) { require(value in STOP_AFTER_RANGE); prefs.edit().putInt("stop_after", value).apply() }
    val stopAfterLabel: String get() = if (stopAfter == 0) L10n.tr(R.string.t_off, "Off") else if (stopAfter == 1) L10n.tr(R.string.t_after_1_good_link, "After 1 good link") else L10n.tr(R.string.t_after_x_good_links, "After %1\$s good links", stopAfter)

    /**
     * Search goal: [UNTIL_TIME] (only the time limit ends it), [UNTIL_LINKS] (once [goodLinksNeeded] good links are in),
     * [UNTIL_BEST] (once Best per quality is full) or [UNTIL_EITHER] (whichever of those two comes first). Before this
     * existed, "Stop early" and the "Keep searching" switch did the same: they carry over.
     */
    fun searchUntil(keepSearching: Boolean): String = prefs.getString("search_until", null)?.takeIf { it in UNTIL_MODES }
        ?: when { stopAfter > 0 -> UNTIL_LINKS; !keepSearching -> UNTIL_BEST; else -> UNTIL_TIME }
    fun setSearchUntil(mode: String) { require(mode in UNTIL_MODES); prefs.edit().putString("search_until", mode).apply() }
    var goodLinksNeeded: Int
        get() = prefs.getInt("good_links_needed", stopAfter.takeIf { it > 0 } ?: 10).coerceIn(GOOD_LINKS_RANGE)
        set(value) { prefs.edit().putInt("good_links_needed", value.coerceIn(GOOD_LINKS_RANGE)).apply() }
    fun untilLabel(mode: String, perQuality: Int): String = when (mode) {
        UNTIL_LINKS -> L10n.tr(R.string.t_x_good_links, "%1\$s good links", goodLinksNeeded)
        UNTIL_BEST -> if (perQuality > 0) L10n.tr(R.string.t_best_per_quality_full, "Best per quality full") else L10n.tr(R.string.t_best_per_quality_full_set_it, "Best per quality full (set it first)")
        UNTIL_EITHER -> L10n.tr(R.string.t_x_good_links_or_best_full, "%1\$s good links or best full", goodLinksNeeded)
        else -> L10n.tr(R.string.t_time_limit_ends, "Time limit ends")
    }

    /**
     * Auto-play: off, the first good link as soon as one is in, the best after [waitSeconds], or the best when the
     * search ends. [legacyOn] is the old "Auto-play first result" switch, which played the top link once all were in.
     */
    fun mode(legacyOn: Boolean): String = prefs.getString("autoplay_mode", null)?.takeIf { it in MODES } ?: if (legacyOn) END else OFF
    fun setMode(value: String) { require(value in MODES); prefs.edit().putString("autoplay_mode", value).apply() }
    var waitSeconds: Int
        get() = prefs.getInt("autoplay_wait", 5).takeIf { it in WAIT_RANGE } ?: 5
        set(value) { require(value in WAIT_RANGE); prefs.edit().putInt("autoplay_wait", value).apply() }
    fun modeLabel(mode: String) = when (mode) { FIRST -> L10n.tr(R.string.t_first_good_link, "First good link"); WAIT -> L10n.tr(R.string.t_best_after_x_s, "Best after %1\$s s", waitSeconds); END -> L10n.tr(R.string.t_best_when_search_ends, "Best when search ends"); else -> L10n.tr(R.string.t_off, "Off") }

    /** An add-on's own time limit in seconds, replacing the max search time for it; 0 = follow the max search time. */
    fun ownLimit(providerId: String): Int = prefs.getInt("limit.$providerId", 0).coerceIn(OWN_LIMIT_RANGE)
    fun setOwnLimit(providerId: String, seconds: Int) { prefs.edit().putInt("limit.$providerId", seconds.coerceIn(OWN_LIMIT_RANGE)).apply() }

    /** An add-on's answer time in ms, or null when it didn't answer in time; kept as a running average. */
    fun record(providerId: String, ms: Long?) {
        val edit = speed.edit()
        if (ms == null) edit.putInt("$providerId.late", speed.getInt("$providerId.late", 0) + 1)
        else {
            val old = speed.getLong("$providerId.avg", -1)
            edit.putLong("$providerId.avg", if (old < 0) ms else (old * 7 + ms * 3) / 10).putInt("$providerId.late", 0)
        }
        edit.apply()
    }
    /** "avg 3.2 s", "avg 7.4 s · late 2×", or "" before its first search. */
    fun speedLabel(providerId: String): String {
        val avg = speed.getLong("$providerId.avg", -1); val late = speed.getInt("$providerId.late", 0)
        return listOfNotNull(avg.takeIf { it >= 0 }?.let { String.format(Locale.US, L10n.tr(R.string.t_avg_1f_s, "avg %.1f s"), it / 1000.0) },
            L10n.tr(R.string.t_late_x_in_a_row, "late %1\$s× in a row", late).takeIf { late > 0 }).joinToString(" · ")
    }

    companion object {
        /** Picked with Up/Down: seconds (0 = no limit), good links (0 = off), and seconds before auto-play. */
        val TIME_LIMIT_RANGE = 0..60
        val STOP_AFTER_RANGE = 0..40
        val WAIT_RANGE = 1..15
        val OWN_LIMIT_RANGE = 0..120
        val GOOD_LINKS_RANGE = 1..40
        const val UNTIL_TIME = "time"; const val UNTIL_LINKS = "links"; const val UNTIL_BEST = "best"; const val UNTIL_EITHER = "either"
        val UNTIL_MODES = listOf(UNTIL_TIME, UNTIL_LINKS, UNTIL_BEST, UNTIL_EITHER)
        const val OFF = "off"; const val FIRST = "first"; const val WAIT = "wait"; const val END = "end"
        val MODES = listOf(OFF, FIRST, WAIT, END)
        /** Seconds the "Playing … in 3" countdown runs before an auto-play starts. */
        const val COUNTDOWN_SECONDS = 3
    }
}
