package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AioLibraryTest {
    private val base = "https://aio.example/stremio/u/c"
    private val catalog = "8761a2c.aiostreams::library.aiostreams"
    private val entryId = "aiostreams::library.aiostreams.usenet.615cb9"

    private fun api(calls: MutableList<String> = mutableListOf()) = AioLibrary { url ->
        calls += url
        when {
            url == "$base/manifest.json" -> """{"id":"com.aiostreams.viren070","catalogs":[{"type":"library","id":"$catalog","name":"AIOStreams"},{"type":"movie","id":"x"}]}"""
            "/catalog/library/" in url && "skip=100" in url -> """{"metas":[]}"""
            "/catalog/library/" in url -> """{"metas":[{"id":"$entryId","type":"library","name":"Animals.2026.2160p.DV.HDR.WEBRip.x265"}]}"""
            "/meta/library/" in url -> """{"meta":{"id":"$entryId","videos":[{"id":"$entryId:7","title":"Animals.mkv"}]}}"""
            "/stream/library/" in url && url.contains("%3A7") -> """{"streams":[{"name":"stats","streamData":{"type":"statistic"}},
                {"url":"https://aio.example/api/v1/debrid/playback/abc","streamData":{"type":"usenet"}}]}"""
            "/stream/library/" in url -> """{"streams":[{"name":"[x] Library AIO","description":"500 - Internal Server Error"}]}"""
            else -> error(url)
        }
    }

    @Test fun `libraries are found from manifests, listed, and played through the entry's video`() {
        val calls = mutableListOf<String>()
        val library = api(calls)
        val sources = library.sources(listOf(SourceProvider("1", "Meeky AIO", "$base/manifest.json")))
        assertEquals(listOf(AioLibrary.Source("Meeky AIO", base, catalog)), sources)
        val entries = library.entries(sources.single())
        assertEquals(listOf("Animals.2026.2160p.DV.HDR.WEBRip.x265"), entries.map { it.name })
        assertEquals("https://aio.example/api/v1/debrid/playback/abc", library.link(sources.single(), entries.single())?.url)
        assertEquals(0, library.entries(sources.single(), skip = 100).size)
    }

    @Test fun `providers without a library catalog, or switched off, add nothing`() {
        val none = AioLibrary { """{"catalogs":[{"type":"movie","id":"top"}]}""" }
        assertEquals(emptyList<AioLibrary.Source>(), none.sources(listOf(SourceProvider("2", "Torrentio", "https://torrentio.example"))))
        assertEquals(emptyList<AioLibrary.Source>(), api().sources(listOf(SourceProvider("1", "Off", "$base/manifest.json", enabled = false))))
        assertNull(AioLibrary { """{"streams":[]}""" }.link(AioLibrary.Source("x", base, catalog), AioLibrary.Entry("e", "n")))
    }
}
