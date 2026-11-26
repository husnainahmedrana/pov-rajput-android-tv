package dev.pov.tv

import android.content.Context

/**
 * Which engine plays videos inside the app. ExoPlayer (the full player: seek previews, skip, Streams panel, Dolby
 * Vision handling) is used first; mpv plays nearly every format and container, with simpler controls.
 * Auto: ExoPlayer, and mpv by itself when ExoPlayer can't play a file (an unsupported format, a decoder failure).
 */
internal object PlayerEngine {
    const val AUTO = "auto"
    const val EXO = "exo"
    const val MPV = "mpv"
    val CHOICES = listOf(AUTO, EXO, MPV)
    private const val KEY = "player_engine"

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("pov_playback", Context.MODE_PRIVATE)
    fun get(context: Context): String = preferences(context).getString(KEY, null)?.takeIf { it in CHOICES } ?: AUTO
    fun set(context: Context, value: String) { preferences(context).edit().putString(KEY, value).apply() }

    fun label(context: Context, value: String = get(context)): String = when (value) {
        EXO -> context.getString(R.string.t_engine_exoplayer)
        MPV -> context.getString(R.string.t_engine_mpv)
        else -> context.getString(R.string.t_engine_auto)
    }
}
