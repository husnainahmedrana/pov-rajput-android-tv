package dev.pov.tv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * One highlight that glides from the old focused child of [row] to the new one, stretching to its size, drawn as the
 * row's background behind the children. Used for the player's button row (white pill, text and icons cross-fading to
 * black), the episode list and the hold-OK menu. Each move restarts from where the highlight is, so holding a key never
 * queues animations. Children must be direct children of [row].
 */
internal class SlidingFocusPill(
    private val row: ViewGroup,
    buttons: List<View> = emptyList(),
    fill: Int = Color.WHITE,
    /** Corner radius in px; null rounds the ends fully (a pill). */
    radius: Float? = null,
    /** Text and icons of [buttons] fade white → black under the highlight. */
    private val fadeText: Boolean = true,
    /** An outline of this width (px) drawn over the children (posters) instead of a fill behind them. */
    ring: Float? = null,
    /** The focused child grows by this much (around its centre); the outline grows with it. */
    private val scale: Float = 1f,
    /** The part of a child to outline (a Top 10 card's poster beside its rank number); the whole child by default. */
    private val part: ((View) -> View?)? = null,
) {
    private val pill = PillDrawable(fill, radius, ring)
    private var motion: ValueAnimator? = null
    private val colours = HashMap<View, ValueAnimator>()

    init {
        if (ring != null) row.foreground = pill else row.background = pill
        if (fadeText) buttons.forEach { button -> button.background = null; paint(button, Color.WHITE) }
    }

    /** Call from each child's focus listener. */
    fun focusChanged(child: View, focused: Boolean) {
        if (fadeText) fade(child, if (focused) Color.BLACK else Color.WHITE)
        if (focused) moveTo(child)
        else row.post { if (!row.hasFocus()) animateTo(pill.rect, 0) }
    }

    private fun moveTo(child: View) {
        // Focus can arrive before the first layout (a menu just opening); wait for real bounds, or nothing is drawn
        // while the text has already turned dark.
        if (child.width == 0 || child.height == 0) { child.post { if (child.isFocused) moveTo(child) }; return }
        val inner = part?.invoke(child)
        val l = inner?.left ?: 0; val t = inner?.top ?: 0
        val r = inner?.right ?: child.width; val b = inner?.bottom ?: child.height
        val cx = child.width / 2f; val cy = child.height / 2f
        val target = RectF(child.left + cx + (l - cx) * scale, child.top + cy + (t - cy) * scale,
            child.left + cx + (r - cx) * scale, child.top + cy + (b - cy) * scale)
        // Arriving from outside (the seek bar, a season tab, controls just shown): appear in place rather than slide in.
        if (pill.shown == 0) { motion?.cancel(); pill.rect.set(target) }
        animateTo(target, 255)
    }

    private fun animateTo(target: RectF, level: Int) {
        motion?.cancel()
        val from = RectF(pill.rect); val fromLevel = pill.shown
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                pill.rect.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
                    from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t)
                pill.shown = (fromLevel + (level - fromLevel) * t).toInt()
                pill.invalidateSelf()
            }
            start()
        }
    }

    private fun fade(button: View, to: Int) {
        colours.remove(button)?.cancel()
        val from = (button as? TextView)?.currentTextColor ?: (button as? ImageView)?.imageTintList?.defaultColor ?: Color.WHITE
        if (from == to) { paint(button, to); return }
        colours[button] = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = MOVE_MS
            addUpdateListener { paint(button, it.animatedValue as Int) }
            start()
        }
    }

    private fun paint(button: View, colour: Int) {
        val tint = ColorStateList.valueOf(if (button.isEnabled) colour else 0xFF6B6B70.toInt())
        if (button is TextView) { button.setTextColor(tint); button.compoundDrawableTintList = tint }
        if (button is ImageView) button.imageTintList = tint
    }

    private class PillDrawable(private val fill: Int, private val radius: Float?, private val ring: Float?) : Drawable() {
        val rect = RectF()
        /** 0 hidden … 255 fully shown; multiplies the fill colour's own alpha. */
        var shown = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            if (shown <= 0 || rect.isEmpty) return
            paint.color = fill
            paint.alpha = Color.alpha(fill) * shown / 255
            val r = radius ?: (rect.height() / 2)
            if (ring == null) { paint.style = Paint.Style.FILL; canvas.drawRoundRect(rect, r, r, paint) }
            else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = ring
                val half = ring / 2
                canvas.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), r, r, paint)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object { const val MOVE_MS = 160L }
}
