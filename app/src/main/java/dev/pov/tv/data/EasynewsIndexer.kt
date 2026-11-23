package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Easynews as a Usenet indexer, like the Easynews-as-indexer bridges: Easynews only searches, and the viewer's news
 * providers download the articles. It is an entry in the indexers list (priority, limits and switches work as for any
 * indexer) with its own Easynews login, kept apart from the Easynews section that streams from Easynews itself.
 *
 * The entry's address is [ADDRESS], a name that never resolves; its "API key" packs the login. A result's link names
 * the file and its signature on that same made-up host, so the link list never takes it for an Easynews web link and
 * [UsenetLinks] marks it as an NZB. When it is played, [nzb] asks Easynews for the article list; the login only ever
 * goes to members.easynews.com (see [EasynewsNzb.fetch]).
 */
object EasynewsIndexer {
    private const val HOST = "easynews-indexer.nabrio.invalid"
    const val ADDRESS = "https://$HOST/api"
    private const val LINK = "https://$HOST/nzb"

    fun isEasynews(indexer: NewznabIndexer) = host(indexer.url) == HOST
    fun isLink(address: String) = host(address) == HOST

    /** The login packed as the entry's key: no spaces, so it passes the indexer checks. */
    fun key(username: String, password: String): String {
        require(username.isNotBlank() && password.isNotEmpty() && (username + password).none { it == '\r' || it == '\n' }) {
            L10n.tr(R.string.eni_enter_login, "Enter your Easynews username and password.")
        }
        return pack(username.trim() + "\n" + password)
    }

    fun credentials(indexer: NewznabIndexer): Pair<String, String>? = try {
        String(Base64.getUrlDecoder().decode(indexer.apiKey), Charsets.UTF_8).split('\n', limit = 2)
            .takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotEmpty() }?.let { it[0] to it[1] }
    } catch (_: Exception) { null }

    fun create(name: String, username: String, password: String): NewznabIndexer =
        NewznabIndexers.create(name.ifBlank { "Easynews" }, ADDRESS, key(username, password))

    /** Checks the login with Easynews; returns a line for the viewer. */
    fun test(indexer: NewznabIndexer, api: (Pair<String, String>) -> EasynewsApi = { login -> EasynewsApi({ login }) }): String {
        val login = credentials(indexer) ?: throw ApiException(0, L10n.tr(R.string.eni_enter_login, "Enter your Easynews username and password."))
        api(login).verify(login.first, login.second)
        return L10n.tr(R.string.eni_signed_in, "Easynews accepted this login")
    }

    /** Easynews's results for [identity] as Usenet links; files without an article list are left out. */
    fun search(indexer: NewznabIndexer, identity: PlaybackIdentity, newsProvider: String,
               api: (Pair<String, String>) -> EasynewsApi = { login -> EasynewsApi({ login }) }): List<SourceItem> {
        val login = credentials(indexer) ?: throw ApiException(0, L10n.tr(R.string.eni_enter_login, "Enter your Easynews username and password."))
        return api(login).search(identity).mapNotNull { item ->
            val token = EasynewsNzb.token(item.directUrl) ?: return@mapNotNull null
            val link = "$LINK/${Net.encode(token.name + token.ext)}?" + listOf("hash" to token.hash, "name" to token.name, "ext" to token.ext, "sig" to token.sig)
                .joinToString("&") { Net.encode(it.first) + "=" + Net.encode(it.second) }
            // The web link's details ("Easynews · 2h 1m · 🌐 English") without its own "Easynews", which leads anyway.
            val rest = item.detail.split(" · ").filter { it.isNotBlank() && it != "Easynews" }
            item.copy(provider = indexer.name, directUrl = UsenetLinks.mark(link), usenet = true, indexer = indexer.name, newsProvider = newsProvider,
                detail = (listOf("Usenet", indexer.name) + rest).joinToString(" · ").take(400))
        }
    }

    /** The article list for a link from [search], from Easynews with the entry's login. */
    fun nzb(address: String, indexer: NewznabIndexer?, fetch: (EasynewsNzb.Token, Map<String, String>) -> String? = { token, auth -> EasynewsNzb.fetch(token, auth) }): String {
        val login = indexer?.let(::credentials) ?: throw ApiException(0, L10n.tr(R.string.eni_no_login, "Add Easynews in Settings → Sources → Usenet to play this link."))
        val query = try { URI(address).rawQuery.orEmpty() } catch (_: Exception) { "" }
        val fields = query.split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else URLDecoder.decode(part.substring(0, at), "UTF-8") to URLDecoder.decode(part.substring(at + 1), "UTF-8")
        }.toMap()
        val token = EasynewsNzb.Token(fields["hash"].orEmpty(), fields["name"].orEmpty(), fields["ext"].orEmpty(), fields["sig"].orEmpty())
        require(token.hash.isNotBlank() && token.sig.isNotBlank() && token.ext.isNotBlank()) { L10n.tr(R.string.eni_no_nzb, "Easynews has no article list for this file. Choose another link.") }
        return fetch(token, Easynews.auth(login.first, login.second))
            ?: throw ApiException(0, L10n.tr(R.string.eni_no_nzb, "Easynews has no article list for this file. Choose another link."))
    }

    private fun pack(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun host(address: String) = try { URI(address).host?.lowercase() } catch (_: Exception) { null }
}
