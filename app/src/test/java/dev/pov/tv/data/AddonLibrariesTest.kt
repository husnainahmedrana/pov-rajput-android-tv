package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddonLibrariesTest {
    private val torbox = object : SourceTorBox {
        override fun cached(hashes: List<String>) = emptySet<String>()
        override fun addMagnet(hash: String) = error("unused")
        override fun folder(kind: String, id: Long) = error("unused")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long) = error("unused")
    }

    private fun item(release: String, status: String = "good", type: String = "movie", season: Int = 0, episode: Int = 0) = JSONObject()
        .put("id", release.hashCode().toString()).put("content_type", type).put("content_id", "tt33764258")
        .put("season", season).put("episode", episode).put("release_title", release).put("size_bytes", 28489102756L).put("status", status)

    @Test fun `StreamNZB library pages leave out bad releases and name episodes by season and episode`() {
        val api = StreamNzbLibrary(get = { url, headers ->
            assertEquals("Bearer t0k", headers["Authorization"]); assertTrue(url.startsWith("https://nzb.example/api/library?"))
            JSONObject().put("total", 3).put("items", JSONArray().put(item("Good.Movie.1080p")).put(item("Broken.Movie", "bad"))
                .put(item("Show.S01E02.1080p", type = "series", season = 1, episode = 2))).toString()
        })
        val page = api.page("https://nzb.example", "t0k")
        assertEquals(listOf("Good.Movie.1080p", "Show.S01E02.1080p"), page.items.map { it.release })
        assertEquals(3, page.total)
        assertEquals(listOf("tt33764258", "tt33764258:1:2"), page.items.map { it.streamId })
    }

    @Test fun `a StreamNZB library release plays the add-on link with the same release name`() {
        fun stream(file: String, url: String) = JSONObject().put("name", "StreamNZB").put("url", url)
            .put("behaviorHints", JSONObject().put("filename", file))
        val api = StreamNzbLibrary(get = { url, _ ->
            assertEquals("https://nzb.example/tok/stream/movie/tt33764258.json", url)
            JSONObject().put("streams", JSONArray().put(stream("The.Odyssey.2026.1080p.Remux.MULTi.x264-DKS", "https://nzb.example/a"))
                .put(stream("The Odyssey 2026 1080p BluRay DTS-HD MA 5.1 x264-H4XO", "https://nzb.example/b"))).toString()
        })
        val kept = StreamNzbLibrary.item(item("The.Odyssey.2026.1080p.BluRay.DTS-HD.MA.5.1.x264-H4XO"))!!
        assertEquals("https://nzb.example/b", api.link("https://nzb.example/tok", kept)?.url)
        assertNull(api.link("https://nzb.example/tok", StreamNzbLibrary.item(item("Some.Other.Release"))!!))
    }

    @Test fun `StreamNZB sign-in returns the token and explains a refusal`() {
        val ok = StreamNzbLibrary(post = { _, body -> assertEquals("admin", body.getString("username")); """{"success":true,"token":"abc"}""" })
        assertEquals("abc", ok.login("https://nzb.example", "admin", "pw"))
        val refused = StreamNzbLibrary(post = { _, _ -> """{"success":false,"error":"Invalid credentials"}""" })
        assertEquals("Invalid credentials", assertThrows(ApiException::class.java) { refused.login("https://nzb.example", "admin", "x") }.message)
        assertEquals("https://nzb.example", StreamNzbLibrary.origin("https://nzb.example/tok"))
        assertNull(StreamNzbLibrary.origin("http://nzb.example/tok"))
    }

    @Test fun `AltMount's deep WebDAV listing gives video files newest first and skips folders, tmp and other hosts`() {
        fun file(path: String, size: Long, date: String) = "<D:response><D:href>$path</D:href><D:propstat><D:prop><D:resourcetype/>" +
            "<D:getcontentlength>$size</D:getcontentlength><D:getlastmodified>$date</D:getlastmodified></D:prop></D:propstat></D:response>"
        val xml = "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">" +
            "<D:response><D:href>/webdav/Movies/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>" +
            file("/webdav/Movies/Verity.2026.2160p/Verity.2026.2160p.AMZN.WEB-DL.mkv", 17399000000, "Fri, 06 Nov 2026 19:54:01 GMT") +
            file("/webdav/Movies/The.Odyssey.2026/The%20Odyssey%202026%201080p.mkv", 21609000000, "Sat, 07 Nov 2026 18:09:41 GMT") +
            file("/webdav/Movies/The.Odyssey.2026/sample.nfo", 10, "Sat, 07 Nov 2026 18:09:41 GMT") +
            file("/webdav/tmp/partial.mkv", 5, "Sat, 07 Nov 2026 20:00:00 GMT") +
            file("https://elsewhere.example/evil.mkv", 5, "Sat, 07 Nov 2026 20:00:00 GMT") +
            "</D:multistatus>"
        val files = AltMountLibrary.parse(xml)
        assertEquals(listOf("The Odyssey 2026 1080p", "Verity.2026.2160p.AMZN.WEB-DL"), files.map { it.name })
        assertEquals("/webdav/Movies/The.Odyssey.2026/The%20Odyssey%202026%201080p.mkv", files.first().path)
        assertEquals("Movies", files.first().folder)
        assertEquals(21609000000, files.first().sizeBytes)
    }

    @Test fun `library is a movable sort row, on top by default and for orders saved before it existed`() {
        assertEquals(listOf("library", "language", "quality", "size"), SourceSortOrder.priority(null))
        assertEquals(listOf("library", "quality", "language", "size"), SourceSortOrder.priority("quality,language,size"))
        assertEquals(listOf("quality", "library", "language", "size"), SourceSortOrder.priority("quality,library,language,size"))
        val small = SourceItem("Small.720p", "p", qualityHeight = 720)
        val kept = SourceItem("Kept.1080p", "p", qualityHeight = 1080, inLibrary = true)
        val best = SourceItem("Best.2160p", "p", qualityHeight = 2160)
        val items = listOf(small, kept, best)
        assertEquals(listOf(kept, best, small), SourceSortOrder().sorted(items))
        assertEquals(listOf(best, kept, small), SourceSortOrder(priority = listOf("quality", "library", "language", "size")).sorted(items))
        assertEquals(listOf(best, kept, small), SourceSortOrder(libraryFirst = false).sorted(items))
    }

    @Test fun `StreamNZB and AltMount library links are marked as in the library`() {
        // Real shapes: StreamNZB names a kept release's indexer "StreamNZB Library - Crawler"; AltMount labels a held file "Local Library".
        val kept = JSONObject().put("name", "StreamNZB\nHunny")
            .put("description", "The Odyssey\nThe.Odyssey.2026.1080p.BluRay.DTS-HD.MA.5.1.x264-H4XO\n🇬🇧\n🔍 StreamNZB Library - Crawler")
            .put("url", "https://nzb.example/token/play/a")
            .put("behaviorHints", JSONObject().put("bingeGroup", "streamnzb-1080p").put("filename", "The.Odyssey.2026.1080p.BluRay.DTS-HD.MA.5.1.x264-H4XO"))
        val fresh = JSONObject().put("name", "StreamNZB\nHunny")
            .put("description", "The Odyssey\nThe.Odyssey.2026.1080p.Remux.AVC-OTHER\n🔍 Crawler").put("url", "https://nzb.example/token/play/b")
            .put("behaviorHints", JSONObject().put("bingeGroup", "streamnzb-1080p").put("filename", "The.Odyssey.2026.1080p.Remux.AVC-OTHER"))
        val nzb = SourceResolver({ "" }, torbox, publicJson = { JSONObject().put("streams", JSONArray().put(kept).put(fresh)) },
            configuredProviders = { listOf(SourceProvider("p", "Hunny nzb", "https://nzb.example/token")) })
            .sources(PlaybackIdentity("tt33764258", "movie", "The Odyssey"))
        assertEquals(listOf(true, false), nzb.sortedBy { it.directUrl }.map { it.inLibrary })
        assertEquals("Crawler", nzb.first { it.inLibrary }.indexer)

        val held = JSONObject().put("name", "AltMount - The.Odyssey.2026.1080p.BluRay.DDP5.1.x264-ZoroSenpai.mkv")
            .put("title", "The.Odyssey.2026.1080p.BluRay.DDP5.1.x264-ZoroSenpai.mkv\n💾 Local Library • ⚡ Instant 0s Playback")
            .put("url", "https://alt.example/stremio/token/play?path=x")
        val alt = SourceResolver({ "" }, torbox, publicJson = { JSONObject().put("streams", JSONArray().put(held)) },
            configuredProviders = { listOf(SourceProvider("p", "Meeky usenet", "https://alt.example/stremio/token")) })
            .sources(PlaybackIdentity("tt33764258", "movie", "The Odyssey")).single()
        assertEquals(AddonBadges.ALTMOUNT, alt.addon)
        assertTrue(alt.inLibrary)
    }
}
