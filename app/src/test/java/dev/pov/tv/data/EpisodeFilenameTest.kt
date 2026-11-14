package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFilenameTest {
    private val episode = PlaybackIdentity("tt0903747", "series", "Example Show", 1, 2)

    @Test fun selectsRequestedEpisodeInsteadOfLargestFile() {
        val files = listOf(
            VideoCandidate(10, "Example.Show.S01E01.mkv", 8_000_000),
            VideoCandidate(20, "Example.Show.S01E02.mkv", 2_000_000)
        )
        assertEquals(20L, EpisodeFilename.select(files, "Example Show Season 1", episode).id)
    }

    @Test fun sampleIsNeverSelectedAsTheOnlyVideo() {
        expectFailure { EpisodeFilename.select(listOf(VideoCandidate(0, "sample.mkv", 1_000)), "Sample", episode) }
    }

    @Test fun duplicateEpisodeVersionsAreAmbiguousWithoutExactFilename() {
        val files = listOf(
            VideoCandidate(1, "Example.Show.S01E02.720p.mkv", 2_000),
            VideoCandidate(2, "Example.Show.S01E02.1080p.mkv", 4_000)
        )
        expectFailure { EpisodeFilename.select(files, "Example Show", episode) }
        assertEquals(2L, EpisodeFilename.select(files, "Example.Show.S01E02.1080p.mkv", episode).id)
    }

    @Test fun filmsOfferedForAnEpisodeAreSpotted() {
        fun movie(name: String) = EpisodeFilename.namesAMovie(name)
        // The report: a 2026 film named "Dark" listed first for Dark S1 E2 (Telugu first).
        assertTrue(movie("www.5MovieRulz.company - Dark (2026) 1080p HQ HDRip - x264 - [Telugu + Hindi] - (DD+5.1 - 640Kbps & AAC 2.0) - 3GB - ESub.mkv"))
        assertTrue(movie("Dark.2026.1080p.WEB-DL.x264.mkv"))
        // Real episode links from the same list stay.
        assertFalse(movie("Dark.2017.S01E02.MULTi.2160p.NF.WEB-DL.SDR.DDP5.1.H.265-HONE.mkv"))
        assertFalse(movie("Dark (2017) S01E02 Lies (1080p NF WEB-DL H264 SDR DDP 5.1 DUAL-DarQ).mkv"))
        assertFalse(movie("Dark S01E02 Lies 1080p NF WEB-DL DUAL DDP5 1 Atmos H 264-Kitsune.mkv"))
        // Packs, other numbering, dated episodes and names without a year stay.
        assertFalse(movie("Dark.2017.S01.COMPLETE.1080p.NF.WEB-DL.mkv"))
        assertFalse(movie("Dark Season 1 (2017) 1080p"))
        assertFalse(movie("Dark 2017 1x02 1080p"))
        assertFalse(movie("[SubsPlease] Frieren - 02 (1080p) 2023.mkv"))
        assertFalse(movie("The.Daily.Show.2026.11.14.1080p.WEB.h264.mkv"))
        assertFalse(movie("Dark Staffel 1 Folge 2 2017 German"))
        assertFalse(movie("Dark Lies 1080p WEB-DL"))
    }

    @Test fun otherShowsMatchedLooselyOnAShortTitleAreSpotted() {
        fun other(name: String, show: String = "Dark") = EpisodeFilename.namesAnotherShow(name, show)
        // Real links for "Dark" (the screenshot from Torrentio).
        assertFalse(other("Dark.S01E02.XviD-AFG"))
        assertFalse(other("dark.s01e02.webrip.x264-strife.mkv"))
        assertFalse(other("DARK.S01E02.Lies.2160p.NF.WEBRip.DDP5.1.x264-NTb"))
        assertFalse(other("Dark.2017.S01E02.1080p.mkv"))
        assertFalse(other("Dark.S01.GERMAN.1080p.NF.WEB.DDP5.1.Atmos.x264-NTb"))
        assertFalse(other("Тьма / Dark [S01] (2017) WEBRip 1080p"))
        // Other shows that turned up for "Dark".
        assertTrue(other("Dark Angel 2016 S01E02 HDTV x264-RiVER"))
        assertTrue(other("Darkness S01E02 WEB x264-TBS"))
        assertTrue(other("Web.of.Darkness.S01E02.Night.of.the.Zombies.XviD-AFG.avi"))
        assertTrue(other("The Dark S01E02 1080p WEB h264-CODSWALLOP EZTV"))
        // Shows with "The", region codes and longer official names keep their links.
        assertFalse(other("The.Office.US.S02E01.mkv", "The Office"))
        assertFalse(other("Office.S02E01.720p.mkv", "The Office"))
        assertFalse(other("Marvels.Daredevil.S01E02.1080p.mkv", "Daredevil"))
        assertFalse(other("Las.Chicas.del.Cable.S01E02.1080p.mkv", "Cable Girls"))
        assertTrue(other("House.of.the.Dragon.S01E02.1080p.mkv", "House"))
    }

    @Test fun wrongSingleEpisodeIsRejected() {
        expectFailure {
            EpisodeFilename.select(listOf(VideoCandidate(0, "Example.Show.S02E02.mp4", 2_000)), "Example Show", episode)
        }
    }

    @Test fun episodeRangeAndConsecutiveEpisodeTagsIncludeRequestedEpisode() {
        assertFalse(EpisodeFilename.isExplicitMismatch("Example.S01E01-E03.mkv", 1, 2))
        assertFalse(EpisodeFilename.isExplicitMismatch("Example.S01E01E02.mkv", 1, 2))
        assertTrue(EpisodeFilename.isExplicitMismatch("Example.S01E01E02.mkv", 1, 3))
        assertFalse(EpisodeFilename.isExplicitMismatch("Example.1x02.mkv", 1, 2))
        assertTrue(EpisodeFilename.isExplicitMismatch("Example.2x02.mkv", 1, 2))
    }

    @Test fun multiMoviePackRequiresAnExactFilename() {
        val movie = PlaybackIdentity("tt0133093", "movie", "The Matrix")
        val files = listOf(VideoCandidate(1, "The.Matrix.1999.mkv", 3_000), VideoCandidate(2, "The.Matrix.Reloaded.mkv", 4_000))
        expectFailure { EpisodeFilename.select(files, "The Matrix Collection", movie) }
        assertEquals(1L, EpisodeFilename.select(files, "The Matrix 1999", movie).id)
    }

    @Test fun legitimateTrailerParkTitleIsPlayable() {
        val movie = PlaybackIdentity("tt0384254", "movie", "Trailer Park Boys")
        assertEquals(1L, EpisodeFilename.select(listOf(VideoCandidate(1, "Trailer.Park.Boys.2006.mkv", 3_000)), movie.title, movie).id)
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalStateException) { failed = true }
        assertTrue("Expected an ambiguous or mismatched video to be rejected", failed)
    }
}
