package dev.pov.tv

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Details-only idle overlay. A reveal gesture is consumed through release, preventing hidden clicks. */
internal class DetailsTrailerChrome(private val eligible: () -> Boolean,
    private val hide: () -> Unit, private val show: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private val held = mutableSetOf<Int>()
    private val revealKeys = mutableSetOf<Int>()
    var hidden = false; private set
    private val idle = Runnable {
        if (playing && eligible() && held.isEmpty()) { hidden = true; hide() }
    }
    fun playbackChanged(active: Boolean) {
        if (playing == active) return
        playing = active
        if (active && eligible()) arm() else restore()
    }
    private fun arm() {
        handler.removeCallbacks(idle)
        if (playing && eligible() && held.isEmpty()) handler.postDelayed(idle, 2_000)
    }
    private fun restore() {
        handler.removeCallbacks(idle)
        if (hidden) { hidden = false; show() }
    }
    fun key(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            held.remove(code)
            val consume = revealKeys.remove(code)
            arm()
            return consume
        }
        if (event.action != KeyEvent.ACTION_DOWN) return code in revealKeys
        if (!playing || !eligible()) { restore(); return code in revealKeys }
        held += code; handler.removeCallbacks(idle)
        if (hidden) { revealKeys += code; restore() }
        return code in revealKeys
    }
    fun stop() {
        playing = false; held.clear(); revealKeys.clear(); restore()
    }
}
