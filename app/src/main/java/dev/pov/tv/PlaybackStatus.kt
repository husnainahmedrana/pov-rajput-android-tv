package dev.pov.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * What the built-in player is doing before the picture appears, for the loading screen: "Getting the NZB", "Connecting
 * to Real-Debrid", "Checking the release is complete"… Written by whoever is working (the Usenet engine, the session),
 * read by the player screen a few times a second. Only short, translated sentences: never addresses or keys.
 */
internal object PlaybackStatus {
    @Volatile var stage: String? = null
        private set

    fun reset(first: String? = null) { stage = first }
    fun stage(text: String) { stage = text }
}

/**
 * Nabrio's frame strip: 24 small frames under the title. While there's nothing to measure, one lit frame travels along
 * it; once there is, it fills with the theme's colour. Used on the player's loading screen.
 */
internal class FrameStrip(context: Context, private val accent: Int) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val frames = List(FRAMES) { View(context).apply { background = tile(DIM) } }
    private var pulse: ValueAnimator? = null
    private var lit = -1

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        frames.forEachIndexed { index, frame ->
            addView(frame, LayoutParams((12 * density).toInt(), (20 * density).toInt()).apply { if (index > 0) leftMargin = (5 * density).toInt() })
        }
    }

    /** Nothing to measure yet: a travelling pulse. */
    fun travel() {
        if (pulse != null) return
        lit = -1
        // A float over 0..FRAMES, so the last frame gets its full share of the trip: counting 0..FRAMES-1 in whole
        // numbers reached it only at the turn's last instant, so the pulse seemed to turn back one frame early.
        pulse = ValueAnimator.ofFloat(0f, FRAMES.toFloat()).apply {
            duration = 1_400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val at = frameAt(animator.animatedValue as Float)
                frames.forEachIndexed { index, frame ->
                    val distance = kotlin.math.abs(index - at)
                    frame.background = tile(if (distance == 0) accent else DIM)
                    frame.alpha = if (distance <= 2) 1f - distance * 0.25f else 1f
                }
            }
            start()
        }
    }

    /** [percent] done: the strip fills from the left. */
    fun fill(percent: Int) {
        pulse?.cancel(); pulse = null
        val now = filledFrames(percent)
        if (now == lit) return
        lit = now
        frames.forEachIndexed { index, frame -> frame.alpha = 1f; frame.background = tile(if (index < now) accent else DIM) }
    }

    fun stop() { pulse?.cancel(); pulse = null }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private fun tile(color: Int) = GradientDrawable().apply { cornerRadius = 2 * density; setColor(color) }

    internal companion object {
        const val FRAMES = 24
        /** The frame lit at [position] along 0..FRAMES. */
        fun frameAt(position: Float): Int = position.toInt().coerceIn(0, FRAMES - 1)
        /** Frames filled at [percent]: rounded up, so 99% (the most the loading card shows) lights the last frame too. */
        fun filledFrames(percent: Int): Int = ((percent.coerceIn(0, 100) * FRAMES + 99) / 100).coerceIn(0, FRAMES)
        const val DIM = 0x26FFFFFF
    }
}
