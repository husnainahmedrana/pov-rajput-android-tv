package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvdbApiTest {
    private class Memory : TvdbApi.Cache {
        val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) { values[name] = value }
    }

    @Test fun `episodes come from TVDB by IMDb id, in aired order, specials kept, with full image links`() {
        val calls = mutableListOf<String>()
        val api = TvdbApi("key", Memory()) { method, url, _, _ ->
            calls += "$method $url"
            when {
                url.endsWith("/login") -> """{"status":"success","data":{"token":"abc"}}"""
                "/search/remoteid/tt0944947" in url -> """{"data":[{"series":{"id":121361,"name":"Game of Thrones"}}]}"""
                "/series/121361/episodes/default/eng?page=0" in url -> """{"data":{"episodes":[
                    {"seasonNumber":1,"number":2,"name":"The Kingsroad","aired":"2011-04-24","image":"/banners/episodes/121361/2.jpg","overview":"Ned travels."},
                    {"seasonNumber":1,"number":1,"name":"Winter Is Coming","aired":"2011-04-17","image":null,"overview":null},
                    {"seasonNumber":0,"number":1,"name":"Inside Game of Thrones","aired":"2010-12-05","image":"/banners/episodes/121361/0.jpg"}
                    ]},"links":{"next":null}}"""
                else -> error(url)
            }
        }
        val episodes = api.episodes("tt0944947")!!
        assertEquals(listOf(0 to 1, 1 to 1, 1 to 2), episodes.map { it.season to it.episode })
        assertEquals("Winter Is Coming", episodes[1].title)
        assertEquals("", episodes[1].thumbnail)
        assertEquals("https://artworks.thetvdb.com/banners/episodes/121361/2.jpg", episodes[2].thumbnail)
        assertEquals("Ned travels.", episodes[2].overview)
        // The login and the show's id are remembered.
        api.episodes("tt0944947")
        assertEquals(1, calls.count { it.endsWith("/login") })
        assertEquals(1, calls.count { "/search/remoteid" in it })
    }

    @Test fun `a show TVDB doesn't have gives null, and an expired token logs in again`() {
        val memory = Memory()
        var logins = 0; var first = true
        val api = TvdbApi("key", memory) { _, url, _, _ ->
            when {
                url.endsWith("/login") -> { logins++; """{"data":{"token":"t$logins"}}""" }
                "/search/remoteid/tt0000001" in url -> """{"data":[]}"""
                "/search/remoteid/tt0000002" in url -> if (first) { first = false; throw ApiException(401, "expired") } else """{"data":[{"series":{"id":5}}]}"""
                "/series/5/episodes" in url -> """{"data":{"episodes":[{"seasonNumber":1,"number":1,"name":"Pilot"}]},"links":{"next":null}}"""
                else -> error(url)
            }
        }
        assertNull(api.episodes("tt0000001"))
        assertEquals("0", memory.values["series:tt0000001"])
        assertEquals(listOf("Pilot"), api.episodes("tt0000002")!!.map { it.title })
        assertTrue(logins == 2)
    }
}
