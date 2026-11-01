package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeRowsAndAddonsTest {
    private fun rows(): HomeRows {
        val saved = mutableMapOf<String, String>()
        return HomeRows({ saved[it] }, { key, value -> if (value == null) saved.remove(key) else saved[key] = value })
    }

    @Test fun `default order until the viewer moves a row`() {
        val home = rows()
        assertEquals(listOf("a", "b", "c"), home.arrange(listOf("a", "b", "c")) { it })
        home.move(listOf("a", "b", "c"), "c", up = true)
        assertEquals(listOf("a", "c", "b"), home.arrange(listOf("a", "b", "c")) { it })
        home.move(listOf("a", "c", "b"), "a", up = true) // already first: nothing changes
        assertEquals(listOf("a", "c", "b"), home.arrange(listOf("a", "b", "c")) { it })
    }

    @Test fun `rows that come and go keep their place and new rows follow`() {
        val home = rows()
        home.move(listOf("a", "tmdb", "b"), "b", up = true) // a, b, tmdb
        // Without a TMDB key that row is gone; a new add-on row appears.
        assertEquals(listOf("a", "b", "addon"), home.arrange(listOf("a", "b", "addon")) { it })
        assertEquals(listOf("a", "b", "tmdb"), home.arrange(listOf("a", "tmdb", "b")) { it })
    }

    @Test fun `hidden rows leave Home and reset restores everything`() {
        val home = rows()
        home.setVisible("b", false)
        assertEquals(listOf("a", "c"), home.visible(listOf("a", "b", "c")) { it })
        home.forget("b")
        assertEquals(listOf("a", "b", "c"), home.visible(listOf("a", "b", "c")) { it })
        home.move(listOf("a", "b"), "b", up = true); home.setVisible("a", false)
        home.reset()
        assertEquals(listOf("a", "b"), home.visible(listOf("a", "b")) { it })
        assertTrue(!home.customized)
    }

    @Test fun `only movie and series catalogs without required filters become rows`() {
        val manifest = JSONObject("""{"name":"AIOMetadata","catalogs":[
            {"type":"movie","id":"tmdb.trending","name":"Trending"},
            {"type":"series","id":"netflix","name":"Netflix","extra":[{"name":"skip"},{"name":"genre"}]},
            {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true}]},
            {"type":"movie","id":"by-genre","name":"Genre","extraRequired":["genre"]},
            {"type":"anime","id":"kitsu","name":"Anime"},
            {"type":"movie","id":"tmdb.trending","name":"Duplicate"}]}""")
        assertEquals(listOf(AddonCatalog("movie", "tmdb.trending", "Trending · Movies"), AddonCatalog("series", "netflix", "Netflix · Shows")),
            CatalogAddons.parseCatalogs(manifest))
    }

    @Test fun `add-on catalogs page by skip and drop titles without IMDb ids`() {
        val asked = mutableListOf<String>()
        val addons = CatalogAddons({ "" }, { _, _ -> }) { url ->
            asked += url
            JSONObject("""{"metas":[{"id":"tt1234567","name":"One"},{"id":"tmdb:55","name":"No IMDb"},{"id":"tmdb:66","imdb_id":"tt7654321","name":"Two"}]}""")
        }
        val addon = CatalogAddon("x", "AIO", "https://aio.example/abc", listOf(AddonCatalog("movie", "tmdb.top", "Top")))
        assertEquals(listOf("tt1234567", "tt7654321"), addons.items(addon, addon.catalogs[0], skip = 40).map { it.imdbId })
        assertEquals("https://aio.example/abc/catalog/movie/tmdb.top/skip=40.json", asked.single())
    }

    @Test fun `OpenSubtitles results match the language and episode, best release first`() {
        val finder = ExternalSubtitles { url ->
            assertEquals("https://opensubtitles-v3.strem.io/subtitles/series/tt0903747:1:2.json", url)
            """{"subtitles":[
              {"url":"https://subs.example/1","lang":"eng","subtitleFileName":"Breaking.Bad.S01E02.DVDRip.XviD-ORPHEUS.srt","season":1,"episode":2},
              {"url":"https://subs.example/2","lang":"eng","subtitleFileName":"Breaking.Bad.S01E02.1080p.WEB-DL.NTb.srt","season":1,"episode":2},
              {"url":"https://subs.example/3","lang":"por","subtitleFileName":"Breaking.Bad.S01E02.1080p.WEB-DL.NTb.srt"},
              {"url":"https://subs.example/4","lang":"eng","subtitleFileName":"Breaking.Bad.S01E03.srt","season":1,"episode":3}]}"""
        }
        val found = finder.find(PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2), "en", "Breaking.Bad.S01E02.1080p.WEB-DL.DD5.1.H.264-NTb.mkv")
        assertEquals(listOf("https://subs.example/2", "https://subs.example/1"), found.map { it.url })
        assertEquals("English · OpenSubtitles", found.first().label)
        assertEquals("por", ExternalSubtitles.iso3("pob"))
        assertEquals("eng", ExternalSubtitles.iso3("en"))
    }
}
