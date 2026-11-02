package dev.pov.tv.data

import java.net.URI
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

/**
 * Easynews search, adapted from the upstream POV Android TV fork. Easynews has no official streaming API, so this uses
 * the members web search; it may stop working if Easynews changes that site.
 *
 * The username and password stay in [SecureStore]. Results carry only the easynews.com file address: the player adds
 * the login itself, and only for Easynews hosts (see [Easynews.isHost]).
 */
class EasynewsApi internal constructor(
    private val credentials: () -> Pair<String, String>?,
    private val get: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, "GET", headers) },
    /** The chosen download server, or "auto" to use the one Easynews names in each search. */
    private val server: () -> String = { Easynews.AUTO }
) {
    constructor(store: SecureStore) : this({ Easynews.credentials(store) }, server = { Easynews.server(store) })

    fun connected() = credentials() != null

    /** Signs in to the members area; a missing account form means the login or web-access plan was refused. */
    fun verify(username: String, password: String) {
        val html = try { get("https://account.easynews.com/editinfo.php", Easynews.auth(username, password)) }
        catch (error: ApiException) {
            if (error.code == 401 || error.code == 403) throw ApiException(error.code, "Easynews didn't accept this username and password.")
            throw error
        }
        if (!Regex("(?is)<form\\b[^>]*\\bid\\s*=\\s*['\"]accountForm['\"]").containsMatchIn(html)) {
            throw ApiException(401, "Easynews didn't verify this account. Check your username, password and web-access plan.")
        }
    }

    fun search(identity: PlaybackIdentity): List<SourceItem> {
        val (username, password) = credentials() ?: return emptyList()
        val query = if (identity.type == "series") {
            require(identity.season >= 0 && identity.episode > 0) { "Choose an episode before searching Easynews." }
            "${identity.title} S${identity.season.toString().padStart(2, '0')}E${identity.episode.toString().padStart(2, '0')}"
        } else identity.title
        if (query.isBlank()) return emptyList()
        val params = listOf("gps" to query.take(300), "pby" to "100", "pno" to "1", "spamf" to "1", "u" to "1", "sb" to "1",
            "gx" to "1", "st" to "adv", "sS" to "3", "s1" to "relevance", "s1d" to "-", "s2" to "dsize", "s2d" to "-",
            "fty[]" to "VIDEO", "safeO" to "1")
        val url = "$BASE/2.0/search/solr-search/advanced?" + params.joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
        val json = try { JSONObject(get(url, Easynews.auth(username, password))) }
            catch (error: ApiException) { throw error }
            catch (_: Exception) { throw ApiException(0, "Easynews returned an unreadable search response.") }
        return parse(json).filterNot { identity.type == "series" && EpisodeFilename.isExplicitMismatch(it.title, identity.season, identity.episode) }
    }

    internal fun parse(json: JSONObject): List<SourceItem> {
        val data = json.optJSONArray("data") ?: throw ApiException(0, "Easynews returned an unsupported search response. Its website may have changed.")
        if (data.length() == 0) return emptyList()
        val down = json.optString("downURL").trimEnd('/')
        // Easynews ignores the port in the address (every port redirects to HTTPS 443), but the farm picks the server.
        val farm = server().takeIf { chosen -> chosen != Easynews.AUTO && Easynews.SERVERS.any { it.first == chosen } } ?: json.optString("dlFarm")
        val port = json.optString("dlPort")
        if (!Easynews.isHost(down) || '/' in farm || '/' in port) return emptyList()
        return (0 until minOf(data.length(), 100)).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val virus = when (val value = item.opt("virus")) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.lowercase(Locale.ROOT) !in setOf("", "0", "false", "null")
                else -> false
            }
            if (virus) return@mapNotNull null
            if (item.has("type") && item.optString("type").uppercase(Locale.ROOT) != "VIDEO") return@mapNotNull null
            // Skip samples and clips: anything under six minutes.
            if (Regex("^\\d+s|^[0-5]m").containsMatchIn(item.optString("14"))) return@mapNotNull null
            val hash = item.optString("0"); val name = item.optString("10"); val ext = item.optString("11")
            if (hash.isBlank() || name.isBlank() || ext.isBlank()) return@mapNotNull null
            val file = name + ext
            val link = "$down/" + listOf(farm, port, hash + ext, file).joinToString("/") { Net.encode(it) }
            if (!Easynews.isHost(link)) return@mapNotNull null
            SourceItem(title = file.take(500), provider = "Easynews", directUrl = link,
                sizeBytes = item.optLong("rawSize", 0).takeIf { it > 0 }, qualityHeight = SourceMetadata.qualityHeight(file),
                detail = listOf("Easynews", item.optString("14")).filter { it.isNotBlank() }.joinToString(" · ").take(200))
        }
    }

    companion object { private const val BASE = "https://members.easynews.com" }
}

object Easynews {
    const val USER = "easynews.username"
    const val PASSWORD = "easynews.password"
    const val SERVER = "easynews.server"
    const val AUTO = "auto"
    /** Download servers found on 2026-11-02; any other farm name falls back to Amsterdam. */
    val SERVERS = listOf(AUTO to "Auto (Easynews picks)", "ams" to "Amsterdam · Europe", "iad" to "Virginia · US East", "lax" to "Los Angeles · US West")
    fun server(store: SecureStore) = store.get(SERVER).takeIf { saved -> SERVERS.any { it.first == saved } } ?: AUTO
    fun serverLabel(store: SecureStore) = SERVERS.first { it.first == server(store) }.second

    fun credentials(store: SecureStore): Pair<String, String>? {
        val user = store.get(USER); val password = store.get(PASSWORD)
        return if (user.isBlank() || password.isBlank()) null else user to password
    }

    fun save(store: SecureStore, username: String, password: String) = store.putAll(mapOf(USER to username, PASSWORD to password))
    fun disconnect(store: SecureStore) { store.remove(USER); store.remove(PASSWORD) }
    /** Off keeps the login saved but leaves Easynews out of link searches. */
    fun enabled(store: SecureStore) = store.get(OFF) != "true"
    fun setEnabled(store: SecureStore, on: Boolean) { if (on) store.remove(OFF) else store.put(OFF, "true") }
    /** Connected and switched on. */
    fun active(store: SecureStore) = credentials(store) != null && enabled(store)
    private const val OFF = "easynews.off"

    fun auth(username: String, password: String): Map<String, String> {
        require(username.isNotBlank() && ':' !in username && username.length <= 256 && username.none { it.isISOControl() }) { "Enter your Easynews username." }
        require(password.isNotEmpty() && password.length <= 1024 && password.none { it == '\r' || it == '\n' }) { "Enter your Easynews password." }
        return mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8)))
    }

    /** HTTPS on easynews.com or a subdomain, default port, no embedded login: the only places the login is ever sent. */
    fun isHost(address: String): Boolean = try {
        val uri = URI(address)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        uri.scheme.equals("https", true) && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "easynews.com" || host.endsWith(".easynews.com"))
    } catch (_: Exception) { false }
}
