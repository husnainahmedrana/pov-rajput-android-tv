package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddonSubtitlesTest {
    private val episode = PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2)
    private val cloud = object : SourceTorBox {
        override fun cached(hashes: List<String>): Set<String> = emptySet()
        override fun addMagnet(hash: String): Long = error("unused")
        override fun folder(kind: String, id: Long): CloudFolder = error("unused")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = error("unused")
    }

    @Before fun clear() = AddonManifests.clearForTest()

    @Test fun `manifest resources are read as strings or objects with their own types`() {
        val info = AddonManifests.parse(JSONObject("""{"types":["movie","series"],"idPrefixes":["tt"],
            "resources":["stream",{"name":"subtitles","types":["movie"],"idPrefixes":["tt"]}]}"""))
        assertTrue(info.offers("stream", "series", "tt1"))
        assertTrue(info.offers("subtitles", "movie", "tt1"))
        assertFalse(info.offers("subtitles", "series", "tt1"))
        assertFalse(info.offers("subtitles", "movie", "kitsu:1"))
        assertFalse(info.offers("catalog"))
    }

    @Test fun `installed subtitle add-ons are asked and keep their own best two`() {
        val asked = mutableListOf<String>()
        val finder = ExternalSubtitles { url ->
            asked += url
            when {
                url == "https://subs.example/cfg/manifest.json" -> """{"resources":["subtitles"],"types":["movie","series"]}"""
                url == "https://streams.example/manifest.json" -> """{"resources":["stream"],"types":["movie","series"]}"""
                url.startsWith("https://subs.example/cfg/subtitles/series/tt0903747:1:2.json") -> """{"subtitles":[
                    {"url":"https://subs.example/a.srt","lang":"ara"},
                    {"url":"https://subs.example/b.vtt","lang":"ar"},
                    {"url":"https://subs.example/c.srt","lang":"ara"},
                    {"url":"https://subs.example/d.srt","lang":"eng"},
                    {"url":"http://8.8.8.8/e.srt","lang":"ara"}]}"""
                "opensubtitles" in url -> """{"subtitles":[]}"""
                else -> error("unexpected $url")
            }
        }
        val found = finder.find(episode, "ar", "Breaking.Bad.S01E02.mkv",
            addons = listOf(SubtitleAddon("My Subs", "https://subs.example/cfg"), SubtitleAddon("Streams", "https://streams.example")))
        assertEquals(2, found.size)
        assertTrue(found.all { it.label == "Arabic · My Subs" })
        assertEquals(ExternalSubtitles.VTT, found.first { it.url.endsWith(".vtt") }.mime)
        assertTrue(asked.none { it.startsWith("https://streams.example/subtitles") })
        // The manifest is read once and remembered.
        finder.find(episode, "ar", "x.mkv", addons = listOf(SubtitleAddon("My Subs", "https://subs.example/cfg")))
        assertEquals(1, asked.count { it == "https://subs.example/cfg/manifest.json" })
    }

    @Test fun `subtitles sent with the stream come first and work without an IMDb id`() {
        val attached = listOf(StreamSubtitle("https://cdn.example/x.ass", "eng", "Alpha"), StreamSubtitle("https://cdn.example/y.srt", "fre", "Alpha"),
            StreamSubtitle("http://192.168.1.5:8080/z.srt", "en", "Alpha"))
        val finder = ExternalSubtitles { """{"subtitles":[{"url":"https://os.example/1","lang":"eng"}]}""" }
        val found = finder.find(episode, "en", "file.mkv", attached = attached)
        assertEquals(listOf("https://cdn.example/x.ass", "http://192.168.1.5:8080/z.srt", "https://os.example/1"), found.map { it.url })
        assertEquals(ExternalSubtitles.SSA, found.first().mime)
        assertEquals("English · Alpha", found.first().label)
        val noImdb = ExternalSubtitles { error("no online search without an IMDb id") }
            .find(PlaybackIdentity("kitsu:1", "movie", "X"), "en", "f.mkv", attached = attached)
        assertEquals(2, noImdb.size)
        // Built-in searches off: only add-ons and the stream's own.
        val extras = ExternalSubtitles { error("no online search") }.find(episode, "en", "file.mkv", attached = attached, builtIn = false)
        assertEquals(2, extras.size)
    }

    @Test fun `stream subtitles are kept on links and a subtitles-only add-on is not unavailable`() {
        val providers = listOf(SourceProvider("a", "Alpha", "https://alpha.example"), SourceProvider("s", "Subs", "https://subs.example"))
        val resolver = SourceResolver({ error("legacy") }, cloud, { url ->
            when {
                url.startsWith("https://alpha.example/stream/") -> JSONObject().put("streams", JSONArray().put(JSONObject()
                    .put("url", "https://video.example/a.mkv").put("title", "Example")
                    .put("subtitles", JSONArray().put(JSONObject().put("id", "1").put("url", "https://cdn.example/a.srt").put("lang", "eng")))))
                url == "https://subs.example/manifest.json" -> JSONObject("""{"resources":["subtitles"],"types":["movie"]}""")
                else -> throw java.io.IOException("404")
            }
        }, serviceOrder = { emptyList() }, configuredProviders = { providers })
        val result = resolver.search(PlaybackIdentity("tt1234567", "movie", "Example"))
        assertEquals(emptyList<String>(), result.unavailableProviders)
        assertEquals(listOf(StreamSubtitle("https://cdn.example/a.srt", "eng", "Alpha")), result.items.single().subtitles)
        assertEquals(result.items.single().subtitles, StreamSubtitle.fromJson(StreamSubtitle.toJson(result.items.single().subtitles)))
    }
}
