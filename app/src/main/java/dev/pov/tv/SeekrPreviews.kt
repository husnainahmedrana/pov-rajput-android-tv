package dev.pov.tv

import android.content.Context

/** Seek-bar thumbnails from Seekr (seekr.tv): ready-made previews for many movies and shows, with the viewer's own API key. */
internal class SeekrPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pov_seek_previews", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("seekr", true)
        set(value) = prefs.edit().putBoolean("seekr", value).apply()

    companion object {
        /** SecureStore key for the viewer's Seekr API key. */
        const val KEY = "seek_previews.seekr_key"
        const val TAG = "Seekr"
    }
}
