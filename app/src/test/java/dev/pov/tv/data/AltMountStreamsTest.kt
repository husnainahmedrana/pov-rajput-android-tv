package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AltMountStreamsTest {
    private val torbox = object : SourceTorBox {
        override fun cached(hashes: List<String>) = emptySet<String>()
        override fun addMagnet(hash: String) = error("unused")
        override fun folder(kind: String, id: Long) = error("unused")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long) = error("unused")
    }

    @Test fun `AltMount add-on links are known by their name and carry the indexer and news provider`() {
        // The shape of a real AltMount Usenet add-on stream (community.altmount 1.1.0), whatever the viewer named the add-on.
        val stream = JSONObject().put("name", "AltMount 4K - Inception (2010) [2160p]")
            .put("title", "Inception.2010.HYBRID.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.DTS-HD.MA.5.1-FraMeSToR\n💾 80.90 GB 🌐 Geek")
            .put("url", "https://alt.example/stremio/token/play?release=abc&type=movie&indexer=Geek&source=newsnab&id=tt1375666")
        val resolver = SourceResolver({ "" }, torbox, publicJson = { JSONObject().put("streams", JSONArray().put(stream)) },
            configuredProviders = { listOf(SourceProvider("p", "Meeky usenet", "https://alt.example/stremio/token", newsProvider = "easynews")) })
        val link = resolver.sources(PlaybackIdentity("tt1375666", "movie", "Inception")).single()
        assertEquals(AddonBadges.ALTMOUNT, link.addon)
        assertTrue(link.usenet)
        assertEquals("Geek", link.indexer)
        assertEquals("easynews", link.newsProvider)
    }

    @Test fun `StreamNZB add-on links are known by their name and carry the indexer and news provider`() {
        // The shape of a real StreamNZB stream (community.streamnzb 6.4.0).
        val stream = JSONObject().put("name", "StreamNZB\nHunny")
            .put("description", "Inception\nInception.2010.1080p.MGMP.WEB-DL.DDP.5.1.H.264-PiRaTeS\n🇬🇧\n🔍 Crawler")
            .put("url", "https://nzb.example/token/play/stream:Hunny:movie:tt1375666:3")
            .put("behaviorHints", JSONObject().put("bingeGroup", "streamnzb-1080p").put("filename", "Inception.2010.1080p.MGMP.WEB-DL.DDP.5.1.H.264-PiRaTeS")
                .put("videoSize", 6579328554L).put("cached", false))
        val resolver = SourceResolver({ "" }, torbox, publicJson = { JSONObject().put("streams", JSONArray().put(stream)) },
            configuredProviders = { listOf(SourceProvider("p", "Hunny nzb", "https://nzb.example/token", newsProvider = "easynews")) })
        val link = resolver.sources(PlaybackIdentity("tt1375666", "movie", "Inception")).single()
        assertEquals(AddonBadges.STREAMNZB, link.addon)
        assertTrue(link.usenet)
        assertEquals("Crawler", link.indexer)
        assertEquals(listOf("streamnzb", "easynews", "usenetcrawler"), dev.pov.tv.SourceBadges.cachedOn(link))
    }
}
