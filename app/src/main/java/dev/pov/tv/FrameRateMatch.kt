package dev.pov.tv

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Match frame rate (Settings → Playback): most films and shows are 23.976 fps, which judders on a TV left at 60 Hz.
 * - Off: the TV stays as it is.
 * - Only when seamless (default, the old behaviour): Android may switch only where the TV can do it without a blank screen.
 * - Always: the player asks for the display mode whose refresh rate fits the video; the screen may go black for a
 *   moment when playback starts, and the TV goes back to its own mode when the player closes.
 */
internal object FrameRateMatch {
    const val OFF = "off"
    const val SEAMLESS = "seamless"
    const val ALWAYS = "always"
    val CHOICES = listOf(OFF, SEAMLESS, ALWAYS)
    private const val PREFS = "pov_player"
    private const val KEY = "frame_rate_match"

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SEAMLESS)
        .takeIf { it in CHOICES } ?: SEAMLESS

    fun set(context: Context, value: String) {
        require(value in CHOICES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    /** One display mode: its id, physical size and refresh rate. */
    data class Mode(val id: Int, val width: Int, val height: Int, val refresh: Float)

    /**
     * The mode at the current resolution whose refresh rate is a whole multiple of [fps] (23.976 → 23.976 Hz, then
     * 47.952, then 119.88; 25 → 50; 29.97 → 59.94), lowest multiple first. Null when the current mode already fits,
     * the frame rate is unknown, or no mode fits (the TV then stays as it is).
     */
    fun best(modes: List<Mode>, current: Mode, fps: Float): Mode? {
        if (fps < 10f || fps > 125f) return null
        fun multiple(refresh: Float): Int? {
            val ratio = refresh / fps
            val whole = ratio.roundToInt()
            return whole.takeIf { it >= 1 && abs(ratio - whole) < 0.002f * whole }
        }
        if (multiple(current.refresh) != null) return null
        return modes.filter { it.width == current.width && it.height == current.height }
            .mapNotNull { mode -> multiple(mode.refresh)?.let { mode to it } }
            .minByOrNull { (mode, times) -> times * 1000 + (abs(mode.refresh - fps * times) * 100).roundToInt() }?.first
    }
}
