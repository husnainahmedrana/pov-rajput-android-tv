package dev.pov.tv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language (Settings → Home & appearance → Language). "Device language" follows the phone or TV, and on
 * Android 13+ also the per-app language in Android's settings; any other choice applies to every screen of the app.
 * Each activity wraps its base context with [wrap], so a change takes effect when its screen is rebuilt.
 */
internal object AppLanguage {
    data class Choice(val tag: String, val name: String)

    /** Language names are written in their own language, as other apps do; the first entry is translated. */
    val CHOICES = listOf(
        Choice("", ""), Choice("en", "English"), Choice("es", "Español"), Choice("fr", "Français"),
        Choice("de", "Deutsch"), Choice("it", "Italiano"), Choice("pt", "Português"), Choice("nl", "Nederlands"),
    )

    private const val PREFS = "pov_language"
    private const val KEY = "tag"

    fun tag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .takeIf { saved -> CHOICES.any { it.tag == saved } }.orEmpty()

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** The label for the current choice: the language's own name, or [deviceLabel]. */
    fun label(context: Context, deviceLabel: String): String =
        CHOICES.firstOrNull { it.tag == tag(context) }?.name?.ifBlank { deviceLabel } ?: deviceLabel

    fun wrap(base: Context): Context {
        val tag = tag(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * Text for code that has no screen of its own (link rules, sort names, service errors): in the app's language once an
 * activity has attached its localized context, otherwise (unit tests, background jobs before any screen) the English
 * [fallback], which uses the same %1$s placeholders as the string resource.
 */
internal object L10n {
    @Volatile private var context: Context? = null
    fun attach(localized: Context) { context = localized }
    fun tr(id: Int, fallback: String, vararg args: Any?): String {
        context?.let { ctx -> try { return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args) } catch (_: Exception) {} }
        return if (args.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *args)
    }
}
