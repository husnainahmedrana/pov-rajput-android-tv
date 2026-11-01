package dev.pov.tv

import android.content.Context

/** Whether leaving the player keeps the video going in the mini player, or stops it (progress is saved either way). */
internal class MiniPlayerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pov_player", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("mini_player", true)
        set(value) = prefs.edit().putBoolean("mini_player", value).apply()
}
