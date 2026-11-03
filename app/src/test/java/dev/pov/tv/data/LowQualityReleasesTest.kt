package dev.pov.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowQualityReleasesTest {
    @Test fun theatreCopiesMatch() {
        listOf(
            "Resident Evil (2026) 1080p HQ HDRip - x264 - [Hindi + Eng] - (DD+5.1 - 640Kbps) - 2.2GB - ESub.mkv",
            "Movie.2026.HDCAM.x264-GROUP", "Movie 2026 CAMRip 720p", "Movie.2026.CAM.Rip", "Movie 2026 HD-TS 1080p",
            "Movie.2026.TELESYNC.x264", "Movie (2026) PreDVDRip", "Movie.2026.HDTC.720p", "Movie.2026.DVDSCR.XviD",
            "Movie 2026 TS 480p", "Movie.2026.WORKPRINT", "Movie.2026.SCREENER", "Movie 2026 HDRip 720p",
        ).forEach { assertTrue(it, LowQualityReleases.matches(it)) }
    }

    @Test fun realReleasesAreKept() {
        listOf(
            "Resident.Evil.2026.2160p.HDR10Plus.DV.WEBRip.6CH.x265.HEVC-PSA.mkv",
            "Scream.2022.1080p.BluRay.x264", "Catch.Me.If.You.Can.2002.1080p.WEB-DL", "Movie.2026.2160p.WEB-DL.DDP5.1.Atmos",
            "Movie 2026 1080p BDRip x265", "Movie.2026.1080p.BluRay.m2ts", "Movie.2026.REMUX.ts", "Tcell.2026.1080p.WEB",
            "Movie.2026.1080p.AMZN.WEB-DL.DDP5.1.H.264-FLUX",
        ).forEach { assertFalse(it, LowQualityReleases.matches(it)) }
    }
}
