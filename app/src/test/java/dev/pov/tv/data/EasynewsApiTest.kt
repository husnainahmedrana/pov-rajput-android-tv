package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EasynewsApiTest {
    private fun api(response: String, seen: MutableList<Pair<String, Map<String, String>>> = mutableListOf()) =
        EasynewsApi({ "user" to "secret" }, { url, headers -> seen += url to headers; response })

    private val payload = JSONObject("""{"downURL":"https://members.easynews.com/dl","dlFarm":"auto","dlPort":"443","data":[
        {"0":"abc123","10":"Some.Movie.2024.2160p.WEB-DL","11":".mkv","14":"2h 1m","rawSize":16000000000},
        {"0":"def456","10":"Some.Movie.2024.sample","11":".mkv","14":"45s","rawSize":1000},
        {"0":"ghi789","10":"Infected.File","11":".mkv","14":"1h 50m","virus":true},
        {"0":"","10":"No.Hash","11":".mkv","14":"1h 50m"}]}""")

    @Test fun `results become direct Easynews links and skip samples, viruses and broken rows`() {
        val items = api("{}").parse(payload)
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Some.Movie.2024.2160p.WEB-DL.mkv", item.title)
        assertEquals("https://members.easynews.com/dl/auto/443/abc123.mkv/Some.Movie.2024.2160p.WEB-DL.mkv", item.directUrl)
        assertEquals(16000000000L, item.sizeBytes)
        assertEquals(2160, item.qualityHeight)
        assertEquals("Easynews", item.provider)
        assertTrue(item.headers.isEmpty())
    }

    @Test fun `a chosen download server replaces the farm Easynews names`() {
        val chosen = EasynewsApi({ "user" to "secret" }, { _, _ -> "{}" }, server = { "iad" })
        assertEquals("https://members.easynews.com/dl/iad/443/abc123.mkv/Some.Movie.2024.2160p.WEB-DL.mkv", chosen.parse(payload).single().directUrl)
        val unknown = EasynewsApi({ "user" to "secret" }, { _, _ -> "{}" }, server = { "../x" })
        assertEquals("https://members.easynews.com/dl/auto/443/abc123.mkv/Some.Movie.2024.2160p.WEB-DL.mkv", unknown.parse(payload).single().directUrl)
    }

    @Test fun `a download address outside Easynews gives no results`() {
        val outside = JSONObject(payload.toString()).put("downURL", "https://evil.example.com/dl")
        assertTrue(api("{}").parse(outside).isEmpty())
    }

    @Test fun `episodes search with SxxEyy and drop other episodes`() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val response = JSONObject(payload.toString()).put("data", org.json.JSONArray()
            .put(JSONObject("""{"0":"a","10":"Show.S01E02.1080p","11":".mkv","14":"45m"}"""))
            .put(JSONObject("""{"0":"b","10":"Show.S01E03.1080p","11":".mkv","14":"45m"}""")))
        val items = api(response.toString(), seen).search(PlaybackIdentity("tt1", "series", "Show", 1, 2))
        assertEquals(listOf("Show.S01E02.1080p.mkv"), items.map { it.title })
        assertTrue(seen.single().first.contains("gps=Show%20S01E02"))
        assertTrue(seen.single().second["Authorization"]!!.startsWith("Basic "))
    }

    @Test fun `the login is only ever sent to Easynews over HTTPS`() {
        assertTrue(Easynews.isHost("https://members.easynews.com/dl/x"))
        assertTrue(Easynews.isHost("https://easynews.com/"))
        assertFalse(Easynews.isHost("http://members.easynews.com/dl/x"))
        assertFalse(Easynews.isHost("https://easynews.com.evil.example/x"))
        assertFalse(Easynews.isHost("https://noteasynews.com/x"))
        assertFalse(Easynews.isHost("https://user:pw@members.easynews.com/x"))
        assertFalse(Easynews.isHost("https://members.easynews.com:8443/x"))
    }

    @Test fun `basic auth header is built from the saved login`() {
        assertEquals("Basic dXNlcjpzZWNyZXQ=", Easynews.auth("user", "secret")["Authorization"])
    }
}
