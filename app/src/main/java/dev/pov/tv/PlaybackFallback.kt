package dev.pov.tv

import android.content.Context

/**
 * Settings → Playback → Automatic fallback: when a link fails (or, with a start timeout, takes too long to show its
 * first picture) the next link in the list plays by itself, in the viewer's own order. Links that failed are
 * remembered until the app closes, so they aren't tried again.
 */
internal object PlaybackFallback {
    /** Start timeout choices in seconds; 0 = Off (the default). */
    val TIMEOUTS = listOf(0, 5, 10, 15, 30)
    private const val AUTO = "auto_fallback"
    private const val TIMEOUT = "start_timeout_s"

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("pov_playback", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(AUTO, true)
    fun setEnabled(context: Context, on: Boolean) { preferences(context).edit().putBoolean(AUTO, on).apply() }

    fun timeoutSeconds(context: Context): Int = preferences(context).getInt(TIMEOUT, 0).takeIf { it in TIMEOUTS } ?: 0
    fun setTimeoutSeconds(context: Context, seconds: Int) { preferences(context).edit().putInt(TIMEOUT, seconds).apply() }

    fun timeoutLabel(context: Context, seconds: Int = timeoutSeconds(context)): String =
        if (seconds <= 0) context.getString(R.string.t_off) else context.getString(R.string.t_x_seconds, seconds.toString())

    /** Links (TvChoiceKeys.source) that failed this app session. */
    val failed: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
}
