package dev.pov.tv.data

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Easynews hands out an NZB (the list of Usenet articles) for any file in its search results. The request needs the
 * file's signature, which only the search answer carries, so searches leave it here for the player; nothing is saved
 * to disk, and a link opened after the app restarts has none (it then streams from the web servers as before).
 */
object EasynewsNzb {
    class Token(val hash: String, val name: String, val ext: String, val sig: String)

    private val tokens = ConcurrentHashMap<String, Token>()

    fun remember(hash: String, name: String, ext: String, sig: String) {
        if (sig.isBlank()) return
        if (tokens.size > 4000) tokens.clear()
        tokens[hash + ext] = Token(hash, name, ext, sig)
    }

    /** The search result behind an Easynews download address (…/farm/port/hash.ext/file.ext), if a search left one. */
    fun token(address: String): Token? = try {
        val parts = URI(address).rawPath.orEmpty().trimEnd('/').split('/')
        if (parts.size < 2) null else tokens[URLDecoder.decode(parts[parts.size - 2], "UTF-8")]
    } catch (_: Exception) { null }

    /** The NZB text, or null when Easynews has none for this file. */
    fun fetch(token: Token, auth: Map<String, String>, form: (String, Map<String, String>, List<Pair<String, String>>) -> String =
        { url, headers, fields -> Net.form(url, "POST", headers, fields) }): String? {
        fun pack(value: String) = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val text = form("https://members.easynews.com/2.0/api/dl-nzb", auth + ("Accept" to "*/*"),
            listOf("autoNZB" to "1", "0&sig=${token.sig}" to "${token.hash}|${pack(token.name)}:${pack(token.ext)}"))
        return text.takeIf { "<segment" in it }
    }
}
