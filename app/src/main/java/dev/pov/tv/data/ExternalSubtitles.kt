package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/** A subtitle file found online for the playing title. [language] is a two-letter code; [mime] a Media3 MIME type. */
internal data class ExternalSubtitle(val url: String, val language: String, val label: String, val mime: String, val score: Int)

/** A Stremio add-on the viewer installed that may offer subtitles: its name and base address (no /manifest.json). */
internal data class SubtitleAddon(val name: String, val base: String)

/**
 * Subtitles for videos that have none in the viewer's language: OpenSubtitles through its free Stremio add-on (no key),
 * plus Wyzie Subs and SubDL when the viewer adds their own free keys. Results that share words with the playing file's name
 * (release group, source, resolution) usually match its timing best, so they come first. Call on a worker.
 */
internal class ExternalSubtitles(
    /** Where SubDL's subtitles are unpacked (it sends zips); without it SubDL is skipped. */
    private val cacheDir: File? = null,
    private val download: (String) -> ByteArray = ::bytes,
    private val fetch: (String) -> String = { Net.raw(it) },
) {
    /**
     * [addons]: the viewer's installed add-ons; those whose manifest offers subtitles are asked too (Stremio
     * /subtitles/ route), up to two of each one's best matches. [attached]: subtitles the playing link's add-on sent
     * with the stream itself; they were made for that exact file, so they come first.
     */
    fun find(identity: PlaybackIdentity, language: String, fileName: String, wyzieKey: String = "", limit: Int = 3,
             subdlKey: String = "", addons: List<SubtitleAddon> = emptyList(), attached: List<StreamSubtitle> = emptyList(),
             /** False: only the viewer's add-ons and [attached] (the video has this language built in, or auto-fetch is off). */
             builtIn: Boolean = true): List<ExternalSubtitle> {
        if (language.length != 2) return emptyList()
        val wanted = iso3(language)
        val withStream = attached.filter { iso3(it.language) == wanted && safeUrl(it.url) }.distinctBy { it.url }.take(limit)
            .map { ExternalSubtitle(it.url, language, label(language, it.source.ifBlank { "Stream" }), mimeOf(it.url), Int.MAX_VALUE) }
        if (!IMDB.matches(identity.imdbId)) return withStream
        val found = if (!builtIn) emptyList() else runCatching { openSubtitles(identity, language, fileName) }.getOrDefault(emptyList()) +
            (if (wyzieKey.isBlank()) emptyList() else runCatching { wyzie(identity, language, fileName, wyzieKey) }.getOrDefault(emptyList())) +
            (cacheDir?.takeIf { subdlKey.isNotBlank() }?.let { dir -> runCatching { subdl(identity, language, fileName, subdlKey, dir) }.getOrDefault(emptyList()) } ?: emptyList())
        val builtIn = found.distinctBy { it.url }.sortedByDescending { it.score }.take(limit)
        // Each add-on keeps its own best two, so the viewer's own subtitle add-ons are never crowded out.
        val fromAddons = addons.distinctBy { it.base }.filterNot { it.base.trimEnd('/').equals(OPEN_SUBTITLES, ignoreCase = true) }.flatMap { addon ->
            runCatching { addonSubtitles(addon, identity, language, fileName) }.getOrDefault(emptyList()).sortedByDescending { it.score }.take(2)
        }
        return (withStream + builtIn + fromAddons).distinctBy { it.url }
    }

    /** An installed add-on's subtitles, when its manifest offers them for this title. */
    private fun addonSubtitles(addon: SubtitleAddon, identity: PlaybackIdentity, language: String, fileName: String): List<ExternalSubtitle> {
        val type = if (identity.type == "series") "series" else "movie"
        val info = AddonManifests.info(addon.base) { JSONObject(fetch(it)) } ?: return emptyList()
        if (!info.offers("subtitles", type, identity.imdbId)) return emptyList()
        return stremioSubtitles("${addon.base}/subtitles/$type/${stremioId(identity)}.json", identity, language, fileName, addon.name)
    }

    /**
     * SubDL (subdl.com, the old Subscene library) with the viewer's free key. Its subtitles come as zips, so only the two
     * best-named matches are downloaded and unpacked to [dir]; for an episode, the file for that episode is taken from
     * a season pack.
     */
    private fun subdl(identity: PlaybackIdentity, language: String, fileName: String, key: String, dir: File): List<ExternalSubtitle> {
        val episode = identity.type == "series" && identity.season > 0 && identity.episode > 0
        val query = buildString {
            append("api_key=${Net.encode(key)}&imdb_id=${identity.imdbId}&languages=${language.uppercase(Locale.ROOT)}&subs_per_page=10")
            append(if (identity.type == "series") "&type=tv" else "&type=movie")
            if (episode) append("&season_number=${identity.season}&episode_number=${identity.episode}")
        }
        val json = JSONObject(fetch("$SUBDL/api/v1/subtitles?$query"))
        if (!json.optBoolean("status")) return emptyList()
        val list = json.optJSONArray("subtitles") ?: return emptyList()
        val picks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("url").startsWith("/subtitle/") && it.optString("language").equals(language, ignoreCase = true) }
            .sortedByDescending { score(fileName, it.optString("release_name")) }.take(2)
        dir.mkdirs()
        return picks.mapNotNull { item ->
            runCatching {
                val file = unzip(download("$SUBDL_FILES${item.optString("url")}"), identity, dir, item.optString("url").hashCode()) ?: return@runCatching null
                val mime = when (file.extension.lowercase(Locale.ROOT)) { "vtt" -> VTT; "ass", "ssa" -> SSA; else -> SUBRIP }
                ExternalSubtitle("file://${file.absolutePath}", language, label(language, "SubDL"), mime, score(fileName, item.optString("release_name")))
            }.getOrNull()
        }
    }

    /** The subtitle in a SubDL zip (the episode's own file in a season pack), written to [dir]. */
    private fun unzip(zip: ByteArray, identity: PlaybackIdentity, dir: File, id: Int): File? {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!entry.isDirectory && ext in setOf("srt", "vtt", "ass", "ssa")) {
                    // Read at most one byte past the limit: an oversized (or zip-bomb) entry is skipped, never inflated whole.
                    val bytes = readUpTo(input, MAX_SUBTITLE_BYTES + 1)
                    if (bytes.size in 1..MAX_SUBTITLE_BYTES) entries += entry.name to bytes
                }
                if (entries.size >= 60) break
            }
        }
        val chosen = if (identity.type == "series" && identity.episode > 0) {
            val tag = Regex("(?i)s0*${identity.season}\\W?e0*${identity.episode}(?!\\d)|(?<!\\d)0*${identity.season}x0*${identity.episode}(?!\\d)")
            entries.firstOrNull { tag.containsMatchIn(it.first) } ?: entries.singleOrNull()
        } else entries.maxByOrNull { it.second.size }
        val (name, bytes) = chosen ?: return null
        return File(dir, "subdl-$id.${name.substringAfterLast('.').lowercase(Locale.ROOT)}").apply { writeBytes(bytes) }
    }

    private fun stremioId(identity: PlaybackIdentity): String =
        if (identity.type == "series" && identity.season > 0 && identity.episode > 0) "${identity.imdbId}:${identity.season}:${identity.episode}" else identity.imdbId

    private fun openSubtitles(identity: PlaybackIdentity, language: String, fileName: String): List<ExternalSubtitle> {
        val type = if (identity.type == "series") "series" else "movie"
        return stremioSubtitles("$OPEN_SUBTITLES/subtitles/$type/${stremioId(identity)}.json", identity, language, fileName, "OpenSubtitles")
    }

    /** A Stremio add-on's subtitles answer ({"subtitles":[{url, lang, ...}]}), kept to [language] and this episode. */
    private fun stremioSubtitles(address: String, identity: PlaybackIdentity, language: String, fileName: String, source: String): List<ExternalSubtitle> {
        val episode = identity.type == "series" && identity.season > 0 && identity.episode > 0
        val subtitles = JSONObject(fetch(address)).optJSONArray("subtitles") ?: return emptyList()
        val wanted = iso3(language)
        return (0 until minOf(subtitles.length(), 500)).mapNotNull { index ->
            val item = subtitles.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (!safeUrl(url) || iso3(item.optString("lang")) != wanted) return@mapNotNull null
            // Season packs can list other episodes' files.
            if (episode && item.optInt("season") > 0 && (item.optInt("season") != identity.season || item.optInt("episode") != identity.episode)) return@mapNotNull null
            val name = listOf("subtitleFileName", "movieReleaseName", "release", "title").map(item::optString).firstOrNull { it.isNotBlank() }.orEmpty()
            ExternalSubtitle(url, language, label(language, source), mimeOf(url), score(fileName, name))
        }
    }

    private fun wyzie(identity: PlaybackIdentity, language: String, fileName: String, key: String): List<ExternalSubtitle> {
        val episode = identity.type == "series" && identity.season > 0 && identity.episode > 0
        val query = buildString {
            append("id=${identity.imdbId}&language=$language&key=${Net.encode(key)}")
            if (episode) append("&season=${identity.season}&episode=${identity.episode}")
        }
        val results = JSONArray(fetch("$WYZIE/search?$query"))
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (!url.startsWith("https://") || !item.optString("language").equals(language, ignoreCase = true)) return@mapNotNull null
            val mime = when (item.optString("format").lowercase(Locale.ROOT)) { "srt", "" -> SUBRIP; "vtt" -> VTT; "ass", "ssa" -> SSA; else -> return@mapNotNull null }
            val name = listOf("release", "fileName", "media").map(item::optString).firstOrNull { it.isNotBlank() }.orEmpty()
            ExternalSubtitle(url, language, label(language, "Wyzie"), mime, score(fileName, name))
        }
    }

    companion object {
        private const val OPEN_SUBTITLES = "https://opensubtitles-v3.strem.io"
        private const val WYZIE = "https://sub.wyzie.io"
        private const val SUBDL = "https://api.subdl.com"
        private const val SUBDL_FILES = "https://dl.subdl.com"
        private const val MAX_SUBTITLE_BYTES = 3 * 1024 * 1024

        /** A subtitle zip, even a whole season's, is far below this; anything bigger isn't one. */
        private const val MAX_ZIP_BYTES = 20 * 1024 * 1024

        private fun bytes(address: String): ByteArray {
            val connection = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 12_000; readTimeout = 20_000 }
            try {
                check(connection.responseCode in 200..299) { "SubDL download failed" }
                val bytes = connection.inputStream.use { readUpTo(it, MAX_ZIP_BYTES + 1) }
                check(bytes.size <= MAX_ZIP_BYTES) { "SubDL download too large" }
                return bytes
            } finally { connection.disconnect() }
        }

        /** Up to [limit] bytes of [input]; stops there instead of reading (and holding) all of it. */
        private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
        /** https, or plain http only to a server on the viewer's own network (a self-hosted add-on). */
        fun safeUrl(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true) && LocalAddress.isLocal(uri.host))
        }.getOrDefault(false)

        /** Subtitle format from the address; most add-ons serve SubRip. */
        fun mimeOf(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "vtt" -> VTT
            "ass", "ssa" -> SSA
            else -> SUBRIP
        }
        const val SUBRIP = "application/x-subrip"
        const val VTT = "text/vtt"
        const val SSA = "text/x-ssa"
        private val IMDB = Regex("tt[0-9]{5,12}")
        private val WORDS = Regex("[a-z0-9]+")
        private val NOISE = setOf("the", "a", "of", "and", "srt", "eng", "english", "sub", "subs", "mkv", "mp4")

        fun label(language: String, source: String) =
            "${Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH).ifBlank { language }} · $source"

        /** ISO 639-2 code for comparing "en", "eng" and "en-US"; OpenSubtitles writes Brazilian Portuguese as "pob". */
        fun iso3(code: String): String = when (val clean = code.trim().lowercase(Locale.ROOT)) {
            "pob", "pb" -> "por"
            "" -> ""
            else -> runCatching { Locale.forLanguageTag(clean).isO3Language }.getOrDefault(clean).ifBlank { clean }
        }

        /** Words the subtitle's release name shares with the playing file's name. */
        fun score(fileName: String, releaseName: String): Int {
            val file = WORDS.findAll(fileName.lowercase(Locale.ROOT)).map { it.value }.filter { it !in NOISE }.toSet()
            if (file.isEmpty()) return 0
            return WORDS.findAll(releaseName.lowercase(Locale.ROOT)).map { it.value }.filter { it in file }.toSet().size
        }
    }
}
