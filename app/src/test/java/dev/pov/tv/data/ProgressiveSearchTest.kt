package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProgressiveSearchTest {
    private val identity = PlaybackIdentity("tt1234567", "movie", "Example")
    private val cloud = object : SourceTorBox {
        override fun cached(hashes: List<String>): Set<String> = error("Unexpected cache request")
        override fun addMagnet(hash: String): Long = error("Unexpected transfer")
        override fun folder(kind: String, id: Long): CloudFolder = error("Unexpected transfer")
        override fun streamUrl(kind: String, folderId: Long, fileId: Long): String = error("Unexpected transfer")
    }
    private fun resolver(providers: List<SourceProvider>, fetch: (String) -> JSONObject) = SourceResolver(
        { error("Legacy endpoint must not be used") }, cloud, fetch, serviceOrder = { emptyList() }, configuredProviders = { providers })
    private fun links(vararg urls: String) = JSONObject().put("streams", JSONArray(urls.map { JSONObject().put("url", it).put("title", "Example.2026.1080p.mkv") }))
    private val providers = (1..9).map { SourceProvider("p$it", "Add-on $it", "https://addon$it.example") }

    @Test fun `all add-ons are asked at once, not four at a time`() {
        val started = CountDownLatch(9)
        val result = resolver(providers) { url ->
            started.countDown()
            // Every add-on waits until all nine have started: a cap of four would never get there.
            check(started.await(5, TimeUnit.SECONDS)) { "Add-ons were not asked at the same time" }
            links("${url.substringBefore("/stream")}/play")
        }.search(identity)
        assertEquals(9, result.items.size)
    }

    @Test fun `results arrive as each add-on answers with a count of those still waited for`() {
        val slow = CountDownLatch(1)
        val steps = Collections.synchronizedList(mutableListOf<SourceResolver.SearchProgress>())
        val result = resolver(providers.take(3)) { url ->
            if ("addon3" in url) slow.await(5, TimeUnit.SECONDS)
            links("${url.substringBefore("/stream")}/play")
        }.search(identity) { step ->
            steps += step
            if (step.answered == 2) slow.countDown()
            false
        }
        assertEquals(listOf(1, 2, 3), steps.map { it.answered })
        assertEquals(listOf("Add-on 3"), steps[1].waiting)
        assertTrue(steps.last().finished)
        assertEquals(3, result.items.size)
    }

    @Test fun `the time limit stops waiting and names the add-ons that did not answer`() {
        val never = CountDownLatch(1)
        val timings = Collections.synchronizedMap(mutableMapOf<String, Long?>())
        val started = System.nanoTime()
        val result = resolver(providers.take(2)) { url ->
            if ("addon2" in url) never.await(10, TimeUnit.SECONDS)
            links("${url.substringBefore("/stream")}/play")
        }.search(identity, timeLimitMs = 400, timing = { provider, ms -> timings[provider.id] = ms })
        never.countDown()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(1, result.items.size)
        assertFalse(result.stoppedEarly)
        assertEquals(listOf("Add-on 2"), result.lateProviders)
        assertTrue(timings.containsKey("p2") && timings["p2"] == null)
        assertNotNull(timings["p1"])
    }

    @Test fun `stopping early returns the links found so far`() {
        val never = CountDownLatch(1)
        val result = resolver(providers.take(2)) { url ->
            if ("addon2" in url) never.await(10, TimeUnit.SECONDS)
            links("${url.substringBefore("/stream")}/play")
        }.search(identity) { step -> step.items.isNotEmpty() }
        never.countDown()
        assertEquals(1, result.items.size)
        assertEquals(listOf("Add-on 2"), result.lateProviders)
        assertTrue(result.stoppedEarly)
    }

    @Test fun `no answer at all within the limit explains the time limit`() {
        val never = CountDownLatch(1)
        val error = assertThrows(IllegalStateException::class.java) {
            resolver(providers.take(1)) { never.await(10, TimeUnit.SECONDS); links() }.search(identity, timeLimitMs = 200)
        }
        never.countDown()
        assertTrue(error.message!!.contains("time limit"))
    }

    @Test fun `an add-on's own time limit replaces the search limit, shorter or longer`() {
        val slow = CountDownLatch(1)
        val result = resolver(providers.take(3)) { url ->
            when {
                "addon1" in url -> slow.await(10, TimeUnit.SECONDS)
                "addon3" in url -> Thread.sleep(600)
            }
            links("${url.substringBefore("/stream")}/play")
        }.search(identity, timeLimitMs = 300, ownLimitMs = { id -> when (id) { "p1" -> 150L; "p3" -> 3_000L; else -> null } })
        slow.countDown()
        // Add-on 1 is dropped at its own 150 ms; Add-on 3 answers at 600 ms, within its own 3 s, past the 300 ms search limit.
        assertEquals(listOf("Add-on 1"), result.lateProviders)
        assertEquals(2, result.items.size)
    }
}
