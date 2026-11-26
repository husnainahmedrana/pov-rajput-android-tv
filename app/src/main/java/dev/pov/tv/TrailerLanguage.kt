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
    /**
     * [code] as TMDB writes it (language-COUNTRY); [search] is how a trailer in that language is called on YouTube.
     * A search result is kept when its title (without accents, lower case) has none of [avoid] and either it comes from a
     * channel whose name has one of [channels] (a studio's own channel for that country, as "Universal Spain" or "Sony
     * Pictures España", which titles its dub only "Tráiler oficial"), first, or its title has one of [want]. Channels
     * with one of [otherChannels] (the same language elsewhere, as "Sony Pictures México" for Castilian) never count.
     */
    data class Choice(val code: String, val label: String, val search: String, val want: List<String> = emptyList(), val avoid: List<String> = emptyList(),
        val channels: List<String> = emptyList(), val otherChannels: List<String> = emptyList())

    private val LATIN_AMERICA = listOf("mexico", "latam", "latino", "latinoamerica", "argentina", "colombia", "chile", "peru")
    private val SUBTITLED = listOf("subtitul", " sub ", " sub-", "(sub", "[sub", " subs ", "vose", "vostfr", "legendad", "untertitel", "sottotitol", "ondertitel")
    val CHOICES = listOf(
        Choice("", "", ""),
        Choice("es-ES", "Español (España)", "tráiler castellano", listOf("castellano", "espanol", "espana"), listOf("latino", "latinoamerica", "mexico") + SUBTITLED,
            listOf("spain", "espana"), LATIN_AMERICA),
        Choice("es-MX", "Español (Latinoamérica)", "tráiler español latino", listOf("latino", "espanol"), listOf("castellano", "espana") + SUBTITLED,
            LATIN_AMERICA, listOf("spain", "espana")),
        Choice("fr-FR", "Français", "bande annonce VF", listOf(" vf", "vf ", "francais", "french"), listOf("quebec") + SUBTITLED, listOf("france"), listOf("quebec", "canada")),
        Choice("de-DE", "Deutsch", "trailer deutsch", listOf("deutsch", "german"), SUBTITLED, listOf("deutschland", "germany")),
        Choice("it-IT", "Italiano", "trailer italiano", listOf("italiano", " ita ", " ita)", "(ita"), SUBTITLED, listOf("italia", "italy")),
        Choice("nl-NL", "Nederlands", "trailer nederlands", listOf("nederlands", "dutch", "vlaams"), emptyList()),
        Choice("pt-BR", "Português (Brasil)", "trailer dublado", listOf("dublado", "portugues", "brasil"), listOf("portugal") + SUBTITLED, listOf("brasil", "brazil"), listOf("portugal")),
        Choice("pt-PT", "Português (Portugal)", "trailer português", listOf("portugal", "portugues", "pt-pt"), listOf("dublado", "brasil") + SUBTITLED, listOf("portugal"), listOf("brasil", "brazil")),
        Choice("hi-IN", "हिन्दी", "trailer hindi", listOf("hindi", "हिन्दी", "हिंदी"), emptyList()),
        Choice("ur-PK", "اردو", "trailer urdu", listOf("urdu", "اردو"), emptyList()),
    )
    /** Videos about a trailer rather than the trailer. */
    private val NOT_A_TRAILER = listOf("reaction", "reaccion", "review", "resena", "explained", "explicado", "breakdown", "analisis", "easter egg", "parodia", "parody", "fan made", "fanmade", "#shorts")

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
        val html = Net.raw("https://www.youtube.com/results?search_query=${Net.encode(query)}&hl=$language&gl=${choice.code.substringAfter('-')}",
            headers = mapOf("Accept-Language" to "${choice.code},$language;q=0.9"))
        return pick(results(html), choice)
    }

    /** A search result: its video id, title and length in seconds (0 when YouTube gave none, as for a live stream). */
    data class Result(val id: String, val title: String, val seconds: Int, val channel: String = "")

    /** The videos of a YouTube results page, in order (its ytInitialData; ads, shorts shelves and channels skipped). */
    internal fun results(html: String): List<Result> {
        val start = html.indexOf("var ytInitialData = ").takeIf { it >= 0 } ?: return emptyList()
        val end = html.indexOf(";</script>", start).takeIf { it > 0 } ?: return emptyList()
        val data = runCatching { org.json.JSONObject(html.substring(start + "var ytInitialData = ".length, end)) }.getOrNull() ?: return emptyList()
        val found = mutableListOf<Result>()
        fun walk(value: Any?) {
            when (value) {
                is org.json.JSONObject -> {
                    value.optJSONObject("videoRenderer")?.let { video ->
                        val runs = video.optJSONObject("title")?.optJSONArray("runs")
                        val title = (0 until (runs?.length() ?: 0)).joinToString("") { runs!!.optJSONObject(it)?.optString("text").orEmpty() }
                        val length = video.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                        val seconds = length.split(':').fold(0) { total, part -> total * 60 + (part.trim().toIntOrNull() ?: return@let) }
                        val owner = video.optJSONObject("ownerText")?.optJSONArray("runs")
                        val channel = (0 until (owner?.length() ?: 0)).joinToString("") { owner!!.optJSONObject(it)?.optString("text").orEmpty() }
                        val id = video.optString("videoId")
                        if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) found += Result(id, title, seconds, channel)
                        return
                    }
                    if (value.has("reelShelfRenderer") || value.has("adSlotRenderer")) return
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is org.json.JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(data)
        return found
    }

    /**
     * The results that are a trailer in [choice]'s language: the country's own studio channels first, then uploads whose
     * titles name the language; up to three, otherwise in YouTube's order.
     */
    internal fun pick(results: List<Result>, choice: Choice): List<String> {
        val usable = results.filter { result ->
            val title = " " + plain(result.title) + " "
            val channel = " " + plain(result.channel) + " "
            result.seconds in 30..300 && choice.avoid.none { plain(it) in title } && NOT_A_TRAILER.none { it in title } &&
                choice.otherChannels.none { plain(it) in channel }
        }
        val official = usable.filter { result -> choice.channels.any { plain(it) in " " + plain(result.channel) + " " } }
        val named = usable.filter { result -> choice.want.any { plain(it) in " " + plain(result.title) + " " } }
        return (official + named).map { it.id }.distinct().take(3)
    }

    /** Lower case without accents (tráiler → trailer, español → espanol); other scripts stay as they are. */
    private fun plain(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace('\u00a0', ' ')
}
