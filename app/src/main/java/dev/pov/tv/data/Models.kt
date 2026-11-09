package dev.pov.tv.data

import org.json.JSONObject

data class MediaItemData(
    val imdbId: String,
    val type: String,
    val title: String,
    val year: String = "",
    val poster: String = "",
    val background: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val runtime: String = "",
    val rating: String = "",
    val episodes: List<EpisodeData> = emptyList()
)

data class EpisodeData(
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String = "",
    val thumbnail: String = "",
    val overview: String = ""
)

data class PlaybackIdentity(
    val imdbId: String,
    val type: String,
    val title: String,
    val season: Int = 0,
    val episode: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imdbId", imdbId).put("type", type).put("title", title)
        .put("season", season).put("episode", episode)

    companion object {
        fun fromJson(json: JSONObject): PlaybackIdentity = PlaybackIdentity(
            json.optString("imdbId"), json.optString("type"), json.optString("title"),
            json.optInt("season"), json.optInt("episode")
        )
    }
}

data class CloudFile(val id: Long, val name: String, val size: Long)
data class CloudFolder(
    val id: Long, val kind: String, val name: String, val ready: Boolean,
    val files: List<CloudFile>
)

data class DeviceAuth(
    val deviceCode: String, val userCode: String, val verificationUrl: String,
    val intervalSeconds: Int, val expiresSeconds: Int
)

data class SourceItem(
    val title: String, val provider: String, val infoHash: String = "",
    val fileIndex: Int? = null, val directUrl: String = "", val cached: Boolean = false,
    val detail: String = "",
    val cachedServices: List<String> = emptyList(),
    val downloadService: String? = null,
    val sizeBytes: Long? = null,
    val qualityHeight: Int? = null,
    /** Request headers the addon requires (Stremio `behaviorHints.proxyHeaders.request`). */
    val headers: Map<String, String> = emptyMap(),
    /** The add-on's own logo for this link (AddonBadges: PenguPlay, CNCVerse), known from the add-on's address. */
    val addon: String? = null,
    /** AIOStreams: the link streams from Usenet (else debrid or direct), from its stream data or title. */
    val usenet: Boolean = false,
    /** AIOStreams: the Usenet indexer the NZB came from (NZBgeek, NzbNest…). */
    val indexer: String = "",
    /** AIOStreams: the debrid service that serves a debrid link (DebridServices id). */
    val servedBy: String? = null,
    /** The news provider the viewer set for this add-on (Easynews, Newshosting), for its logo on Usenet links. */
    val newsProvider: String = "",
    /** Already in the viewer's library (AIOStreams says so in its stream data): plays at once, nothing new is added. */
    val inLibrary: Boolean = false,
    /**
     * A ready-made link from an add-on holding the viewer's debrid key ("[TB+]", "[RD download]"): true cached, false the
     * add-on must download it first (it won't play at once), null unknown or not such a link. [servedBy] names the service.
     */
    val addonCached: Boolean? = null,
    /** A torrent from a scraper add-on (Torrentio, Comet…) that POV's own debrid plays: the scraper, for its logo. */
    val scraper: String? = null,
)

/** A selected source either streams directly or offers stable TorBox file identities. */
sealed interface PreparedSource {
    val identity: PlaybackIdentity

    data class Direct(val url: String, override val identity: PlaybackIdentity,
                      val headers: Map<String, String> = emptyMap()) : PreparedSource

    data class OffcloudFiles(
        val requestId: String?,
        val files: List<OffcloudFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val accountTag: String
    ) : PreparedSource

    /** Real-Debrid, AllDebrid, Premiumize and Debrid-Link: files whose links are unlocked when one is played. */
    data class Links(
        val service: String,
        val files: List<DebridFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val accountTag: String
    ) : PreparedSource

    data class Files(
        val folder: CloudFolder,
        val files: List<CloudFile>,
        override val identity: PlaybackIdentity,
        val preferredFileId: Long?,
        val resolvedUrls: Map<Long, String> = emptyMap(),
        val accountTag: String? = null,
        val sourceInfoHash: String? = null
    ) : PreparedSource {
        /** An exact filename can suggest focus, but never hides other available versions. */
        val automaticFile: CloudFile? get() = files.singleOrNull()
    }
}
