package dev.pov.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/** Preferred audio language for fresh playback; manual track choices remain session-local. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class AudioPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_audio", Context.MODE_PRIVATE)
    var language: String
        get() = preferences.getString("language", "en").orEmpty().takeIf { value -> LANGUAGES.any { it.first == value } }.orEmpty()
        set(value) { require(LANGUAGES.any { it.first == value }); preferences.edit().putString("language", value).apply() }
    val languageLabel: String get() = LANGUAGES.first { it.first == language }.second
    /**
     * Tried when a video lacks the first language, before English and the player's default: e.g. English first, Hindi
     * second picks Hindi over Telugu or Tamil on an Indian multi-audio release. Empty = none.
     */
    var secondLanguage: String
        get() = preferences.getString("second_language", "").orEmpty()
            .takeIf { value -> value.isNotEmpty() && LANGUAGES.any { it.first == value } && value != language }.orEmpty()
        set(value) { require(value.isEmpty() || LANGUAGES.any { it.first == value }); preferences.edit().putString("second_language", value).apply() }
    val secondLanguageLabel: String get() = secondLanguage.takeIf { it.isNotEmpty() }?.let { code -> LANGUAGES.first { it.first == code }.second } ?: "None"
    /** When a video lacks the preferred audio language, English is tried before the player's default (subtitles have their own). */
    var englishFallback: Boolean
        get() = preferences.getBoolean("english_fallback", true)
        set(value) { preferences.edit().putBoolean("english_fallback", value).apply() }
    fun withFallback(code: String): Array<String> =
        (listOf(code, secondLanguage) + if (englishFallback) listOf("en") else emptyList()).filter { it.isNotEmpty() }.distinct().toTypedArray()

    fun trackParameters(current: TrackSelectionParameters): TrackSelectionParameters {
        val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        return if (language.isEmpty()) builder.setPreferredAudioLanguages().build()
        else builder.setPreferredAudioLanguages(*withFallback(language)).build()
    }

    companion object {
        val LANGUAGES = listOf("" to "Automatic (player default)") +
            SubtitlePreferences.LANGUAGES.filter { it.first.isNotEmpty() && it.first != "off" }
        val COMMON_LANGUAGES = SubtitlePreferences.COMMON_LANGUAGES.filter { it != "off" }
    }
}
