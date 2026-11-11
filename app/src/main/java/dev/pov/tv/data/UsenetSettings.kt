package dev.pov.tv.data

import java.net.URI
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A Usenet news server account. [backup] ones are asked only for articles the main ones don't have. */
data class NewsProvider(
    val id: String, val name: String, val host: String, val port: Int, val tls: Boolean,
    val user: String, val password: String, val connections: Int, val backup: Boolean = false, val enabled: Boolean = true
)

/** The viewer's news providers, in the order they are tried. Logins stay in [SecureStore]. */
class NewsProviders(private val get: (String) -> String, private val put: (String, String) -> Unit) {
    constructor(store: SecureStore) : this({ store.get(it) }, store::put)

    class Preset(val name: String, val host: String, val maxConnections: Int, val port: Int = 563)

    fun load(): List<NewsProvider> {
        val saved = get(KEY)
        if (saved.isBlank()) return emptyList()
        return try {
            val array = JSONArray(saved)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                NewsProvider(item.getString("id"), item.getString("name"), item.getString("host"), item.getInt("port"), item.optBoolean("tls", true),
                    item.getString("user"), item.getString("password"), item.optInt("connections", DEFAULT_CONNECTIONS), item.optBoolean("backup"), item.optBoolean("enabled", true))
            }
        } catch (_: Exception) { throw IllegalStateException("Saved news providers could not be read. Check Settings → Usenet.") }
    }

    fun save(providers: List<NewsProvider>) {
        providers.forEach(::validate)
        require(providers.map { it.id }.distinct().size == providers.size) { "Invalid news provider identifiers." }
        val array = JSONArray()
        providers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("tls", it.tls)
            .put("user", it.user).put("password", it.password).put("connections", it.connections).put("backup", it.backup).put("enabled", it.enabled)) }
        put(KEY, array.toString())
    }

    /** Switched-on providers in the order articles are asked of them: main ones first, then backups. */
    fun active(): List<NewsProvider> = load().filter { it.enabled }.sortedBy { it.backup }

    companion object {
        const val KEY = "usenet.providers"
        const val DEFAULT_CONNECTIONS = 20
        const val MAX_CONNECTIONS = 100

        /** Well-known providers' secure servers. Every field stays editable after picking one. */
        val PRESETS = listOf(
            Preset("Newshosting", "news.newshosting.com", 100), Preset("Easynews", "news.easynews.com", 60), Preset("Tweaknews (Newshosting deal)", "newshosting.tweaknews.eu", 40), Preset("Tweaknews (standalone)", "news.tweaknews.eu", 40),
            Preset("Eweka", "news.eweka.nl", 50), Preset("UsenetServer", "news.usenetserver.com", 20), Preset("Giganews", "news.giganews.com", 100),
            Preset("Frugal Usenet", "news.frugalusenet.com", 100), Preset("NewsDemon", "news.newsdemon.com", 50), Preset("Usenet.Farm", "news.usenet.farm", 40),
            Preset("UsenetExpress", "news.usenetexpress.com", 50), Preset("NewsgroupDirect", "news.newsgroupdirect.com", 100),
            Preset("Newsgroup Ninja", "news.newsgroup.ninja", 50), Preset("Supernews", "news.supernews.com", 30), Preset("Pure Usenet", "news.pureusenet.nl", 20),
            Preset("Sunny Usenet", "news.sunnyusenet.com", 20), Preset("XS News", "reader.xsnews.nl", 50), Preset("ThunderNews", "news.thundernews.com", 60),
            Preset("Astraweb", "news.astraweb.com", 20), Preset("ViperNews", "news.vipernews.com", 60), Preset("Cheapnews", "news.cheapnews.eu", 30),
            Preset("Fast Usenet", "news.fastusenet.org", 50), Preset("UsenetNow", "news.usenetnow.net", 50), Preset("UsenetPrime", "news.usenetprime.com", 50),
            Preset("Easy Usenet", "news.easyusenet.nl", 20), Preset("Bulknews", "news.bulknews.eu", 30), Preset("XLned", "news.xlned.com", 20),
            Preset("123 Usenet", "news.123usenet.nl", 20), Preset("Hitnews", "news.hitnews.com", 20))

        fun create(name: String, host: String, port: Int, tls: Boolean, user: String, password: String, connections: Int, backup: Boolean) =
            NewsProvider(UUID.randomUUID().toString(), name.trim(), host.trim().lowercase(Locale.ROOT), port, tls, user.trim(), password, connections, backup).also(::validate)

        fun validate(provider: NewsProvider) {
            require(provider.name.isNotBlank() && provider.name.length <= 60 && provider.name.none { it.isISOControl() }) { "Give the provider a name." }
            require(provider.host.matches(Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?"))) { "Enter the server address, such as news.example.com." }
            require(provider.port in 1..65535) { "Enter a port from 1 to 65535 (563 for SSL, 119 without)." }
            require(provider.user.isNotEmpty() && provider.user.none { it == '\r' || it == '\n' }) { "Enter the username." }
            require(provider.password.isNotEmpty() && provider.password.none { it == '\r' || it == '\n' }) { "Enter the password." }
            require(provider.connections in 1..MAX_CONNECTIONS) { "Connections must be from 1 to $MAX_CONNECTIONS." }
        }
    }
}

/** A Newznab indexer (or an aggregator such as NZBHydra2 or Prowlarr, which speak the same API). */
data class NewznabIndexer(val id: String, val name: String, /** The API address, such as https://api.example.com/api. */ val url: String,
                          val apiKey: String, val enabled: Boolean = true)

/** The viewer's indexers. API keys stay in [SecureStore]. */
class NewznabIndexers(private val get: (String) -> String, private val put: (String, String) -> Unit) {
    constructor(store: SecureStore) : this({ store.get(it) }, store::put)

    class Preset(val name: String, val url: String, val keyOptional: Boolean = false)

    fun load(): List<NewznabIndexer> {
        val saved = get(KEY)
        if (saved.isBlank()) return emptyList()
        return try {
            val array = JSONArray(saved)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                NewznabIndexer(item.getString("id"), item.getString("name"), item.getString("url"), item.optString("apiKey"), item.optBoolean("enabled", true))
            }
        } catch (_: Exception) { throw IllegalStateException("Saved indexers could not be read. Check Settings → Usenet.") }
    }

    fun save(indexers: List<NewznabIndexer>) {
        indexers.forEach(::validate)
        require(indexers.map { it.id }.distinct().size == indexers.size) { "Invalid indexer identifiers." }
        val array = JSONArray()
        indexers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("apiKey", it.apiKey).put("enabled", it.enabled)) }
        put(KEY, array.toString())
    }

    /** What an indexer last said about today's allowance ("API 12/100 · grabs 1/50"), shown beside it in Settings. */
    fun usage(id: String): String = get("$USAGE$id")
    fun noteUsage(id: String, usage: String) { if (usage.isNotBlank() && usage != get("$USAGE$id")) put("$USAGE$id", usage) }

    companion object {
        const val KEY = "usenet.indexers"
        private const val USAGE = "usenet.usage."

        /** Well-known indexers' API addresses; the two aggregators' addresses are examples to edit. */
        val PRESETS = listOf(
            Preset("NZBgeek", "https://api.nzbgeek.info/api"), Preset("NzbNest", "https://nzbnest.com/api"), Preset("DrunkenSlug", "https://drunkenslug.com/api"), Preset("NZBFinder", "https://nzbfinder.ws/api"),
            Preset("NzbPlanet", "https://api.nzbplanet.net/api"), Preset("NinjaCentral", "https://ninjacentral.co.za/api"), Preset("DOGnzb", "https://api.dognzb.cr/api"),
            Preset("Usenet Crawler", "https://www.usenet-crawler.com/api"), Preset("altHUB", "https://api.althub.co.za/api"), Preset("abNZB", "https://abnzb.com/api"),
            Preset("SceneNZBs", "https://scenenzbs.com/api"), Preset("Tabula Rasa", "https://www.tabula-rasa.pw/api/v1/api"), Preset("NZBCat", "https://nzb.cat/api"),
            Preset("Nzb.life", "https://api.nzb.life/api"), Preset("NZBStars", "https://nzbstars.com/api"), Preset("NzbNoob", "https://www.nzbnoob.com/api"),
            Preset("NZBNDX", "https://www.nzbndx.com/api"), Preset("GingaDADDY", "https://www.gingadaddy.com/api"), Preset("Miatrix", "https://www.miatrix.com/api"),
            Preset("Newz69", "https://newz69.keagaming.com/api"), Preset("AnimeTosho (Usenet)", "https://feed.animetosho.net/api", keyOptional = true),
            Preset("aniNZB", "https://aninzb.moe/api", keyOptional = true),
            Preset("NZBHydra2 (your server)", "http://192.168.1.10:5076/api"), Preset("Prowlarr (your server, one indexer)", "http://192.168.1.10:9696/1/api"))

        fun create(name: String, url: String, apiKey: String) = NewznabIndexer(UUID.randomUUID().toString(), name.trim(), address(url), apiKey.trim()).also(::validate)

        /** An http(s) API address without query or login parts; a bare site address gets the usual /api path. */
        fun address(value: String): String {
            val typed = value.trim().trimEnd('/')
            val uri = try { URI(if ("://" in typed) typed else "https://$typed") } catch (_: Exception) { throw IllegalArgumentException("Enter the indexer's address, such as https://api.example.com/api.") }
            require((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Enter the indexer's address, such as https://api.example.com/api."
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "${uri.scheme.lowercase(Locale.ROOT)}://${uri.rawAuthority}${path.ifEmpty { "/api" }}"
        }

        fun validate(indexer: NewznabIndexer) {
            require(indexer.name.isNotBlank() && indexer.name.length <= 60 && indexer.name.none { it.isISOControl() }) { "Give the indexer a name." }
            require(address(indexer.url) == indexer.url) { "Enter the indexer's address, such as https://api.example.com/api." }
            require(indexer.apiKey.length <= 200 && indexer.apiKey.none { it <= ' ' }) { "The API key can't contain spaces." }
        }
    }
}

/**
 * The User-Agent names sent to indexers. Many indexers only answer clients they know, and some check the version, so
 * these name a well-known search tool and download client and are moved to those tools' current versions about once
 * a day (and on request) from the tools' own release pages.
 */
object UsenetHeaders {
    const val QUERY = "usenet.header.query"
    const val GRAB = "usenet.header.grab"
    const val CHECKED = "usenet.header.checked"
    /** Current when this build was made; refreshed from the release pages after that. */
    const val DEFAULT_QUERY = "Prowlarr/2.7.0.5723"
    const val DEFAULT_GRAB = "SABnzbd/5.1.3"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** A tool whose releases are on GitHub: the name it writes in its own User-Agent, and its repository. */
    private val TOOLS = listOf("Prowlarr" to "Prowlarr/Prowlarr", "Sonarr" to "Sonarr/Sonarr", "Radarr" to "Radarr/Radarr",
        "SABnzbd" to "sabnzbd/sabnzbd", "nzbget" to "nzbgetcom/nzbget")
    private val VERSION = Regex("[0-9]+(\\.[0-9]+){1,3}")

    fun query(get: (String) -> String) = clean(get(QUERY)).ifBlank { DEFAULT_QUERY }
    fun grab(get: (String) -> String) = clean(get(GRAB)).ifBlank { DEFAULT_GRAB }
    /** Header text goes on a request line: printable characters only. */
    fun clean(value: String) = value.trim().filter { it in ' '..'~' }.take(120)

    fun due(get: (String) -> String, now: Long = System.currentTimeMillis()) = now - (get(CHECKED).toLongOrNull() ?: 0) >= DAY_MS

    /**
     * Moves each header that names a known tool ("Prowlarr/2.3.0") to that tool's latest stable version. Headers the
     * viewer wrote differently are left alone. Returns what changed, as lines for the viewer; throws when no release
     * page could be read.
     */
    fun refresh(get: (String) -> String, put: (String, String) -> Unit, now: Long = System.currentTimeMillis(),
                latest: (String) -> String = { repo -> JSONObject(Net.raw("https://api.github.com/repos/$repo/releases/latest",
                    headers = mapOf("Accept" to "application/vnd.github+json"))).optString("tag_name") }): List<String> {
        val changes = ArrayList<String>()
        var reached = false
        var failure: Exception? = null
        for ((key, current) in listOf(QUERY to query(get), GRAB to grab(get))) {
            val product = current.substringBefore('/')
            val tool = TOOLS.firstOrNull { it.first.equals(product, ignoreCase = true) } ?: continue
            val version = try { latest(tool.second).trim().removePrefix("v") } catch (error: Exception) { failure = error; continue }
            reached = true
            if (!VERSION.matches(version)) continue
            val updated = "${tool.first}/$version"
            if (updated != current) { put(key, updated); changes += updated }
        }
        if (!reached) failure?.let { throw it }
        put(CHECKED, now.toString())
        return changes
    }
}
