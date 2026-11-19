package dev.pov.tv.data

import java.util.Locale

/**
 * The language TMDB titles, descriptions and lists are asked for (Settings → Home & appearance → Metadata language).
 * "Same as app language" follows the app's own language (or the device's). Language names are written in their own
 * language and script, as other apps do. Where TMDB has no translation, English stays.
 */
object MetadataLanguage {
    const val KEY = "tmdb.language"
    data class Choice(val tag: String, val name: String)

    val CHOICES = listOf(
        Choice("en-US", "English"), Choice("ar-SA", "العربية"), Choice("es-ES", "Español"), Choice("es-MX", "Español (Latinoamérica)"),
        Choice("fr-FR", "Français"), Choice("de-DE", "Deutsch"), Choice("it-IT", "Italiano"), Choice("pt-BR", "Português (Brasil)"),
        Choice("pt-PT", "Português (Portugal)"), Choice("nl-NL", "Nederlands"), Choice("tr-TR", "Türkçe"), Choice("ru-RU", "Русский"),
        Choice("pl-PL", "Polski"), Choice("ro-RO", "Română"), Choice("el-GR", "Ελληνικά"), Choice("sv-SE", "Svenska"),
        Choice("hi-IN", "हिन्दी"), Choice("ur-PK", "اردو"), Choice("fa-IR", "فارسی"), Choice("he-IL", "עברית"),
        Choice("id-ID", "Bahasa Indonesia"), Choice("ja-JP", "日本語"), Choice("ko-KR", "한국어"), Choice("zh-CN", "中文"),
    )

    /** The saved choice's name, or null for "Same as app language". */
    fun name(saved: String): String? = CHOICES.firstOrNull { it.tag == saved }?.name

    /** The TMDB language tag to use: the saved choice, else the app's language [appTag] (blank = the device's). */
    fun resolve(saved: String, appTag: String, device: Locale = Locale.getDefault()): String {
        CHOICES.firstOrNull { it.tag == saved }?.let { return it.tag }
        val language = appTag.ifBlank { device.language }.substringBefore('-').lowercase(Locale.ROOT)
        val matches = CHOICES.filter { it.tag.substringBefore('-') == language }
        return matches.firstOrNull { it.tag.endsWith("-" + device.country) }?.tag ?: matches.firstOrNull()?.tag ?: "en-US"
    }

    fun isEnglish(tag: String) = tag.startsWith("en")
}
