package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONObject
import java.net.URI

/**
 * StreamNZB's own library: the Usenet releases it has streamed and kept (its dashboard's Library page). StreamNZB keeps
 * this behind its admin login, not the add-on link, so the viewer signs in once and only the returned token is saved.
 * Each entry names its title (IMDb id, season, episode) and release; it plays through the viewer's StreamNZB add-on,
 * which offers the kept release among that title's links. Call on a worker.
 */
internal class StreamNzbLibrary(
    private val get: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, headers = headers) },
    private val post: (String, JSONObject) -> String = { url, body -> Net.raw(url, "POST", emptyMap(), body) },
) {
    data class Item(
        val id: String, val type: String, val contentId: String, val season: Int, val episode: Int,
        val release: String, val sizeBytes: Long, val status: String,
    ) {
        /** The Stremio id the add-on's streams are asked for: "tt…" for a movie, "tt…:1:2" for an episode. */
        val streamId: String get() = if (type == "movie" || season <= 0 || episode <= 0) contentId else "$contentId:$season:$episode"
    }
    data class Page(val items: List<Item>, val total: Int)
    data class Link(val url: String, val headers: Map<String, String>)

    /** Signs in to the StreamNZB dashboard and returns its session token; the password is never kept. */
    fun login(origin: String, username: String, password: String): String {
        val reply = JSONObject(post("$origin/api/login", JSONObject().put("username", username).put("password", password)))
        val token = reply.optString("token")
        if (!reply.optBoolean("success") || token.isBlank()) throw ApiException(401,
            reply.optString("error").takeIf { it.isNotBlank() && it != "null" }?.take(160) ?: "StreamNZB did not accept that username and password.")
        if (reply.optBoolean("must_change_password")) throw ApiException(403, L10n.tr(R.string.t_change_the_streamnzb_admin_password_in, "Change the StreamNZB admin password in its dashboard first, then sign in here."))
        return token
    }

    /** A page of the library, newest first as StreamNZB lists it. Releases StreamNZB has marked bad are left out. */
    fun page(origin: String, token: String, offset: Int = 0, limit: Int = PAGE): Page {
        val json = JSONObject(get("$origin/api/library?offset=$offset&limit=$limit", mapOf("Authorization" to "Bearer $token")))
        val list = json.optJSONArray("items")
        val items = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.mapNotNull(::item)
            .filterNot { it.status.equals("bad", ignoreCase = true) }
        return Page(items, json.optInt("total", items.size))
    }

    /**
     * The kept release's playable link: the add-on's streams for its title, picked by release name. Null when the
     * add-on does not offer that release right now (the viewer then chooses from the title's links).
     */
    fun link(endpoint: String, item: Item): Link? {
        val kind = if (item.type == "movie") "movie" else "series"
        val streams = JSONObject(get("$endpoint/stream/$kind/${Net.encode(item.streamId)}.json", emptyMap())).optJSONArray("streams") ?: return null
        val wanted = normalized(item.release)
        if (wanted.isBlank()) return null
        return (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.firstNotNullOfOrNull { stream ->
            val url = stream.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@firstNotNullOfOrNull null
            val hints = stream.optJSONObject("behaviorHints")
            val text = listOf(stream.optString("title"), stream.optString("description"), stream.optString("name"),
                hints?.optString("filename").orEmpty()).joinToString(" ")
            if (wanted !in normalized(text)) return@firstNotNullOfOrNull null
            val request = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            Link(url, request?.keys()?.asSequence()?.mapNotNull { name ->
                val value = request.optString(name)
                if (name.isBlank() || name.any { it <= ' ' || it == ':' } || value.any { it == '\r' || it == '\n' }) null else name to value
            }?.take(20)?.toMap().orEmpty())
        }
    }

    companion object {
        const val PAGE = 50
        /** SecureStore keys: the dashboard's address and the token it returned. */
        const val ORIGIN = "streamnzb.library.origin"
        const val TOKEN = "streamnzb.library.token"

        /** The StreamNZB server the add-on link points at ("https://host"), where its dashboard and library live. */
        fun origin(endpoint: String): String? = runCatching {
            val uri = URI(endpoint)
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) null else "https://${uri.rawAuthority}"
        }.getOrNull()

        /** Letters and digits only, so "The.Odyssey.2026…" matches "The Odyssey 2026…" in a link's text. */
        internal fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

        internal fun item(json: JSONObject): Item? {
            val id = json.optString("id").ifBlank { return null }
            val content = json.optString("content_id").trim().takeIf { it.matches(Regex("[A-Za-z0-9:._-]{2,64}")) } ?: return null
            val release = json.optString("release_title").trim().ifBlank { json.optString("media_file_name").trim() }.ifBlank { return null }
            return Item(id, json.optString("content_type").lowercase().ifBlank { "movie" }, content,
                json.optInt("season"), json.optInt("episode"), release.take(300),
                json.optLong("media_file_size").takeIf { it > 0 } ?: json.optLong("size_bytes"), json.optString("status"))
        }
    }
}
