package dev.pov.tv

import dev.pov.tv.data.EpisodeData
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EpisodePresentationTest {
    @Test fun `seasons are unique and sorted with specials after normal seasons`() {
        val episodes = listOf(episode(3, 4), episode(0, 2), episode(1, 9), episode(3, 1), episode(1, 1), episode(0, 1))
        assertEquals(listOf(1, 3, 0), EpisodePresentation.seasons(episodes))
        assertEquals(1, EpisodePresentation.selectedSeason(episodes, null))
    }

    @Test fun `a valid preferred season including specials is restored`() {
        val episodes = listOf(episode(0), episode(1), episode(2))
        assertEquals(2, EpisodePresentation.selectedSeason(episodes, 2))
        assertEquals(0, EpisodePresentation.selectedSeason(episodes, 0))
    }

    @Test fun `missing preferred season falls back to the earliest regular season`() {
        val episodes = listOf(episode(4), episode(0), episode(2))
        assertEquals(2, EpisodePresentation.selectedSeason(episodes, 99))
        assertEquals(2, EpisodePresentation.selectedSeason(episodes, null))
    }

    @Test fun `only specials remains browsable and no episodes has no selected season`() {
        assertEquals(listOf(0), EpisodePresentation.seasons(listOf(episode(0), episode(0, 2))))
        assertEquals(0, EpisodePresentation.selectedSeason(listOf(episode(0)), null))
        assertTrue(EpisodePresentation.seasons(emptyList()).isEmpty())
        assertNull(EpisodePresentation.selectedSeason(emptyList(), 1))
    }

    @Test fun `air dates use exact instants including an offset and the release boundary`() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertTrue(EpisodePresentation.future("2026-10-22T12:00:01Z", now))
        assertFalse(EpisodePresentation.future("2026-10-22T12:00:00Z", now))
        assertFalse(EpisodePresentation.future("2026-10-22T11:59:59Z", now))
        assertTrue(EpisodePresentation.future("2026-10-22T08:00:01-04:00", now))
        assertFalse(EpisodePresentation.future("2026-10-22T08:00:00-04:00", now))
    }

    @Test fun `date-only releases use UTC and missing malformed dates remain usable`() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertTrue(EpisodePresentation.future("2026-10-23", now))
        assertFalse(EpisodePresentation.future("2026-10-22", now))
        for (value in listOf("", "unknown", "2026-02-30", "null")) assertFalse(EpisodePresentation.future(value, now))
    }

    @Test fun `the next episode follows the furthest one watched`() {
        val episodes = (1..80).map { episode(1, it) } + listOf(episode(0, 1))
        assertEquals(70, EpisodePresentation.nextAfterWatched(episodes, listOf(1 to 3, 1 to 69, 1 to 12))?.episode)
    }

    @Test fun `the next episode crosses into the next season and skips specials`() {
        val episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1), episode(2, 2), episode(0, 5))
        val next = EpisodePresentation.nextAfterWatched(episodes, listOf(1 to 2, 0 to 9))
        assertEquals(2, next?.season); assertEquals(1, next?.episode)
    }

    @Test fun `nothing watched, everything watched, or only unaired left gives no next episode`() {
        val now = Instant.parse("2026-11-14T00:00:00Z")
        val episodes = listOf(episode(1, 1), episode(1, 2), EpisodeData(1, 3, "Later", released = "2027-01-01"))
        assertNull(EpisodePresentation.nextAfterWatched(episodes, emptyList(), now))
        assertNull(EpisodePresentation.nextAfterWatched(episodes, listOf(1 to 2), now))
    }

    private fun episode(season: Int, number: Int = 1) = EpisodeData(season, number, "Episode $number")
}
