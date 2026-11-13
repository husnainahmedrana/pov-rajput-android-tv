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
    private val server: () -> String = { Easynews.AUTO },
    /** A movie's release year, from Cinemeta; null when unknown. */
    private val year: (PlaybackIdentity) -> Int? = { identity -> cinemetaYear(identity) }
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
        if (identity.title.isBlank()) return emptyList()
        if (identity.type == "series") {
            require(identity.season >= 0 && identity.episode > 0) { "Choose an episode before searching Easynews." }
            val episode = "S${identity.season.toString().padStart(2, '0')}E${identity.episode.toString().padStart(2, '0')}"
            return query("${identity.title} $episode", username, password).filter { matches(it.title, identity, null) }
        }
        // Easynews matches words anywhere in a file name, so "Verity" alone finds unrelated files: search with the
        // release year, and keep only names that start with the title followed by that year.
        val year = year(identity)
        if (year != null) {
            val dated = query("${identity.title} $year", username, password).filter { matches(it.title, identity, year) }
            if (dated.isNotEmpty()) return dated
        }
        return query(identity.title, username, password).filter { matches(it.title, identity, year) }
    }

    /** Any videos matching [text], unfiltered: test material for the NNTP speed test. */
    internal fun sample(text: String): List<SourceItem> = credentials()?.let { (username, password) -> query(text, username, password) }.orEmpty()

    private fun query(text: String, username: String, password: String): List<SourceItem> {
        val params = listOf("gps" to text.take(300), "pby" to "100", "pno" to "1", "spamf" to "1", "u" to "1", "sb" to "1",
            "gx" to "1", "st" to "adv", "sS" to "3", "s1" to "relevance", "s1d" to "-", "s2" to "dsize", "s2d" to "-",
            "fty[]" to "VIDEO", "safeO" to "1")
        val url = "$BASE/2.0/search/solr-search/advanced?" + params.joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
        val json = try { JSONObject(get(url, Easynews.auth(username, password))) }
            catch (error: ApiException) { throw error }
            catch (_: Exception) { throw ApiException(0, "Easynews returned an unreadable search response.") }
        return parse(json)
    }

    /**
     * The file name must start with the title (a leading "The" optional). After it, a movie needs a year within one of
     * the release year (or, when the name carries no year, straight to the quality); an episode needs its SxxEyy,
     * optionally after the show's year.
     */
    internal fun matches(file: String, identity: PlaybackIdentity, year: Int?): Boolean {
        val name = normalize(file.substringBeforeLast('.'))
        val title = normalize(identity.title).removePrefix("the ")
        if (title.isBlank()) return false
        // A leading "The" may be dropped only when the show or film itself has one: "The Office" finds "Office.S01E01",
        // but "Dark" must not take "The.Dark.S01E02", a different show.
        val candidates = if (normalize(identity.title).startsWith("the ")) listOf(name, name.removePrefix("the ")) else listOf(name)
        val rest = candidates.firstNotNullOfOrNull { candidate ->
            when { candidate == title -> ""; candidate.startsWith("$title ") -> candidate.substring(title.length + 1); else -> null }
        } ?: return false
        if (identity.type == "series") {
            val episode = Regex("^(?:(?:19|20)\\d\\d )?s(\\d{1,2}) ?e(\\d{1,3})(?!\\d)").find(rest) ?: return false
            return episode.groupValues[1].toInt() == identity.season && episode.groupValues[2].toInt() == identity.episode
        }
        val named = Regex("^((?:19|20)\\d\\d)(?!\\d)").find(rest)?.groupValues?.get(1)?.toInt()
        return when {
            named != null -> year == null || kotlin.math.abs(named - year) <= 1
            // No year right after the title: accept only when the name has no year at all and goes straight to the quality.
            Regex("(?<!\\d)(?:19|20)\\d\\d(?!\\d)").containsMatchIn(rest) -> false
            else -> rest.isEmpty() || Regex("^(?:\\d{3,4}p|4k|uhd)\\b").containsMatchIn(rest)
        }
    }

    private fun normalize(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("&", " and ").replace(Regex("['’]"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

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
            EasynewsNzb.remember(hash, name, ext, item.optString("sig"))
            val link = "$down/" + listOf(farm, port, hash + ext, file).joinToString("/") { Net.encode(it) }
            if (!Easynews.isHost(link)) return@mapNotNull null
            // Easynews reports each file's audio and subtitle languages; written as 🌐 / 💬 lines (as add-ons such as
            // Meteor do) they rank in the link list like any tagged link instead of falling behind as untagged.
            val audio = languages(item.optJSONArray("alangs"))
            val subtitles = languages(item.optJSONArray("slangs"))
            SourceItem(title = file.take(500), provider = "Easynews", directUrl = link,
                sizeBytes = item.optLong("rawSize", 0).takeIf { it > 0 },
                qualityHeight = SourceMetadata.qualityHeight(file) ?: videoHeight(item),
                detail = listOf("Easynews", item.optString("14"), audio.takeIf { it.isNotEmpty() }?.let { "🌐 " + it.joinToString(", ") },
                    subtitles.takeIf { it.isNotEmpty() }?.let { "💬 " + it.joinToString(", ") })
                    .filterNot { it.isNullOrBlank() }.joinToString(" · ").take(400))
        }
    }

    /** "eng", "ita" (ISO 639-2) as English names, which the link-list language ranking recognises. */
    private fun languages(codes: org.json.JSONArray?): List<String> {
        if (codes == null) return emptyList()
        return (0 until minOf(codes.length(), 12)).mapNotNull { index ->
            val code = codes.optString(index).trim().lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return@mapNotNull null
            val name = Locale.forLanguageTag(ISO3_ALIASES[code] ?: code).getDisplayLanguage(Locale.ENGLISH)
            name.takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
        }.distinct()
    }

    /** Quality from the real picture size when the file name has none: width first, so 3840 × 1600 scope films count as 4K. */
    private fun videoHeight(item: JSONObject): Int? {
        val width = item.optString("width").toIntOrNull() ?: 0
        val height = item.optString("height").toIntOrNull() ?: 0
        return when {
            width >= 7000 || height >= 4000 -> 4320
            width >= 3200 || height >= 2000 -> 2160
            width >= 1800 || height >= 1000 -> 1080
            width >= 1200 || height >= 700 -> 720
            width >= 900 || height >= 560 -> 576
            height > 0 -> 480
            else -> null
        }
    }

    companion object {
        private const val BASE = "https://members.easynews.com"
        private val years = java.util.concurrent.ConcurrentHashMap<String, Int>()
        /** "2025" or "2019–2022" from Cinemeta's public metadata; remembered per title for this app session. */
        internal fun cinemetaYear(identity: PlaybackIdentity): Int? {
            if (identity.type != "movie" || !identity.imdbId.matches(Regex("tt\\d{5,10}"))) return null
            years[identity.imdbId]?.let { return it }
            val meta = try { Net.publicJson("https://v3-cinemeta.strem.io/meta/movie/${identity.imdbId}.json").optJSONObject("meta") } catch (_: Exception) { null }
            val year = listOf(meta?.optString("year").orEmpty(), meta?.optString("releaseInfo").orEmpty())
                .firstNotNullOfOrNull { Regex("(?:19|20)\\d\\d").find(it)?.value?.toInt() } ?: return null
            years[identity.imdbId] = year
            return year
        }
        /** Bibliographic ISO 639-2 codes that Java's Locale doesn't know by that spelling. */
        private val ISO3_ALIASES = mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "ice" to "is", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
            "geo" to "ka", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy")
    }
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
    /** Parallel download connections per video. Easynews caps each connection, so more connections stream faster. */
    const val CONNECTIONS = "easynews.connections"
    const val MAX_CONNECTIONS = 16
    const val DEFAULT_CONNECTIONS = 8
    fun connections(store: SecureStore) = store.get(CONNECTIONS).toIntOrNull()?.coerceIn(1, MAX_CONNECTIONS) ?: DEFAULT_CONNECTIONS

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
