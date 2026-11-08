package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceProvidersTest {
    private val identity = PlaybackIdentity("tt1234567", "movie", "Example")
    private fun config(values: MutableMap<String, String>) = SourceProviders({ values[it].orEmpty() }, { values.putAll(it) })
    private val cloud = object : SourceTorBox {
        override fun cached(hashes: List<String>): Set<String> = error("Unexpected cache request")
        override fun addMagnet(hash: String): Long = error("Unexpected transfer")
        override fun folder(kind: String, id: Long): CloudFolder = error("Unexpected transfer")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = error("Unexpected transfer")
    }
    private fun resolver(providers: List<SourceProvider>, fetch: (String) -> JSONObject) = SourceResolver(
        { error("Legacy endpoint must not be used") }, cloud, fetch, serviceOrder = { emptyList() }, configuredProviders = { providers })
    private fun payload(vararg streams: JSONObject) = JSONObject().put("streams", JSONArray(streams.toList()))
    private fun torrent(hash: String = "a".repeat(40), index: Int? = 0) = JSONObject().put("infoHash", hash)
        .put("fileIdx", index ?: JSONObject.NULL).put("title", "Example.2026.1080p.mkv")
    private fun direct(url: String) = JSONObject().put("url", url).put("title", "Example")
    private val alpha = SourceProvider("a", "Alpha", "https://alpha.example/configured-private-path/manifest.json")
    private val beta = SourceProvider("b", "Beta", "https://beta.example")

    @Test fun `empty installation has no preset providers`() {
        assertTrue(config(mutableMapOf()).load().isEmpty())
    }
    @Test fun `legacy address is preserved and last removal does not resurrect it`() {
        val values = mutableMapOf("sources.endpoint" to "https://legacy.example/private")
        val config = config(values); val migrated = config.load().single()
        assertEquals("Source 1", migrated.name); assertEquals(values["sources.endpoint"], migrated.address)
        config.save(listOf(migrated.copy(name = "My provider", enabled = false)))
        assertEquals("", values["sources.endpoint"]); assertFalse(config.load().single().enabled)
        assertEquals("My provider", config.load().single().name)
        config.save(emptyList()); assertTrue(config.load().isEmpty())
    }
    @Test fun `manifest base normalization and private configuration survive persistence`() {
        val config = config(mutableMapOf()); config.save(listOf(alpha))
        assertEquals("https://alpha.example/configured-private-path", config.load().single().address)
    }
    @Test fun `invalid addresses names duplicate endpoints and limits are rejected before saving`() {
        val values = mutableMapOf<String, String>(); val config = config(values)
        listOf("http://example.com", "https://user:password@example.com", "https://example.com?a=b", "https://example.com/#x", "broken").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { config.save(listOf(alpha.copy(address = address))) }
        }
        listOf("", "https://example.com", "a".repeat(51), "Name\nSecond line").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { config.save(listOf(alpha.copy(name = name))) }
        }
        assertThrows(IllegalArgumentException::class.java) { config.save(listOf(alpha, beta.copy(address = alpha.address.removeSuffix("/manifest.json") + "/"))) }
        assertTrue(values.isEmpty())
    }
    @Test fun `disabled provider is never requested and name replaces address`() {
        val calls = mutableListOf<String>()
        val sources = resolver(listOf(alpha, beta.copy(enabled = false))) { url -> calls += url; payload(direct("https://video.example/play")) }.sources(identity)
        assertEquals(listOf("https://alpha.example/configured-private-path/stream/movie/tt1234567.json"), calls)
        assertEquals("Alpha", sources.single().provider)
    }
    @Test fun `all disabled fails before network`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolver(listOf(alpha.copy(enabled = false))) { error("Unexpected request") }.sources(identity)
        }
        assertTrue(error.message!!.contains("enable"))
    }
    @Test fun `duplicates merge provider names and metadata but different files survive`() {
        val result = resolver(listOf(alpha, beta)) { url ->
            if (url.contains("alpha.example")) payload(torrent(), torrent(index = 1))
            else payload(torrent().put("behaviorHints", JSONObject().put("videoSize", 123456L)), torrent(index = null))
        }.sources(identity)
        assertEquals(3, result.size)
        assertEquals("Alpha + Beta", result.first().provider)
        assertEquals(123456L, result.first().sizeBytes)
        assertEquals(listOf(0, 1, null), result.map { it.fileIndex })
    }
    @Test fun `direct links deduplicate exactly and keep distinct signed query strings`() {
        val result = resolver(listOf(alpha, beta)) { url ->
            if (url.contains("alpha.example")) payload(direct("https://video.example/play?sig=one"))
            else payload(direct("https://video.example/play?sig=one"), direct("https://video.example/play?sig=two"))
        }.sources(identity)
        assertEquals(2, result.size); assertEquals("Alpha + Beta", result.first().provider)
    }
    @Test fun `direct URLs retain separate identities even when a provider also supplies a hash`() {
        val result = resolver(listOf(alpha, beta)) { url ->
            payload(direct(if (url.contains("alpha.example")) "https://video.example/a" else "https://video.example/b").put("infoHash", "a".repeat(40)))
        }.sources(identity)
        assertEquals(2, result.size)
    }
    @Test fun `failed provider does not hide successful results or expose its URL`() {
        val resolver = resolver(listOf(alpha, beta)) { url ->
            if (url.contains("alpha.example")) error("private endpoint information") else payload(torrent())
        }
        val search = resolver.search(identity)
        assertEquals("Beta", search.items.single().provider)
        assertEquals(listOf("Alpha"), search.unavailableProviders)
        val failure = assertThrows(IllegalStateException::class.java) {
            resolver(listOf(alpha, beta)) { error("private endpoint information") }.sources(identity)
        }
        assertFalse(failure.message!!.contains("private")); assertFalse(failure.message!!.contains("https"))
    }
    @Test fun `enabled providers fetch concurrently and preserve configured result order`() {
        val latch = CountDownLatch(2)
        val result = resolver(listOf(alpha, beta)) { url ->
            latch.countDown(); check(latch.await(3, TimeUnit.SECONDS)) { "Providers should run concurrently" }
            payload(direct(if (url.contains("alpha.example")) "https://video.example/a" else "https://video.example/b"))
        }.sources(identity)
        assertEquals(listOf("Alpha", "Beta"), result.map { it.provider })
    }
    @Test fun `cache lookup happens once for combined unique hashes`() {
        val requests = mutableListOf<List<String>>()
        val cache = object : SourceTorBox by cloud {
            override fun cached(hashes: List<String>): Set<String> { requests += hashes; return hashes.toSet() }
        }
        val resolver = SourceResolver({ "" }, cache, { payload(torrent()) }, serviceOrder = { listOf("torbox") }, configuredProviders = { listOf(alpha, beta) })
        val result = resolver.sources(identity)
        assertEquals(listOf(listOf("a".repeat(40))), requests)
        assertEquals(listOf("torbox"), result.single().cachedServices)
    }
    @Test fun `series keeps episode routing and filters mismatched files across providers`() {
        val series = identity.copy(type = "series", season = 2, episode = 3)
        val result = resolver(listOf(alpha, beta)) { url ->
            assertTrue(url.endsWith("/stream/series/tt1234567:2:3.json"))
            payload(torrent().put("title", if (url.contains("alpha.example")) "Show.S02E03.mkv" else "Show.S02E04.mkv"))
        }.sources(series)
        assertEquals("Alpha", result.single().provider)
    }
}
