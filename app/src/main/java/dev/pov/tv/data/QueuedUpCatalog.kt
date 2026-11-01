package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject

/**
 * Global trending and latest-release lists from Queued Up (queued-up.app), a free community Stremio catalog add-on:
 * IMDb ids and artwork, no key needed. It is someone else's hobby service, so answers stay on the device for six
 * hours, a stale copy covers a short outage, and otherwise a list is null and the caller uses its own. Call on a worker.
 */
class QueuedUpCatalog internal constructor(
    private val cache: Cache,
    private val fetch: (String) -> JSONObject = Net::publicJson,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(PreferenceCache(context))

    interface Cache {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    fun trending(type: String): List<MediaItemData>? = list(type, "trending")
    fun latest(type: String): List<MediaItemData>? = list(type, "latest")

    /** Discover's Newest without a TMDB key: one page of a (genre's) latest releases, uncached; null when unavailable. */
    fun newest(type: String, genre: String, skip: Int): List<MediaItemData>? {
        require(type == "movie" || type == "series") { "Unsupported catalog type." }
        val prefix = if (genre.isEmpty()) "" else requireNotNull(GENRES[genre]) { "Unknown genre." } + "-"
        val url = "$BASE/$type/${prefix}latest-$type" + (if (skip > 0) "/skip=$skip" else "") + ".json"
        return runCatching { parser.parseCatalog(fetch(url), type) }.getOrNull()
    }

    private fun list(type: String, kind: String): List<MediaItemData>? {
        require(type == "movie" || type == "series") { "Unsupported catalog type." }
        val url = "$BASE/$type/$kind-$type.json"
        val saved = cache.get(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val age = saved?.let { now() - it.optLong("at") } ?: Long.MAX_VALUE
        fun savedItems() = saved?.optJSONObject("answer")?.let { runCatching { parser.parseCatalog(it, type) }.getOrNull() }?.takeIf { it.isNotEmpty() }
        if (age in 0..FRESH_MS) savedItems()?.let { return it }
        val answer = runCatching { fetch(url) }.getOrNull()
        val items = answer?.let { runCatching { parser.parseCatalog(it, type) }.getOrNull() }
        if (!items.isNullOrEmpty()) {
            cache.put(url, JSONObject().put("at", now()).put("answer", answer).toString())
            return items
        }
        return if (age in 0..STALE_MS) savedItems() else null
    }

    private class PreferenceCache(context: Context) : Cache {
        private val preferences = context.applicationContext.getSharedPreferences("pov_queued_up", Context.MODE_PRIVATE)
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String) { preferences.edit().putString(key, value).apply() }
    }

    companion object {
        /** The genres Queued Up keeps latest-release lists for, by chip name. */
        val GENRES = linkedMapOf(
            "Action" to "action", "Adventure" to "adventure", "Animation" to "animation", "Comedy" to "comedy",
            "Crime" to "crime", "Documentary" to "documentary", "Drama" to "drama", "Family" to "family",
            "Fantasy" to "fantasy", "History" to "history", "Horror" to "horror", "Music" to "music",
            "Mystery" to "mystery", "Romance" to "romance", "Sci-Fi" to "scifi", "Thriller" to "thriller",
            "True Crime" to "truecrime", "War" to "war", "Western" to "western",
        )
        private const val BASE = "https://queued-up.app/api/catalog"
        private const val FRESH_MS = 6 * 60 * 60 * 1000L
        private const val STALE_MS = 3 * 24 * 60 * 60 * 1000L
        private val parser = CatalogApi { throw UnsupportedOperationException() }
    }
}
