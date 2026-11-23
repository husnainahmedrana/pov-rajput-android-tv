package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FreeCastTest {
    private val cinemeta = """{"meta":{"cast":["Tim Robbins","Morgan Freeman","Bob Gunton"]}}"""
    private val wikidata = """{"results":{"bindings":[
        {"actor":{"value":"Q1"},"actorLabel":{"value":"Clancy Brown"},"img":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Clancy.jpg"}},
        {"actor":{"value":"Q2"},"actorLabel":{"value":"Morgan Freeman"},"img":{"value":"http://commons.wikimedia.org/wiki/Special:FilePath/Morgan.jpg"},"roleLabel":{"value":"Red"}},
        {"actor":{"value":"Q3"},"actorLabel":{"value":"Tim Robbins"}},
        {"actor":{"value":"Q4"},"actorLabel":{"value":"Q4"}},
        {"actor":{"value":"Q5"},"actorLabel":{"value":"William Sadler"}}]}}"""

    @Test fun `films come from Wikidata, billed leads first, photos as small https thumbnails`() {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val cast = FreeCast { url, headers ->
            asked += url to headers
            if ("cinemeta" in url) cinemeta else wikidata
        }.cast("tt0111161", "movie")
        assertEquals(listOf("Tim Robbins", "Morgan Freeman", "Bob Gunton", "Clancy Brown", "William Sadler"), cast.map { it.name })
        assertEquals("Red", cast[1].character)
        assertTrue(cast[1].photo, cast[1].photo.startsWith("https://upload.wikimedia.org/wikipedia/commons/thumb/") && cast[1].photo.endsWith("/Morgan.jpg/250px-Morgan.jpg"))
        // Bob Gunton isn't in Wikidata's answer: still shown (initials card).
        assertEquals("", cast[2].photo)
        // No TMDB person behind them: unique negative ids.
        assertEquals((1..5).map { -it.toLong() }, cast.map { it.personId })
        val sparql = asked.first { "wikidata" in it.first }
        assertTrue(sparql.second["User-Agent"].orEmpty().startsWith("Nabrio/"))
        assertTrue(sparql.first.contains("tt0111161"))
    }

    @Test fun `Commons file links become direct upload thumbnails`() {
        // Checked live: this address returns the photo (with the app's User-Agent).
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/e/e4/Morgan_Freeman_Deauville_2018.jpg/250px-Morgan_Freeman_Deauville_2018.jpg",
            FreeCast.commonsThumb("http://commons.wikimedia.org/wiki/Special:FilePath/Morgan%20Freeman%20Deauville%202018.jpg"))
        assertTrue(FreeCast.commonsThumb("http://commons.wikimedia.org/wiki/Special:FilePath/Logo.svg")!!.endsWith("/250px-Logo.svg.png"))
        assertEquals(null, FreeCast.commonsThumb("http://commons.wikimedia.org/wiki/Special:FilePath/Scan.tif"))
    }

    @Test fun `shows come from TVmaze in its order, with characters and photos`() {
        val cast = FreeCast { url, _ ->
            when {
                "cinemeta" in url -> cinemeta
                "lookup/shows" in url -> """{"id":169,"name":"Breaking Bad"}"""
                "/shows/169/cast" in url -> """[{"person":{"name":"Bryan Cranston","image":{"medium":"http://static.tvmaze.com/b.jpg"}},"character":{"name":"Walter White"}},
                    {"person":{"name":"Aaron Paul","image":null},"character":{"name":"Jesse Pinkman"}}]"""
                else -> error("unexpected $url")
            }
        }.cast("tt0903747", "series")
        assertEquals(listOf("Bryan Cranston" to "Walter White", "Aaron Paul" to "Jesse Pinkman"), cast.map { it.name to it.character })
        assertEquals("https://static.tvmaze.com/b.jpg", cast[0].photo)
    }

    @Test fun `a show TVmaze doesn't know falls back to Wikidata`() {
        val cast = FreeCast { url, _ ->
            when {
                "cinemeta" in url -> """{"meta":{}}"""
                "tvmaze" in url -> throw ApiException(404, "Not found")
                else -> wikidata
            }
        }.cast("tt0903747", "series")
        assertEquals(4, cast.size)
    }
}
