package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrewMetadataTest {
    private class Ids : TmdbCatalog.IdCache {
        private val ids = mutableMapOf<String, String>()
        override fun get(key: String) = ids[key]
        override fun put(values: Map<String, String>) { ids.putAll(values) }
    }
    @Test fun `movie crew prioritizes creative roles and groups duplicate people`() {
        val data = JSONObject("""{"credits":{"crew":[
            {"id":2,"name":"Composer","job":"Original Music Composer","profile_path":"/c.jpg"},
            {"id":1,"name":"Director","job":"Director","profile_path":null},
            {"id":1,"name":"Director","job":"Writer","profile_path":"/d.jpg"},
            {"id":3,"name":"Assistant","job":"Assistant Camera"},
            {"id":0,"name":"Invalid","job":"Producer"}]}}""")
        val crew = DetailCrew.from(data, "movie")
        assertEquals(listOf("Director", "Composer"), crew.map { it.name })
        assertEquals("Director · Writer", crew[0].character)
        assertTrue(crew.all { it.crew })
        assertEquals("https://image.tmdb.org/t/p/w185/d.jpg", crew[0].photo)
        assertEquals("Composer", crew[1].character)
    }
    @Test fun `series creators and aggregate jobs keep key crew rather than only latest season credits`() {
        val data = JSONObject("""{"created_by":[{"id":1,"name":"Creator"}],"aggregate_credits":{"crew":[
            {"id":1,"name":"Creator","jobs":[{"job":"Writer","episode_count":40}]},
            {"id":2,"name":"Director A","jobs":[{"job":"Director","episode_count":3}]},
            {"id":3,"name":"Director B","jobs":[{"job":"Director","episode_count":20}]},
            {"id":4,"name":"Director C","jobs":[{"job":"Director","episode_count":1}]},
            {"id":5,"name":"Producer","jobs":[{"job":"Executive Producer","episode_count":60}]}]}}""")
        val crew = DetailCrew.from(data, "series")
        assertEquals("Creator · Writer", crew.first().character)
        assertEquals(listOf("Director B", "Director A"), crew.filter { it.character == "Director" }.map { it.name })
        assertTrue(crew.any { it.name == "Producer" })
        assertEquals("", crew.first().photo)
    }
    @Test fun `empty or malformed crew entries are omitted`() {
        assertTrue(DetailCrew.from(JSONObject(), "movie").isEmpty())
        assertTrue(DetailCrew.from(JSONObject("""{"credits":{"crew":[{"id":1,"name":null,"job":"Director"}]}}"""), "movie").isEmpty())
    }
    @Test fun `details fetch includes crew without fetching a separate endpoint`() {
        val requests = mutableListOf<String>()
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            requests += url
            if ("/find/" in url) """{"movie_results":[{"id":1}]}""" else
                """{"credits":{"crew":[{"id":2,"name":"Director","job":"Director"}]}}"""
        }
        val details = api.detailExtras("tt0000001", "movie", withSimilar = false)
        assertEquals("Director", details.crew.single().name)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("append_to_response=keywords,credits"))
    }
    @Test fun `crew filmography reads crew credits while actor pages still read cast credits`() {
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            if ("/combined_credits" in url) """{"crew":[{"id":10,"media_type":"movie","title":"Directed Film","poster_path":"/p.jpg"}],"cast":[{"id":20,"media_type":"movie","title":"Acted Film","poster_path":"/p.jpg"}]}"""
            else """{"imdb_id":"tt0000001"}"""
        }
        assertEquals("Directed Film", api.filmography(1, crew = true).movies.single().title)
        assertEquals("Acted Film", api.filmography(1).movies.single().title)
    }
    @Test fun `a show downloads its whole-run credits once for both cast and crew`() {
        val requests = mutableListOf<String>()
        val api = TmdbCatalog("test", "US", Ids()) { url, _ ->
            requests += url
            when {
                "/find/" in url -> """{"tv_results":[{"id":987654}]}"""
                "/aggregate_credits" in url -> """{"cast":[{"id":7,"name":"Actor","order":0,"roles":[{"character":"Lead"}]}],"crew":[{"id":8,"name":"Showrunner","jobs":[{"job":"Director","episode_count":9}]}]}"""
                else -> """{"created_by":[]}"""
            }
        }
        assertEquals("Actor", api.cast("tt7654321", "series").single().name)
        assertEquals("Showrunner", api.detailExtras("tt7654321", "series", withSimilar = false).crew.single().name)
        assertEquals(1, requests.count { "/aggregate_credits" in it })
        assertFalse(requests.any { "append_to_response" in it && "aggregate_credits" in it })
    }
}
