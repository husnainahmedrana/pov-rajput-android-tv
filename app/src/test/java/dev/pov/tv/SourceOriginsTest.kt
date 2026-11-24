package dev.pov.tv

import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceSortOrder
import dev.pov.tv.data.UsenetLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceOriginsTest {
    private val torbox = SourceItem("torbox", "Torrentio", infoHash = "a".repeat(40), cached = true, cachedServices = listOf("torbox"), qualityHeight = 1080)
    private val offcloud = SourceItem("offcloud", "Torrentio", infoHash = "b".repeat(40), cached = true, cachedServices = listOf("offcloud"), qualityHeight = 1080)
    private val easynews = SourceItem("easynews", "Easynews", directUrl = "https://members.easynews.com/dl/file.mkv", qualityHeight = 1080)
    private val usenet = SourceItem("usenet", "NZBgeek", directUrl = UsenetLinks.mark("https://api.nzbgeek.info/get/1"), qualityHeight = 1080)
    private val uncached = SourceItem("uncached", "Torrentio", infoHash = "c".repeat(40), qualityHeight = 1080)

    @Test fun `each link knows where it comes from`() {
        assertEquals(setOf("torbox"), SourceOrigins.of(torbox))
        assertEquals(setOf(SourceOrigins.EASYNEWS), SourceOrigins.of(easynews))
        assertEquals(setOf(SourceOrigins.USENET), SourceOrigins.of(usenet))
        assertEquals(emptySet<String>(), SourceOrigins.of(uncached))
        assertEquals(setOf("torbox", "offcloud"), SourceOrigins.of(torbox.copy(cachedServices = listOf("torbox", "offcloud"))))
    }

    @Test fun `a saved order is repaired and every origin is listed once`() {
        val order = SourceOrigins.order("easynews,bogus,torbox,easynews")
        assertEquals(listOf("easynews", "torbox"), order.take(2))
        assertEquals(SourceOrigins.IDS.toSet(), order.toSet())
        assertEquals(SourceOrigins.IDS.size, order.size)
    }

    @Test fun `TorBox then Easynews then Offcloud, uncached torrents last`() {
        val origins = SourceOrigins.order("torbox,easynews,offcloud")
        val sort = SourceSortOrder(sourceRank = { SourceOrigins.rank(it, origins) })
        assertEquals(listOf("torbox", "easynews", "offcloud", "usenet", "uncached"),
            sort.sorted(listOf(uncached, offcloud, usenet, easynews, torbox)).map { it.title })
    }

    @Test fun `Source is off by default and sits after quality`() {
        val links = listOf(offcloud.copy(sizeBytes = 2), torbox.copy(sizeBytes = 1))
        assertEquals(listOf("offcloud", "torbox"), SourceSortOrder().sorted(links).map { it.title })   // size decides, not source
        assertEquals(listOf("library", "language", "quality", "source", "preferred", "age", "grabs", "size"), SourceSortOrder.DEFAULT_PRIORITY)
    }
}
