package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.Net
import dev.pov.tv.data.TmdbCatalog
import java.util.concurrent.ConcurrentHashMap

/**
 * The language of the trailers that play by themselves on title pages and Home (Settings → Trailers → Trailer
 * language). Automatic keeps Cinemeta's (English) trailers. Another language asks TMDB for that language's trailers
 * when the viewer has a TMDB key, else searches YouTube for "‹title› ‹year› ‹trailer in that language›"; the English
 * trailer follows in case the chosen one can't play, and plays alone when no other is found.
 */
internal object TrailerLanguage {
    /** [code] as TMDB writes it (language-COUNTRY); [search] is how a trailer in that language is called on YouTube. */
    data class Choice(val code: String, val label: String, val search: String)

    val CHOICES = listOf(
        Choice("", "", ""),
        Choice("es-ES", "Español (España)", "tráiler castellano"),
        Choice("es-MX", "Español (Latinoamérica)", "tráiler español latino"),
        Choice("fr-FR", "Français", "bande annonce VF"),
        Choice("de-DE", "Deutsch", "trailer deutsch"),
        Choice("it-IT", "Italiano", "trailer italiano"),
        Choice("nl-NL", "Nederlands", "trailer nederlands"),
        Choice("pt-BR", "Português (Brasil)", "trailer dublado"),
        Choice("pt-PT", "Português (Portugal)", "trailer português"),
        Choice("hi-IN", "हिन्दी", "trailer hindi"),
        Choice("ur-PK", "اردو", "trailer urdu"),
    )

    private const val PREFS = "pov_hero_trailers"
    private const val KEY = "language"
    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        .takeIf { code -> CHOICES.any { it.code == code } }.orEmpty()
    fun set(context: Context, code: String) {
        require(CHOICES.any { it.code == code })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }
    fun label(context: Context, code: String = get(context)): String =
        if (code.isEmpty()) context.getString(R.string.t_trailer_language_auto) else CHOICES.first { it.code == code }.label

    private val found = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private const val KEEP_MS = 6L * 60 * 60 * 1000
    private val VIDEO_ID = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    /** Trailers of [item] in [code]'s language, best first (network: off the main thread); empty when none is found. */
    fun videos(item: MediaItemData, code: String, tmdb: TmdbCatalog?): List<String> {
        val choice = CHOICES.firstOrNull { it.code == code && code.isNotEmpty() } ?: return emptyList()
        val cacheKey = "$code:${item.type}:${item.imdbId}"
        found[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second }
        val fromTmdb = if (tmdb != null) runCatching { tmdb.trailers(item.imdbId, item.type, code) }.getOrDefault(emptyList()) else emptyList()
        val ids = fromTmdb.ifEmpty { runCatching { youtube(item, choice) }.getOrDefault(emptyList()) }
        found[cacheKey] = System.currentTimeMillis() to ids
        while (found.size > 128) found.keys.firstOrNull()?.let(found::remove)
        return ids
    }

    /** YouTube's own search, for viewers without a TMDB key: the first results for the title and the trailer's local name. */
    private fun youtube(item: MediaItemData, choice: Choice): List<String> {
        val query = listOf(item.title, item.year.take(4), choice.search).filter { it.isNotBlank() }.joinToString(" ")
        val language = choice.code.substringBefore('-')
        val html = Net.raw("https://www.youtube.com/results?search_query=${Net.encode(query)}&hl=$language",
            headers = mapOf("Accept-Language" to "${choice.code},$language;q=0.9"))
        return VIDEO_ID.findAll(html).map { it.groupValues[1] }.distinct().take(3).toList()
    }
}
