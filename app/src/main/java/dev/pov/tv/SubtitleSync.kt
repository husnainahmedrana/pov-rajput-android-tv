package dev.pov.tv

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import java.util.Locale
import kotlin.math.abs

/**
 * Subtitle timing offset for the current video. Positive values show subtitles later, negative values earlier.
 * It resets for every new video, because each release is out of sync by its own amount.
 */
internal object SubtitleSync {
    const val LIMIT_MS = 30_000L
    @Volatile var offsetMs: Long = 0L
        private set
    /** Speed of the subtitle's clock against the video's (a subtitle timed for 25 fps on a 23.976 fps video). */
    @Volatile var factor: Double = 1.0
        private set
    /** The viewer moved the timing by hand: automatic sync leaves it alone for this video. */
    @Volatile var manual = false
        private set

    fun reset() { offsetMs = 0L; factor = 1.0; manual = false }
    fun adjust(stepMs: Long) { manual = true; offsetMs = (offsetMs + stepMs).coerceIn(-LIMIT_MS, LIMIT_MS) }
    /** Auto sync's answer: a subtitle line timed at t shows at t × [factor] + [offsetMs]. */
    fun applyAuto(offsetMs: Long, factor: Double) {
        this.offsetMs = offsetMs.coerceIn(-LIMIT_MS, LIMIT_MS); this.factor = factor.takeIf { it in 0.9..1.1 } ?: 1.0
    }

    fun label(value: Long = offsetMs): String = when {
        value == 0L -> "0.0 s"
        else -> (if (value > 0) "+" else "−") + String.format(Locale.US, "%.1f s", abs(value) / 1000.0)
    }
}

/** Renders text as if playback were [SubtitleSync.offsetMs] behind, so cues appear later (or earlier when negative). */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SubtitleSyncRenderer(val inner: Renderer) : ForwardingRenderer(inner) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // A line timed at t shows at t × factor + offset, so the subtitle's own clock is (position − offset) / factor.
        super.render(((positionUs - SubtitleSync.offsetMs * 1000) / SubtitleSync.factor).toLong(), elapsedRealtimeUs)
    }
}
