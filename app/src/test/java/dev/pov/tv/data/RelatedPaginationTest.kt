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
class RelatedPaginationTest {
    private class Ids : TmdbCatalog.IdCache {
        private val ids = (1..100).associate { "movie:$it" to "tt${it.toString().padStart(7, '0')}" }
        override fun get(key: String) = ids[key]
        override fun put(values: Map<String, String>) {}
    }
    private fun page(ids: IntRange, total: Int) = JSONObject().put("total_pages", total).put("results", JSONArray(ids.map {
        JSONObject().put("id", it).put("title", "Title $it").put("poster_path", "/p.jpg").put("release_date", "2024-01-01")
    })).toString()
    @Test fun `more loads both provider lists without dropping the second twenty and deduplicates shown items`() {
        val requests = mutableListOf<String>()
        val api = TmdbCatalog("test", "US", Ids()) { url, _ -> requests += url
            if ("recommendations" in url) page(1..20, 3) else page(21..40, 2)
        }
        val result = api.relatedTitles("movie", 100, 2, setOf("tt0000001", "tt0000021"))
        assertEquals(38, result.items.size)
        assertTrue(result.items.any { it.imdbId == "tt0000040" })
        assertEquals(3, result.nextPage)
        assertTrue(requests.all { "page=2" in it })
    }
    @Test fun `last page ends pagination and excludes the selected title and overlap between sources`() {
        val api = TmdbCatalog("test", "US", Ids()) { _, _ -> page(1..3, 1) }
        val result = api.relatedTitles("movie", 2, 1, emptySet())
        assertEquals(listOf("tt0000001", "tt0000003"), result.items.map { it.imdbId })
        assertNull(result.nextPage)
    }
    @Test fun `failed provider page is retryable rather than silently advancing past results`() {
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            if ("similar" in url) error("temporarily unavailable") else page(1..3, 3)
        }
        try { api.relatedTitles("movie", 100, 2, emptySet()); fail("Expected retryable failure") }
        catch (expected: IllegalStateException) { assertEquals("temporarily unavailable", expected.message) }
    }
}
