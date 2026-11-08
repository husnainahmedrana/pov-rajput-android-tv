package dev.pov.tv.data

import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** [newsProvider]: the Usenet news provider this add-on (AIOStreams) downloads through, chosen by the viewer, for its logo. */
internal data class SourceProvider(val id: String, val name: String, val address: String, val enabled: Boolean = true, val newsProvider: String = "")

/** Configured add-on addresses may contain private configuration; keep them in SecureStore. */
internal class SourceProviders(private val get: (String) -> String, private val put: (Map<String, String>) -> Unit) {
    constructor(store: SecureStore) : this({ store.get(it) }, { store.putAll(it) })

    fun load(): List<SourceProvider> {
        val saved = get(KEY)
        if (saved.isNotBlank()) return try {
            val array = JSONArray(saved)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SourceProvider(item.getString("id"), item.getString("name"), item.getString("address"), item.getBoolean("enabled"), item.optString("newsProvider"))
            }.also(::validate)
        } catch (_: Exception) { throw IllegalStateException("Saved source providers could not be read. Check source providers in Settings.") }
        val legacy = get("sources.endpoint")
        return if (legacy.isBlank()) emptyList() else listOf(SourceProvider("legacy", "Source 1", legacy))
    }

    fun save(providers: List<SourceProvider>) {
        validate(providers)
        val array = JSONArray()
        providers.forEach { provider -> array.put(JSONObject().put("id", provider.id).put("name", provider.name)
            .put("address", endpoint(provider.address)).put("enabled", provider.enabled).put("newsProvider", provider.newsProvider)) }
        // Clear the legacy slot atomically so an empty list never resurrects a removed provider.
        put(mapOf(KEY to array.toString(), "sources.endpoint" to ""))
    }

    private fun validate(providers: List<SourceProvider>) {
        require(providers.map { it.id }.distinct().size == providers.size && providers.none { it.id.isBlank() }) { "Invalid source provider identifiers." }
        providers.forEach { name(it.name); endpoint(it.address) }
        require(providers.map { endpoint(it.address) }.distinct().size == providers.size) { "This source address is already saved." }
    }

    companion object {
        const val KEY = "sources.providers.v1"
        fun create(name: String, address: String) = SourceProvider(UUID.randomUUID().toString(), name(name), endpoint(address))
        fun name(value: String): String {
            val clean = value.trim()
            require(clean.isNotBlank() && clean.length <= 50 && clean.none { it.isISOControl() } && !clean.contains("://")) {
                "Enter a short provider name (up to 50 characters), rather than a URL."
            }
            return clean
        }
        fun endpoint(value: String): String {
            require(value.isNotBlank()) { "Add a source provider in Settings before searching for links." }
            val candidate = value.trim().trimEnd('/').removeSuffix("/manifest.json").trimEnd('/')
            val uri = try { URI(candidate) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid HTTPS Stremio-compatible add-on address.") }
            require(candidate.length <= 4096 && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Use an HTTPS add-on base or manifest URL, without a query or fragment."
            }
            return candidate
        }

        /**
         * Torz settings for a StremThru server: the P2P store, so it lists plain torrents and the viewer's own debrid
         * services (connected in POV) check them, like Torrentio. No key is inside: base64url of {"stores":[{"c":"p2p"}]}.
         */
        const val STREMTHRU_TORZ_P2P = "eyJzdG9yZXMiOlt7ImMiOiJwMnAifV19"

        /**
         * A bare server address ("https://stremthru.example/") is not an add-on. When it is a StremThru server, its Torz
         * torrent search is used instead. [manifest] fetches a manifest (call on a worker); any other address, or a
         * server that isn't StremThru, comes back unchanged.
         */
        fun resolveServer(address: String, manifest: (String) -> JSONObject): String {
            val endpoint = endpoint(address)
            if (!URI(endpoint).path.isNullOrEmpty()) return endpoint
            val torz = try { manifest("$endpoint/stremio/torz/manifest.json") } catch (_: Exception) { return endpoint }
            return if (torz.optString("id").endsWith("stremthru.torz")) "$endpoint/stremio/torz/$STREMTHRU_TORZ_P2P" else endpoint
        }
    }
}
