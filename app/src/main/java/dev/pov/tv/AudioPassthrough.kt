package dev.pov.tv

import android.content.Context
import androidx.media3.common.MimeTypes

/**
 * Audio passthrough (Settings → Audio): which encoded formats go to the TV, sound bar or receiver as they are.
 * - Automatic (default, the old behaviour): every format the device says it can pass through is passed through.
 * - Decode lossless: TrueHD (Atmos), DTS-HD MA and DTS:X are decoded in the app (the device's decoder or FFmpeg) and
 *   sent as multichannel PCM; Dolby Digital, Dolby Digital Plus and plain DTS still pass through. For devices that
 *   accept lossless passthrough and then play static or noise.
 * - Decode everything: no passthrough at all, every format is sent as PCM.
 * Decoding to PCM keeps every channel but loses Atmos/DTS:X objects (the sound bar no longer shows Atmos).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object AudioPassthrough {
    const val AUTO = "auto"
    const val DECODE_LOSSLESS = "decode_lossless"
    const val DECODE_ALL = "decode_all"
    val CHOICES = listOf(AUTO, DECODE_LOSSLESS, DECODE_ALL)
    private const val PREFS = "pov_player"
    private const val KEY = "audio_passthrough"

    /** Lossless formats (and their object-audio extensions) that the middle choice decodes in the app. */
    val LOSSLESS = setOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X)

    fun mode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO)
        .takeIf { it in CHOICES } ?: AUTO

    fun set(context: Context, value: String) {
        require(value in CHOICES)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    /**
     * True when the TV's own Settings → Surround sound is "Never" (Settings.Global encoded_surround_output = 1): Android
     * then reports no passthrough to any app, so Media3 decodes everything. Kodi packs the audio itself and ignores it.
     */
    fun surroundNever(context: Context): Boolean =
        runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "encoded_surround_output", 0) == 1 }.getOrDefault(false)

    /** True when the chosen [mode] refuses passthrough of this encoded [mimeType], so it is decoded to PCM in the app. */
    fun decodes(mode: String, mimeType: String?): Boolean = when {
        mimeType == null || mimeType == MimeTypes.AUDIO_RAW -> false
        mode == DECODE_ALL -> true
        mode == DECODE_LOSSLESS -> mimeType in LOSSLESS
        else -> false
    }
}
