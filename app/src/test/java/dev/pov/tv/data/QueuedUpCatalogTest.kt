package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QueuedUpCatalogTest {
    private class MemoryCache : QueuedUpCatalog.Cache {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }
    private val answer = JSONObject("""{"metas":[{"id":"tt1234567","name":"Trending example","releaseInfo":"2026"}]}""")
    private val hour = 60 * 60 * 1000L

    @Test fun `trending reads the add-on catalog and keeps it for six hours`() {
        val cache = MemoryCache(); val addresses = mutableListOf<String>(); var now = 0L
        val catalog = QueuedUpCatalog(cache, { addresses += it; answer }, { now })
        assertEquals(listOf("tt1234567"), catalog.trending("movie")!!.map { it.imdbId })
        now = 5 * hour
        catalog.trending("movie")
        assertEquals(listOf("https://queued-up.app/api/catalog/movie/trending-movie.json"), addresses)
        now = 7 * hour
        catalog.trending("movie")
        assertEquals(2, addresses.size)
    }

    @Test fun `an outage uses a recent saved list, then gives up so Home falls back`() {
        val cache = MemoryCache(); var now = 0L; var down = false
        val catalog = QueuedUpCatalog(cache, { if (down) throw ApiException(503, "down") else answer }, { now })
        assertNotNull(catalog.latest("series"))
        down = true
        now = 30 * hour
        assertEquals(listOf("tt1234567"), catalog.latest("series")!!.map { it.imdbId })
        now = 4 * 24 * hour
        assertNull(catalog.latest("series"))
        assertNull(QueuedUpCatalog(MemoryCache(), { throw ApiException(503, "down") }).trending("movie"))
    }
}
