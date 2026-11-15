package dev.pov.tv

import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceSortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AgeSortTest {
    private fun link(name: String, height: Int, age: Long?, size: Long = 1_000) =
        SourceItem(title = name, provider = "indexer", qualityHeight = height, ageDays = age, sizeBytes = size, usenet = age != null)

    @Test fun `newest first puts recent Usenet posts first within a quality, undated links after`() {
        val links = listOf(link("old", 1080, 900), link("torrent", 1080, null), link("new", 1080, 3), link("mid", 1080, 120), link("4k", 2160, 2000))
        val order = SourceSortOrder(newestFirst = true)
        assertEquals(listOf("4k", "new", "mid", "old", "torrent"), order.sorted(links).map { it.title })
    }

    @Test fun `with Age off the order is unchanged by age`() {
        val links = listOf(link("old", 1080, 900, size = 3_000), link("new", 1080, 3, size = 2_000))
        assertEquals(listOf("old", "new"), SourceSortOrder().sorted(links).map { it.title })   // largest first
    }

    @Test fun `the NZB age badge shows days, then months, then years`() {
        assertEquals("Today", dev.pov.tv.data.SourceMetadata.ageLabel(0))
        assertEquals("12d", dev.pov.tv.data.SourceMetadata.ageLabel(12))
        assertEquals("5mo", dev.pov.tv.data.SourceMetadata.ageLabel(150))
        assertEquals("3y", dev.pov.tv.data.SourceMetadata.ageLabel(1250))
    }

    @Test fun `a saved order from before gets Age just before file size`() {
        assertEquals(listOf("library", "language", "quality", "preferred", "age", "size"), SourceSortOrder.priority("library,language,quality,preferred,size"))
    }
}
