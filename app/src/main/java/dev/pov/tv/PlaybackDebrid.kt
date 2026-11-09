package dev.pov.tv

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dev.pov.tv.data.AddonBadges
import dev.pov.tv.data.DebridServices
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.Easynews

/** Explicit resolution provenance, never guessed from the account priority or filename. */
internal object PlaybackDebrid {
    const val EASYNEWS = "easynews"
    const val PENGUPLAY = AddonBadges.PENGUPLAY
    const val CNCVERSE = AddonBadges.CNCVERSE
    const val PLAVIO = AddonBadges.PLAVIO
    const val AIOSTREAMS = AddonBadges.AIOSTREAMS
    private val ADDONS = AddonBadges.IDS
    /** The add-on badge of a direct link: from the add-on's address (see AddonBadges), else its name. */
    fun addon(source: SourceItem): String? = source.addon ?: if (source.directUrl.isNotEmpty()) AddonBadges.of("", source.provider) else null
    /** A logo id's name; unknown ids give the id itself. */
    fun describe(id: String) = label(id)
    private fun label(id: String) = when (id) { EASYNEWS -> "Easynews"; PENGUPLAY -> "PenguPlay"; CNCVERSE -> "CNCVerse"; PLAVIO -> "PlavioHD"; AIOSTREAMS -> "AIOStreams"; AddonBadges.ALTMOUNT -> "AltMount"; AddonBadges.STREAMNZB -> "StreamNZB"; AddonBadges.DEBRIDIO -> "Debridio"; AddonBadges.TORRENTIO -> "Torrentio"; AddonBadges.COMET -> "Comet"; AddonBadges.METEOR -> "Meteor"; AddonBadges.STREMTHRU -> "StremThru";in AddonBadges.NEWS_PROVIDER_IDS -> AddonBadges.NEWS_PROVIDERS.first { it.first == id }.second
        in AddonBadges.INDEXER_NAMES -> AddonBadges.INDEXER_NAMES.getValue(id)
        // Never crash on a logo id without a name (it only feeds the spoken description).
        else -> if (id in DebridServices.IDS) DebridServices.name(id) else id }
    fun normalize(service: String?): String? = service?.takeIf { it in DebridServices.IDS }
    /** The service's logo (Easynews included), as the player badge and the source list show it. */
    fun logo(id: String) = when (id) {
        "offcloud" -> R.drawable.pov_offcloud_logo
        "torbox" -> R.drawable.pov_torbox_logo
        "realdebrid" -> R.drawable.pov_realdebrid_logo
        "alldebrid" -> R.drawable.pov_alldebrid_logo
        "premiumize" -> R.drawable.pov_premiumize_logo
        EASYNEWS -> R.drawable.pov_easynews_logo
        PENGUPLAY -> R.drawable.pov_penguplay_logo
        CNCVERSE -> R.drawable.pov_cncverse_logo
        PLAVIO -> R.drawable.pov_plavio_logo
        AIOSTREAMS -> R.drawable.pov_aiostreams_logo
        AddonBadges.ALTMOUNT -> R.drawable.pov_altmount_logo
        AddonBadges.STREAMNZB -> R.drawable.pov_streamnzb_logo
        AddonBadges.DEBRIDIO -> R.drawable.pov_debridio_logo
        AddonBadges.TORRENTIO -> R.drawable.pov_torrentio_logo
        AddonBadges.COMET -> R.drawable.pov_comet_logo
        AddonBadges.METEOR -> R.drawable.pov_meteor_logo
        AddonBadges.STREMTHRU -> R.drawable.pov_stremthru_logo
        AddonBadges.NEWSHOSTING -> R.drawable.pov_newshosting_logo
        "eweka" -> R.drawable.pov_eweka_logo
        "giganews" -> R.drawable.pov_giganews_logo
        "usenetserver" -> R.drawable.pov_usenetserver_logo
        "frugalusenet" -> R.drawable.pov_frugalusenet_logo
        "tweaknews" -> R.drawable.pov_tweaknews_logo
        "usenetfarm" -> R.drawable.pov_usenetfarm_logo
        "newsdemon" -> R.drawable.pov_newsdemon_logo
        "pureusenet" -> R.drawable.pov_pureusenet_logo
        "vipernews" -> R.drawable.pov_vipernews_logo
        "nzbgeek" -> R.drawable.pov_nzbgeek_logo
        "ninjacentral" -> R.drawable.pov_ninjacentral_logo
        "miatrix" -> R.drawable.pov_miatrix_logo
        "usenetcrawler" -> R.drawable.pov_usenetcrawler_logo
        "nzbnest" -> R.drawable.pov_nzbnest_logo
        "drunkenslug" -> R.drawable.pov_drunkenslug_logo
        "nzbfinder" -> R.drawable.pov_nzbfinder_logo
        "nzbplanet" -> R.drawable.pov_nzbplanet_logo
        "dognzb" -> R.drawable.pov_dognzb_logo
        "althub" -> R.drawable.pov_althub_logo
        "abnzb" -> R.drawable.pov_abnzb_logo
        "tabularasa" -> R.drawable.pov_tabularasa_logo
        "omgwtfnzbs" -> R.drawable.pov_omgwtfnzbs_logo
        "digitalcarnage" -> R.drawable.pov_digitalcarnage_logo
        else -> R.drawable.pov_debridlink_logo
    }
    /** AllDebrid's mark is a wordmark that already spells the name. */
    private fun wordmark(id: String?) = id == "alldebrid"

    /**
     * [url] marks Easynews: an easynews.com address is the same test the player uses to add the Easynews login, so the
     * badge shows exactly when the video streams from Easynews.
     */
    /**
     * The add-on tag the player gets for a direct link: the add-on's id, and for AIOStreams the logos of what it streams
     * from ("aiostreams|easynews,nzbgeek": news provider or debrid, then indexer), the same logos as on its link card.
     */
    fun tag(source: SourceItem): String? {
        // A debrid add-on's link without an add-on logo: the service's logo.
        val addon = addon(source) ?: return source.servedBy?.takeIf { it in DebridServices.IDS }
        // Other add-ons: their logo, then the debrid service a debrid add-on named for the link (Debridio · TorBox).
        if (!AddonBadges.usenet(addon)) return source.servedBy?.takeIf { it in DebridServices.IDS && it != addon }?.let { "$addon|$it" } ?: addon
        val more = SourceBadges.cachedOn(source).drop(1)
        return if (more.isEmpty()) addon else "$addon|${more.joinToString(",")}"
    }

    fun bind(root: View, service: String?, url: String = "", addon: String? = null) {
        val tagged = addon?.substringBefore('|')
        // Logo ids only; a tag saved by an older preview held words ("Usenet · Newshosting"), which are skipped.
        val more = addon?.substringAfter('|', "").orEmpty().split(',').filter { it.matches(Regex("[a-z]+")) }
        // A scraper's torrent played on the viewer's debrid: the scraper's logo first, then the service's (Torrentio · TorBox).
        val scraperLead = tagged?.takeIf { it in AddonBadges.SCRAPERS && normalize(service) != null }
        val id = scraperLead ?: normalize(service) ?: EASYNEWS.takeIf { Easynews.isHost(url) } ?: tagged?.takeIf { it in ADDONS || it in DebridServices.IDS }
        val badge = root.findViewById<android.view.ViewGroup>(R.id.pov_debrid_badge)
        badge.visibility = if (id == null) View.GONE else View.VISIBLE
        // AIOStreams shows only logos (its own, the news provider or debrid, the indexer), no words.
        val logosOnly = AddonBadges.usenet(id) && id == tagged
        // The tag's extra logos (news provider, indexer, or the debrid service behind an add-on's link) follow its own.
        val extras = if (scraperLead != null) (listOfNotNull(normalize(service)) + more).distinct().filter { it != id }
            else if (id == tagged) more.filter { it != id } else emptyList()
        val label = if (id == null) "" else label(id)
        root.findViewById<TextView>(R.id.pov_debrid_name).apply {
            // Scrapers (Debridio, Torrentio, Comet…): their logo alone, no name (the viewer asked for that).
            text = label; visibility = if (wordmark(id) || logosOnly || id in AddonBadges.SCRAPERS) View.GONE else View.VISIBLE
        }
        val density = root.resources.displayMetrics.density
        root.findViewById<ImageView>(R.id.pov_debrid_logo).apply {
            // Torrentio's thin ring reads small beside solid logos, so it gets a slightly larger box.
            val box = if (id == AddonBadges.TORRENTIO) 22 else 20
            layoutParams = layoutParams.apply { width = ((if (wordmark(id)) 72 else box) * density).toInt(); height = (box * density).toInt() }
            setImageResource(if (id == null) 0 else logo(id))
            contentDescription = if (id == null) null else "Streaming with " + (listOf(label) + extras.map(::label)).joinToString(", ")
        }
        // The extra logos from a previous video are replaced.
        (badge.childCount - 1 downTo 0).map(badge::getChildAt).filter { it.tag == EXTRA_LOGO }.forEach(badge::removeView)
        extras.forEach { extra ->
            badge.addView(ImageView(root.context).apply {
                tag = EXTRA_LOGO; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; maxWidth = (120 * density).toInt()
                setImageResource(logo(extra))
            }, android.widget.LinearLayout.LayoutParams(-2, (20 * density).toInt()).apply { leftMargin = (8 * density).toInt() })
        }
    }
    private const val EXTRA_LOGO = "pov-extra-logo"

    /**
     * The debrid services a torrent link can be played on when the viewer holds OK on it (link list and the player's
     * Streams panel), as (service id, label): the services it is cached on (the preferred one first), then the connected
     * services that can only check when played. Empty for direct links.
     */
    fun choices(source: SourceItem, checkOnPlay: List<String>): List<Pair<String, String>> {
        if (source.directUrl.isNotEmpty() || source.infoHash.isBlank()) return emptyList()
        val cached = source.cachedServices.distinct()
        return cached.mapIndexed { index, service -> service to "Play with ${DebridServices.name(service)}${if (index == 0) " · preferred" else ""}" } +
            checkOnPlay.filterNot { it in cached }.map { service -> service to "Try ${DebridServices.name(service)} · checks when played" }
    }
}
