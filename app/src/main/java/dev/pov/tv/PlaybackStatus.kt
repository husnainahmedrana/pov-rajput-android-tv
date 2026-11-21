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
        pulse = ValueAnimator.ofInt(0, FRAMES - 1).apply {
            duration = 1_400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val at = animator.animatedValue as Int
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
        val now = (percent * FRAMES / 100).coerceIn(0, FRAMES)
        if (now == lit) return
        lit = now
        frames.forEachIndexed { index, frame -> frame.alpha = 1f; frame.background = tile(if (index < now) accent else DIM) }
    }

    fun stop() { pulse?.cancel(); pulse = null }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    private fun tile(color: Int) = GradientDrawable().apply { cornerRadius = 2 * density; setColor(color) }

    private companion object {
        const val FRAMES = 24
        const val DIM = 0x26FFFFFF
    }
}
