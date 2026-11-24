package dev.pov.tv

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Keeps card artwork only near the screen, the way lazy lists do (Nuvio's LazyRow, a browser's image decoding).
 *
 * Every Home row is built up front, so without this each card kept its decoded poster alive: 20+ rows of large
 * posters held ~400-800 MB of native memory on a 2 GB TV, and Android then killed the app during Usenet playback.
 * Now a card's picture is loaded when it comes within about half a screen of view (1.5 screens ahead sideways) and dropped once it is well
 * outside (the gap between the two zones stops pictures flickering at the edge). A dropped picture comes back from
 * the in-memory cache or, failing that, from the disk cache at card size, which takes a few milliseconds.
 *
 * While another activity (the player) covers the app, only what was on screen is kept.
 */
internal class ArtworkTrimmer(private val root: View) {
    /** Tracked cards; weak so a card that is gone for good is forgotten without being removed. */
    private val views = WeakHashMap<ImageView, Unit>()
    /** The player (or another app) is in front: keep only the visible pictures. */
    var stopped = false
        set(value) { field = value; if (value) pass() else passBeforeDraw() }
    private var scheduled = false
    private var preDrawPending = false
    private val location = IntArray(2)
    private val delayedPass = Runnable { scheduled = false; pass() }
    private val preDraw = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            root.viewTreeObserver.removeOnPreDrawListener(this); preDrawPending = false; pass(); return true
        }
    }

    init {
        // Scrolling a row or the page, and pages being swapped or laid out, move cards in and out of the zones.
        root.viewTreeObserver.addOnScrollChangedListener { schedule() }
        root.viewTreeObserver.addOnGlobalLayoutListener { schedule() }
    }

    /** [start] loads [view]'s picture (from the cache when it has it); it runs once the card is near the screen. */
    fun track(view: ImageView, start: Runnable) {
        view.setTag(R.id.pov_art_start, start)
        view.setTag(R.id.pov_art_state, PENDING)
        views[view] = Unit
        passBeforeDraw()
    }

    /** False once [view]'s picture was dropped: a load still finishing then doesn't put it back. */
    fun wanted(view: ImageView) = view.getTag(R.id.pov_art_state) != RELEASED

    /** Throttled pass while scrolling: at most one every [THROTTLE_MS]. */
    fun schedule() {
        if (scheduled) return
        scheduled = true
        root.postDelayed(delayedPass, THROTTLE_MS)
    }

    /** A pass right after the next layout, so new cards know where they are before their first frame. */
    fun passBeforeDraw() {
        if (preDrawPending) return
        preDrawPending = true
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        root.invalidate()
    }

    fun pass() {
        val width = root.width; val height = root.height
        if (width <= 0 || height <= 0) return
        for (view in views.keys.toList()) {
            val start = view.getTag(R.id.pov_art_start) as? Runnable ?: continue
            val state = view.getTag(R.id.pov_art_state)
            val onScreen = view.isAttachedToWindow && view.isShown && view.width > 0
            if (onScreen) view.getLocationInWindow(location)
            fun within(left: Float, top: Float, right: Float, bottom: Float) = onScreen &&
                location[0] + view.width > width * left && location[0] < width * right &&
                location[1] + view.height > height * top && location[1] < height * bottom
            if (stopped) {
                if (state == LOADED && !within(0f, 0f, 1f, 1f)) release(view)
                continue
            }
            // Sideways a row slides fast, so posters load well ahead (1.5 screens to the right) and arrive ready
            // instead of popping in as they slide on screen.
            if (state != LOADED && within(-1f, -0.5f, 2.5f, 1.5f)) {
                view.setTag(R.id.pov_art_state, LOADED)
                start.run()
            } else if (state == LOADED && !within(-2f, -1.25f, 3.5f, 2.25f)) release(view)
        }
    }

    private fun release(view: ImageView) {
        view.setTag(R.id.pov_art_state, RELEASED)
        view.setImageDrawable(null)
    }

    private companion object {
        const val THROTTLE_MS = 120L
        const val PENDING = 0
        const val LOADED = 1
        const val RELEASED = 2
    }
}
