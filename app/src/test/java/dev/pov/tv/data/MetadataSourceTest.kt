package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MetadataSourceTest {
    private val manifest = JSONObject("""{"name":"AIOMetadata","resources":["catalog","meta"],"catalogs":[
        {"type":"movie","id":"tmdb.top","name":"Top"},
        {"type":"movie","id":"people_search.people_search_movie","extra":[{"name":"search","isRequired":true}]},
        {"type":"movie","id":"search.movie","extra":[{"name":"search","isRequired":true}]},
        {"type":"series","id":"search.series","extra":[{"name":"search","isRequired":true}]},
        {"type":"anime.series","id":"search.anime_series","extra":[{"name":"search","isRequired":true}]}]}""")

    @Test fun `an AIOMetadata manifest gives its movie and series search and details`() {
        val source = MetadataSource.inspect("AIOMetadata", "https://aio.example/stremio/u", manifest)!!
        assertEquals(mapOf("movie" to "search.movie", "series" to "search.series"), source.searches)
        assertEquals(true, source.details)
        assertEquals(source, MetadataSource.fromJson(source.toJson()))
        assertNull(MetadataSource.inspect("Plain", "https://x.example", JSONObject("""{"resources":["stream"],"catalogs":[]}""")))
    }

    @Test fun `search and details come from the add-on, with Cinemeta as the fallback`() {
        val source = MetadataSource.inspect("AIOMetadata", "https://aio.example/stremio/u", manifest)!!
        val cinemeta = mutableListOf<String>()
        val api = CatalogApi(source = { source }, publicJson = { url -> cinemeta += url; JSONObject("""{"metas":[{"id":"tt0000001","name":"From Cinemeta"}],"meta":{"id":"tt1160419","name":"Dune (Cinemeta)"}}""") }, addonJson = { url ->
            when {
                "search.movie/search=dune" in url -> JSONObject("""{"metas":[{"id":"tt1160419","imdb_id":"tt1160419","type":"movie","name":"Dune"}]}""")
                "search.movie/" in url -> JSONObject("""{"metas":[]}""")
                "/meta/movie/tt1160419.json" in url -> JSONObject("""{"meta":{"id":"tt1160419","imdb_id":"tt1160419","type":"movie","name":"Dune","description":"From AIOMetadata"}}""")
                else -> error(url)
            }
        })
        assertEquals(listOf("Dune"), api.search("dune", "movie").map { it.title })
        assertEquals(0, cinemeta.size)
        assertEquals(listOf("From Cinemeta"), api.search("nothing there", "movie").map { it.title })
        assertEquals("From AIOMetadata", api.details(MediaItemData("tt1160419", "movie", "Dune")).description)
    }
}
