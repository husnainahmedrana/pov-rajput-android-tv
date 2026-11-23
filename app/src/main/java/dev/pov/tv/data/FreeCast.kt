package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cast without a TMDB key, from free sources that need no account: TVmaze for shows (full main cast, photos,
 * characters, in billing order) and Wikidata for films and any show TVmaze doesn't know (cast with Wikimedia Commons
 * photos where there is one). Wikidata's list isn't in billing order, so Cinemeta's top-billed names lead.
 * People get negative ids: without TMDB there's no person page, so opening one searches their name instead.
 */
class FreeCast(
    /** One GET (address, headers) → body. */
    private val get: (String, Map<String, String>) -> String = ::publicGet,
) {
    fun cast(imdbId: String, type: String): List<TmdbCatalog.CastMember> {
        require(IMDB.matches(imdbId))
        val leads = runCatching { cinemetaLeads(imdbId, type) }.getOrDefault(emptyList())
        if (type == "series") runCatching { tvmaze(imdbId) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return number(it) }
        val wiki = runCatching { wikidata(imdbId) }.getOrDefault(emptyList())
        return number(order(wiki, leads))
    }

    /** Cinemeta's top-billed actors (usually three), used to put Wikidata's unordered list in a sensible order. */
    private fun cinemetaLeads(imdbId: String, type: String): List<String> {
        val meta = JSONObject(get("https://v3-cinemeta.strem.io/meta/${if (type == "series") "series" else "movie"}/$imdbId.json", emptyMap())).optJSONObject("meta")
        return meta?.optJSONArray("cast")?.let { array -> (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
    }

    internal fun tvmaze(imdbId: String): List<TmdbCatalog.CastMember> {
        val show = JSONObject(get("https://api.tvmaze.com/lookup/shows?imdb=$imdbId", emptyMap()))
        val id = show.optLong("id", 0).takeIf { it > 0 } ?: return emptyList()
        val list = JSONArray(get("https://api.tvmaze.com/shows/$id/cast", emptyMap()))
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { entry ->
            val person = entry.optJSONObject("person") ?: return@mapNotNull null
            val name = person.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val photo = person.optJSONObject("image")?.let { it.optString("medium").ifBlank { it.optString("original") } }.orEmpty()
            TmdbCatalog.CastMember(0, name, entry.optJSONObject("character")?.optString("name").orEmpty().trim(), https(photo))
        }.distinctBy { it.name }
    }

    internal fun wikidata(imdbId: String): List<TmdbCatalog.CastMember> {
        val query = """SELECT ?actor ?actorLabel ?img ?roleLabel WHERE { ?work wdt:P345 "$imdbId". ?work p:P161 ?st. ?st ps:P161 ?actor.
            OPTIONAL { ?st pq:P453 ?role } OPTIONAL { ?actor wdt:P18 ?img } SERVICE wikibase:label { bd:serviceParam wikibase:language "en". } } LIMIT 200"""
        val text = get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8"),
            mapOf("Accept" to "application/sparql-results+json", "User-Agent" to USER_AGENT))
        val rows = JSONObject(text).optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
        fun value(row: JSONObject, name: String) = row.optJSONObject(name)?.optString("value").orEmpty()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.groupBy { value(it, "actor") }.mapNotNull { (_, same) ->
            val name = value(same.first(), "actorLabel").trim()
            // An unnamed entry comes back as its bare id (Q123…): nothing to show.
            if (name.isBlank() || Regex("Q\\d+").matches(name)) return@mapNotNull null
            val photo = same.map { value(it, "img") }.firstNotNullOfOrNull { commonsThumb(it) }.orEmpty()
            val role = same.map { value(it, "roleLabel") }.firstOrNull { it.isNotBlank() && !Regex("Q\\d+").matches(it) }.orEmpty()
            TmdbCatalog.CastMember(0, name, role, photo)
        }
    }

    companion object {
        private val IMDB = Regex("tt[0-9]{5,12}")
        /** Wikidata asks every client to name itself; the app's public name and site only (nothing about the viewer). */
        const val USER_AGENT = "Nabrio/1.0 (https://nabrio.rajput.cc; Android media app)"
        const val MAX = 30

        /** Billed leads first (in Cinemeta's order), then people with a photo, then the rest. */
        internal fun order(cast: List<TmdbCatalog.CastMember>, leads: List<String>): List<TmdbCatalog.CastMember> {
            fun norm(text: String) = text.lowercase().replace(Regex("[^a-z0-9]"), "")
            val leadIndex = leads.mapIndexed { index, name -> norm(name) to index }.toMap()
            val known = cast.associateBy { norm(it.name) }
            // A lead Wikidata doesn't list still shows, as an initials card.
            val missing = leads.filter { norm(it) !in known }.map { TmdbCatalog.CastMember(0, it, "", "") }
            return (cast + missing).distinctBy { norm(it.name) }.sortedWith(compareBy(
                { leadIndex[norm(it.name)] ?: Int.MAX_VALUE }, { if (it.photo.isNotBlank()) 0 else 1 }))
        }

        /** Unique negative ids (no TMDB person behind them) so each card keeps its own focus place. */
        internal fun number(cast: List<TmdbCatalog.CastMember>) = cast.take(MAX).mapIndexed { index, member -> member.copy(personId = -(index + 1L)) }

        private fun https(address: String) = if (address.startsWith("http://")) "https://" + address.removePrefix("http://") else address

        /**
         * A Wikidata image (…/Special:FilePath/Name.jpg on commons.wikimedia.org, which some networks can't reach) as
         * the direct 250 px thumbnail on upload.wikimedia.org: /thumb/a/ab/Name.jpg/250px-Name.jpg, a and ab from the
         * MD5 of the file name. SVGs are thumbnailed as PNG; TIFF, PDF and similar files are skipped (no photo).
         */
        internal fun commonsThumb(filePath: String): String? {
            val raw = filePath.substringAfter("Special:FilePath/", "").substringBefore('?').takeIf { it.isNotBlank() } ?: return null
            val name = java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(' ', '_')
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("jpg", "jpeg", "png", "gif", "webp", "svg")) return null
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2C", ",").replace("%28", "(").replace("%29", ")")
            return "https://upload.wikimedia.org/wikipedia/commons/thumb/${md5[0]}/${md5.take(2)}/$encoded/250px-$encoded" + if (ext == "svg") ".png" else ""
        }

        /**
         * GET for these public, key-free sources (TVmaze, Cinemeta, Wikidata), following their redirects (TVmaze's
         * IMDb lookup and Cinemeta answer with one), HTTPS only. Nothing private is ever sent this way.
         */
        internal fun publicGet(address: String, headers: Map<String, String>): String {
            var url = java.net.URL(address)
            repeat(4) {
                require(url.protocol == "https") { "Cast sources must use HTTPS." }
                val connection = url.openConnection() as java.net.HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                    val status = connection.responseCode
                    if (status in 300..399) { url = java.net.URL(url, connection.getHeaderField("Location") ?: throw ApiException(status, "Redirect without a location.")); return@repeat }
                    if (status !in 200..299) throw ApiException(status, "Cast source answered $status.")
                    return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } finally { connection.disconnect() }
            }
            throw ApiException(0, "Too many redirects.")
        }
    }
}
