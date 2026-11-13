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

    @Test fun `Easynews languages and picture size feed the link-list ranking`() {
        val tagged = JSONObject(payload.toString()).put("data", org.json.JSONArray().put(JSONObject(
            """{"0":"a","10":"Some.Movie.2024.WEB-DL","11":".mkv","14":"2h 1m","width":"3840","height":"1600","alangs":["eng","ita"],"slangs":["ger"]}""")))
        val item = api("{}").parse(tagged).single()
        assertEquals(2160, item.qualityHeight)
        assertTrue(item.detail, item.detail.contains("🌐 English, Italian") && item.detail.contains("💬 German"))
        val found = SourceLanguages.detect("${item.title} · ${item.detail}", listOf("en", "it", "de"))
        assertEquals(setOf("en", "it"), found.audio)
        assertEquals(setOf("de"), found.subtitles)
    }

    @Test fun `only files named for the title and its year are kept`() {
        val api = api("{}")
        val verity = PlaybackIdentity("tt1", "movie", "Verity")
        assertTrue(api.matches("Verity.2025.1080p.WEB-DL.x264.mkv", verity, 2025))
        assertTrue(api.matches("verity (2026) 2160p.mkv", verity, 2025))
        assertTrue(api.matches("Verity.1080p.WEB.mkv", verity, 2025))
        assertFalse(api.matches("Verity.2010.720p.mkv", verity, 2025))
        assertFalse(api.matches("The.Verity.Files.S01E02.mkv", verity, 2025))
        assertFalse(api.matches("Colleen.Hoover.Verity.Audiobook.Reading.2023.mkv", verity, 2025))
        assertFalse(api.matches("Verity.Bad.Night.2025.mkv", verity, 2025))
        val dune = PlaybackIdentity("tt2", "movie", "Dune: Part Two")
        assertTrue(api.matches("Dune.Part.Two.2024.2160p.WEB-DL.mkv", dune, 2024))
        assertFalse(api.matches("Dune.2021.2160p.mkv", dune, 2024))
        assertTrue(api.matches("The.Batman.2022.1080p.mkv", PlaybackIdentity("tt3", "movie", "The Batman"), 2022))
        assertTrue(api.matches("Amelie.2001.1080p.mkv", PlaybackIdentity("tt4", "movie", "Amélie"), 2001))
        val show = PlaybackIdentity("tt5", "series", "Severance", 2, 3)
        assertTrue(api.matches("Severance.S02E03.1080p.mkv", show, null))
        assertTrue(api.matches("Severance.2022.S02E03.2160p.mkv", show, null))
        assertFalse(api.matches("Severance.S02E04.1080p.mkv", show, null))
        assertFalse(api.matches("Corporate.Severance.S02E03.mkv", show, null))
        // "Dark" must not take "The Dark", a different show; a show called "The …" still finds names without it.
        val dark = PlaybackIdentity("tt5753856", "series", "Dark", 1, 2)
        assertTrue(api.matches("Dark.S01E02.Lies.1080p.NF.WEB-DL.mkv", dark, null))
        assertFalse(api.matches("The.Dark.S01E02.1080p.WEB.h264-CODSWALLOP.mkv", dark, null))
        val office = PlaybackIdentity("tt0386676", "series", "The Office", 2, 1)
        assertTrue(api.matches("The.Office.US.S02E01.mkv", office.copy(title = "The Office US"), null))
        assertTrue(api.matches("Office.S02E01.720p.mkv", office, null))
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
