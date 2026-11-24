package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Holding Back for [HOLD_MS] opens Settings from any screen. Some remotes send only a press and a
 * release (no key repeats), so the hold is timed from the press itself and also checked on release.
 * Once a hold completes, the rest of that press is swallowed so it never also acts as an ordinary Back.
 */
internal class BackHoldGesture(private val completed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var pressing = false
    private var fired = false
    private val fire = Runnable { if (pressing && !fired) { fired = true; completed() } }

    /** True when the event belongs to a completed hold and must not reach normal Back handling. */
    fun intercept(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (fired) return true
                if (event.repeatCount == 0) {
                    pressing = true; handler.removeCallbacks(fire); handler.postDelayed(fire, HOLD_MS)
                    return false
                }
                if (event.eventTime - event.downTime >= HOLD_MS) { handler.removeCallbacks(fire); fire.run() }
                return true
            }
            KeyEvent.ACTION_UP -> {
                pressing = false; handler.removeCallbacks(fire)
                if (fired) { fired = false; return true }
                if (event.eventTime - event.downTime >= HOLD_MS) { completed(); return true }
            }
        }
        return false
    }

    companion object {
        const val HOLD_MS = 600L
        /** Set by the player so the browser opens Settings as soon as it returns. */
        @Volatile var settingsRequested = false
    }
}
