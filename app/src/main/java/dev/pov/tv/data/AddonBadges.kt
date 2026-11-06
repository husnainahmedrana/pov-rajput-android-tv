package dev.pov.tv.data

import java.net.URI
import java.util.Locale

/**
 * Add-ons with their own logo (PenguPlay, CNCVerse, PlavioHD). They are known by the add-on's address, which stays the same
 * however the viewer names the add-on; the name is only a fallback, e.g. for a self-hosted copy.
 */
object AddonBadges {
    const val PENGUPLAY = "penguplay"
    const val CNCVERSE = "cncverse"
    const val PLAVIO = "plavio"
    const val AIOSTREAMS = "aiostreams"
    /** AltMount: the self-hosted Usenet streamer, as its own Stremio add-on ("AltMount Usenet") or behind AIOStreams. */
    const val ALTMOUNT = "altmount"
    /** StreamNZB: the self-hosted Usenet streaming add-on ("StreamNZB · …", manifest id community.streamnzb). */
    const val STREAMNZB = "streamnzb"
    val IDS = setOf(PENGUPLAY, CNCVERSE, PLAVIO, AIOSTREAMS, ALTMOUNT, STREAMNZB)
    /** Usenet add-ons on their own (not behind AIOStreams): their logo, the news provider, then the indexer. */
    fun standaloneUsenet(addon: String?) = addon == ALTMOUNT || addon == STREAMNZB
    /** Usenet add-ons whose links show their logo, the news provider and the indexer. */
    fun usenet(addon: String?) = addon == AIOSTREAMS || standaloneUsenet(addon)

    /** Usenet logos for AIOStreams links: news providers and indexers. */
    const val NEWSHOSTING = "newshosting"
    /** News providers the viewer can pick per add-on (id to name); blank shows no news-provider logo. */
    val NEWS_PROVIDERS = listOf("" to "Not set", "easynews" to "Easynews", "newshosting" to "Newshosting", "eweka" to "Eweka", "giganews" to "Giganews", "usenetserver" to "UsenetServer", "frugalusenet" to "Frugal Usenet", "tweaknews" to "Tweaknews", "usenetfarm" to "Usenet.Farm", "newsdemon" to "NewsDemon", "pureusenet" to "Pure Usenet", "vipernews" to "ViperNews")
    val NEWS_PROVIDER_IDS = NEWS_PROVIDERS.map { it.first }.filter(String::isNotEmpty).toSet()
    private val INDEXERS = listOf("geek" to "nzbgeek", "ninja" to "ninjacentral", "miatrix" to "miatrix",
        "crawler" to "usenetcrawler", "nest" to "nzbnest", "easynews" to "easynews", "slug" to "drunkenslug", "finder" to "nzbfinder",
        "planet" to "nzbplanet", "dog" to "dognzb", "althub" to "althub", "abnzb" to "abnzb", "tabula" to "tabularasa",
        "omgwtf" to "omgwtfnzbs", "carnage" to "digitalcarnage")
    val INDEXER_LOGOS = INDEXERS.map { it.second }.toSet()
    /** Indexer logo ids to their names, for the player badge's spoken description. */
    val INDEXER_NAMES = mapOf("nzbgeek" to "NZBgeek", "ninjacentral" to "NinjaCentral", "miatrix" to "Miatrix", "usenetcrawler" to "Usenet-Crawler",
        "nzbnest" to "NzbNest", "drunkenslug" to "DrunkenSlug", "nzbfinder" to "NZBFinder", "nzbplanet" to "NZBPlanet", "dognzb" to "DOGnzb",
        "althub" to "altHUB", "abnzb" to "abNZB", "tabularasa" to "Tabula Rasa", "omgwtfnzbs" to "omgwtfnzbs", "digitalcarnage" to "Digital Carnage")
    /** An indexer's logo id from its name ("NZBgeek", "NzbNest", "Usenet-Crawler"…), when we have one. */
    fun indexer(name: String): String? = name.lowercase(Locale.ROOT).let { lower -> INDEXERS.firstOrNull { it.first in lower }?.second }

    private val HOSTS = mapOf("pengu.uk" to PENGUPLAY, "cncverse.dpdns.org" to CNCVERSE, "plavio.site" to PLAVIO)

    fun of(address: String, name: String = ""): String? {
        val host = try { URI(address.trim()).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }.orEmpty()
        HOSTS.entries.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.let { return it.value }
        return when {
            name.contains("pengu", ignoreCase = true) -> PENGUPLAY
            name.contains("cncverse", ignoreCase = true) -> CNCVERSE
            name.contains("plavio", ignoreCase = true) -> PLAVIO
            name.contains("aiostreams", ignoreCase = true) -> AIOSTREAMS
            name.contains("altmount", ignoreCase = true) -> ALTMOUNT
            name.contains("streamnzb", ignoreCase = true) -> STREAMNZB
            else -> null
        }
    }
}
