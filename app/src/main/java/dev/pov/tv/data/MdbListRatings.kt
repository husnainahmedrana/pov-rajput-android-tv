package dev.pov.tv.data

import org.json.JSONObject

/**
 * Ratings from MDBList (mdblist.com) with the viewer's own free API key: one request per title brings IMDb, TMDB,
 * Trakt, Letterboxd and Rotten Tomatoes (critics and audience) scores. The free key allows about 1,000
 * requests a day per account, so the app asks only for the title the Home feature has settled on and keeps answers.
 */
class MdbListRatings internal constructor(private val get: (String) -> String) {
    constructor() : this({ url -> Net.raw(url) })

    /**
     * One score: [value] on the source's own scale (IMDb 0-10, Letterboxd 0-5, the others 0-100). [certified]: Rotten
     * Tomatoes marks it Certified Fresh (critics) or Verified Hot (audience), from MDBList's keywords.
     */
    data class Rating(val source: String, val value: Double, val certified: Boolean = false)

    fun title(key: String, type: String, imdbId: String): List<Rating> {
        require(key.isNotBlank()) { "MDBList key is missing." }
        require(imdbId.matches(ID)) { "Not an IMDb id." }
        val kind = if (type == "movie") "movie" else "show"
        // The keywords say whether Rotten Tomatoes certified the scores.
        return parse(get("$BASE/imdb/$kind/$imdbId/?apikey=${Net.encode(key.trim())}&append_to_response=keyword"))
    }

    /** Checks a key by asking for a well-known title; throws when MDBList refuses it. */
    fun validate(key: String) {
        val ratings = title(key, "movie", "tt0111161")
        check(ratings.isNotEmpty()) { "MDBList answered without ratings." }
    }

    companion object {
        const val KEY = "mdblist.key"
        private const val BASE = "https://api.mdblist.com"
        private val ID = Regex("tt\\d{5,10}")
        /** Shown in this order; MDBList's other sources (Metacritic, whose scores were often missing or odd, Roger Ebert, MyAnimeList…) are left out. */
        val ORDER = listOf("imdb", "tmdb", "trakt", "letterboxd", "tomatoes", "popcorn")

        internal fun parse(text: String): List<Rating> {
            val answer = JSONObject(text)
            val ratings = answer.optJSONArray("ratings") ?: return emptyList()
            // Keywords come as {"name": "…"} or plain strings, sometimes prefixed ("rt.certified-fresh").
            val keywords = answer.optJSONArray("keywords")?.let { list ->
                (0 until list.length()).mapNotNull { index ->
                    (list.optJSONObject(index)?.optString("name") ?: list.optString(index)).takeIf { it.isNotBlank() }?.substringAfterLast('.')?.lowercase()
                }.toSet()
            }.orEmpty()
            val found = (0 until ratings.length()).mapNotNull { index ->
                val entry = ratings.optJSONObject(index) ?: return@mapNotNull null
                val source = when (val name = entry.optString("source").lowercase()) {
                    "audience", "tomatoesaudience" -> "popcorn"
                    else -> name
                }
                if (source !in ORDER) return@mapNotNull null
                val raw = entry.optDouble("value", Double.NaN)
                // Letterboxd's value is out of 5; some answers carry only its 0-100 score.
                val value = if (source == "letterboxd" && (raw.isNaN() || raw > 5.0)) entry.optDouble("score", Double.NaN) / 20.0 else raw
                val maximum = when (source) { "imdb" -> 10.0; "letterboxd" -> 5.0; else -> 100.0 }
                val certified = (source == "tomatoes" && "certified-fresh" in keywords) || (source == "popcorn" && "certified-hot" in keywords)
                if (value.isNaN() || value <= 0.0 || value > maximum) null else Rating(source, value, certified)
            }.distinctBy { it.source }
            return found.sortedBy { ORDER.indexOf(it.source) }
        }
    }
}
