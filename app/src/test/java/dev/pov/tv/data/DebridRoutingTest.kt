package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebridRoutingTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)
    private val d = "d".repeat(40)
    private val identity = PlaybackIdentity("tt1234567", "movie", "Movie")
    private fun source(hash: String = a) = SourceItem("Movie.mkv", "fixture", hash)

    private inner class Harness {
        var order = listOf("offcloud", "torbox")
        var offcloudHits = setOf(a, b)
        var torboxHits = setOf(a, c)
        var offcloudCacheFails = false
        var torboxCacheFails = false
        var offcloudResolveFails = false
        var torboxResolveFails = false
        var offcloudCreates = 0
        var torboxCreates = 0
        var offcloudPrepares = 0
        var torboxPrepares = 0
        val offcloudChecks = java.util.concurrent.atomic.AtomicInteger()
        val torboxChecks = java.util.concurrent.atomic.AtomicInteger()
        var transfers = JSONObject()
        val offcloud = OffcloudApi(object : OffcloudAccount {
            override fun key() = "fixture-offcloud-key"
            override fun transfers() = transfers
            override fun save(key: String, transfers: JSONObject) { this@Harness.transfers = transfers }
        }, { path, _, body, _ -> when (path) {
            "cache/info" -> {
                offcloudChecks.incrementAndGet()
                if (offcloudCacheFails) throw ApiException(503, "Offcloud unavailable.")
                val urls = body!!.getJSONArray("urls")
                JSONArray().apply { repeat(urls.length()) { put(JSONObject().put("cached", urls.getString(it).substringAfterLast(':') in offcloudHits)) } }.toString()
            }
            "cache/download" -> {
                offcloudPrepares++
                if (offcloudResolveFails) throw ApiException(503, "Offcloud unavailable.")
                """["https://offcloud.example/Movie.mkv"]"""
            }
            "cloud" -> { offcloudCreates++; """{"requestId":"download1"}""" }
            "cloud/status" -> """{"status":"downloaded"}"""
            "cloud/explore/download1" -> """["https://offcloud.example/Movie.mkv"]"""
            else -> error("Unexpected Offcloud request")
        } })
        val torbox = object : SourceTorBox {
            override fun cached(hashes: List<String>): Set<String> {
                torboxChecks.incrementAndGet()
                if (torboxCacheFails) throw ApiException(503, "TorBox unavailable.")
                return hashes.filter { it in torboxHits }.toSet()
            }
            override fun addMagnet(hash: String): Long { torboxCreates++; return 77 }
            override fun folder(kind: String, id: Long) = CloudFolder(77, "torrents", "Movie", true, listOf(CloudFile(42, "Movie.mkv", 1000)))
            override fun streamUrl(kind: String, folderId: Long, fileId: Long) = "https://torbox.example/Movie.mkv"
            override fun prepareCached(source: SourceItem, identity: PlaybackIdentity): PreparedSource.Files {
                torboxPrepares++
                assertTrue(source.cached)
                if (torboxResolveFails) throw ApiException(503, "TorBox unavailable.")
                val folder = folder("torrents", 77)
                return PreparedSource.Files(folder, folder.files, identity, 42,
                    mapOf(42L to "https://torbox.example/Movie.mkv"), "fixture-tag", source.infoHash)
            }
        }
        val resolver = SourceResolver({ "https://sources.example" }, torbox,
            publicJson = { JSONObject().put("streams", JSONArray().apply {
                listOf(a, b, c, d, a).forEach { put(JSONObject().put("infoHash", it).put("title", "Movie.mkv")) }
            }) }, offcloud = { offcloud }, serviceOrder = { order })
    }

    @Test fun `combined source list includes both unique caches and merges shared cached copies`() {
        val h = Harness(); val sources = h.resolver.sources(identity)
        assertEquals(4, sources.size)
        assertEquals(listOf("offcloud", "torbox"), sources.single { it.infoHash == a }.cachedServices)
        assertEquals(listOf("offcloud"), sources.single { it.infoHash == b }.cachedServices)
        assertEquals(listOf("torbox"), sources.single { it.infoHash == c }.cachedServices)
        assertFalse(sources.single { it.infoHash == d }.cached)
        assertEquals(d, sources.last().infoHash)
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `shared cached source uses Offcloud when it is first`() {
        val h = Harness()
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.OffcloudFiles)
        assertEquals(1, h.offcloudPrepares); assertEquals(0, h.torboxPrepares)
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `a service picked by holding OK plays the link there instead of the preferred one`() {
        val h = Harness()
        assertTrue(h.resolver.prepare(source(), identity, only = "torbox") is PreparedSource.Files)
        assertEquals(0, h.offcloudPrepares); assertEquals(1, h.torboxPrepares)
        // Not cached on the picked service: a clear error, never a download.
        val error = assertThrows(IllegalStateException::class.java) { h.resolver.prepare(source(b), identity, only = "torbox") }
        assertTrue(error.message!!.contains("isn't cached on TorBox"))
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `shared cached source uses TorBox when it is first`() {
        val h = Harness(); h.order = listOf("torbox", "offcloud")
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.Files)
        assertEquals(1, h.torboxPrepares); assertEquals(0, h.offcloudPrepares)
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `only Offcloud cached uses Offcloud despite TorBox first`() {
        val h = Harness(); h.order = listOf("torbox", "offcloud")
        assertTrue(h.resolver.prepare(source(b), identity) is PreparedSource.OffcloudFiles)
        assertEquals(0, h.torboxCreates + h.torboxPrepares)
    }

    @Test fun `only TorBox cached uses TorBox despite Offcloud first`() {
        val h = Harness()
        assertTrue(h.resolver.prepare(source(c), identity) is PreparedSource.Files)
        assertEquals(0, h.offcloudCreates + h.offcloudPrepares)
    }

    @Test fun `priority changes are applied when resolving previously listed sources`() {
        val h = Harness(); val shared = h.resolver.sources(identity).single { it.infoHash == a }
        h.order = listOf("torbox", "offcloud")
        assertTrue(h.resolver.prepare(shared, identity) is PreparedSource.Files)
    }

    @Test fun `failed cache service does not hide playable results from the other service`() {
        val h = Harness(); h.offcloudCacheFails = true
        val sources = h.resolver.sources(identity)
        assertEquals(listOf("torbox"), sources.single { it.infoHash == a }.cachedServices)
        assertTrue(sources.single { it.infoHash == a }.detail.contains("Offcloud cache unavailable"))
        assertTrue(h.resolver.prepare(source(a), identity) is PreparedSource.Files)
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `preferred resolution failure tries the other confirmed cached service`() {
        val h = Harness(); h.offcloudResolveFails = true
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.Files)
        assertEquals(1, h.offcloudPrepares); assertEquals(1, h.torboxPrepares)
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `reverse priority also falls back only to a confirmed cached service`() {
        val h = Harness(); h.order = listOf("torbox", "offcloud"); h.torboxResolveFails = true
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.OffcloudFiles)
        assertEquals(1, h.offcloudPrepares); assertEquals(1, h.torboxPrepares)
    }

    @Test fun `cached failures never fall back to adding an uncached transfer`() {
        val h = Harness(); h.offcloudResolveFails = true
        assertThrows(ApiException::class.java) { h.resolver.prepare(source(b).copy(cached = true), identity) }
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `stale cache state on both services cannot create a download`() {
        val h = Harness(); h.offcloudHits = emptySet(); h.torboxHits = emptySet()
        assertThrows(IllegalStateException::class.java) { h.resolver.prepare(source().copy(cached = true), identity) }
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `uncertain cache state cannot silently create an uncached download`() {
        val h = Harness(); h.offcloudCacheFails = true; h.torboxHits = emptySet()
        assertThrows(IllegalStateException::class.java) { h.resolver.prepare(source(d), identity) }
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
    }

    @Test fun `confirmed uncached download uses first priority service`() {
        val h = Harness()
        assertTrue(h.resolver.prepare(source(d), identity) is PreparedSource.OffcloudFiles)
        assertEquals(1, h.offcloudCreates); assertEquals(0, h.torboxCreates)
    }

    @Test fun `confirmation remains bound to the chosen download account after priority change`() {
        val h = Harness(); h.order = listOf("torbox", "offcloud")
        h.resolver.prepare(source(d).copy(downloadService = "offcloud"), identity)
        assertEquals(1, h.offcloudCreates); assertEquals(0, h.torboxCreates)
    }

    @Test fun `disconnected download account is not silently replaced by another account`() {
        val h = Harness(); h.order = listOf("torbox")
        assertThrows(IllegalStateException::class.java) { h.resolver.prepare(source(d).copy(downloadService = "offcloud"), identity) }
        assertEquals(0, h.offcloudCreates + h.torboxCreates)
        assertEquals(0, h.offcloudChecks.get())
    }

    @Test fun `single connected service works without checking the disconnected account`() {
        val h = Harness(); h.order = listOf("offcloud")
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.OffcloudFiles)
        assertEquals(0, h.torboxChecks.get())
        h.order = listOf("torbox")
        val checks = h.offcloudChecks.get()
        assertTrue(h.resolver.prepare(source(), identity) is PreparedSource.Files)
        assertEquals(checks, h.offcloudChecks.get())
    }

    @Test fun `direct URL sources do not contact either debrid service`() {
        val h = Harness()
        assertTrue(h.resolver.prepare(source().copy(directUrl = "https://media.example/Movie.mkv"), identity) is PreparedSource.Direct)
        assertEquals(0, h.offcloudChecks.get() + h.torboxChecks.get())
    }
}
