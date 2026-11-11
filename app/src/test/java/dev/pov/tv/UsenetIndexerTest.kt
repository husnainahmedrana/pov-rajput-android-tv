package dev.pov.tv

import dev.pov.tv.data.ApiException
import dev.pov.tv.data.NewsProviders
import dev.pov.tv.data.Newznab
import dev.pov.tv.data.NewznabIndexer
import dev.pov.tv.data.NewznabIndexers
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.UsenetHeaders
import dev.pov.tv.data.UsenetLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsenetIndexerTest {
    private val indexer = NewznabIndexer("i1", "Test Indexer", "https://api.example.com/api", "KEY123")
    private val now = 1_800_000_000_000L

    private fun rss(items: String, limits: String = "") = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
        <channel><title>x</title>$limits$items</channel></rss>"""
    private fun item(title: String, size: Long, extra: String = "", link: String = "https://api.example.com/getnzb/abc.nzb&amp;i=1&amp;r=KEY123") =
        """<item><title>$title</title><guid>g</guid><link>https://example.com/details/1</link><pubDate>Fri, 26 Feb 2027 10:00:00 +0000</pubDate>
           <enclosure url="$link" length="$size" type="application/x-nzb"/><newznab:attr name="size" value="$size"/>$extra</item>"""
    private val caps = """<caps><searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,season,ep,imdbid"/>
        <movie-search available="yes" supportedParams="q,imdbid"/></searching></caps>"""

    private class Recorder(val answers: (String) -> String) { val asked = ArrayList<Pair<String, String>>(); fun get(address: String, agent: String): String { asked += address to agent; return answers(address) } }

    @Test fun `a movie is asked for by IMDb id and its results become marked Usenet links`() {
        val recorder = Recorder { address -> if ("t=caps" in address) caps else rss(
            item("Oppenheimer.2023.2160p.UHD.BluRay.REMUX-GRP", 60_000_000_000, """<newznab:attr name="grabs" value="340"/>""") +
            item("Oppenheimer.2023.1080p.WEB-DL.x264-GRP", 8_000_000_000, """<newznab:attr name="password" value="1"/>""") +
            item("<![CDATA[Oppenheimer 2023 1080p BluRay x264-GRP]]>", 12_000_000_000),
            """<newznab:apilimits apicurrent="12" apimax="100" grabcurrent="1" grabmax="50"/>""") }
        var usage = ""
        val found = Newznab(recorder::get) { now }.search(indexer.copy(id = "movie"), PlaybackIdentity("tt15398776", "movie", "Oppenheimer"), 2023, "Prowlarr/9.9", "Newshosting") { usage = it }
        assertEquals(listOf("Oppenheimer.2023.2160p.UHD.BluRay.REMUX-GRP", "Oppenheimer 2023 1080p BluRay x264-GRP"), found.map { it.title })
        assertEquals("https://api.example.com/getnzb/abc.nzb&i=1&r=KEY123#pov-nzb", found[0].directUrl)
        assertTrue(UsenetLinks.isNzb(found[0].directUrl))
        assertEquals("https://api.example.com/getnzb/abc.nzb&i=1&r=KEY123", UsenetLinks.nzbAddress(found[0].directUrl))
        assertEquals(60_000_000_000, found[0].sizeBytes)
        assertEquals(2160, found[0].qualityHeight)
        assertTrue(found[0].usenet); assertEquals("Test Indexer", found[0].indexer); assertEquals("Newshosting", found[0].newsProvider)
        assertTrue(found[0].detail, found[0].detail.startsWith("Usenet · Test Indexer · ") && found[0].detail.endsWith("340 grabs"))
        assertEquals("API 12/100 · grabs 1/50", usage)
        val search = recorder.asked.last()
        assertEquals("https://api.example.com/api?t=movie&imdbid=15398776&cat=2000&extended=1&limit=100&apikey=KEY123", search.first)
        assertEquals("Prowlarr/9.9", search.second)
    }

    @Test fun `an episode keeps only its own releases and falls back to a name search`() {
        val recorder = Recorder { address -> when {
            "t=caps" in address -> """<caps><searching><tv-search available="yes" supportedParams="q,season,ep"/></searching></caps>"""
            else -> rss(item("The.Show.S02E05.1080p.WEB.h264-GRP", 3_000_000_000) + item("The.Show.S02.1080p.WEB.h264-GRP", 40_000_000_000) +
                item("Other.Show.S02E05.720p.WEB-GRP", 1_000_000_000) + item("The.Show.S02E06.1080p.WEB.h264-GRP", 3_000_000_000))
        } }
        val found = Newznab(recorder::get) { now }.search(indexer.copy(id = "tv"), PlaybackIdentity("tt1234567", "series", "The Show", 2, 5), null, "A/1")
        assertEquals(listOf("The.Show.S02E05.1080p.WEB.h264-GRP"), found.map { it.title })
        assertTrue(recorder.asked.last().first, "t=tvsearch&q=The%20Show&season=2&ep=5" in recorder.asked.last().first)
    }

    @Test fun `a name search for a movie keeps only that title and year`() {
        assertTrue(Newznab.named("Dune.2021.1080p.BluRay-GRP", PlaybackIdentity("tt1", "movie", "Dune"), 2021))
        assertFalse(Newznab.named("Planet.Dune.2021.1080p-GRP", PlaybackIdentity("tt1", "movie", "Dune"), 2021))
        assertFalse(Newznab.named("Dune.Part.Two.2024.1080p-GRP", PlaybackIdentity("tt1", "movie", "Dune"), 2021))
        assertFalse(Newznab.named("Dune.1984.1080p-GRP", PlaybackIdentity("tt1", "movie", "Dune"), 2021))
        assertTrue(Newznab.named("The.Show.2019.S01E02.720p-GRP", PlaybackIdentity("tt1", "series", "The Show", 1, 2), null))
    }

    @Test fun `an indexer's refusal is told in plain words`() {
        val client = Newznab({ _, _ -> """<?xml version="1.0"?><error code="100" description="Incorrect user credentials"/>""" })
        try { client.test(indexer, "A/1"); fail("expected a refusal") } catch (error: ApiException) { assertEquals("The indexer didn't accept this API key.", error.message) }
        assertEquals("Connected · movies by IMDb id · shows by IMDb id", Newznab({ _, _ -> caps }).test(indexer, "A/1"))
    }

    @Test fun `indexer addresses are tidied and refused when unusable`() {
        assertEquals("https://api.nzbgeek.info/api", NewznabIndexers.address(" https://api.nzbgeek.info/api/ "))
        assertEquals("https://example.com/api", NewznabIndexers.address("example.com"))
        assertEquals("http://192.168.1.10:5076/api", NewznabIndexers.address("http://192.168.1.10:5076/api"))
        for (bad in listOf("ftp://example.com/api", "https://user:pw@example.com/api", "https://example.com/api?apikey=1", "")) {
            try { NewznabIndexers.address(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
        // Every built-in address is one this app itself accepts.
        NewznabIndexers.PRESETS.forEach { assertEquals(it.name, it.url, NewznabIndexers.address(it.url)) }
    }

    @Test fun `news providers are saved in order with main ones asked before backups`() {
        val saved = HashMap<String, String>()
        val config = NewsProviders({ saved[it].orEmpty() }, { key, value -> saved[key] = value })
        assertTrue(config.load().isEmpty())
        val backup = NewsProviders.create("Easynews", "News.Easynews.com", 563, true, "u", "p w", 10, backup = true)
        val main = NewsProviders.create("Newshosting", "news.newshosting.com", 443, true, "u2", "p2", 20, backup = false)
        val off = NewsProviders.create("Plain", "news.example.com", 119, false, "u3", "p3", 5, backup = false).copy(enabled = false)
        config.save(listOf(backup, main, off))
        assertEquals(listOf(backup, main, off), config.load())
        assertEquals(listOf("Newshosting", "Easynews"), config.active().map { it.name })
        assertEquals("news.easynews.com", backup.host)
        for (bad in listOf({ NewsProviders.create("", "a.b", 563, true, "u", "p", 5, false) }, { NewsProviders.create("n", "bad host", 563, true, "u", "p", 5, false) },
            { NewsProviders.create("n", "a.b", 0, true, "u", "p", 5, false) }, { NewsProviders.create("n", "a.b", 563, true, "u", "p\nQUIT", 5, false) },
            { NewsProviders.create("n", "a.b", 563, true, "u", "p", 500, false) })) {
            try { bad(); fail("accepted a bad provider") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(NewsProviders.PRESETS.size, NewsProviders.PRESETS.map { it.host }.distinct().size)
    }

    @Test fun `headers move to a known tool's newest version and leave other text alone`() {
        val saved = HashMap<String, String>()
        val get: (String) -> String = { saved[it].orEmpty() }
        val put: (String, String) -> Unit = { key, value -> saved[key] = value }
        assertEquals(UsenetHeaders.DEFAULT_QUERY, UsenetHeaders.query(get))
        assertTrue(UsenetHeaders.due(get, now))
        val asked = ArrayList<String>()
        val changed = UsenetHeaders.refresh(get, put, now) { repo -> asked += repo; if (repo == "Prowlarr/Prowlarr") "v9.1.2.3456" else "9.0.1" }
        assertEquals(listOf("Prowlarr/Prowlarr", "sabnzbd/sabnzbd"), asked)
        assertEquals(listOf("Prowlarr/9.1.2.3456", "SABnzbd/9.0.1"), changed)
        assertEquals("Prowlarr/9.1.2.3456", UsenetHeaders.query(get)); assertEquals("SABnzbd/9.0.1", UsenetHeaders.grab(get))
        assertFalse(UsenetHeaders.due(get, now + 3_600_000)); assertTrue(UsenetHeaders.due(get, now + 25 * 3_600_000L))
        // A tool the viewer chose keeps its name; free text and odd version tags are not touched.
        saved[UsenetHeaders.QUERY] = "radarr/5.0.0"; saved[UsenetHeaders.GRAB] = "MyClient 1.0"
        assertEquals(listOf("Radarr/6.0.1.1"), UsenetHeaders.refresh(get, put, now) { repo -> assertEquals("Radarr/Radarr", repo); "v6.0.1.1" })
        assertEquals("MyClient 1.0", UsenetHeaders.grab(get))
        saved[UsenetHeaders.QUERY] = "Prowlarr/1.0"
        assertTrue(UsenetHeaders.refresh(get, put, now) { "testing" }.isEmpty())
        assertEquals("Prowlarr/1.0", UsenetHeaders.query(get))
        try { UsenetHeaders.refresh(get, put, now) { throw java.io.IOException("offline") }; fail("expected a failure") } catch (_: java.io.IOException) {}
        assertEquals("Linebreak removed", UsenetHeaders.clean("Line\nbreak removed"))
    }
}
