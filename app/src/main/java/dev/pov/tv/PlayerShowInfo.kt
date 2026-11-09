package dev.pov.tv

import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.TmdbCatalog

/**
 * What the player's Info and Episodes panels need from Home: the title's details (episode list, synopsis, runtime) and
 * a TMDB client for episode cast and runtimes. Home fills these in onCreate and clears them in onDestroy; both run on a
 * worker. [pickedEpisode] is an episode chosen in the Episodes panel: Home searches its links and plays it once the
 * player closes, like Next episode.
 */
internal object PlayerShowInfo {
    @Volatile var details: ((PlaybackIdentity) -> MediaItemData?)? = null
    @Volatile var tmdb: (() -> TmdbCatalog?)? = null
    @Volatile var pickedEpisode: PlaybackIdentity? = null
}
