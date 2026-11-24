package dev.pov.tv

import dev.pov.tv.data.Newznab
import dev.pov.tv.data.NewznabIndexer
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.UsenetLinks
import dev.pov.tv.data.UsenetSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsenetNameSearchTest {
    private val indexer = NewznabIndexer("i1", "Test Indexer", "https://api.example.com/api", "KEY123")
    private val rss = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
        <item><title>UFC.310.PPV.1080p.WEB-DL.H264-GRP</title><link>https://example.com/d/1</link><pubDate>Fri, 26 Feb 2027 10:00:00 +0000</pubDate>
        <enclosure url="https://api.example.com/getnzb/u1.nzb&amp;r=KEY123" length="6800000000" type="application/x-nzb"/><newznab:attr name="size" value="6800000000"/></item>
        </channel></rss>"""

    @Test fun `a name search asks t=search with the typed words and the chosen category`() {
        val asked = ArrayList<String>()
        val found = Newznab({ address, _ -> asked += address; rss }) { 1_800_000_000_000L }.searchText(indexer, "  ufc 310 ", "5060", "Prowlarr/9.9", "Newshosting")
        assertEquals(1, asked.size)
        assertTrue(asked[0], asked[0].startsWith("https://api.example.com/api?t=search&q=ufc%20310&cat=5060") || asked[0].startsWith("https://api.example.com/api?t=search&q=ufc+310&cat=5060"))
        assertEquals(listOf("UFC.310.PPV.1080p.WEB-DL.H264-GRP"), found.map { it.title })
        assertTrue(UsenetLinks.isNzb(found[0].directUrl)); assertTrue(found[0].usenet)
        assertEquals(1080, found[0].qualityHeight)
    }

    @Test fun `All sends no category, so the indexer returns every category it allows`() {
        val asked = ArrayList<String>()
        Newznab({ address, _ -> asked += address; rss }) { 0L }.searchText(indexer, "ufc 310", UsenetSearch.NameCategory.ALL.newznab, "a")
        assertFalse(asked[0], "cat=" in asked[0])
        assertEquals("2000", UsenetSearch.NameCategory.MOVIES.newznab)
        assertEquals("5000", UsenetSearch.NameCategory.TV.newznab)
        assertEquals("5060", UsenetSearch.NameCategory.SPORTS.newznab)
    }

    @Test fun `More results asks the next page by offset, and a short page means there is no more`() {
        val asked = ArrayList<String>()
        val (found, full) = Newznab({ address, _ -> asked += address; rss }) { 0L }.searchTextPage(indexer, "ufc", "5060", 100, "a")
        assertTrue(asked[0], "&offset=100" in asked[0] && "&limit=100" in asked[0])
        assertEquals(1, found.size)
        assertFalse(full)
        val first = ArrayList<String>()
        Newznab({ address, _ -> first += address; rss }) { 0L }.searchTextPage(indexer, "ufc", null, 0, "a")
        assertFalse(first[0], "offset=" in first[0])
    }

    @Test fun `an empty name asks nothing`() {
        val asked = ArrayList<String>()
        assertTrue(Newznab({ address, _ -> asked += address; rss }) { 0L }.searchText(indexer, "   ", null, "a").isEmpty())
        assertTrue(asked.isEmpty())
    }

    @Test fun `name-search links play under their own id, never an IMDb one`() {
        val identity = UsenetSearch.nameIdentity(" UFC 310 ")
        assertTrue(identity.imdbId.startsWith(UsenetSearch.NAME_ID))
        assertEquals("UFC 310", identity.title)
        assertEquals(identity.imdbId, UsenetSearch.nameIdentity("ufc 310").imdbId)
        assertTrue(UsenetSearch.isNameSearch(identity))
        assertFalse(UsenetSearch.isNameSearch(PlaybackIdentity("tt15398776", "movie", "Oppenheimer")))
        assertFalse(UsenetSearch.isNameSearch(null))
    }

    @Test fun `the loading strip reaches its last frame`() {
        assertEquals(FrameStrip.FRAMES - 1, FrameStrip.frameAt(FrameStrip.FRAMES - 0.5f))
        assertEquals(FrameStrip.FRAMES - 1, FrameStrip.frameAt(FrameStrip.FRAMES.toFloat()))
        assertEquals(0, FrameStrip.frameAt(0f))
        assertEquals(FrameStrip.FRAMES, FrameStrip.filledFrames(99))
        assertEquals(1, FrameStrip.filledFrames(1))
        assertEquals(0, FrameStrip.filledFrames(0))
        assertEquals(12, FrameStrip.filledFrames(50))
    }
}
