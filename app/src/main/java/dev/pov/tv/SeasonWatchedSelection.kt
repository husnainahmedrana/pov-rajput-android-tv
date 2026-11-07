package dev.pov.tv

import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.WatchedState
import java.time.Instant

internal object SeasonWatchedSelection {
    fun pending(show: MediaItemData, season: Int, watched: WatchedState, now: Instant = Instant.now()): List<PlaybackIdentity> {
        require(show.type == "series" && season >= 0)
        return show.episodes.filter { it.season == season && it.episode > 0 && !EpisodePresentation.future(it.released, now) &&
            !watched.episode(show.imdbId, season, it.episode) }.distinctBy { it.episode }.sortedBy { it.episode }
            .map { PlaybackIdentity(show.imdbId, "series", show.title, season, it.episode) }
    }
}
