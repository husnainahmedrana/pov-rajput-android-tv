package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * The viewer's own MediaFlow Proxy server (github.com/mhdzumair/mediaflow-proxy): chosen debrid services and add-ons
 * stream through it, so the debrid service sees the server's IP instead of the device's (Real-Debrid ties links to one
 * IP) and a blocked or throttled CDN is reached from the server. Usenet never goes through it: the in-app news-server
 * engine speaks NNTP, and Usenet add-on links (AIOStreams Usenet, AltMount, StreamNZB) and Easynews stay direct.
 *
 * Saved in SecureStore (the password is a secret; the whole set goes with the account backup's secrets layer).
 */
class MediaFlowProxy internal constructor(private val get: (String) -> String, private val put: (Map<String, String>) -> Unit) {
    constructor(store: SecureStore) : this({ store.get(it) }, { store.putAll(it) })

    data class Settings(val server: String, val password: String, val on: Boolean, val debrid: Set<String>, val addons: Set<String>) {
        val ready get() = on && server.isNotBlank()
    }

    /**
     * Where a link came from, carried to the player (and kept with Continue Watching): the add-on ([provider], a
     * SourceProvider id), the debrid service that serves an add-on link ([servedBy]), and whether it is a Usenet link.
     */
    data class Origin(val provider: String = "", val servedBy: String? = null, val usenet: Boolean = false) {
        fun encode() = listOf(provider, servedBy.orEmpty(), if (usenet) "u" else "").joinToString("|")
        companion object {
            fun of(source: SourceItem) = Origin(source.providerId, source.servedBy?.takeIf { it in DebridServices.IDS },
                source.usenet || UsenetLinks.isNzb(source.directUrl))
            fun decode(value: String?): Origin {
                val parts = value.orEmpty().split('|')
                return Origin(parts.getOrNull(0).orEmpty().take(64), parts.getOrNull(1)?.takeIf { it in DebridServices.IDS }, parts.getOrNull(2) == "u")
            }
        }
    }

    /** What the player opens: the proxy address (headers travel inside it as h_ parameters), or the link unchanged. */
    data class Route(val url: String, val headers: Map<String, String>, val proxied: Boolean)

    fun load() = Settings(get(SERVER), get(PASSWORD), get(ON) == "true", list(get(DEBRID)), list(get(ADDONS)))

    fun saveServer(address: String, password: String) {
        put(mapOf(SERVER to server(address), PASSWORD to password.trim().also {
            require(it.length <= 512 && it.none(Char::isISOControl)) { L10n.tr(R.string.t_mediaflow_password_invalid, "Enter the API password set on your MediaFlow Proxy server.") }
        }))
    }
    fun setOn(on: Boolean) = put(mapOf(ON to on.toString()))
    fun setDebrid(id: String, on: Boolean) { require(id in DebridServices.IDS); put(mapOf(DEBRID to toggle(load().debrid, id, on))) }
    fun setAddon(id: String, on: Boolean) { require(id.isNotBlank() && ',' !in id); put(mapOf(ADDONS to toggle(load().addons, id, on))) }
    fun forget() = put(mapOf(SERVER to "", PASSWORD to "", ON to "false"))

    /**
     * Whether this link goes through the proxy: [debridService] is the viewer's own debrid playback, [origin] the add-on
     * the link came from and the debrid service serving it. A ticked service or a ticked add-on is enough; Usenet never.
     */
    fun route(address: String, headers: Map<String, String>, debridService: String?, origin: Origin): Route {
        val settings = load()
        val service = debridService ?: origin.servedBy
        val wanted = settings.ready && !origin.usenet && direct(address) &&
            (service != null && service in settings.debrid || origin.provider.isNotBlank() && origin.provider in settings.addons)
        return if (!wanted) Route(address, headers, false)
        else Route(proxied(settings.server, settings.password, address, headers), emptyMap(), true)
    }

    /**
     * Whether a link in the list will stream through the proxy, as [route] will decide when it plays: [playOn] is the
     * debrid service it would be played on (its cache, or the first check-on-play service), null for direct links.
     */
    fun routes(source: SourceItem, playOn: String?) = routes(source, playOn, load())

    /**
     * An API call to a ticked debrid service, relayed by the server's `/proxy/forward` so the service sees the server's IP
     * (the same IP that then streams its links); null for anything else. Never falls back to the device's own IP: a
     * relay that can't be used fails the call instead. The headers (the service's key among them) ride as h_ parameters,
     * and the service is asked for an uncompressed answer (MediaFlow garbles compressed ones).
     */
    fun forward(address: String, headers: Map<String, String>, contentType: String?): Pair<String, Map<String, String>>? {
        val host = runCatching { URI(address).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        val service = API_HOSTS.entries.firstOrNull { it.value == host }?.key ?: return null
        val settings = load()
        if (!settings.ready || service !in settings.debrid) return null
        if (!settings.server.startsWith("https://", ignoreCase = true)) {
            throw ApiException(0, L10n.tr(R.string.t_mediaflow_https_needed, "Your MediaFlow Proxy address must start with https:// to carry debrid keys."))
        }
        val query = buildList {
            add("d=${Net.encode(address)}")
            if (settings.password.isNotBlank()) add("api_password=${Net.encode(settings.password)}")
            add("h_accept-encoding=identity")
            contentType?.let { add("h_content-type=${Net.encode(it)}") }
            headers.forEach { (name, value) -> if (HEADER.matches(name)) add("h_${name.lowercase(Locale.ROOT)}=${Net.encode(value)}") }
        }
        return "${settings.server}/proxy/forward?${query.joinToString("&")}" to mapOf("Accept-Encoding" to "identity")
    }

    /** The server's public IP (`/proxy/ip`), which also proves the address and password work. Call on a worker. */
    fun test(transport: (String) -> String = { Net.raw(it) }): String {
        val settings = load()
        check(settings.server.isNotBlank()) { L10n.tr(R.string.t_mediaflow_add_server_first, "Add your MediaFlow Proxy server first.") }
        val answer = try { transport("${settings.server}/proxy/ip" + if (settings.password.isBlank()) "" else "?api_password=${Net.encode(settings.password)}") }
            catch (error: ApiException) {
                if (error.code == 401 || error.code == 403) throw IllegalStateException(L10n.tr(R.string.t_mediaflow_wrong_password, "MediaFlow Proxy did not accept this API password."))
                throw error
            }
        // {"ip":"203.0.113.7"}
        val ip = Regex("\"ip\"\\s*:\\s*\"([^\"]{1,64})\"").find(answer)?.groupValues?.get(1).orEmpty()
        check(ip.isNotBlank() && ip.length <= 64 && ip.none(Char::isWhitespace)) { L10n.tr(R.string.t_mediaflow_no_answer, "The server did not answer like MediaFlow Proxy. Check the address.") }
        return ip
    }

    companion object {
        const val SERVER = "mediaflow.server"
        const val PASSWORD = "mediaflow.password"
        const val ON = "mediaflow.on"
        const val DEBRID = "mediaflow.debrid"
        const val ADDONS = "mediaflow.addons"
        /** The player and link-list logo id. */
        const val LOGO = "mediaflow"
        /** Each debrid service's API host, for [forward]. */
        val API_HOSTS = mapOf("realdebrid" to "api.real-debrid.com", "alldebrid" to "api.alldebrid.com", "premiumize" to "www.premiumize.me",
            "debridlink" to "debrid-link.com", "offcloud" to "offcloud.com", "torbox" to "api.torbox.app")

        fun routes(source: SourceItem, playOn: String?, settings: Settings): Boolean {
            if (!settings.ready) return false
            val origin = Origin.of(source)
            if (origin.usenet || Easynews.isHost(source.directUrl)) return false
            if (source.directUrl.isNotEmpty() && !direct(source.directUrl)) return false
            val service = playOn ?: origin.servedBy
            return service != null && service in settings.debrid || origin.provider.isNotBlank() && origin.provider in settings.addons
        }

        /** Sends ticked debrid services' API calls through the server from now on (idempotent; any activity may call it). */
        fun install(store: SecureStore) {
            if (Net.apiRoute == null) Net.apiRoute = { address, headers, contentType -> MediaFlowProxy(store).forward(address, headers, contentType) }
        }

        private fun list(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        private fun toggle(current: Set<String>, id: String, on: Boolean) = (if (on) current + id else current - id).joinToString(",")

        /** "mfp.example.com", "https://mfp.example.com/", "http://192.168.1.5:8888" → a base address without a trailing slash. */
        fun server(value: String): String {
            val raw = value.trim().trimEnd('/')
            val candidate = if ("://" in raw) raw else "https://$raw"
            val uri = try { URI(candidate) } catch (_: Exception) { null }
            require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null && candidate.length <= 512) {
                L10n.tr(R.string.t_mediaflow_address_invalid, "Enter your MediaFlow Proxy address, like https://mfp.example.com")
            }
            return candidate
        }

        /**
         * The proxy address for [address]: HLS and DASH get MediaFlow's manifest endpoints (they rewrite segment links
         * so those go through the proxy too), anything else the byte-range stream endpoint.
         */
        fun proxied(server: String, password: String, address: String, headers: Map<String, String>): String {
            val path = runCatching { URI(address).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            val endpoint = when {
                path.endsWith(".m3u8") -> "/proxy/hls/manifest.m3u8"
                path.endsWith(".mpd") -> "/proxy/mpd/manifest.m3u8"
                else -> "/proxy/stream"
            }
            val query = buildList {
                add("d=${Net.encode(address)}")
                if (password.isNotBlank()) add("api_password=${Net.encode(password)}")
                headers.forEach { (name, value) -> if (HEADER.matches(name)) add("h_${name.lowercase(Locale.ROOT)}=${Net.encode(value)}") }
            }
            return "$server$endpoint?${query.joinToString("&")}"
        }
        private val HEADER = Regex("[A-Za-z0-9-]{1,64}")

        /** Only internet links: a proxy far away can't reach the device's own or LAN addresses, and Usenet/Easynews stay direct. */
        fun direct(address: String): Boolean {
            if (UsenetLinks.isNzb(address) || Easynews.isHost(address)) return false
            val host = runCatching { URI(address).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return false
            val literal = host.trim('[', ']')
            val numeric = literal.all { it.isDigit() || it == '.' } || ':' in literal
            if (!numeric) return true
            val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            return !(ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress)
        }
    }
}
