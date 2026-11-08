package dev.pov.tv.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Home shelves from TMDB with the viewer's own API key (v3 key or v4 read-access token). Call on a worker.
 *
 * TMDB lists carry TMDB ids only, while sources, tracking and artwork all key on IMDb ids, so each title's
 * IMDb id is looked up once and remembered on the device. Titles TMDB cannot map to IMDb are left out.
 */
class TmdbCatalog internal constructor(
    private val key: String,
    private val region: String,
    private val ids: IdCache,
    private val get: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, "GET", headers) },
) {
    constructor(context: Context, key: String, region: String) : this(key, region, PreferenceIdCache(context))

    interface IdCache {
        fun get(key: String): String?
        fun put(values: Map<String, String>)
    }

    private data class Shelf(val title: String, val type: String, val path: String)

    fun homeShelves(): List<CatalogApi.HomeShelf> {
        val shelves = listOf(
            Shelf("Trending Movies Today", "movie", "/trending/movie/day"),
            Shelf("Popular Movies", "movie", "/movie/popular"),
            Shelf("Now Playing in Cinemas", "movie", "/movie/now_playing?region=$region"),
            Shelf("Trending Shows Today", "series", "/trending/tv/day"),
            Shelf("Popular Shows", "series", "/tv/popular"),
        )
        val pool = Executors.newFixedThreadPool(6)
        try {
            val lists = shelves.map { shelf -> pool.submit(Callable { runCatching { results(shelf) } }) }.map { it.get() }
            // Resolve every unknown IMDb id across all shelves in one parallel pass.
            val missing = lists.flatMap { it.getOrDefault(emptyList()) }.map { it.first }.distinct().filter { ids.get(it) == null }
            val found = missing.map { ref -> pool.submit(Callable { ref to runCatching { imdbId(ref) }.getOrNull() }) }
                .mapNotNull { task -> task.get().let { (ref, imdb) -> imdb?.let { ref to it } } }.toMap()
            ids.put(found)
            return shelves.mapIndexed { index, shelf ->
                val result = lists[index]
                val items = result.getOrDefault(emptyList()).mapNotNull { (ref, item) ->
                    ids.get(ref)?.takeIf { it.startsWith("tt") }?.let { item.copy(imdbId = it) }
                }
                CatalogApi.HomeShelf(shelf.title, shelf.type, items, result.isFailure)
            }
        } finally { pool.shutdownNow() }
    }

    /** Discover: one page (20 titles) of TMDB's discover list, optionally one genre; titles without an IMDb id drop out. */
    fun discover(type: String, genre: String, sort: String, page: Int): List<MediaItemData> {
        val movie = type == "movie"
        val genreId = if (genre.isEmpty()) null else requireNotNull(discoverGenres(type)[genre]) { "Unknown genre." }
        val date = if (movie) "primary_release_date" else "first_air_date"
        val order = when (sort) {
            SORT_TOP_RATED -> "sort_by=vote_average.desc&vote_count.gte=${if (movie) 1000 else 300}"
            // Only released titles, with a few votes so placeholder entries stay out.
            SORT_NEWEST -> "sort_by=$date.desc&$date.lte=${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}&vote_count.gte=${if (movie) 25 else 10}"
            else -> "sort_by=popularity.desc"
        }
        val path = "/discover/${if (movie) "movie" else "tv"}?include_adult=false&page=${page.coerceIn(1, 500)}&$order" +
            (genreId?.let { "&with_genres=$it" } ?: "")
        val entries = results(Shelf("", type, path))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val missing = entries.map { it.first }.distinct().filter { ids.get(it) == null }
            ids.put(missing.map { ref -> pool.submit(Callable { ref to runCatching { imdbId(ref) }.getOrNull() }) }
                .mapNotNull { task -> task.get().let { (ref, imdb) -> imdb?.let { ref to it } } }.toMap())
        } finally { pool.shutdownNow() }
        return entries.mapNotNull { (ref, item) -> ids.get(ref)?.takeIf { it.startsWith("tt") }?.let { item.copy(imdbId = it) } }
    }

    /** Checks the key with the smallest authenticated request. */
    fun validate() { json("/configuration") }

    /** One actor of a title: TMDB person id, name, the part they play and a portrait (blank when TMDB has none). */
    data class CastMember(val personId: Long, val name: String, val character: String, val photo: String, val crew: Boolean = false)
    /** An actor's films and shows, each most popular first. */
    data class Filmography(val movies: List<MediaItemData>, val shows: List<MediaItemData>)

    data class Topic(val id: Long, val name: String)
    data class DetailExtras(val keywords: List<Topic>, val similar: List<MediaItemData>,
                            val relatedTmdbId: Long = 0, val hasMoreRelated: Boolean = false,
                            val facts: List<Pair<String, String>> = emptyList(), val crew: List<CastMember> = emptyList())
    data class RelatedPage(val items: List<MediaItemData>, val nextPage: Int?)

    /**
     * Keywords and similar titles share one details request. Missing IMDb mappings are omitted.
     * [withSimilar] false (the details page) stops after the keywords, skipping the discover call and
     * the per-title IMDb lookups that only the Similar titles page needs.
     */
    fun detailExtras(imdbId: String, type: String, withSimilar: Boolean = true): DetailExtras {
        require(type in listOf("movie", "series") && imdbId.matches(Regex("tt\\d{5,12}")))
        val kind = if (type == "movie") "movie" else "tv"
        val found = json("/find/$imdbId?external_source=imdb_id").optJSONArray("${kind}_results")
        val id = found?.optJSONObject(0)?.optLong("id", -1)?.takeIf { it > 0 }
            ?: return DetailExtras(emptyList(), emptyList())
        ids.put(mapOf("$kind:$id" to imdbId))
        val data = json("/$kind/$id?append_to_response=" + (if (withSimilar) "keywords,recommendations,similar" else "keywords") +
            if (type == "movie") ",credits" else "")
        // A show's crew comes from the same whole-run credits the Cast row already downloaded: read it from the cache.
        if (type == "series") runCatching { aggregateCredits(id) }.getOrNull()?.let { data.put("aggregate_credits", it) }
        val keywords = data.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }
        val topics = (0 until (keywords?.length() ?: 0)).mapNotNull { index ->
            val entry = keywords?.optJSONObject(index) ?: return@mapNotNull null
            val topicId = entry.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val name = entry.optString("name").trim().takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            Topic(topicId, name)
        }.distinctBy { it.id }.take(20)
        val facts = runCatching { DetailFacts.from(data, type) }.getOrDefault(emptyList())
        val crew = DetailCrew.from(data, type)
        if (!withSimilar) return DetailExtras(topics, emptyList(), id, facts = facts, crew = crew)
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val genres = data.optJSONArray("genres")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optLong("id")?.takeIf { it > 0 } }.take(3)
        }.orEmpty()
        // A bounded, topical recent pool complements recommendations dominated by older classics.
        val recent = if (topics.isNotEmpty() && genres.isNotEmpty()) runCatching {
            val date = if (type == "movie") "primary_release_date" else "first_air_date"
            val excluded = listOf(16L, 10751L, 10762L).filterNot { it in genres }
            json("/discover/$kind?include_adult=false&sort_by=popularity.desc&vote_average.gte=7&vote_count.gte=${if (type == "movie") 100 else 50}" +
                "&$date.gte=${today.minusYears(5)}&$date.lte=$today&with_genres=${genres.joinToString(",")}" +
                (if (excluded.isEmpty()) "" else "&without_genres=${excluded.joinToString(",")}") +
                "&with_keywords=${Net.encode(topics.take(5).joinToString("|") { it.id.toString() })}").optJSONArray("results")
        }.getOrNull() else null
        val related = RelatedTitleSelection.select(data.optJSONObject("recommendations")?.optJSONArray("results"), recent,
            data.optJSONObject("similar")?.optJSONArray("results"), type, id, today)
        val similar = resolveEntries(related, type)
            .filterNot { it.imdbId == imdbId }
        val selectedIds = (0 until related.length()).map { related.getJSONObject(it).optLong("id") }.toSet()
        val sources = listOfNotNull(data.optJSONObject("recommendations"), data.optJSONObject("similar"))
        val more = sources.any { source -> source.optInt("total_pages", 1) > 1 || source.optJSONArray("results")?.let { list ->
            (0 until list.length()).any { list.optJSONObject(it)?.optLong("id")?.let { candidate -> candidate > 0 && candidate != id && candidate !in selectedIds } == true }
        } == true }
        return DetailExtras(topics, similar, id, more, facts, crew)
    }

    /** Page 1 also recovers candidates displaced by the initial fresh-title selection. */
    fun relatedTitles(type: String, tmdbId: Long, page: Int, excluded: Set<String>): RelatedPage {
        require(type in listOf("movie", "series") && tmdbId > 0 && page in 1..500)
        val kind = if (type == "movie") "movie" else "tv"
        val recommendations = json("/$kind/$tmdbId/recommendations?page=$page")
        val similar = json("/$kind/$tmdbId/similar?page=$page")
        val entries = RelatedTitleSelection.select(recommendations.optJSONArray("results"), null,
            similar.optJSONArray("results"), type, tmdbId, java.time.LocalDate.now(java.time.ZoneOffset.UTC), limit = 40)
        val items = resolveEntries(entries, type, limit = 40).filterNot { it.imdbId in excluded }.distinctBy { it.imdbId }
        val total = maxOf(recommendations.optInt("total_pages", 1), similar.optInt("total_pages", 1)).coerceIn(1, 500)
        return RelatedPage(items, (page + 1).takeIf { it <= total })
    }

    /** A keyword is a structured discover filter, not a title-text search. */
    fun keywordTitles(type: String, keywordId: Long, page: Int = 1): List<MediaItemData> {
        require(type in listOf("movie", "series") && keywordId > 0 && page in 1..500)
        val kind = if (type == "movie") "movie" else "tv"
        return resolveEntries(json("/discover/$kind?include_adult=false&with_keywords=$keywordId&sort_by=popularity.desc&page=$page")
            .optJSONArray("results"), type)
    }

    private fun resolveEntries(list: org.json.JSONArray?, type: String, limit: Int = 20): List<MediaItemData> {
        val kind = if (type == "movie") "movie" else "tv"
        val entries = (0 until minOf(list?.length() ?: 0, limit)).mapNotNull { index ->
            val entry = list?.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            if (!entry.optString("poster_path").startsWith('/')) return@mapNotNull null
            item(entry, type)?.let { "$kind:$id" to it }
        }.distinctBy { it.first }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val missing = entries.map { it.first }.filter { ids.get(it) == null }
            ids.put(missing.map { ref -> pool.submit(Callable { ref to runCatching { imdbId(ref) }.getOrNull() }) }
                .mapNotNull { task -> task.get().let { (ref, imdb) -> imdb?.let { ref to it } } }.toMap())
        } finally { pool.shutdownNow() }
        return entries.mapNotNull { (ref, media) -> ids.get(ref)?.takeIf { it.matches(Regex("tt\\d{5,12}")) }?.let { media.copy(imdbId = it) } }
    }

    /** The first [limit] billed actors of an IMDb title; for shows the whole run's cast (aggregate credits). */
    fun cast(imdbId: String, type: String, limit: Int = 7): List<CastMember> {
        val tv = type == "series"
        val found = json("/find/$imdbId?external_source=imdb_id").optJSONArray(if (tv) "tv_results" else "movie_results")
        val id = found?.optJSONObject(0)?.optLong("id", -1)?.takeIf { it > 0 } ?: return emptyList()
        ids.put(mapOf("${if (tv) "tv" else "movie"}:$id" to imdbId))
        val list = (if (tv) aggregateCredits(id) else json("/movie/$id/credits")).optJSONArray("cast") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .mapNotNull { entry ->
                val person = entry.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val name = entry.optString("name").ifBlank { return@mapNotNull null }
                val character = if (tv) entry.optJSONArray("roles")?.optJSONObject(0)?.optString("character").orEmpty()
                    else entry.optString("character")
                CastMember(person, name, character.trim(), image(entry.optString("profile_path"), "w185"))
            }.distinctBy { it.personId }.take(limit)
    }

    /**
     * An actor's films and shows from TMDB combined credits: posters only, no talk, news or reality shows (where they
     * appear as themselves), up to [limit] of each, most popular first, or with [byRelease] newest release first.
     * Titles without an IMDb id drop out.
     */
    fun filmography(personId: Long, limit: Int = 30, byRelease: Boolean = false, crew: Boolean = false): Filmography {
        val list = json("/person/$personId/combined_credits").optJSONArray(if (crew) "crew" else "cast") ?: return Filmography(emptyList(), emptyList())
        val entries = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("poster_path").startsWith('/') }
            .filterNot { entry -> entry.optJSONArray("genre_ids")?.let { genres -> (0 until genres.length()).any { genres.optInt(it) in NOT_ACTING } } == true }
            .let { all ->
                // Release order puts titles without a date (announced, not out) after the dated ones.
                if (byRelease) all.sortedByDescending { it.optString("release_date").ifBlank { it.optString("first_air_date") }.ifBlank { "0" } }
                else all.sortedByDescending { it.optDouble("popularity", 0.0) }
            }
        fun pick(mediaType: String, type: String) = entries.filter { it.optString("media_type") == mediaType }
            .distinctBy { it.optLong("id") }.take(limit)
            .mapNotNull { entry -> item(entry, type)?.let { "$mediaType:${entry.optLong("id")}" to it } }
        val movies = pick("movie", "movie"); val shows = pick("tv", "series")
        val pool = Executors.newFixedThreadPool(6)
        try {
            val missing = (movies + shows).map { it.first }.distinct().filter { ids.get(it) == null }
            ids.put(missing.map { ref -> pool.submit(Callable { ref to runCatching { imdbId(ref) }.getOrNull() }) }
                .mapNotNull { task -> task.get().let { (ref, imdb) -> imdb?.let { ref to it } } }.toMap())
        } finally { pool.shutdownNow() }
        fun resolved(items: List<Pair<String, MediaItemData>>) =
            items.mapNotNull { (ref, item) -> ids.get(ref)?.takeIf { it.startsWith("tt") }?.let { item.copy(imdbId = it) } }
        return Filmography(resolved(movies), resolved(shows))
    }

    private fun item(entry: JSONObject, type: String): MediaItemData? {
        val title = entry.optString(if (type == "movie") "title" else "name").ifBlank { return null }
        val genres = entry.optJSONArray("genre_ids")?.let { array -> (0 until array.length()).mapNotNull { GENRES[array.optInt(it)] } }.orEmpty()
        val rating = entry.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
        return MediaItemData(
            imdbId = "", type = type, title = title,
            year = entry.optString(if (type == "movie") "release_date" else "first_air_date").take(4),
            poster = image(entry.optString("poster_path"), "w342"),
            background = image(entry.optString("backdrop_path"), "w1280"),
            description = entry.optString("overview"), genres = genres.distinct().take(3), rating = rating,
        )
    }

    private fun results(shelf: Shelf): List<Pair<String, MediaItemData>> {
        val list = json(shelf.path).optJSONArray("results") ?: return emptyList()
        val tmdbType = if (shelf.type == "movie") "movie" else "tv"
        return (0 until minOf(list.length(), 20)).mapNotNull { index ->
            val entry = list.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val title = entry.optString(if (shelf.type == "movie") "title" else "name").ifBlank { return@mapNotNull null }
            val genres = entry.optJSONArray("genre_ids")?.let { array -> (0 until array.length()).mapNotNull { GENRES[array.optInt(it)] } }.orEmpty()
            val rating = entry.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
            "$tmdbType:$id" to MediaItemData(
                imdbId = "", type = shelf.type, title = title,
                year = entry.optString(if (shelf.type == "movie") "release_date" else "first_air_date").take(4),
                poster = image(entry.optString("poster_path"), "w342"),
                background = image(entry.optString("backdrop_path"), "w1280"),
                description = entry.optString("overview"), genres = genres.distinct().take(3), rating = rating,
            )
        }
    }

    private fun imdbId(ref: String): String {
        val (type, id) = ref.split(':', limit = 2)
        // "-" records a title TMDB has no IMDb id for, so it is not asked about again.
        return json("/$type/$id/external_ids").optString("imdb_id").takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: "-"
    }

    /** A show's whole-run credits (large for long shows), kept for the last few shows so Cast and Crew share one download. */
    private fun aggregateCredits(id: Long): JSONObject = synchronized(AGGREGATE_CREDITS) { AGGREGATE_CREDITS[id] }
        ?: json("/tv/$id/aggregate_credits").also { credits -> synchronized(AGGREGATE_CREDITS) { AGGREGATE_CREDITS[id] = credits } }

    private fun json(path: String): JSONObject {
        val bearer = key.startsWith("eyJ")
        val separator = if ('?' in path) '&' else '?'
        val url = "$BASE$path${separator}language=en-US" + if (bearer) "" else "&api_key=$key"
        val raw = get(url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())
        return try { JSONObject(raw) } catch (_: Exception) { throw IllegalStateException("TMDB returned an unreadable response.") }
    }

    private fun image(path: String, size: String) = if (path.startsWith('/')) "https://image.tmdb.org/t/p/$size$path" else ""

    private class PreferenceIdCache(context: Context) : IdCache {
        private val preferences = context.applicationContext.getSharedPreferences("pov_tmdb_ids", Context.MODE_PRIVATE)
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(values: Map<String, String>) {
            if (values.isNotEmpty()) preferences.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.apply()
        }
    }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        private val AGGREGATE_CREDITS = object : LinkedHashMap<Long, JSONObject>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, JSONObject>?) = size > 3
        }
        const val SORT_POPULAR = "popular"
        const val SORT_TOP_RATED = "top_rated"
        const val SORT_NEWEST = "newest"
        /** Discover's genre chips: TMDB keeps separate genre ids for films and TV. */
        fun discoverGenres(type: String): Map<String, Int> = if (type == "movie") linkedMapOf(
            "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
            "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402,
            "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37,
        ) else linkedMapOf(
            "Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
            "Drama" to 18, "Family" to 10751, "Kids" to 10762, "Mystery" to 9648, "Reality" to 10764,
            "Sci-Fi & Fantasy" to 10765, "Soap" to 10766, "Talk" to 10767, "War & Politics" to 10768, "Western" to 37,
        )
        // TMDB's fixed genre ids for movies and TV.
        /** TV talk, news and reality genres: an actor there is usually a guest appearing as themselves. */
        private val NOT_ACTING = setOf(10767, 10763, 10764)
        private val GENRES = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
            18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
            9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
            10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
            10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
        )
    }
}
