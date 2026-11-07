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
class ImdbRatingsTest {
    private fun episode(season: Int, number: Int, rating: Any?, votes: Int = 1000) =
        """{"node":{"series":{"episodeNumber":{"episodeNumber":$number,"seasonNumber":$season}},
            "ratingsSummary":{"aggregateRating":${rating ?: "null"},"voteCount":$votes}}}"""

    @Test fun `a season answer gives the show score and each episode's own score`() {
        val json = JSONObject("""{"data":{"title":{"ratingsSummary":{"aggregateRating":9.5,"voteCount":2702330},
            "episodes":{"episodes":{"edges":[${episode(1, 1, 9.1, 87422)},${episode(1, 2, 8.6)},
            ${episode(2, 1, 8.0)},${episode(1, 3, null, 0)}]}}}}}""")
        val scores = ImdbRatings.parseSeason(json, 1)!!
        assertEquals("9.5", scores.title!!.label)
        assertEquals("2.7M", scores.title!!.votesLabel)
        // Another season's episode and one with no votes yet are left out.
        assertEquals(setOf(1, 2), scores.episodes.keys)
        assertEquals("9.1", scores.episodes.getValue(1).label)
        assertEquals("87K", scores.episodes.getValue(1).votesLabel)
    }

    @Test fun `a request through the client is parsed and a failure is null`() {
        val ok = ImdbRatings { """{"data":{"title":{"ratingsSummary":{"aggregateRating":8.8,"voteCount":2600000}}}}""" }
        assertEquals("8.8", ok.title("tt1375666")!!.label)
        assertNull(ImdbRatings { throw ApiException(403, "Forbidden") }.title("tt1375666"))
        assertNull(ImdbRatings { "<html>403 Forbidden</html>" }.season("tt0903747", 1))
        assertNull(ImdbRatings { """{"data":{"title":null}}""" }.season("tt0903747", 1))
    }

    @Test fun `scores show one decimal and votes are compact`() {
        assertEquals("8.0", ImdbRatings.Score(8.0, 1).label)
        assertEquals("950", ImdbRatings.compactVotes(950))
        assertEquals("1.2K", ImdbRatings.compactVotes(1_234))
        assertEquals("12K", ImdbRatings.compactVotes(12_000))
        assertEquals("654K", ImdbRatings.compactVotes(654_321))
        assertEquals("1M", ImdbRatings.compactVotes(1_000_000))
        assertNull(ImdbRatings.score(JSONObject("""{"aggregateRating":0,"voteCount":0}""")))
    }

    @Test fun `original air dates come with the season, also for unaired episodes, and partial dates are ignored`() {
        val json = JSONObject("""{"data":{"title":{"ratingsSummary":null,"episodes":{"episodes":{"edges":[
            {"node":{"series":{"episodeNumber":{"episodeNumber":1,"seasonNumber":1}},"releaseDate":{"day":20,"month":1,"year":2008},
             "ratingsSummary":{"aggregateRating":9.1,"voteCount":87422}}},
            {"node":{"series":{"episodeNumber":{"episodeNumber":2,"seasonNumber":1}},"releaseDate":{"day":25,"month":6,"year":2027},
             "ratingsSummary":{"aggregateRating":null,"voteCount":0}}},
            {"node":{"series":{"episodeNumber":{"episodeNumber":3,"seasonNumber":1}},"releaseDate":{"day":null,"month":null,"year":2027}}}
        ]}}}}}""")
        val scores = ImdbRatings.parseSeason(json, 1)!!
        assertNull(scores.title)
        assertEquals(setOf(1), scores.episodes.keys)
        assertEquals(mapOf(1 to java.time.LocalDate.of(2008, 1, 20), 2 to java.time.LocalDate.of(2027, 6, 25)), scores.airDates)
    }
}
