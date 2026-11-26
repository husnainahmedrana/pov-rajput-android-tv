package dev.pov.tv

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

/** How far ahead the player downloads. The byte budget follows the app's memory so long buffers cannot exhaust it. */
internal enum class ReadAhead(private val english: String, val seconds: Int) {
    STANDARD("Standard · 50 seconds", 50),
    LONG("Long · 2 minutes", 120),
    MAXIMUM("Maximum · 5 minutes", 300);
    val label: String get() = UiText.of(english)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ReadAheadPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_playback", Context.MODE_PRIVATE)
    var mode: ReadAhead
        get() = ReadAhead.entries.firstOrNull { it.name == preferences.getString("read_ahead", null) } ?: ReadAhead.LONG
        set(value) { preferences.edit().putString("read_ahead", value.name).apply() }

    /** [usenet]: the in-app Usenet engine keeps its own read-ahead in memory too, so the player's share is smaller. */
    fun loadControl(usenet: Boolean = false): DefaultLoadControl {
        val read = mode
        val builder = DefaultLoadControl.Builder()
            // A seek lands on the keyframe before its target, which has usually already played. Keeping the last
            // few seconds (from a keyframe) lets short skips play from memory instead of reloading the file.
            .setBackBuffer(BACK_BUFFER_MS, true)
        // Usenet: start (and restart after a stall) with a few seconds more in hand. One slow article in the first
        // seconds is common from far away (700 KB can take 5–10 s on one connection), and with only the usual
        // 2–3 s buffered the picture froze at 0:02–0:03 on nearly every start; the picture now comes about 2 s
        // later but keeps going.
        val startMs = if (usenet) USENET_START_MS else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        val restartMs = if (usenet) USENET_START_MS else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        if (read == ReadAhead.STANDARD) {
            if (!usenet) return builder.build()
            return builder.setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, startMs, restartMs).build()
        }
        val ms = read.seconds * 1000
        return builder
            // Equal min and max keep the buffer topped up instead of waiting for it to drain to 50 seconds.
            .setBufferDurationsMs(ms, ms, startMs, restartMs)
            .setTargetBufferBytes(budgetBytes(Runtime.getRuntime().maxMemory(), read, if (usenet) 3 else 2))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    companion object {
        /** Ten seconds covers the keyframe gap of almost every film encode. */
        const val BACK_BUFFER_MS = 10_000
        /** Buffered before a Usenet video starts or carries on after a stall. */
        const val USENET_START_MS = 5_500
        /**
         * Up to half the heap: about 190 MB on the onn 4K's 384 MB, enough for over a minute of a typical 1080p file.
         * A third ([share] 3) while Usenet streams in the app: its read-ahead, the next volume and the NZB need room too.
         */
        internal fun budgetBytes(maxMemory: Long, read: ReadAhead, share: Int = 2): Int {
            val ceiling = maxMemory / share
            val wanted = read.seconds * 2_500_000L  // about 20 Mbit/s
            return minOf(wanted, ceiling, Int.MAX_VALUE.toLong()).coerceAtLeast(DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE.toLong()).toInt()
        }
    }
}
