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
class DetailFactsTest {
    @Test fun `a movie lists director, dates, runtime, language, studios and money`() {
        val data = JSONObject("""{
            "release_date": "2010-07-15", "runtime": 148, "original_language": "en",
            "spoken_languages": [{"iso_639_1": "ja", "english_name": "Japanese"}, {"iso_639_1": "en", "english_name": "English"}],
            "production_companies": [{"name": "Legendary Pictures"}, {"name": "Syncopy"}, {"name": "Warner Bros. Pictures"}],
            "budget": 160000000, "revenue": 839030630,
            "credits": {"crew": [{"name": "Hans Zimmer", "job": "Original Music Composer"}, {"name": "Christopher Nolan", "job": "Director"}]}
        }""")
        assertEquals(listOf(
            "Directed by" to "Christopher Nolan", "Released" to "15 July 2010", "Runtime" to "2h 28m",
            "Language" to "English", "Studio" to "Legendary Pictures, Syncopy", "Budget" to "$160M", "Box office" to "$839M",
        ), DetailFacts.from(data, "movie"))
    }

    @Test fun `placeholder money, missing dates and zero runtime are left out`() {
        val facts = DetailFacts.from(JSONObject("""{"budget": 1, "revenue": 0, "runtime": 0, "release_date": "", "original_language": "xx"}"""), "movie")
        assertTrue(facts.isEmpty())
        assertEquals(listOf("Box office" to "$1.2B"), DetailFacts.from(JSONObject("""{"revenue": 1234000000}"""), "movie"))
    }

    @Test fun `an ended show lists creators, network, both air dates and its size`() {
        val data = JSONObject("""{
            "created_by": [{"name": "Vince Gilligan"}], "networks": [{"name": "AMC"}],
            "first_air_date": "2008-01-20", "last_air_date": "2013-09-29", "status": "Ended",
            "number_of_seasons": 5, "number_of_episodes": 62, "episode_run_time": [47], "original_language": "en"
        }""")
        assertEquals(listOf(
            "Created by" to "Vince Gilligan", "Network" to "AMC", "First aired" to "20 January 2008",
            "Last aired" to "29 September 2013", "Status" to "Ended", "Seasons" to "5 seasons · 62 episodes",
            "Episode length" to "47m", "Language" to "English",
        ), DetailFacts.from(data, "series"))
    }

    @Test fun `a running show skips the last air date and says it is still running`() {
        val facts = DetailFacts.from(JSONObject("""{"status": "Returning Series", "last_air_date": "2026-10-01",
            "number_of_seasons": 1, "number_of_episodes": 1, "episode_run_time": []}"""), "series")
        assertEquals(listOf("Status" to "Still running", "Seasons" to "1 season · 1 episode"), facts)
    }
}
