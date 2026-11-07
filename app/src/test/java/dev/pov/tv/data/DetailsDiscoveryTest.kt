package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetailsDiscoveryTest {
    private class Ids : TmdbCatalog.IdCache {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(values: Map<String, String>) { this.values += values }
    }
    @Test fun `movie extras exclude the current title and unmapped items and retain keyword ids`() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            requests += url
            when {
                "/find/" in url -> """{"movie_results":[{"id":1}]}"""
                "append_to_response" in url -> """{"vote_average":8.123,"vote_count":12,"keywords":{"keywords":[{"id":5,"name":"dream"},{"id":5,"name":"duplicate"},{"id":0,"name":"invalid"}]},"similar":{"results":[{"id":1,"title":"Self","poster_path":"/a.jpg"},{"id":2,"title":"Related","poster_path":"/b.jpg"},{"id":3,"title":"No IMDb","poster_path":"/c.jpg"},{"id":4,"title":"No poster"}]}}"""
                "/movie/2/external_ids" in url -> """{"imdb_id":"tt0000002"}"""
                "/external_ids" in url -> """{"imdb_id":null}"""
                else -> error("Unexpected request")
            }
        }
        val data = api.detailExtras("tt0000001", "movie")
        assertEquals(listOf(TmdbCatalog.Topic(5, "dream")), data.keywords)
        assertEquals(listOf("tt0000002"), data.similar.map { it.imdbId })
        assertFalse(requests.any { "/movie/1/external_ids" in it || "/movie/4/external_ids" in it })
    }
    @Test fun `shows read results shaped keywords`() {
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            if ("/find/" in url) """{"tv_results":[{"id":42}]}""" else
                """{"vote_average":10,"vote_count":0,"keywords":{"results":[{"id":6,"name":"space"}]}}"""
        }
        val data = api.detailExtras("tt0000042", "series")
        assertEquals("space", data.keywords.single().name)
        assertTrue(data.similar.isEmpty())
    }
    @Test fun `recent pool requires matching genres solid votes and a suitable audience and tolerates service failure`() {
        var discover = ""
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            when {
                "/find/" in url -> """{"movie_results":[{"id":1}]}"""
                "append_to_response" in url -> """{"genres":[{"id":28},{"id":12},{"id":878}],"keywords":{"keywords":[{"id":5,"name":"dreams"}]}}"""
                "/discover/" in url -> { discover = url; error("temporarily unavailable") }
                else -> error("Unexpected request")
            }
        }
        val result = api.detailExtras("tt0000001", "movie")
        assertEquals("dreams", result.keywords.single().name)
        assertTrue(discover.contains("vote_average.gte=7&vote_count.gte=100"))
        assertTrue(discover.contains("with_genres=28,12,878"))
        assertTrue(discover.contains("without_genres=16,10751,10762"))
    }
    @Test fun `keyword opens structured discover filter and reuses cached IMDb mappings`() {
        val ids = Ids().apply { put(mapOf("tv:8" to "tt0000008")) }
        var request = ""
        val api = TmdbCatalog("test", "US", ids) { url, _ ->
            request = url
            """{"results":[{"id":8,"name":"Space Show","poster_path":"/p.jpg"}]}"""
        }
        assertEquals("Space Show", api.keywordTitles("series", 99, 2).single().title)
        assertTrue(request.contains("/discover/tv?include_adult=false&with_keywords=99&sort_by=popularity.desc&page=2"))
    }
    @Test fun `unknown titles return no extras without fetching guessed metadata`() {
        var count = 0
        val api = TmdbCatalog("test", "US", Ids()) { _, _ -> count++; "{}" }
        assertTrue(api.detailExtras("tt0000001", "movie").keywords.isEmpty())
        assertEquals(1, count)
    }
}
