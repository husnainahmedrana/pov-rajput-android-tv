package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Batch20261123Test {
    // ---- Home rows: catalogs with a required filter, and titles named by TMDB/Kitsu ids ----

    @Test fun `a catalog that requires a genre is offered with its values, a search-only one is not`() {
        val manifest = JSONObject("""{"catalogs":[
            {"type":"movie","id":"top","name":"Top","extra":[{"name":"genre","isRequired":true,"options":["Action","Comedy"]},{"name":"skip"}]},
            {"type":"series","id":"by-year","name":"By year","extraRequired":["year"],"extra":[{"name":"year","options":["2026","2025"]}]},
            {"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]},
            {"type":"movie","id":"nolist","name":"No list","extraRequired":["genre"]}]}""")
        val found = CatalogAddons.parseCatalogs(manifest)
        assertEquals(listOf("top", "by-year"), found.map { it.id })
        assertEquals(AddonFilter("genre", listOf("Action", "Comedy")), found[0].filter)
        assertEquals(AddonFilter("year", listOf("2026", "2025")), found[1].filter)
        val row = found[0].withValue("Science Fiction")
        assertEquals("genre=Science Fiction", row.extra)
        assertEquals("Top · Movies · Science Fiction", row.name)
        assertNull(row.filter)
    }

    @Test fun `filter values and paging go in the address the Stremio way, and are saved`() {
        assertEquals("/genre=Science%20Fiction&skip=40", CatalogAddons.extraPath("genre=Science Fiction", 40))
        assertEquals("/skip=20", CatalogAddons.extraPath("", 20))
        assertEquals("", CatalogAddons.extraPath("", 0))
        val saved = mutableMapOf<String, String>()
        val addons = CatalogAddons({ saved[it].orEmpty() }, { k, v -> saved[k] = v })
        val catalog = AddonCatalog("movie", "top", "Top · Movies").copy(filter = AddonFilter("genre", listOf("Action"))).withValue("Action")
        addons.save(listOf(CatalogAddon("a", "Add-on", "https://x.example/c", listOf(catalog))))
        val back = addons.load().single().catalogs.single()
        assertEquals("genre=Action", back.extra)
        assertEquals("addon:a:movie:top:genre=Action", CatalogAddons.rowId(addons.load().single(), back))
    }

    @Test fun `titles named by TMDB or Kitsu id get their IMDb id instead of being dropped`() {
        val asked = mutableListOf<String>()
        val addons = CatalogAddons({ "" }, { _, _ -> }, fetch = { url ->
            asked += url
            when {
                "/catalog/" in url -> JSONObject("""{"metas":[{"id":"tt1234567","name":"Has IMDb"},{"id":"tmdb:550","name":"Fight Club"},
                    {"id":"kitsu:1","name":"Cowboy Bebop"},{"id":"tmdb:999","name":"Unknown"}]}""")
                url.endsWith("/meta/movie/kitsu%3A1.json") -> JSONObject("""{"meta":{"id":"kitsu:1"}}""")
                "anime-kitsu" in url && "kitsu:1" in url -> JSONObject("""{"meta":{"imdb_id":"tt0213338"}}""")
                else -> JSONObject("""{"meta":{}}""")
            }
        }, tmdbKey = { "KEY" }, tmdbGet = { url, _ ->
            if ("/movie/550/external_ids" in url) """{"imdb_id":"tt0137523"}""" else """{"imdb_id":null}"""
        })
        val addon = CatalogAddon("t", "TMDB add-on", "https://tmdb-addon.example/cfg", listOf(AddonCatalog("movie", "pop", "Popular")))
        val items = addons.items(addon, addon.catalogs[0])
        assertEquals(listOf("tt1234567", "tt0137523", "tt0213338"), items.map { it.imdbId })
        assertEquals("Fight Club", items[1].title)
    }

    // ---- Search history ----

    @Test fun `recent searches are newest first, without repeats, and can be removed or cleared`() {
        var saved: String? = null
        val history = SearchHistory({ saved }, { saved = it })
        history.add("Dune"); history.add("The Office"); history.add("  dune  "); history.add("Breaking Bad")
        assertEquals(listOf("Breaking Bad", "dune", "The Office"), history.all())
        assertEquals(listOf("The Office"), history.matching("off"))
        assertEquals(listOf("dune"), history.matching("du"))
        history.remove("DUNE")
        assertEquals(listOf("Breaking Bad", "The Office"), history.all())
        repeat(30) { history.add("q$it") }
        assertEquals(SearchHistory.MAX, history.all().size)
        history.clear(); assertTrue(history.all().isEmpty())
    }

    // ---- Poster services ----

    @Test fun `each poster service builds the same address as AIOStreams`() {
        fun url(service: String, key: String = "K", custom: String = "", profile: String = "default", base: String = PosterServices.DEFAULT_OPENPOSTERDB) =
            PosterServices.url("tt0111161", "movie", PosterServices.Config(service, key, custom, profile, base))
        assertEquals("https://api.ratingposterdb.com/K/imdb/poster-default/tt0111161.jpg?fallback=true", url(PosterServices.RPDB))
        assertEquals("https://api.ratingposterdb.com/t0-free-rpdb/imdb/poster-default/tt0111161.jpg?fallback=true", url(PosterServices.RPDB, key = ""))
        assertEquals("https://api.top-posters.com/K/imdb/poster/tt0111161.jpg?fallback=true", url(PosterServices.TOP_POSTERS))
        assertEquals("https://apiv2.aioratings.com/api/K/imdb/default/tt0111161.jpg", url(PosterServices.AIORATINGS))
        assertEquals("https://openposterdb.com/K/imdb/poster-default/tt0111161", url(PosterServices.OPENPOSTERDB))
        assertEquals("https://btttr.cc/poster/imdb/poster-default/tt0111161.jpg", url(PosterServices.BETTERPOSTERS, key = ""))
        assertEquals("https://img.example/movie/tt0111161.jpg", url(PosterServices.CUSTOM, custom = "https://img.example/{type}/{imdb_id}.jpg"))
        // A key-needing service without a key, no service, a plain-http pattern or a title without an IMDb id: the usual poster.
        assertNull(url(PosterServices.TOP_POSTERS, key = ""))
        assertNull(url(PosterServices.NONE))
        assertNull(url(PosterServices.CUSTOM, custom = "http://img.example/{imdb_id}.jpg"))
        assertNull(url(PosterServices.CUSTOM, custom = "https://img.example/{tmdb_id}.jpg"))
        assertNull(PosterServices.url("tmdb:5", "movie", PosterServices.Config(PosterServices.BETTERPOSTERS)))
        assertNull(PosterServices.customProblem("https://img.example/{imdb_id}.jpg"))
        assertTrue(PosterServices.customProblem("https://img.example/poster.jpg") != null)
    }

    // ---- Calendar ----

    @Test fun `the calendar lists the next episodes of the viewer's shows, soonest first`() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        val shows = mapOf(
            "tt0000001" to MediaItemData("tt0000001", "series", "Show A", episodes = listOf(
                EpisodeData(1, 1, "Old", "2026-10-01T00:00:00.000Z"), EpisodeData(1, 2, "Soon", "2026-11-26T02:00:00.000Z"),
                EpisodeData(0, 1, "Special", "2026-11-25T00:00:00.000Z"), EpisodeData(1, 3, "Far", "2027-03-01T00:00:00.000Z"))),
            "tt0000002" to MediaItemData("tt0000002", "series", "Show B", episodes = listOf(EpisodeData(2, 5, "Tomorrow", "2026-11-24"))))
        val calendar = EpisodeCalendar({ item -> shows.getValue(item.imdbId) }, now = { now })
        val entries = calendar.upcoming(listOf(MediaItemData("tt0000001", "series", ""), MediaItemData("tt0000002", "series", ""), MediaItemData("x", "series", "")))
        assertEquals(listOf("Show B 2x5", "Show A 1x2"), entries.map { "${it.show.title} ${it.episode.season}x${it.episode.episode}" })
        assertEquals(PlaybackIdentity("tt0000001", "series", "Show A", 1, 2), entries[1].identity)
    }
}
