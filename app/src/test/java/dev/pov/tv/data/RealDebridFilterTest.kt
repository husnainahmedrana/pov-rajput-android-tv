package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test

class RealDebridFilterTest {
    @Test fun `names real-debrid refuses`() {
        listOf("Movie.2024.1080p.WEB-DL.DDP5.1.H.264-GRP", "Show.S01E02.1080p.WEB.H264-GRP", "Show.S01E02.720p.WEB.x264-GRP",
            "Movie.2010.1080p.webrip.x265", "Movie.2001.BDRip.XviD", "Movie.HDRip", "Movie.1999.DVDRip", "Movie.2010.720p.BluRay.x264-GRP",
            "Movie.2010.1080p.BluRay.DTS-GRP", "Show.S01E01.HDTV.XviD-LOL", "Show.S01E01.hdtv.x264")
            .forEach { assertTrue(it, RealDebridFilter.refusedName(it)) }
    }

    @Test fun `names real-debrid takes`() {
        listOf("Movie.2024.1080p.web-dl.mkv", "Movie.2024.1080p.WEB.DL", "Movie.2024.WEBDL", "Show.S01E02.1080p.WEB.h264-GRACE",
            "Movie.2024.WEB.X264", "Movie.2010.1080p.BluRay.x265-GRP", "Movie.2010.2160p.REMUX.BluRay.DTS", "Movie.2010.BluRay-x264",
            "Show.S01E01.HDTV.H264", "Movie.2024.2160p.AMZN.WEB.DDP5.1.HEVC")
            .forEach { assertFalse(it, RealDebridFilter.refusedName(it)) }
    }

    @Test fun `list hides refused links only when real-debrid would serve them`() {
        val blocked = "Movie.2024.1080p.WEB-DL.mkv"
        val hash = "a".repeat(40)
        val rdOnly = listOf("realdebrid")
        assertTrue(RealDebridFilter.hides(SourceItem(blocked, "Torrentio", directUrl = "https://x/y", servedBy = "realdebrid"), emptyList()))
        assertFalse(RealDebridFilter.hides(SourceItem(blocked, "Torrentio", directUrl = "https://x/y", servedBy = "torbox"), emptyList()))
        assertTrue(RealDebridFilter.hides(SourceItem(blocked, "Torrentio", hash), rdOnly))
        assertFalse(RealDebridFilter.hides(SourceItem(blocked, "Torrentio", hash), listOf("realdebrid", "torbox")))
        assertFalse(RealDebridFilter.hides(SourceItem(blocked, "Torrentio", hash, cachedServices = listOf("premiumize")), rdOnly))
        assertFalse(RealDebridFilter.hides(SourceItem("Movie.2024.1080p.BluRay.x265.mkv", "Torrentio", hash), rdOnly))
    }

    @Test fun `the shared list rule keeps clean names and other services' links`() {
        val hash = "a".repeat(40)
        val refused = SourceItem("Movie.2024.1080p.WEB-DL.mkv", "Torrentio", hash)
        val clean = SourceItem("Movie.2024.2160p.BluRay.REMUX.mkv", "Torrentio", hash)
        val cachedElsewhere = SourceItem("Movie.2024.WEBRip.mkv", "Torrentio", hash, cachedServices = listOf("torbox"))
        val addonRd = SourceItem("Movie.2024.WEB-DL.mkv", "Torrentio", directUrl = "https://t/x", servedBy = "realdebrid")
        val all = listOf(refused, clean, cachedElsewhere, addonRd)
        assertEquals(listOf(clean, cachedElsewhere), RealDebridFilter.visible(all, listOf("realdebrid")))
        assertEquals(listOf(refused, clean, cachedElsewhere), RealDebridFilter.visible(all, listOf("realdebrid", "torbox")))
    }

    @Test fun `a season pack's own torrent name counts (From S01E01 inside a LostFilm WEB-DL pack)`() {
        val hash = "a".repeat(40)
        val pack = SourceItem("From.S01E01.Long.Days.Journey.Into.Night.mkv", "Torrentio", hash,
            torrentName = "Izvne / From / Sezon: 1 / Serii: 1-10 iz 10 [2022 WEB-DL 1080p] MVO (LostFilm) + Original + Sub (Rus Eng)")
        assertTrue(RealDebridFilter.refused(pack))
        assertTrue(RealDebridFilter.hides(pack, listOf("realdebrid")))
        assertFalse(RealDebridFilter.refused(SourceItem("From.S01E01.1080p.WEB.h264-GOSSIP.mkv", "Torrentio", hash,
            torrentName = "From.S01E01.1080p.WEB.h264-GOSSIP[rarbg]")))
    }

    @Test fun `refusal is the 451 or infringing_file answer`() {
        assertTrue(RealDebridFilter.refusal(ApiException(451, "x")))
        assertTrue(RealDebridFilter.refusal(ApiException(403, "x", "infringing_file")))
        assertFalse(RealDebridFilter.refusal(ApiException(403, "x")))
        assertFalse(RealDebridFilter.refusal(IllegalStateException("x")))
    }
}
