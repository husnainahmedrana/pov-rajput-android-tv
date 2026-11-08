package dev.pov.tv

import dev.pov.tv.data.SourceItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BestPerQualityTest {
    private fun link(name: String, height: Int?) = SourceItem(title = name, provider = "p", directUrl = "https://x.example/$name", qualityHeight = height)

    @Test fun `keeps the first links of each quality in sort order`() {
        val sorted = listOf(link("a4k", 2160), link("b1080", 1080), link("c4k", 2160), link("d4k", 2160), link("e1080", 1080),
            link("f720", 720), link("g1080", 1080), link("h", null), link("i480", 480))
        assertEquals(listOf("a4k", "b1080", "c4k", "e1080", "f720", "h", "i480"), SourceSortPreferences.bestPerQuality(sorted, 2).map { it.title })
        assertEquals(listOf("a4k", "b1080", "f720", "h"), SourceSortPreferences.bestPerQuality(sorted, 1).map { it.title })
        assertEquals(sorted, SourceSortPreferences.bestPerQuality(sorted, 0))
    }
}
