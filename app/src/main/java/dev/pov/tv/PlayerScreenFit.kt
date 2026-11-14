@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package dev.pov.tv

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * How the picture fills the screen: Fit shows the whole picture (black bars when its shape differs from the screen),
 * Fill covers the screen and crops the edges, Stretch covers the screen by stretching the picture. The choice is
 * remembered for every video. On a tall phone screen Fill removes the side bars of a wide film.
 */
internal enum class PlayerScreenFit(val resizeMode: Int, val label: Int) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, R.string.t_fit),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, R.string.t_fill),
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL, R.string.t_stretch);

    fun next(): PlayerScreenFit = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "pov_player_screen"
        private const val KEY = "fit"

        fun load(context: Context): PlayerScreenFit =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { saved -> entries.firstOrNull { it.name == saved } } ?: FIT

        fun save(context: Context, fit: PlayerScreenFit) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, fit.name).apply()
        }
    }
}
