package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Torznab indexer: a Jackett instance (all its indexers through one address), one of a Prowlarr's torrent indexers,
 * or any Torznab address (StremThru, Comet and the like). Its results are torrent links that play through the
 * viewer's debrid services, like Torrentio's.
 */
data class TorznabIndexer(val id: String, val name: String, /** The Torznab API address, such as https://example.com/api. */ val url: String,
                          val apiKey: String, val enabled: Boolean = true,
                          /** 1 is searched first; a higher number only when the ones before found too little. */
                          val priority: Int = 1,
                          /** Searched only when every other Torznab indexer found nothing. */
                          val backupOnly: Boolean = false,
                          /** Where it came from: [TorznabIndexers.JACKETT], [TorznabIndexers.PROWLARR] or [TorznabIndexers.URL]. */
                          val kind: String = TorznabIndexers.URL)

/** The viewer's Torznab indexers. API keys stay in [SecureStore]. */
class TorznabIndexers(private val get: (String) -> String, private val put: (String, String) -> Unit) {
    constructor(store: SecureStore) : this({ store.get(it) }, store::put)

    fun load(): List<TorznabIndexer> {
        val saved = get(KEY)
        if (saved.isBlank()) return emptyList()
        return try {
            val array = JSONArray(saved)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TorznabIndexer(item.getString("id"), item.getString("name"), item.getString("url"), item.optString("apiKey"), item.optBoolean("enabled", true),
                    item.optInt("priority", 1).coerceIn(1, MAX_PRIORITY), item.optBoolean("backupOnly", false),
                    item.optString("kind", URL).takeIf { it in KINDS } ?: URL)
            }
        } catch (_: Exception) { throw IllegalStateException(L10n.tr(R.string.tz_saved_unreadable, "Saved Torznab indexers could not be read. Check Settings → Torznab.")) }
    }

    fun save(indexers: List<TorznabIndexer>) {
        indexers.forEach(::validate)
        require(indexers.map { it.id }.distinct().size == indexers.size) { "Invalid indexer identifiers." }
        val array = JSONArray()
        indexers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("apiKey", it.apiKey).put("enabled", it.enabled)
            .put("priority", it.priority.coerceIn(1, MAX_PRIORITY)).put("backupOnly", it.backupOnly).put("kind", it.kind)) }
        put(KEY, array.toString())
    }

    /** Adds [added], skipping any already saved with the same address and key. Returns how many were new. */
    fun add(added: List<TorznabIndexer>): Int {
        val existing = load()
        val fresh = added.filter { new -> existing.none { it.url == new.url && it.apiKey == new.apiKey } }.distinctBy { it.url to it.apiKey }
        if (fresh.isNotEmpty()) save(existing + fresh)
        return fresh.size
    }

    companion object {
        const val KEY = "torznab.indexers"
        /** Same choices as Usenet's: 2160, 1080 (default), 720, or 0 = only when nothing was found. */
        const val NEXT_TIER_QUALITY = "torznab.nextTierQuality"
        /** Links kept from each indexer, best-seeded first; public aggregators can send over a thousand. */
        const val RESULT_LIMIT = "torznab.resultLimit"
        val RESULT_LIMIT_CHOICES = listOf(50, 100, 200, 300)
        const val MAX_PRIORITY = 20
        const val JACKETT = "jackett"
        const val PROWLARR = "prowlarr"
        const val URL = "torznab"
        val KINDS = setOf(JACKETT, PROWLARR, URL)
        private const val JACKETT_ALL = "/api/v2.0/indexers/all/results/torznab/api"

        fun nextTierQuality(get: (String) -> String) = get(NEXT_TIER_QUALITY).toIntOrNull()?.takeIf { it in UsenetSearch.NEXT_TIER_CHOICES } ?: 1080
        fun resultLimit(get: (String) -> String) = get(RESULT_LIMIT).toIntOrNull()?.takeIf { it in RESULT_LIMIT_CHOICES } ?: 100

        fun create(name: String, url: String, apiKey: String, kind: String = URL) =
            TorznabIndexer(UUID.randomUUID().toString(), name.trim().take(60), if (kind == JACKETT) jackettAddress(url) else address(url), apiKey.trim(), kind = kind).also(::validate)

        /** An http(s) address without query, fragment or login parts; a bare site gets the usual /api path. */
        fun address(value: String): String {
            val uri = parse(value)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}${path.ifEmpty { "/api" }}"
        }

        /** A Jackett server's address searches all its indexers; a pasted Torznab feed address is kept as it is. */
        fun jackettAddress(value: String): String {
            val uri = parse(value)
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val base = "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}"
            if (path.contains("/torznab", ignoreCase = true)) return base + path
            // "http://host:9117/UI/Dashboard" copied from the browser: the server is what counts.
            val root = path.substringBefore("/UI", path).substringBefore("/ui").trimEnd('/')
            return base + root + JACKETT_ALL
        }

        /** A Prowlarr server's base address (with its URL base, if it has one), for its API. */
        fun serverAddress(value: String): String {
            val uri = parse(value)
            val path = uri.rawPath.orEmpty().trimEnd('/').let { p -> listOf("/indexers", "/system", "/settings", "/search", "/history").fold(p) { acc, page -> acc.substringBefore(page) } }
            return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}${path.trimEnd('/')}"
        }

        private fun parse(value: String): URI {
            val typed = value.trim().trimEnd('/')
            val invalid = L10n.tr(R.string.tz_address_invalid, "Enter the address, such as https://example.com/api.")
            val uri = try { URI(if ("://" in typed) typed else "https://$typed") } catch (_: Exception) { throw IllegalArgumentException(invalid) }
            require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) { invalid }
            return uri
        }

        fun validate(indexer: TorznabIndexer) {
            require(indexer.name.isNotBlank() && indexer.name.length <= 60 && indexer.name.none { it.isISOControl() }) { "Give the indexer a name." }
            require(address(indexer.url) == indexer.url) { L10n.tr(R.string.tz_address_invalid, "Enter the address, such as https://example.com/api.") }
            require(indexer.apiKey.length <= 200 && indexer.apiKey.none { it <= ' ' }) { "The API key can't contain spaces." }
            require(indexer.kind in KINDS) { "Unknown indexer kind." }
        }

        /**
         * A copied feed address often carries its key ("…/api?apikey=abc&t=search"): the address without its query, and
         * the key from it (empty when there is none).
         */
        fun splitKey(value: String): Pair<String, String> {
            val typed = value.trim()
            val query = typed.substringAfter('?', "").substringBefore('#')
            val key = query.split('&').firstOrNull { it.startsWith("apikey=", ignoreCase = true) }?.substringAfter('=')
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
            return typed.substringBefore('?').substringBefore('#') to key
        }

        /** A Torznab address pasted where a Stremio add-on was expected. */
        fun looksLikeTorznab(value: String): Boolean {
            val typed = value.trim().lowercase(Locale.ROOT)
            return typed.startsWith("http") && "manifest.json" !in typed && (Regex("/torznab(/|$|\\?)").containsMatchIn(typed) || typed.contains("/results/torznab"))
        }
    }
}

/** Searches Torznab indexers for a title's torrents. */
class Torznab(
    /** One GET: address and User-Agent in, the body out. Injected so tests need no network. */
    private val get: (String, String) -> String = { address, agent -> Newznab.fetch(address, agent, 8 * 1024 * 1024) }
) {
    class Release(val title: String, val hash: String, val size: Long?, val seeders: Int?, val tracker: String)
    private class Caps(val movie: Set<String>, val tv: Set<String>)

    /** The indexer's torrents for [identity], best-seeded first, at most [limit]. [year] is a movie's release year, when known. */
    fun search(indexer: TorznabIndexer, identity: PlaybackIdentity, year: Int?, agent: String, limit: Int = 100): List<SourceItem> {
        val caps = caps(indexer, agent)
        val logo = logoOf(indexer)
        val series = identity.type == "series"
        fun ask(parameters: String) = parse(get(address(indexer, parameters), agent))
        var releases = emptyList<Release>()
        if (series) {
            val episode = "&season=${identity.season}&ep=${identity.episode}"
            if ("imdbid" in caps.tv) releases = ask("t=tvsearch&imdbid=${identity.imdbId}$episode&cat=5000")
            if (releases.isEmpty() && identity.title.isNotBlank()) releases = ask("t=tvsearch&q=${Net.encode(identity.title)}$episode&cat=5000")
        } else {
            if ("imdbid" in caps.movie) releases = ask("t=movie&imdbid=${identity.imdbId}&cat=2000")
            if (releases.isEmpty() && identity.title.isNotBlank())
                releases = ask("t=search&q=${Net.encode(listOfNotNull(identity.title, year?.toString()).joinToString(" "))}&cat=2000")
        }
        // Aggregators match loosely even by id ("Home Before Dark S01E02" for Dark): the name must be this title's.
        return releases.filter { fits(it.title, identity, year) }.distinctBy { it.hash }
            .sortedByDescending { it.seeders ?: -1 }.take(limit).map { release ->
                SourceItem(title = release.title.take(500), provider = indexer.name, providerId = "torznab:${indexer.id}", infoHash = release.hash, scraper = logo,
                    torrentName = release.title.take(500), sizeBytes = release.size, qualityHeight = SourceMetadata.qualityHeight(release.title),
                    detail = listOfNotNull("Torznab", release.tracker.takeIf { it.isNotBlank() && !it.equals(indexer.name, true) },
                        release.seeders?.let { L10n.tr(R.string.tz_seeders, "%1\$s seeders", it) }).joinToString(" · "))
            }
    }

    /** Checks the address and key with a capabilities request; returns a line for the viewer. */
    fun test(indexer: TorznabIndexer, agent: String): String {
        val text = get(address(indexer, "t=caps"), agent)
        error(text)?.let { throw ApiException(0, it) }
        if ("<caps" !in text) throw ApiException(0, L10n.tr(R.string.tz_not_torznab, "This address didn't answer like a Torznab indexer."))
        capsCache.remove(indexer.id)
        val movie = supported(text, "movie-search"); val tv = supported(text, "tv-search")
        fun by(id: Boolean) = if (id) L10n.tr(R.string.t_by_imdb_id, "IMDb id") else L10n.tr(R.string.t_by_name, "name")
        return L10n.tr(R.string.t_connected_movies_by_x_shows_by_x, "Connected · movies by %1\$s · shows by %2\$s", by("imdbid" in movie), by("imdbid" in tv))
    }

    /** A Prowlarr server's switched-on torrent indexers as Torznab indexers (its Usenet ones belong in Settings → Usenet). */
    fun prowlarrIndexers(server: String, apiKey: String, agent: String): List<TorznabIndexer> {
        val base = TorznabIndexers.serverAddress(server)
        val text = get("$base/api/v1/indexer?apikey=${Net.encode(apiKey.trim())}", agent)
        return prowlarrList(text).map { (id, name) ->
            TorznabIndexer(UUID.randomUUID().toString(), name.take(60).ifBlank { "Prowlarr $id" }, "$base/$id/api", apiKey.trim(), kind = TorznabIndexers.PROWLARR)
                .also(TorznabIndexers::validate)
        }
    }

    private fun caps(indexer: TorznabIndexer, agent: String): Caps = capsCache.getOrPut(indexer.id) {
        val text = try { get(address(indexer, "t=caps"), agent) } catch (_: Exception) { "" }
        // An indexer that won't describe itself is asked by name only.
        Caps(supported(text, "movie-search"), supported(text, "tv-search"))
    }

    companion object {
        private val capsCache = ConcurrentHashMap<String, Caps>()
        private val HEX = Regex("[0-9a-f]{40}")
        private val BASE32 = Regex("[a-z2-7]{32}")
        private val SEASON_ONLY = Regex("(?i)(?<![a-z0-9])(?:s|season[ ._-]?)(\\d{1,2})(?![0-9]|[ ._-]*e\\d)")
        private val SEASON_RANGE = Regex("(?i)(?<![a-z0-9])(?:s|seasons?[ ._-]?)(\\d{1,2})[ ._]*(?:-|to)[ ._]*(?:s|season[ ._-]?)?(\\d{1,2})(?![0-9])")
        private val EPISODE_MARK = Regex("(?i)(?<![a-z0-9])s\\d{1,2}[ ._-]*e\\d{1,3}|(?<![0-9])\\d{1,2}x\\d{1,3}(?![0-9])")

        /**
         * The logo on an indexer's links and in the player, as for Torrentio's: Jackett's or Prowlarr's, or for a plain
         * Torznab address the add-on it belongs to (a Comet or StremThru instance), else none.
         */
        internal fun logoOf(indexer: TorznabIndexer): String? = when (indexer.kind) {
            TorznabIndexers.JACKETT -> AddonBadges.JACKETT
            TorznabIndexers.PROWLARR -> AddonBadges.PROWLARR_TORZNAB
            else -> AddonBadges.of(indexer.url)?.takeIf { it in AddonBadges.SCRAPERS }
        }

        internal fun address(indexer: TorznabIndexer, parameters: String) =
            "${indexer.url}?$parameters&limit=100" + if (indexer.apiKey.isBlank()) "" else "&apikey=${Net.encode(indexer.apiKey)}"

        internal fun prowlarrList(text: String): List<Pair<Int, String>> {
            val array = try { JSONArray(text) } catch (_: Exception) { throw ApiException(0, L10n.tr(R.string.tz_not_prowlarr, "This address didn't answer like Prowlarr. Check the address and API key.")) }
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optString("protocol").equals("torrent", true) && it.optBoolean("enable", true) && it.optInt("id", 0) > 0 }
                .map { it.getInt("id") to it.optString("name").trim() }
        }

        /** The 40-character hex info hash of a magnet link (hex or base32 btih), or null. */
        internal fun hashOf(magnet: String): String? {
            val value = Regex("(?i)xt=urn:btih:([a-z0-9]+)").find(magnet)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
            if (HEX.matches(value)) return value
            if (!BASE32.matches(value)) return null
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            val bits = StringBuilder()
            value.forEach { bits.append(Integer.toBinaryString(alphabet.indexOf(it)).padStart(5, '0')) }
            return bits.chunked(4).joinToString("") { Integer.toHexString(it.toInt(2)) }
        }

        internal fun parse(text: String): List<Release> {
            error(text)?.let { throw ApiException(0, it) }
            val releases = ArrayList<Release>()
            var at = 0
            while (releases.size < 1000) {
                val open = text.indexOf("<item", at); if (open < 0) break
                val close = text.indexOf("</item>", open); if (close < 0) break
                val item = text.substring(open, close)
                at = close + 7
                val attributes = Regex("""<(?:[\w.-]+:)?attr\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(item).mapNotNull { tag ->
                    fun field(key: String) = Regex("""\b$key\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(tag.value)?.groupValues?.get(2)?.let(::unescape)?.trim()
                    val key = field("name")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                    key to field("value").orEmpty()
                }.toList()
                fun attribute(name: String) = attributes.firstOrNull { it.first == name }?.second
                fun element(name: String) = Regex("(?s)<$name\\b[^>]*>(.*?)</$name>").find(item)?.groupValues?.get(1)?.let(::unescape)?.trim()
                val title = element("title").orEmpty()
                val enclosure = Regex("<enclosure\\b[^>]*>").find(item)?.value
                val enclosureUrl = enclosure?.let { Regex("\\burl=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }?.let(::unescape)
                // The hash: its own attribute, else a magnet link wherever the indexer put it. A .torrent-only result is skipped.
                val hash = attribute("infohash")?.lowercase(Locale.ROOT)?.takeIf(HEX::matches)
                    ?: listOfNotNull(attribute("magneturl"), element("link"), enclosureUrl, element("guid")).firstNotNullOfOrNull(::hashOf)
                if (title.isEmpty() || hash == null) continue
                val size = attribute("size")?.toLongOrNull() ?: element("size")?.toLongOrNull()
                    ?: enclosure?.let { Regex("\\blength=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                val tracker = (attribute("indexername") ?: element("jackettindexer") ?: element("prowlarrindexer")).orEmpty().take(40)
                releases += Release(title, hash, size?.takeIf { it > 0 }, attribute("seeders")?.toIntOrNull(), tracker)
            }
            return releases
        }

        /**
         * Whether [release] is this title's: it starts with the title (a leading "The" dropped only when the title has
         * one); a movie then has its year (±1) when the release names one; an episode names this episode or is a pack of
         * this season.
         */
        internal fun fits(release: String, identity: PlaybackIdentity, year: Int?): Boolean {
            if (identity.type != "series") return Newznab.named(release, identity, year)
            if (EpisodeFilename.isExplicitMismatch(release, identity.season, identity.episode)) return false
            val name = normalize(release); val full = normalize(identity.title); val title = full.removePrefix("the ")
            if (title.isBlank()) return false
            val candidates = if (full.startsWith("the ")) listOf(name, name.removePrefix("the ")) else listOf(name)
            val rest = candidates.firstNotNullOfOrNull { candidate ->
                when { candidate == title -> ""; candidate.startsWith("$title ") -> candidate.substring(title.length + 1); else -> null }
            } ?: return false
            // Right after the title (and a year or country): the episode, a season, or a complete pack.
            val marker = Regex("^(?:(?:19|20)\\d\\d |us |uk )?(s\\d{1,2}|season|complete|\\d{1,2}x\\d|saison|staffel|temporada)").containsMatchIn(rest)
            if (!marker) return false
            // It names episodes, and (not a mismatch) this one is among them.
            if (EPISODE_MARK.containsMatchIn(release)) return true
            // A pack: of this season, of a range that holds it, or of the whole show (no season named).
            val seasons = seasonsOf(release)
            return seasons.isEmpty() || identity.season in seasons
        }

        /** The seasons a pack names: "S01", "Season 2", "S01-S03" (1, 2 and 3). */
        internal fun seasonsOf(release: String): Set<Int> {
            val found = SEASON_ONLY.findAll(release).map { it.groupValues[1].toInt() }.toMutableSet()
            SEASON_RANGE.findAll(release).forEach { range ->
                val (from, to) = range.groupValues[1].toInt() to range.groupValues[2].toInt()
                if (to in from..from + 40) found += from..to
            }
            return found
        }

        private fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace("&", " and ").replace(Regex("['’]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun supported(caps: String, tag: String): Set<String> {
            val element = Regex("<$tag\\b[^>]*>").find(caps)?.value ?: return emptySet()
            if (Regex("available=\"no\"", RegexOption.IGNORE_CASE).containsMatchIn(element)) return emptySet()
            return Regex("supportedParams=\"([^\"]*)\"").find(element)?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.toSet().orEmpty()
        }

        /** `<error code="100" description="Invalid API key"/>`, in words for the viewer. */
        private fun error(text: String): String? {
            val element = Regex("<error\\b[^>]*>").find(text.take(2000))?.value ?: return null
            val code = Regex("code=\"(\\d+)\"").find(element)?.groupValues?.get(1)?.toIntOrNull()
            val described = Regex("description=\"([^\"]*)\"").find(element)?.groupValues?.get(1)?.let(::unescape)?.take(120).orEmpty()
            return when (code) {
                100, 101, 102 -> L10n.tr(R.string.t_the_indexer_didn_t_accept_this, "The indexer didn't accept this API key.")
                else -> L10n.tr(R.string.t_the_indexer_answered_x, "The indexer answered: %1\$s", described.ifBlank { "error $code" })
            }
        }

        private fun unescape(value: String): String {
            val plain = value.trim().let { if (it.startsWith("<![CDATA[") && it.endsWith("]]>")) return it.substring(9, it.length - 3) else it }
            return if ('&' !in plain) plain else plain.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#039;", "'").replace("&amp;", "&")
        }
    }
}

/** The viewer's Torznab indexers as searches for the link list. Without a debrid service their torrents couldn't play, so none are offered. */
object TorznabSearch {
    fun tiers(indexers: List<TorznabIndexer>): List<List<TorznabIndexer>> =
        indexers.groupBy { if (it.backupOnly) Int.MAX_VALUE else it.priority }.toSortedMap().values.toList()

    fun sources(store: SecureStore): List<SourceResolver.IndexerSearch> {
        if (DebridServices.connected(store).isEmpty()) return emptyList()
        val indexers = try { TorznabIndexers(store).load().filter { it.enabled } } catch (_: Exception) { emptyList() }
        if (indexers.isEmpty()) return emptyList()
        val agent = UsenetHeaders.query { store.get(it) }
        val limit = TorznabIndexers.resultLimit { store.get(it) }
        val client = Torznab()
        fun search(indexer: TorznabIndexer, identity: PlaybackIdentity) = client.search(indexer, identity, EasynewsApi.cinemetaYear(identity), agent, limit)
        val tiers = tiers(indexers)
        // One priority and no backups: every indexer is its own search, answering as soon as it can.
        if (tiers.size <= 1) return indexers.map { indexer ->
            SourceResolver.IndexerSearch("torznab:${indexer.id}", indexer.name) { identity -> search(indexer, identity) }
        }
        val quality = TorznabIndexers.nextTierQuality { store.get(it) }
        val backupTier = indexers.any { it.backupOnly }
        return listOf(SourceResolver.IndexerSearch("torznab", "Torznab") { identity ->
            UsenetSearch.searchTiers(tiers, backupTier, quality) { indexer -> search(indexer, identity) }
        })
    }
}
