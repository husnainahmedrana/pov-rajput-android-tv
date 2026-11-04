package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TmdbCatalogTest {
    private class MemoryIds : TmdbCatalog.IdCache {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(values: Map<String, String>) { this.values += values }
    }

    private fun page(type: String, id: Int) =
        """{"results":[{"id":$id,"${if (type == "movie") "title" else "name"}":"Title $id","${if (type == "movie") "release_date" else "first_air_date"}":"2026-01-02",
        "poster_path":"/p$id.jpg","backdrop_path":"/b$id.jpg","overview":"About $id","vote_average":7.46,"genre_ids":[28,18]}]}"""

    private fun catalog(key: String, ids: MemoryIds, requests: MutableList<Pair<String, Map<String, String>>>) =
        TmdbCatalog(key, "PK", ids) { url, headers ->
            synchronized(requests) { requests += url to headers }
            when {
                "/external_ids" in url -> if ("/tv/" in url) """{"imdb_id":null}""" else """{"imdb_id":"tt0${url.substringAfter("/movie/").substringBefore('/')}000"}"""
                "/movie/" in url || "/trending/movie" in url -> page("movie", 11)
                else -> page("tv", 22)
            }
        }

    @Test fun `shelves keep the chosen order and map TMDB titles to IMDb ids`() {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val shelves = catalog("abc123", MemoryIds(), requests).homeShelves()
        assertEquals(listOf("Trending Movies Today", "Popular Movies", "Now Playing in Cinemas", "Trending Shows Today", "Popular Shows"), shelves.map { it.title })
        val movie = shelves[1].items.single()
        assertEquals("tt011000", movie.imdbId)
        assertEquals("2026", movie.year)
        assertEquals("7.5", movie.rating)
        assertEquals(listOf("Action", "Drama"), movie.genres)
        assertEquals("https://image.tmdb.org/t/p/w1280/b11.jpg", movie.background)
        // Shows without an IMDb id are left out rather than shown unplayable.
        assertTrue(shelves[4].items.isEmpty())
        assertTrue(requests.any { (url, _) -> "/movie/now_playing?region=PK&language=en-US&api_key=abc123" in url })
    }

    @Test fun `ids are looked up once and read access tokens use a bearer header`() {
        val ids = MemoryIds()
        val first = mutableListOf<Pair<String, Map<String, String>>>()
        catalog("eyJtoken", ids, first).homeShelves()
        assertTrue(first.all { (url, headers) -> "api_key" !in url && headers["Authorization"] == "Bearer eyJtoken" })
        val second = mutableListOf<Pair<String, Map<String, String>>>()
        catalog("eyJtoken", ids, second).homeShelves()
        assertTrue("cached ids must not be fetched again", second.none { "/external_ids" in it.first })
    }

    @Test fun `cast is the first seven billed actors, with shows using the whole run's roles`() {
        val cast = (0 until 9).joinToString(",") { """{"id":${100 + it},"name":"Actor $it","character":"Part $it","order":${8 - it},"profile_path":"/a$it.jpg"}""" }
        val movie = TmdbCatalog("k", "PK", MemoryIds()) { url, _ ->
            when {
                "/find/tt1375666" in url -> """{"movie_results":[{"id":27205}],"tv_results":[]}"""
                "/movie/27205/credits" in url -> """{"cast":[$cast]}"""
                else -> error("unexpected $url")
            }
        }.cast("tt1375666", "movie")
        assertEquals(7, movie.size)
        assertEquals("Actor 8", movie.first().name)   // lowest "order" first
        assertEquals("Part 8", movie.first().character)
        assertEquals("https://image.tmdb.org/t/p/w185/a8.jpg", movie.first().photo)
        val show = TmdbCatalog("k", "PK", MemoryIds()) { url, _ ->
            when {
                "/find/tt0944947" in url -> """{"movie_results":[],"tv_results":[{"id":1399}]}"""
                "/tv/1399/aggregate_credits" in url -> """{"cast":[{"id":5,"name":"Emilia","order":0,"roles":[{"character":"Daenerys"}]},{"id":6,"name":"No photo","order":1,"roles":[]}]}"""
                else -> error("unexpected $url")
            }
        }.cast("tt0944947", "series")
        assertEquals(listOf("Daenerys", ""), show.map { it.character })
        assertEquals("", show[1].photo)
    }

    @Test fun `filmography splits films and shows, most popular first, without talk shows or posterless titles`() {
        val ids = MemoryIds()
        val credits = """{"cast":[
            {"id":1,"media_type":"movie","title":"Small","poster_path":"/1.jpg","popularity":2.0,"release_date":"2001-01-01"},
            {"id":2,"media_type":"movie","title":"Big","poster_path":"/2.jpg","popularity":90.0,"release_date":"2010-07-16"},
            {"id":3,"media_type":"movie","title":"No poster","poster_path":null,"popularity":99.0},
            {"id":2,"media_type":"movie","title":"Big","poster_path":"/2.jpg","popularity":90.0},
            {"id":4,"media_type":"tv","name":"Drama show","poster_path":"/4.jpg","popularity":50.0,"genre_ids":[18]},
            {"id":5,"media_type":"tv","name":"Late night","poster_path":"/5.jpg","popularity":80.0,"genre_ids":[10767]}]}"""
        val result = TmdbCatalog("k", "PK", ids) { url, _ ->
            when {
                "/person/6193/combined_credits" in url -> credits
                "/external_ids" in url -> """{"imdb_id":"tt00000${url.substringAfter("/3/").substringAfter('/').substringBefore('/')}0"}"""
                else -> error("unexpected $url")
            }
        }.filmography(6193)
        assertEquals(listOf("Big", "Small"), result.movies.map { it.title })
        assertEquals(listOf("tt0000020", "tt0000010"), result.movies.map { it.imdbId })
        assertEquals(listOf("Drama show"), result.shows.map { it.title })
        assertEquals("series", result.shows.single().type)
        // See all: every title, newest release first.
        val all = TmdbCatalog("k", "PK", ids) { url, _ -> if ("/person/6193/combined_credits" in url) credits else """{"imdb_id":"tt0000099"}""" }.filmography(6193, Int.MAX_VALUE, byRelease = true)
        assertEquals(listOf("Big", "Small"), all.movies.map { it.title }.distinct())
        assertEquals("2010", all.movies.first().year)
    }
}
