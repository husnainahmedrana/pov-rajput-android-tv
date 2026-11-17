package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TorznabTest {
    private val dark = PlaybackIdentity("tt5753856", "series", "Dark", 1, 2)
    private val matrix = PlaybackIdentity("tt0133093", "movie", "The Matrix")

    @Test fun `hash comes from the infohash attribute or any magnet`() {
        val feed = """<rss><channel>
            <item><title>Dark.S01E02.1080p</title><torznab:attr name="infohash" value="2E6824DFF7F62B692323AF09383054FC09DB129D"/>
              <torznab:attr name="seeders" value="12"/><torznab:attr name="size" value="1000"/></item>
            <item><title>Dark.S01E02.720p</title><link>magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&amp;dn=x</link></item>
            <item><title>Dark.S01E02.480p</title><torznab:attr name="magneturl" value="magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U&amp;tr=x"/></item>
            <item><title>Torrent file only</title><link>https://tracker.example/download/1.torrent</link></item>
        </channel></rss>"""
        val releases = Torznab.parse(feed)
        assertEquals(3, releases.size)
        assertEquals("2e6824dff7f62b692323af09383054fc09db129d", releases[0].hash)
        assertEquals(12, releases[0].seeders); assertEquals(1000L, releases[0].size)
        assertEquals("a".repeat(40), releases[1].hash)
        assertEquals(40, releases[2].hash.length)
    }

    @Test fun `base32 magnets convert to hex`() {
        // "abcdefghijklmnopqrst" in base32 is MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U.
        assertEquals("6162636465666768696a6b6c6d6e6f7071727374", Torznab.hashOf("magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"))
        assertNull(Torznab.hashOf("magnet:?xt=urn:sha1:abc"))
    }

    @Test fun `episodes keep this show's episode and its season packs`() {
        assertTrue(Torznab.fits("Dark.S01E02.1080p.WEB", dark, null))
        assertTrue(Torznab.fits("Dark.S01.E02.Lügen.2160p", dark, null))
        assertTrue(Torznab.fits("Dark.S01.GERMAN.COMPLETE.720p", dark, null))
        assertTrue(Torznab.fits("Dark (Season 1) WEB-DLRip", dark, null))
        assertTrue(Torznab.fits("Dark S01-S03 Complete 1080p", dark, null))
        assertTrue(Torznab.fits("Dark Complete Series 1080p", dark, null))
        assertTrue(Torznab.fits("Dark 2017 S01E02 720p", dark, null))
    }

    @Test fun `episodes drop other shows, episodes and seasons`() {
        assertFalse(Torznab.fits("It Welcome to Derry S01E02 The Thing in the Dark 2160p", dark, null))
        assertFalse(Torznab.fits("Home.Before.Dark.S01E02.2160p", dark, null))
        assertFalse(Torznab.fits("The.Dark.S01E02.1080p", dark, null))
        assertFalse(Torznab.fits("Dark.Angel.S01E02.1080p", dark, null))
        assertFalse(Torznab.fits("Dark.S01E03.1080p", dark, null))
        assertFalse(Torznab.fits("Dark.S02.1080p.WEB", dark, null))
        assertFalse(Torznab.fits("Dark 2017 1080p", dark, null))
    }

    @Test fun `movies match by name and year`() {
        assertTrue(Torznab.fits("The.Matrix.1999.1080p.BluRay", matrix, 1999))
        assertFalse(Torznab.fits("The.Matrix.Reloaded.2003.1080p", matrix, 1999))
    }

    @Test fun `seasons named by a pack`() {
        assertEquals(setOf(1), Torznab.seasonsOf("Dark.S01.1080p"))
        assertEquals(setOf(1, 2, 3), Torznab.seasonsOf("Dark S01-S03 1080p"))
        assertEquals(setOf(2), Torznab.seasonsOf("Dark Season 2 720p"))
        assertEquals(emptySet<Int>(), Torznab.seasonsOf("Dark.S01E02.1080p"))
    }

    @Test fun `prowlarr keeps switched-on torrent indexers`() {
        val list = """[{"id":1,"name":"1337x","protocol":"torrent","enable":true},
            {"id":2,"name":"NZBgeek","protocol":"usenet","enable":true},
            {"id":3,"name":"EZTV","protocol":"torrent","enable":false}]"""
        assertEquals(listOf(1 to "1337x"), Torznab.prowlarrList(list))
    }

    @Test fun `addresses for each kind`() {
        assertEquals("http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab/api", TorznabIndexers.jackettAddress("http://192.168.1.10:9117/"))
        assertEquals("http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab/api", TorznabIndexers.jackettAddress("http://192.168.1.10:9117/UI/Dashboard"))
        assertEquals("https://j.example.com/api/v2.0/indexers/yts/results/torznab", TorznabIndexers.jackettAddress("https://j.example.com/api/v2.0/indexers/yts/results/torznab/"))
        assertEquals("https://p.example.com", TorznabIndexers.serverAddress("https://p.example.com/indexers"))
        assertEquals("https://comet.example/torznab/api", TorznabIndexers.address("comet.example/torznab/api"))
        assertEquals("https://x.example/api" to "abc", TorznabIndexers.splitKey("https://x.example/api?apikey=abc&t=search"))
    }

    @Test fun `links carry Jackett, Prowlarr or the add-on's logo`() {
        assertEquals(AddonBadges.JACKETT, Torznab.logoOf(TorznabIndexers.create("Jackett", "http://10.0.0.2:9117", "k", TorznabIndexers.JACKETT)))
        assertEquals(AddonBadges.PROWLARR_TORZNAB, Torznab.logoOf(TorznabIndexer("1", "1337x", "http://10.0.0.2:9696/1/api", "k", kind = TorznabIndexers.PROWLARR)))
        assertEquals(AddonBadges.COMET, Torznab.logoOf(TorznabIndexers.create("Comet", "https://comet.feels.legal/torznab/api", "")))
        assertEquals(AddonBadges.STREMTHRU, Torznab.logoOf(TorznabIndexers.create("ST", "https://stremthrufortheweebs.midnightignite.me/v0/torznab/api", "")))
        assertNull(Torznab.logoOf(TorznabIndexers.create("Other", "https://indexer.example/api", "")))
    }

    @Test fun `torznab addresses are told apart from add-ons`() {
        assertTrue(TorznabIndexers.looksLikeTorznab("https://comet.feels.legal/torznab/api"))
        assertTrue(TorznabIndexers.looksLikeTorznab("https://stremthru.example/v0/torznab/api?apikey=1"))
        assertFalse(TorznabIndexers.looksLikeTorznab("https://torrentio.strem.fun/manifest.json"))
        assertFalse(TorznabIndexers.looksLikeTorznab("https://stremthru.example/stremio/torz/abc/manifest.json"))
    }

    @Test fun `saved indexers round-trip and duplicates are skipped`() {
        val map = HashMap<String, String>()
        val config = TorznabIndexers({ map[it].orEmpty() }, { k, v -> map[k] = v })
        val one = TorznabIndexers.create("Comet", "https://comet.example/torznab/api", "")
        assertEquals(1, config.add(listOf(one)))
        assertEquals(0, config.add(listOf(one.copy(id = "other"))))
        assertEquals(listOf(one), config.load())
    }
}
