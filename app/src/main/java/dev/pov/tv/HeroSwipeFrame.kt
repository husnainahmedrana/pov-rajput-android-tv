package dev.pov.tv

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The phone's Home feature: a sideways swipe moves to the next (+1) or previous (-1) title. Taps still reach the
 * buttons, and up/down drags still scroll the page.
 */
internal class HeroSwipeFrame(context: Context) : FrameLayout(context) {
    var onSwipe: ((Int) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val distance = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (onSwipe == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false }
            MotionEvent.ACTION_MOVE -> if (sideways(event)) { swiping = true; parent?.requestDisallowInterceptTouchEvent(true) }
        }
        return swiping
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val swipe = onSwipe ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false; return true }
            MotionEvent.ACTION_MOVE -> if (!swiping && sideways(event)) { swiping = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (swiping && abs(dx) >= distance) swipe(if (dx < 0) 1 else -1)
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }

    private fun sideways(event: MotionEvent): Boolean {
        val dx = abs(event.x - downX); val dy = abs(event.y - downY)
        return dx > slop * 2 && dx > dy * 1.5f
    }
}
