package dev.pov.tv

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.util.WeakHashMap

/** Draws [inner] at a fading opacity, for focus outlines that cross-fade instead of popping in and out. */
internal class FadeDrawable(val inner: Drawable) : Drawable() {
    var opacity255 = 0
        set(value) { field = value.coerceIn(0, 255); invalidateSelf() }
    override fun draw(canvas: Canvas) {
        when (opacity255) {
            0 -> return
            255 -> inner.draw(canvas)
            else -> { val save = canvas.saveLayerAlpha(RectF(bounds), opacity255); inner.draw(canvas); canvas.restoreToCount(save) }
        }
    }
    override fun onBoundsChange(bounds: Rect) { inner.bounds = bounds }
    override fun setAlpha(alpha: Int) { opacity255 = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val FADE_MS = 150L
        private val running = WeakHashMap<View, ValueAnimator>()

        /** Fades [ring] in as [view]'s foreground, or (null) fades the current one out. */
        fun foreground(view: View, ring: Drawable?) {
            running.remove(view)?.cancel()
            val fading: FadeDrawable
            val to: Int
            if (ring != null) { fading = FadeDrawable(ring); view.foreground = fading; to = 255 }
            else { fading = view.foreground as? FadeDrawable ?: run { view.foreground = null; return }; to = 0 }
            running[view] = ValueAnimator.ofInt(fading.opacity255, to).apply {
                duration = FADE_MS
                addUpdateListener { fading.opacity255 = it.animatedValue as Int }
                if (to == 0) addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (view.foreground === fading && fading.opacity255 == 0) view.foreground = null }
                })
                start()
            }
        }
    }
}
