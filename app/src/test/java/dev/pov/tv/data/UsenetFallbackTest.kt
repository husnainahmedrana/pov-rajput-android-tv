package dev.pov.tv.data

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsenetFallbackTest {
    private val torbox = object : SourceTorBox {
        override fun cached(hashes: List<String>) = emptySet<String>()
        override fun addMagnet(hash: String) = error("unused")
        override fun folder(kind: String, id: Long) = error("unused")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long) = error("unused")
    }
    private val identity = PlaybackIdentity("tt1375666", "movie", "Inception")
    private val nzb = SourceItem("Inception.2010.1080p.nzb", "Geek", directUrl = "https://geek.example/getnzb/1")

    private fun resolver(addonStreams: JSONArray, fallback: Boolean, asked: AtomicInteger) =
        SourceResolver({ "" }, torbox, publicJson = { JSONObject().put("streams", addonStreams) },
            configuredProviders = { listOf(SourceProvider("p", "Add-on", "https://addon.example/manifest")) },
            indexers = { listOf(SourceResolver.IndexerSearch("indexer:geek", "Geek", fallback) { asked.incrementAndGet(); listOf(nzb) }) })

    private val oneLink = JSONArray().put(JSONObject().put("name", "Add-on 1080p").put("title", "Inception.2010.1080p.WEB").put("url", "https://cdn.example/a.mkv"))

    @Test fun `fallback indexers are not asked when an add-on found links`() {
        val asked = AtomicInteger()
        val links = resolver(oneLink, fallback = true, asked).sources(identity)
        assertEquals(0, asked.get())
        assertEquals(listOf("Inception.2010.1080p.WEB"), links.map { it.title.lines().first() })
    }

    @Test fun `fallback indexers are asked when nothing else found a link`() {
        val asked = AtomicInteger()
        val links = resolver(JSONArray(), fallback = true, asked).sources(identity)
        assertEquals(1, asked.get())
        assertEquals(listOf("Inception.2010.1080p.nzb"), links.map { it.title })
    }

    @Test fun `with the switch off indexers are searched alongside the add-ons`() {
        val asked = AtomicInteger()
        val links = resolver(oneLink, fallback = false, asked).sources(identity)
        assertEquals(1, asked.get())
        assertEquals(2, links.size)
    }
}
