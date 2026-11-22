package dev.pov.tv

import dev.pov.tv.data.AddonBadges
import dev.pov.tv.data.DebridServices
import dev.pov.tv.data.Easynews
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.UsenetLinks

/**
 * The Source row of Link sorting: where a link comes from (a debrid service, Easynews, Usenet or another add-on), so the
 * viewer can rank e.g. TorBox, then Easynews, then Offcloud. Off by default.
 */
internal object SourceOrigins {
    const val EASYNEWS = "easynews"
    const val USENET = "usenet"
    const val ADDONS = "addons"
    val IDS: List<String> get() = DebridServices.IDS + listOf(EASYNEWS, USENET, ADDONS)

    fun name(id: String): String = when (id) {
        EASYNEWS -> "Easynews"
        USENET -> "Usenet"
        ADDONS -> L10n.tr(R.string.t_other_add_ons, "Other add-ons")
        else -> DebridServices.name(id)
    }

    /** A saved order, repaired: each origin once, unknown ones dropped, missing ones added at the end. */
    fun order(saved: String?): List<String> {
        val keys = saved.orEmpty().split(',').map(String::trim).filter { it in IDS }.distinct()
        return keys + IDS.filterNot { it in keys }
    }

    /**
     * Where a link can play from. A torrent cached on several services counts for each (its best rank wins); a debrid
     * add-on's link counts for the service that serves it; an uncached torrent has no origin and sorts after the rest.
     */
    fun of(source: SourceItem): Set<String> = buildSet {
        source.servedBy?.takeIf { it in DebridServices.IDS }?.let(::add)
        if (source.directUrl.isEmpty()) source.cachedServices.filterTo(this) { it in DebridServices.IDS }
        when {
            Easynews.isHost(source.directUrl) -> add(EASYNEWS)
            UsenetLinks.isNzb(source.directUrl) || source.usenet || AddonBadges.standaloneUsenet(PlaybackDebrid.addon(source)) -> add(USENET)
            source.directUrl.isNotEmpty() && isEmpty() -> add(ADDONS)
        }
    }

    /** Lower first: the best-ranked origin of [source] in [order]; links with none come last. */
    fun rank(source: SourceItem, order: List<String>): Int =
        of(source).minOfOrNull { id -> order.indexOf(id).takeIf { it >= 0 } ?: order.size } ?: (order.size + 1)
}
