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
            check(array.length() <= MAX)
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
        require(providers.size <= MAX) { "You can add up to $MAX source providers." }
        require(providers.map { it.id }.distinct().size == providers.size && providers.none { it.id.isBlank() }) { "Invalid source provider identifiers." }
        providers.forEach { name(it.name); endpoint(it.address) }
        require(providers.map { endpoint(it.address) }.distinct().size == providers.size) { "This source address is already saved." }
    }

    companion object {
        const val KEY = "sources.providers.v1"
        const val MAX = 12
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
    }
}
