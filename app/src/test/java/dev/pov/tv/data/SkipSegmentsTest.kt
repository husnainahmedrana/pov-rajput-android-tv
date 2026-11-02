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
class SkipSegmentsTest {
    private val requests = mutableListOf<String>()
    private fun api(responses: Map<String, String>) = SkipSegmentsApi { url, _ ->
        requests += url
        responses.entries.firstOrNull { url.contains(it.key) }?.value ?: throw ApiException(404, "none")
    }

    @Test fun `TheIntroDB intro parts, recap and credits to the end`() {
        // Real shape (Severance S1E2, 2026-11-02): an intro in two touching parts and credits running to the end.
        val json = JSONObject("""{"tvdb_id":371980,"type":"tv","season":1,"episode":2,
            "intro":[{"start_ms":320575,"end_ms":399992},{"start_ms":401616,"end_ms":481033}],
            "recap":[{"start_ms":null,"end_ms":45000}],"credits":[{"start_ms":3167532,"end_ms":null}],"preview":[{"start_ms":3300000,"end_ms":null}]}""")
        val api = api(emptyMap())
        val cleaned = SkipSegmentsApi.clean(api.parseIntroDb(json), 3_300_000)
        assertEquals(listOf(SkipSegment(SkipKind.RECAP, 0, 45_000), SkipSegment(SkipKind.INTRO, 320_575, 481_033),
            SkipSegment(SkipKind.CREDITS, 3_167_532, null)), cleaned)
    }

    @Test fun `AniSkip openings and endings`() {
        val json = JSONObject("""{"found":true,"results":[{"interval":{"startTime":208,"endTime":298.556},"skipType":"op"},
            {"interval":{"startTime":1377,"endTime":1420.25},"skipType":"ed"},{"interval":{"startTime":0,"endTime":30},"skipType":"recap"}]}""")
        val segments = SkipSegmentsApi.clean(api(emptyMap()).parseAniSkip(json), 1_435_122)
        assertEquals(listOf(SkipSegment(SkipKind.RECAP, 0, 30_000), SkipSegment(SkipKind.INTRO, 208_000, 298_556),
            SkipSegment(SkipKind.CREDITS, 1_377_000, 1_420_250)), segments)
    }

    @Test fun `impossible segments are dropped`() {
        val segments = SkipSegmentsApi.clean(listOf(
            SkipSegment(SkipKind.INTRO, 10_000, 11_000),          // under 3 s
            SkipSegment(SkipKind.CREDITS, 60_000, null),          // credits in the first third
            SkipSegment(SkipKind.INTRO, 5_000_000, 5_100_000),    // after the end
            SkipSegment(SkipKind.CREDITS, 2_500_000, 2_699_500)), // ends at the end: becomes "to the end"
            2_700_000)
        assertEquals(listOf(SkipSegment(SkipKind.CREDITS, 2_500_000, null)), segments)
    }

    @Test fun `anime asks AniSkip through Kitsu's MyAnimeList mapping, shows ask TheIntroDB`() {
        val api = api(mapOf(
            "kitsu.app/api/edge/anime/42422/mappings" to """{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"40028"}}]}""",
            "api.aniskip.com/v2/skip-times/40028/1?" to """{"found":true,"results":[{"interval":{"startTime":208,"endTime":298.5},"skipType":"op"}]}""",
            "api.theintrodb.org/v4/media?imdb_id=tt0903747&season=1&episode=2" to """{"intro":[{"start_ms":314568,"end_ms":330969}]}"""))
        val anime = api.segments(PlaybackIdentity("tt2560140", "series", "Attack on Titan", 4, 1), 1_435_000, AnimeEpisodeRef("42422", 1))
        assertEquals(listOf(SkipSegment(SkipKind.INTRO, 208_000, 298_500)), anime)
        assertTrue(requests.any { it.contains("episodeLength=1435") })
        val show = api.segments(PlaybackIdentity("tt0903747", "series", "Breaking Bad", 1, 2), 2_900_000, null)
        assertEquals(listOf(SkipSegment(SkipKind.INTRO, 314_568, 330_969)), show)
        assertTrue(requests.last().contains("duration_ms=2900000"))
    }

    @Test fun `anime without AniSkip data falls back to TheIntroDB`() {
        val api = api(mapOf(
            "kitsu.app/api/edge/anime/7/mappings" to """{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"77"}}]}""",
            "api.theintrodb.org/v4/media?imdb_id=tt1234567&season=1&episode=3" to """{"intro":[{"start_ms":60000,"end_ms":150000}]}"""))
        assertEquals(listOf(SkipSegment(SkipKind.INTRO, 60_000, 150_000)),
            api.segments(PlaybackIdentity("tt1234567", "series", "Some Anime", 1, 3), 1_400_000, AnimeEpisodeRef("7", 3)))
    }
}
