package dev.pov.tv

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

/** How far ahead the player downloads. The byte budget follows the app's memory so long buffers cannot exhaust it. */
internal enum class ReadAhead(val label: String, val seconds: Int) {
    STANDARD("Standard · 50 seconds", 50),
    LONG("Long · 2 minutes", 120),
    MAXIMUM("Maximum · 5 minutes", 300)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ReadAheadPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_playback", Context.MODE_PRIVATE)
    var mode: ReadAhead
        get() = ReadAhead.entries.firstOrNull { it.name == preferences.getString("read_ahead", null) } ?: ReadAhead.LONG
        set(value) { preferences.edit().putString("read_ahead", value.name).apply() }

    fun loadControl(): DefaultLoadControl {
        val read = mode
        val builder = DefaultLoadControl.Builder()
            // A seek lands on the keyframe before its target, which has usually already played. Keeping the last
            // few seconds (from a keyframe) lets short skips play from memory instead of reloading the file.
            .setBackBuffer(BACK_BUFFER_MS, true)
        if (read == ReadAhead.STANDARD) return builder.build()
        val ms = read.seconds * 1000
        return builder
            // Equal min and max keep the buffer topped up instead of waiting for it to drain to 50 seconds.
            .setBufferDurationsMs(ms, ms, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .setTargetBufferBytes(budgetBytes(Runtime.getRuntime().maxMemory(), read))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    companion object {
        /** Ten seconds covers the keyframe gap of almost every film encode. */
        const val BACK_BUFFER_MS = 10_000
        /** Up to half the heap: about 190 MB on the onn 4K's 384 MB, enough for over a minute of a typical 1080p file. */
        internal fun budgetBytes(maxMemory: Long, read: ReadAhead): Int {
            val ceiling = maxMemory / 2
            val wanted = read.seconds * 2_500_000L  // about 20 Mbit/s
            return minOf(wanted, ceiling, Int.MAX_VALUE.toLong()).coerceAtLeast(DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE.toLong()).toInt()
        }
    }
}
