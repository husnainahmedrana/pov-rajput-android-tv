package dev.pov.tv.data

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Live IMDb scores (rating and vote count) for a title, and for every episode of one season in a single request.
 * Cinemeta's own episode "rating" is not IMDb's, and its title score can lag behind, so the details page asks here.
 * Any failure returns null: the page then keeps Cinemeta's title score and shows no episode scores.
 */
class ImdbRatings internal constructor(private val post: (JSONObject) -> String) {
    constructor() : this({ body -> Net.raw(ENDPOINT, "POST", HEADERS, body) })

    data class Score(val rating: Double, val votes: Int) {
        val label: String get() = String.format(Locale.ENGLISH, "%.1f", rating)
        val votesLabel: String get() = compactVotes(votes)
    }
    /** [airDates] are IMDb's original air dates, also for episodes not yet aired (they have no score yet). */
    data class SeasonScores(val title: Score?, val episodes: Map<Int, Score>, val airDates: Map<Int, LocalDate> = emptyMap())

    fun title(imdbId: String): Score? {
        require(imdbId.matches(ID))
        return runCatching { parseTitle(JSONObject(post(query(TITLE_QUERY, JSONObject().put("id", imdbId))))) }.getOrNull()
    }

    fun season(imdbId: String, season: Int): SeasonScores? {
        require(imdbId.matches(ID) && season > 0)
        return runCatching {
            parseSeason(JSONObject(post(query(SEASON_QUERY, JSONObject().put("id", imdbId).put("season", season.toString())))), season)
        }.getOrNull()
    }

    companion object {
        private const val ENDPOINT = "https://caching.graphql.imdb.com/"
        private val HEADERS = mapOf("x-imdb-client-name" to "imdb-web-next-localized")
        private val ID = Regex("tt\\d{5,12}")
        private const val SCORE = "ratingsSummary { aggregateRating voteCount }"
        private const val TITLE_QUERY = "query(\$id: ID!) { title(id: \$id) { $SCORE } }"
        private const val SEASON_QUERY = "query(\$id: ID!, \$season: String!) { title(id: \$id) { $SCORE " +
            "episodes { episodes(first: 250, filter: {includeSeasons: [\$season]}) { edges { node { " +
            "series { episodeNumber { episodeNumber seasonNumber } } releaseDate { day month year } $SCORE } } } } } }"

        private fun query(text: String, variables: JSONObject) = JSONObject().put("query", text).put("variables", variables)

        /** A title with no votes yet has a null rating: no score, rather than a misleading 0.0. */
        internal fun score(summary: JSONObject?): Score? {
            if (summary == null || summary.isNull("aggregateRating")) return null
            val rating = summary.optDouble("aggregateRating", Double.NaN).takeIf { it in 0.1..10.0 } ?: return null
            return Score(rating, summary.optInt("voteCount").coerceAtLeast(0))
        }

        internal fun parseTitle(json: JSONObject): Score? =
            score(json.optJSONObject("data")?.optJSONObject("title")?.optJSONObject("ratingsSummary"))

        internal fun parseSeason(json: JSONObject, season: Int): SeasonScores? {
            val title = json.optJSONObject("data")?.optJSONObject("title") ?: return null
            val edges = title.optJSONObject("episodes")?.optJSONObject("episodes")?.optJSONArray("edges")
            val episodes = mutableMapOf<Int, Score>()
            val airDates = mutableMapOf<Int, LocalDate>()
            for (index in 0 until (edges?.length() ?: 0)) {
                val node = edges?.optJSONObject(index)?.optJSONObject("node") ?: continue
                val number = node.optJSONObject("series")?.optJSONObject("episodeNumber") ?: continue
                // IMDb occasionally files an episode under another season; trust only this season's numbers.
                if (number.optInt("seasonNumber", -1) != season) continue
                val episode = number.optInt("episodeNumber", -1).takeIf { it > 0 } ?: continue
                score(node.optJSONObject("ratingsSummary"))?.let { episodes[episode] = it }
                date(node.optJSONObject("releaseDate"))?.let { airDates[episode] = it }
            }
            return SeasonScores(score(title.optJSONObject("ratingsSummary")), episodes, airDates)
        }

        /** Only a full day, month and year counts: "2026" alone is a plan, not an air date. */
        internal fun date(json: JSONObject?): LocalDate? {
            if (json == null || json.isNull("day") || json.isNull("month") || json.isNull("year")) return null
            return runCatching { LocalDate.of(json.getInt("year"), json.getInt("month"), json.getInt("day")) }.getOrNull()
        }

        internal fun compactVotes(votes: Int): String = when {
            votes >= 1_000_000 -> String.format(Locale.ENGLISH, "%.1fM", votes / 1_000_000.0).replace(".0M", "M")
            votes >= 1_000 -> if (votes >= 10_000) "${votes / 1_000}K"
                else String.format(Locale.ENGLISH, "%.1fK", votes / 1_000.0).replace(".0K", "K")
            else -> votes.toString()
        }
    }
}
