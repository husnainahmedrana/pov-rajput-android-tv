package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONObject
import java.net.URI

/** A displayed anime episode retains the canonical identity sent to sources and WeTrakr. */
data class AnimeEpisodeChoice(
    val catalogEpisode: Int,
    val absoluteEpisode: Int?,
    val title: String,
    val released: String,
    val thumbnail: String,
    val identity: PlaybackIdentity
)

data class AnimeEpisodeGroup(val title: String, val season: Int, val episodes: List<AnimeEpisodeChoice>)

/** [media.episodes] contains this selected anime entry's episodes, never the full parent show. */
data class AnimeEpisodeCatalog(
    val anime: AnimeTitle,
    val media: MediaItemData,
    val groups: List<AnimeEpisodeGroup>,
    val missingMappings: Int = 0,
    val notice: String = ""
)

/** Uses only the metadata provider's explicit per-video mapping, checked against canonical S/E. */
internal object AnimeEpisodeCatalogParser {
    fun parse(payload: JSONObject, anime: AnimeTitle, canonical: MediaItemData): AnimeEpisodeCatalog {
        val meta = payload.optJSONObject("meta") ?: unavailable()
        check(meta.optString("id") == "kitsu:${anime.kitsuId}" && meta.optString("kitsu_id") == anime.kitsuId &&
            meta.optString("type") == anime.type && canonical.type == anime.type) { L10n.tr(R.string.t_the_anime_metadata_does_not_match, "The anime metadata does not match the selected title.") }
        if (!canonical.imdbId.matches(Regex("tt[0-9]{5,12}"))) unavailable()
        val selected = canonical.copy(title = anime.title, year = anime.year.ifBlank { canonical.year },
            poster = anime.poster.ifBlank { canonical.poster }, background = anime.background.ifBlank { canonical.background },
            description = anime.description.ifBlank { canonical.description }, genres = (listOf("Anime") + anime.genres + canonical.genres).distinct())
        if (anime.type == "movie") return AnimeEpisodeCatalog(anime, selected.copy(episodes = emptyList()), emptyList(),
            notice = L10n.tr(R.string.t_anime_movie_playback_and_wetrakr_use, "Anime movie. Playback and WeTrakr use this film's IMDb identity."))
        val videos = meta.optJSONArray("videos") ?: unavailable()
        val canonicalEpisodes = canonical.episodes.associateBy { it.season to it.episode }
        val candidates = (0 until videos.length()).mapNotNull { index ->
            val video = videos.optJSONObject(index) ?: return@mapNotNull null
            val number = video.positiveInt("episode") ?: return@mapNotNull null
            val videoId = video.optString("id")
            // The provider uses a root-only video ID for a single long special/presentation.
            // Its explicit episode=1 and canonical IMDb S/E are still mandatory; never infer
            // the episode from that ID or accept it for one row of a multi-episode season.
            val singlePresentation = videos.length() == 1 && number == 1 && videoId == "kitsu:${anime.kitsuId}"
            if (videoId != "kitsu:${anime.kitsuId}:$number" && !singlePresentation) return@mapNotNull null
            if (video.optString("imdb_id") != canonical.imdbId) return@mapNotNull null
            val season = video.nonNegativeInt("imdbSeason") ?: return@mapNotNull null
            val episode = video.positiveInt("imdbEpisode") ?: return@mapNotNull null
            val verified = canonicalEpisodes[season to episode] ?: return@mapNotNull null
            AnimeEpisodeChoice(number, video.positiveInt("absoluteEpisode"),
                video.string("title").ifBlank { verified.title }.ifBlank { "Episode $number" },
                verified.released.ifBlank { video.string("released") },
                artwork(video.string("thumbnail")).ifBlank { verified.thumbnail },
                PlaybackIdentity(canonical.imdbId, "series", canonical.title, season, episode))
        }
        // Conflicting local numbering or two catalog episodes pointing to the same canonical
        // episode are unsafe. Exclude every conflicting record and report the missing mappings.
        val uniqueCatalogNumbers = candidates.groupBy { it.catalogEpisode }.filterValues { it.size == 1 }.values.flatten()
        val choices = uniqueCatalogNumbers.groupBy { it.identity.season to it.identity.episode }
            .filterValues { it.size == 1 }.values.flatten().sortedBy { it.catalogEpisode }
        val groups = choices.groupBy { it.identity.season }.toSortedMap().map { (season, episodes) ->
            val label = when {
                anime.category == "special" && season == 0 -> L10n.tr(R.string.t_specials_and_ovas, "Specials and OVAs")
                anime.category == "special" -> L10n.tr(R.string.t_special_presentation_season_x, "Special presentation · Season %1\$s", season)
                season == 0 -> L10n.tr(R.string.t_specials, "Specials")
                else -> L10n.tr(R.string.t_season_x, "Season %1\$s", season)
            }
            AnimeEpisodeGroup(label, season, episodes)
        }
        val missing = videos.length() - choices.size
        val note = when {
            choices.isEmpty() -> L10n.tr(R.string.t_episode_mappings_for_this_anime_entry, "Episode mappings for this anime entry could not be verified. Choose a matching title manually; no episodes have been substituted.")
            missing > 0 -> "$missing episode ${if (missing == 1) "mapping is" else "mappings are"} unavailable. Only verified episodes from this anime entry are shown."
            anime.category == "special" -> L10n.tr(R.string.t_special_or_ova_presentation_source_searches, "Special or OVA presentation. Source searches and WeTrakr keep the original show episode numbers.")
            else -> L10n.tr(R.string.t_only_this_selected_season_or_part, "Only this selected season or part is shown. Source searches and WeTrakr keep the original show episode numbers.")
        }
        val scopedEpisodes = choices.map { choice -> EpisodeData(choice.identity.season, choice.identity.episode,
            choice.title, choice.released, choice.thumbnail) }
        return AnimeEpisodeCatalog(anime, selected.copy(episodes = scopedEpisodes), groups, missing, note)
    }

    private fun JSONObject.positiveInt(key: String): Int? = nonNegativeInt(key)?.takeIf { it > 0 }
    private fun JSONObject.nonNegativeInt(key: String): Int? {
        val value = opt(key) as? Number ?: return null
        val asLong = value.toLong()
        return asLong.takeIf { it in 0..1_000_000 && value.toDouble() == it.toDouble() }?.toInt()
    }
    private fun JSONObject.string(key: String): String = (opt(key) as? String)?.trim().orEmpty()
    private fun artwork(value: String): String = try {
        val uri = URI(value)
        value.takeIf { uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null }.orEmpty()
    } catch (_: Exception) { "" }
    private fun unavailable(): Nothing = throw ApiException(0, L10n.tr(R.string.t_verified_anime_episode_metadata_is_currently, "Verified anime episode metadata is currently unavailable. Please try again later."))
}
