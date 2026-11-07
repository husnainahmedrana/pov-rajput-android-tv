package dev.pov.tv

import dev.pov.tv.data.EpisodeData
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SeasonWatchedUpdate
import dev.pov.tv.data.WatchedState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SeasonWatchedSelectionTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private fun show(vararg episodes: EpisodeData) = MediaItemData("tt0903747", "series", "Show", episodes = episodes.toList())
    private fun ep(season: Int, number: Int, released: String = "2020-01-01") = EpisodeData(season, number, "E$number", released)

    @Test fun `only the chosen season's aired unwatched episodes are pending, in order`() {
        val item = show(ep(1, 3), ep(1, 1), ep(1, 2), ep(2, 1), ep(1, 4, "2027-01-01"), ep(1, 2))
        val watched = WatchedState(episodes = setOf(WatchedState.key("tt0903747", 1, 2)))
        val pending = SeasonWatchedSelection.pending(item, 1, watched, now)
        assertEquals(listOf(1, 3), pending.map { it.episode })
        assertTrue(pending.all { it.season == 1 && it.type == "series" && it.imdbId == "tt0903747" })
    }

    @Test fun `episodes without a release date count as aired and specials are a season`() {
        val item = show(ep(0, 1, ""), ep(0, 2), ep(1, 1))
        assertEquals(listOf(1, 2), SeasonWatchedSelection.pending(item, 0, WatchedState.EMPTY, now).map { it.episode })
    }

    @Test fun `a fully watched season has nothing pending`() {
        val item = show(ep(1, 1), ep(1, 2))
        val watched = WatchedState(episodes = setOf(WatchedState.key("tt0903747", 1, 1), WatchedState.key("tt0903747", 1, 2)))
        assertTrue(SeasonWatchedSelection.pending(item, 1, watched, now).isEmpty())
    }

    @Test fun `update body lists exactly one season and its episodes`() {
        val identity = PlaybackIdentity("tt0903747", "series", "Show")
        val simkl = SeasonWatchedUpdate.body(identity, 2, listOf(3, 1, 3), weTrakr = false)
        val season = simkl.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons")
        assertEquals(1, season.length())
        assertEquals(2, season.getJSONObject(0).getInt("number"))
        val episodes = season.getJSONObject(0).getJSONArray("episodes")
        assertEquals(listOf(1, 3), (0 until episodes.length()).map { episodes.getJSONObject(it).getInt("number") })
        assertFalse(episodes.getJSONObject(0).has("status"))
        assertFalse(simkl.has("allow_rewatch"))

        val weTrakr = SeasonWatchedUpdate.body(identity, 2, listOf(1), weTrakr = true)
        assertEquals("watched", weTrakr.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0)
            .getJSONArray("episodes").getJSONObject(0).getString("status"))
        assertFalse(weTrakr.getBoolean("allow_rewatch"))
    }
}
