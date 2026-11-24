package dev.pov.tv

import dev.pov.tv.data.MdbListRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MdbListRatingsTest {
    @Test fun `ratings come out in display order with their own scales`() {
        val text = """{"title":"Movie","ratings":[
            {"source":"metacritic","value":74,"score":74},
            {"source":"imdb","value":8.1,"score":81,"votes":120000},
            {"source":"letterboxd","value":3.9,"score":78},
            {"source":"tomatoes","value":91,"score":91},
            {"source":"popcorn","value":85,"score":85},
            {"source":"trakt","value":82,"score":82},
            {"source":"tmdb","value":78,"score":78},
            {"source":"rogerebert","value":3.5,"score":70}]}"""
        val ratings = MdbListRatings.parse(text)
        // Metacritic is left out on purpose.
        assertEquals(listOf("imdb", "tmdb", "trakt", "letterboxd", "tomatoes", "popcorn"), ratings.map { it.source })
        assertEquals(8.1, ratings.first().value, 0.001)
        assertEquals(3.9, ratings.first { it.source == "letterboxd" }.value, 0.001)
    }

    @Test fun `missing, empty and out of range scores are left out`() {
        val text = """{"ratings":[
            {"source":"imdb","value":null},
            {"source":"tomatoes","value":0},
            {"source":"metacritic","value":140},
            {"source":"letterboxd","score":80},
            {"source":"audience","value":66}]}"""
        val ratings = MdbListRatings.parse(text)
        assertEquals(listOf("letterboxd", "popcorn"), ratings.map { it.source })
        assertEquals(4.0, ratings.first().value, 0.001)
    }

    @Test fun `an answer without ratings gives none`() {
        assertTrue(MdbListRatings.parse("""{"error":"Invalid API key"}""").isEmpty())
    }

    @Test fun `the request names the kind of title and carries the key`() {
        var asked = ""
        MdbListRatings { url -> asked = url; """{"ratings":[]}""" }.title("abc 123", "series", "tt0903747")
        assertEquals("https://api.mdblist.com/imdb/show/tt0903747/?apikey=abc%20123", asked)
    }
}
