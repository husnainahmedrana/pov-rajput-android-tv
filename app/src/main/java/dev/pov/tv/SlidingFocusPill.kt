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
 * The player's button-row highlight as one white pill that glides from the old button to the new one, stretching to
 * each button's width, while text and icon colours cross-fade. Drawn as the row's background, behind the buttons.
 * Each move restarts from where the pill is, so holding a key never queues animations.
 */
internal class SlidingFocusPill(private val row: ViewGroup, private val buttons: List<View>) {
    private val pill = PillDrawable()
    private var motion: ValueAnimator? = null
    private val colours = HashMap<View, ValueAnimator>()

    init {
        row.background = pill
        buttons.forEach { button ->
            button.background = null
            paint(button, Color.WHITE)
        }
    }

    /** Call from each button's focus listener. */
    fun focusChanged(button: View, focused: Boolean) {
        fade(button, if (focused) Color.BLACK else Color.WHITE)
        if (focused) moveTo(button)
        else row.post { if (buttons.none { it.isFocused }) animateTo(pill.rect, 0) }
    }

    private fun moveTo(button: View) {
        val target = RectF(button.left.toFloat(), button.top.toFloat(), button.right.toFloat(), button.bottom.toFloat())
        // Arriving from outside the row (the seek bar, or controls just shown): appear in place rather than slide in.
        if (pill.fill == 0) { motion?.cancel(); pill.rect.set(target) }
        animateTo(target, 255)
    }

    private fun animateTo(target: RectF, fill: Int) {
        motion?.cancel()
        val from = RectF(pill.rect); val fromFill = pill.fill
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                pill.rect.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
                    from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t)
                pill.fill = (fromFill + (fill - fromFill) * t).toInt()
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

    private class PillDrawable : Drawable() {
        val rect = RectF()
        var fill = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(canvas: Canvas) {
            if (fill <= 0 || rect.isEmpty) return
            paint.alpha = fill
            val radius = rect.height() / 2
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object { const val MOVE_MS = 160L }
}
