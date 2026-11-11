package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Signed playback URLs are credentials: retain a bounded, encrypted, account-scoped cache. */
class LastPlayedLinks internal constructor(
    private val get: (String) -> String,
    private val put: (String, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(store: SecureStore) : this({ store.get(it) }, store::put)
    /**
     * [name] and [size] describe the file, so a resumed video shows the same badges as a fresh pick. [headers] are
     * the request headers an add-on link needs; if they have expired, the replay fails and a fresh search follows.
     */
    data class Link(val identity: PlaybackIdentity, val url: String, val key: String, val service: String?, val account: String, val savedAt: Long,
                    val name: String = "", val size: Long? = null, val headers: Map<String, String> = emptyMap(),
                    /** The player's source tag (add-on, or "usenet|provider,indexer"), so a resumed video shows the same logos. */
                    val addon: String = "")
    private fun token(identity: PlaybackIdentity) = "${identity.type}:${identity.imdbId}:${identity.season}:${identity.episode}"
    private fun account(service: String?): String {
        val credential = when (service) { null -> return "direct"; in DebridServices.IDS -> get(DebridServices.credential(service)); else -> return "" }
        if (credential.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256").digest(credential.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun scope(service: String?): String = account(service)
    private fun entries(): List<Link> = runCatching {
        val array = JSONArray(get(STORAGE).ifBlank { "[]" })
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Link(PlaybackIdentity.fromJson(it.getJSONObject("identity")), it.getString("url"), it.getString("key"),
                it.optString("service").ifBlank { null }, it.getString("account"), it.getLong("savedAt"),
                it.optString("name"), it.optLong("size").takeIf { size -> size > 0 },
                it.optJSONObject("headers")?.let { json -> json.keys().asSequence().associateWith { name -> json.getString(name) } }.orEmpty(),
                it.optString("addon"))
        } }.filter { it.savedAt <= now() && now() - it.savedAt < KEEP_FOR && secure(it.url) }
    }.getOrDefault(emptyList())
    fun find(identity: PlaybackIdentity): Link? = synchronized(lock) {
        entries().firstOrNull { token(it.identity) == token(identity) && it.account.isNotBlank() && it.account == account(it.service) }
    }
    fun save(identity: PlaybackIdentity, url: String, key: String, service: String?, expectedAccount: String? = null,
             name: String = "", size: Long? = null, headers: Map<String, String> = emptyMap(), addon: String? = null) = synchronized(lock) {
        if (!secure(url) || !identity.imdbId.matches(Regex("tt[0-9]{5,12}"))) return@synchronized
        val scope = account(service).takeIf { it.isNotBlank() && (expectedAccount == null || expectedAccount == it) } ?: return@synchronized
        write(listOf(Link(identity, url, key, service, scope, now(), name.take(4000), size?.takeIf { it > 0 }, headers, addon.orEmpty().take(200))) +
            entries().filterNot { token(it.identity) == token(identity) })
    }
    fun remove(identity: PlaybackIdentity) = synchronized(lock) { write(entries().filterNot { token(it.identity) == token(identity) }) }
    private fun write(items: List<Link>) {
        val array = JSONArray()
        items.take(MAX_LINKS).forEach { array.put(JSONObject().put("identity", it.identity.toJson()).put("url", it.url).put("key", it.key)
            .put("service", it.service.orEmpty()).put("account", it.account).put("savedAt", it.savedAt)
            .put("name", it.name).also { item -> it.size?.let { size -> item.put("size", size) } }
            .also { item -> if (it.headers.isNotEmpty()) item.put("headers", JSONObject(it.headers)) }
            .also { item -> if (it.addon.isNotEmpty()) item.put("addon", it.addon) }) }
        put(STORAGE, array.toString())
    }
    // Add-on direct links may be plain HTTP (debrid links are always HTTPS); embedded credentials and local files never replay.
    private fun secure(value: String) = runCatching { URI(value).let { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    companion object {
        private const val STORAGE = "playback.last_links"
        // A week covers a show watched every few days; a dead link only costs one failed try before a fresh search.
        internal const val KEEP_FOR = 7 * 24 * 60 * 60 * 1000L; internal const val MAX_LINKS = 60; private val lock = Any() }
}
