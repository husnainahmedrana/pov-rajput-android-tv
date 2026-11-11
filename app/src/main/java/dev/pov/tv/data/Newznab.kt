package dev.pov.tv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Searches Newznab indexers (and aggregators that speak the same API) for a title's releases. A result is only a
 * pointer: its NZB is fetched when the viewer plays it, so browsing the list costs no grabs.
 */
class Newznab(
    /** One GET: address and User-Agent in, the body out. Injected so tests need no network. */
    private val get: (String, String) -> String = { address, agent -> fetch(address, agent, 4 * 1024 * 1024) },
    private val now: () -> Long = System::currentTimeMillis
) {
    class Release(val title: String, val link: String, val size: Long?, val postedAt: Long?, val grabs: Int?)
    class Answer(val releases: List<Release>, /** "API 12/100 · grabs 1/50", when the indexer says. */ val usage: String)
    private class Caps(val movie: Set<String>, val tv: Set<String>)

    /** The indexer's releases for [identity] as playable links. [year] is a movie's release year, when known. */
    fun search(indexer: NewznabIndexer, identity: PlaybackIdentity, year: Int?, agent: String, newsProvider: String = "",
               noteUsage: (String) -> Unit = {}): List<SourceItem> {
        val caps = caps(indexer, agent)
        val imdb = identity.imdbId.removePrefix("tt")
        val series = identity.type == "series"
        fun ask(parameters: String) = parse(get(address(indexer, parameters), agent)).also { noteUsage(it.usage) }.releases
        var releases = emptyList<Release>()
        if (series) {
            val episode = "&season=${identity.season}&ep=${identity.episode}"
            if ("imdbid" in caps.tv) releases = ask("t=tvsearch&imdbid=$imdb$episode&cat=5000")
            if (releases.isEmpty() && identity.title.isNotBlank())
                releases = ask("t=tvsearch&q=${Net.encode(identity.title)}$episode&cat=5000").filter { named(it.title, identity, null) }
        } else {
            if ("imdbid" in caps.movie) releases = ask("t=movie&imdbid=$imdb&cat=2000")
            if (releases.isEmpty() && identity.title.isNotBlank())
                releases = ask("t=search&q=${Net.encode(listOfNotNull(identity.title, year?.toString()).joinToString(" "))}&cat=2000").filter { named(it.title, identity, year) }
        }
        // An id search can still return a whole season: keep only this episode.
        if (series) releases = releases.filter { episodeOf(it.title)?.let { (season, episode) -> season == identity.season && episode == identity.episode } == true }
        return releases.take(150).map { release ->
            val age = release.postedAt?.let { ((now() - it) / 86_400_000L).coerceAtLeast(0) }
            SourceItem(title = release.title.take(500), provider = indexer.name, directUrl = UsenetLinks.mark(release.link),
                sizeBytes = release.size, qualityHeight = SourceMetadata.qualityHeight(release.title), usenet = true, indexer = indexer.name, newsProvider = newsProvider,
                detail = listOfNotNull("Usenet", indexer.name, age?.let { if (it == 0L) "today" else if (it == 1L) "1 day old" else "$it days old" },
                    release.grabs?.takeIf { it > 0 }?.let { "$it grabs" }).joinToString(" · "))
        }
    }

    /** Checks the address and key with a capabilities request; returns a line for the viewer. */
    fun test(indexer: NewznabIndexer, agent: String): String {
        val text = get(address(indexer, "t=caps"), agent)
        error(text)?.let { throw ApiException(0, it) }
        if ("<caps" !in text) throw ApiException(0, "This address didn't answer like a Newznab indexer.")
        capsCache.remove(indexer.id)
        val movie = supported(text, "movie-search"); val tv = supported(text, "tv-search")
        return "Connected · movies by ${if ("imdbid" in movie) "IMDb id" else "name"} · shows by ${if ("imdbid" in tv) "IMDb id" else "name"}"
    }

    private fun caps(indexer: NewznabIndexer, agent: String): Caps = capsCache.getOrPut(indexer.id) {
        val text = try { get(address(indexer, "t=caps"), agent) } catch (_: Exception) { "" }
        // An indexer that won't describe itself is asked by name only.
        Caps(supported(text, "movie-search"), supported(text, "tv-search"))
    }

    private fun supported(caps: String, tag: String): Set<String> {
        val element = Regex("<$tag\\b[^>]*>").find(caps)?.value ?: return emptySet()
        if (Regex("available=\"no\"", RegexOption.IGNORE_CASE).containsMatchIn(element)) return emptySet()
        return Regex("supportedParams=\"([^\"]*)\"").find(element)?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.toSet().orEmpty()
    }

    internal fun parse(text: String): Answer {
        error(text)?.let { throw ApiException(0, it) }
        val releases = ArrayList<Release>()
        var at = 0
        while (releases.size < 300) {
            val open = text.indexOf("<item", at); if (open < 0) break
            val close = text.indexOf("</item>", open); if (close < 0) break
            val item = text.substring(open, close)
            at = close + 7
            fun attribute(name: String) = Regex("<(?:newznab|nzbindex):attr\\s+name=\"$name\"\\s+value=\"([^\"]*)\"").find(item)?.groupValues?.get(1)
            if (attribute("password").let { it != null && it != "0" }) continue
            val title = Regex("(?s)<title>(.*?)</title>").find(item)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            val enclosure = Regex("<enclosure\\b[^>]*>").find(item)?.value
            val link = (enclosure?.let { Regex("\\burl=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                ?: Regex("(?s)<link>(.*?)</link>").find(item)?.groupValues?.get(1))?.let(::unescape)?.trim().orEmpty()
            if (title.isEmpty() || !(link.startsWith("https://") || link.startsWith("http://"))) continue
            val size = attribute("size")?.toLongOrNull() ?: enclosure?.let { Regex("\\blength=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            val posted = (attribute("usenetdate") ?: Regex("<pubDate>(.*?)</pubDate>").find(item)?.groupValues?.get(1))?.let(::date)
            releases += Release(title, link, size?.takeIf { it > 0 }, posted, attribute("grabs")?.toIntOrNull())
        }
        val limits = Regex("<(?:newznab:)?apilimits\\b[^>]*>").find(text)?.value
        fun limit(name: String) = limits?.let { Regex("\\b$name=\"(\\d+)\"").find(it)?.groupValues?.get(1) }
        val usage = listOfNotNull(limit("apicurrent")?.let { used -> "API $used" + (limit("apimax")?.let { "/$it" } ?: "") },
            limit("grabcurrent")?.let { used -> "grabs $used" + (limit("grabmax")?.let { "/$it" } ?: "") }).joinToString(" · ")
        return Answer(releases, usage)
    }

    /** `<error code="100" description="Incorrect user credentials"/>`, in words for the viewer. */
    private fun error(text: String): String? {
        val element = Regex("<error\\b[^>]*>").find(text.take(2000))?.value ?: return null
        val code = Regex("code=\"(\\d+)\"").find(element)?.groupValues?.get(1)?.toIntOrNull()
        val described = Regex("description=\"([^\"]*)\"").find(element)?.groupValues?.get(1)?.let(::unescape)?.take(120).orEmpty()
        return when (code) {
            100, 101, 102 -> "The indexer didn't accept this API key."
            429, 500 -> "The indexer's daily limit is used up."
            else -> "The indexer answered: ${described.ifBlank { "error $code" }}"
        }
    }

    private fun date(value: String): Long? = try { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value.trim())?.time } catch (_: Exception) { null }

    private fun unescape(value: String): String {
        val plain = value.trim().let { if (it.startsWith("<![CDATA[") && it.endsWith("]]>")) return it.substring(9, it.length - 3) else it }
        return if ('&' !in plain) plain else plain.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#039;", "'").replace("&amp;", "&")
    }

    companion object {
        private val capsCache = ConcurrentHashMap<String, Caps>()
        private val EPISODE = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?!\\d)")

        internal fun address(indexer: NewznabIndexer, parameters: String) =
            "${indexer.url}?$parameters&extended=1&limit=100" + if (indexer.apiKey.isBlank()) "" else "&apikey=${Net.encode(indexer.apiKey)}"

        internal fun episodeOf(title: String): Pair<Int, Int>? = EPISODE.find(title)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        private fun normalize(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace("&", " and ").replace(Regex("['’]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

        /** A name search matches loosely: the release must start with the title, then the year (a movie) or go on to its episode. */
        internal fun named(release: String, identity: PlaybackIdentity, year: Int?): Boolean {
            val name = normalize(release); val title = normalize(identity.title).removePrefix("the ")
            if (title.isBlank()) return false
            val rest = listOf(name, name.removePrefix("the ")).firstNotNullOfOrNull { candidate ->
                when { candidate == title -> ""; candidate.startsWith("$title ") -> candidate.substring(title.length + 1); else -> null }
            } ?: return false
            if (identity.type == "series") return Regex("^(?:(?:19|20)\\d\\d )?s\\d{1,2} ?e\\d{1,3}").containsMatchIn(rest)
            val stated = Regex("^((?:19|20)\\d\\d)(?!\\d)").find(rest)?.groupValues?.get(1)?.toInt()
            return if (stated != null) year == null || kotlin.math.abs(stated - year) <= 1 else year == null
        }

        /** A plain GET over HTTP or HTTPS (a home NZBHydra2 is usually plain HTTP), capped at [limit] bytes. */
        fun fetch(address: String, agent: String, limit: Int): String {
            val url = try { URL(address) } catch (_: Exception) { throw ApiException(0, "The indexer address is invalid.") }
            if (url.protocol != "https" && url.protocol != "http") throw ApiException(0, "The indexer address is invalid.")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12_000; connection.readTimeout = 25_000; connection.useCaches = false
                connection.setRequestProperty("User-Agent", agent)
                connection.setRequestProperty("Accept", "*/*")
                val status = connection.responseCode
                if (status !in 200..299) throw ApiException(status, when (status) {
                    401, 403 -> "The indexer refused the request. Check the API key."
                    429 -> "The indexer's limit is used up for now."
                    else -> "The indexer answered with error $status."
                })
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > limit) throw ApiException(0, "The indexer's answer is too large.")
                        out.write(buffer, 0, count)
                    }
                }
                return out.toString("UTF-8")
            } catch (error: ApiException) { throw error
            } catch (error: IOException) { throw ApiException(0, "The indexer couldn't be reached.")
            } finally { connection.disconnect() }
        }
    }
}

/** Marks a link as an NZB to stream from the viewer's news providers, so the player doesn't open it as a video file. */
object UsenetLinks {
    private const val MARK = "#pov-nzb"
    fun mark(link: String) = link.substringBefore('#') + MARK
    fun isNzb(address: String) = address.endsWith(MARK)
    fun nzbAddress(address: String) = address.removeSuffix(MARK)
}
