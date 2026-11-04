package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject

/**
 * Show episodes from TheTVDB (v4 API) with the viewer's own key: seasons, episode titles, air dates, synopses and
 * stills, usually more complete than Cinemeta's (specials, anime, daily shows). Shows are found by their IMDb id;
 * episodes use TVDB's default (aired) order, which is what stream add-ons number by. Call on a worker.
 */
class TvdbApi internal constructor(
    private val key: String,
    private val cache: Cache,
    private val request: (method: String, url: String, headers: Map<String, String>, body: JSONObject?) -> String =
        { method, url, headers, body -> Net.raw(url, method, headers, body) },
) {
    constructor(context: Context, key: String) : this(key, PreferenceCache(context))

    /** The login token (valid about a month) and IMDb → TVDB ids, kept on the device. */
    interface Cache {
        fun get(name: String): String?
        fun put(name: String, value: String)
    }

    /** Checks the key with a login. */
    fun validate() { token(fresh = true) }

    /** A show's episodes, or null when TVDB has no show for this IMDb id. */
    fun episodes(imdbId: String): List<EpisodeData>? {
        val series = seriesId(imdbId) ?: return null
        val episodes = mutableListOf<EpisodeData>()
        var page = 0
        while (page < MAX_PAGES) {
            val json = get("/series/$series/episodes/default/eng?page=$page")
            val list = json.optJSONObject("data")?.optJSONArray("episodes") ?: break
            for (index in 0 until list.length()) {
                val entry = list.optJSONObject(index) ?: continue
                val season = entry.optInt("seasonNumber", -1); val number = entry.optInt("number", -1)
                if (season < 0 || number <= 0) continue
                episodes += EpisodeData(
                    season = season, episode = number,
                    title = entry.optString("name").takeUnless { it.isBlank() || it == "null" } ?: "Episode $number",
                    released = entry.optString("aired").takeUnless { it == "null" }.orEmpty(),
                    thumbnail = image(entry.optString("image")),
                    overview = entry.optString("overview").takeUnless { it == "null" }.orEmpty(),
                )
            }
            if (json.optJSONObject("links")?.optString("next").let { it.isNullOrBlank() || it == "null" }) break
            page++
        }
        return episodes.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode })).takeIf { it.isNotEmpty() }
    }

    private fun seriesId(imdbId: String): Long? {
        if (!imdbId.matches(Regex("tt\\d{5,12}"))) return null
        cache.get("series:$imdbId")?.let { return it.toLongOrNull()?.takeIf { id -> id > 0 } }
        val list = get("/search/remoteid/$imdbId").optJSONArray("data")
        val id = (0 until (list?.length() ?: 0)).firstNotNullOfOrNull { list?.optJSONObject(it)?.optJSONObject("series")?.optLong("id", -1)?.takeIf { id -> id > 0 } }
        // "0" records a show TVDB doesn't have, so it isn't asked about again.
        cache.put("series:$imdbId", (id ?: 0L).toString())
        return id
    }

    private fun get(path: String): JSONObject {
        fun call(token: String) = JSONObject(request("GET", "$BASE$path", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), null))
        return try { call(token()) } catch (error: ApiException) {
            // An expired token: log in again once.
            if (error.code == 401) call(token(fresh = true)) else throw error
        }
    }

    private fun token(fresh: Boolean = false): String {
        val name = "token:${key.hashCode()}"
        if (!fresh) cache.get(name)?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (at, token) ->
            if (System.currentTimeMillis() - (at.toLongOrNull() ?: 0L) < TOKEN_KEEP_MS) return token
        }
        val raw = try { request("POST", "$BASE/login", mapOf("Accept" to "application/json"), JSONObject().put("apikey", key)) }
            catch (error: ApiException) { throw IllegalArgumentException(if (error.code == 401) "TVDB didn't accept this key." else "TVDB couldn't be reached. Try again.") }
        val token = JSONObject(raw).optJSONObject("data")?.optString("token").orEmpty()
        require(token.isNotBlank()) { "TVDB didn't accept this key." }
        cache.put(name, "${System.currentTimeMillis()}|$token")
        return token
    }

    private fun image(path: String) = when {
        path.startsWith("https://") -> path
        path.startsWith('/') -> "https://artworks.thetvdb.com$path"
        else -> ""
    }

    private class PreferenceCache(context: Context) : Cache {
        private val preferences = context.applicationContext.getSharedPreferences("pov_tvdb", Context.MODE_PRIVATE)
        override fun get(name: String): String? = preferences.getString(name, null)
        override fun put(name: String, value: String) { preferences.edit().putString(name, value).apply() }
    }

    companion object {
        private const val BASE = "https://api4.thetvdb.com/v4"
        private const val MAX_PAGES = 10
        private const val TOKEN_KEEP_MS = 25L * 24 * 60 * 60 * 1000
    }
}
