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
    /** Usenet: days since the release was posted (newer posts are less likely to miss articles); null when unknown. */
    val ageDays: Long? = null,
    /** The add-on (SourceProvider id) the link came from; empty for the app's own Usenet and Easynews search. */
    val providerId: String = "",
    /**
     * A torrent's own name when the add-on also sends the file's (Torrentio: a season pack "From [S01] WEB-DL 1080p"
     * holding "From.S01E01.mkv"). Real-Debrid judges this name, so its copyright filter checks it too.
     */
    val torrentName: String = "",
    /** Subtitles the add-on sent with this link (Stremio stream `subtitles`), made for this exact file. */
    val subtitles: List<StreamSubtitle> = emptyList(),
)

/** One subtitle an add-on attached to a stream: its address, language code and the add-on it came from. */
data class StreamSubtitle(val url: String, val language: String, val source: String = "") {
    companion object {
        fun toJson(list: List<StreamSubtitle>): String = org.json.JSONArray().apply {
            list.forEach { put(org.json.JSONObject().put("url", it.url).put("lang", it.language).put("source", it.source)) }
        }.toString()

        fun fromJson(text: String?): List<StreamSubtitle> = runCatching {
            val array = org.json.JSONArray(text ?: "[]")
            (0 until minOf(array.length(), 100)).mapNotNull { index -> array.optJSONObject(index)?.let { item ->
                StreamSubtitle(item.optString("url"), item.optString("lang"), item.optString("source")).takeIf { it.url.isNotBlank() }
            } }
        }.getOrDefault(emptyList())
    }
}

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
        val accountTag: String,
        /** The link that was prepared (check-on-play tries several): its add-on and scraper logo go with the video. */
        val source: SourceItem? = null
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
