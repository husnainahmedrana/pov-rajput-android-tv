package dev.pov.tv

import android.content.Context

/**
 * Connections per debrid or direct video on this device: 4 (default), 2 or 1. Kept on the device only, outside the
 * account backup, because what works depends on the device and its network.
 */
internal object PlaybackConnections {
    val CHOICES = listOf(4, 2, 1)
    private fun prefs(context: Context) = context.getSharedPreferences("pov_playback_device", Context.MODE_PRIVATE)
    fun count(context: Context) = prefs(context).getInt("connections", 4).takeIf { it in CHOICES } ?: 4
    fun set(context: Context, value: Int) = prefs(context).edit().putInt("connections", value.takeIf { it in CHOICES } ?: 4).apply()
}
