package dev.pov.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSeekPreviewsTest {
    @Test fun positionsGoCoarseToFineAndCoverEverything() {
        val plan = BuiltInSeekPreviews.coarseToFine(97_000L * 60, 96)
        assertEquals(96, plan.size)
        assertEquals(96, plan.distinct().size)
        val step = 97_000L * 60 / 97
        assertEquals(48 * step, plan.first())                              // the middle first
        // The first quarter of the plan already spans the film from the first tenth to the last.
        val early = plan.take(24)
        assertTrue(early.minOrNull()!! < 97_000L * 6 && early.maxOrNull()!! > 97_000L * 54)
    }

    @Test fun aTenSecondGridStillSpansTheFilmFirst() {
        val duration = 7_200_000L                                         // 2 hours: 719 slots of 10 s
        val plan = BuiltInSeekPreviews.coarseToFine(duration, 719)
        assertEquals(719, plan.distinct().size)
        val first = plan.take(96)
        assertTrue(first.minOrNull()!! < duration / 10 && first.maxOrNull()!! > duration * 9 / 10)
    }

    @Test fun aLightCopyIsChosenAndChecked() {
        fun link(title: String, url: String, height: Int, gb: Double) =
            dev.pov.tv.data.SourceItem(title, "fixture", directUrl = url, qualityHeight = height, sizeBytes = (gb * 1e9).toLong())
        val film = "https://cdn.example/film.mkv"
        val links = listOf(
            link("Movie.2026.2160p.UHD.BluRay.REMUX.DV.HEVC", film, 2160, 60.0),
            link("Movie.2026.1080p.BluRay.x265.10bit.HDR", "https://cdn.example/hevc.mkv", 1080, 5.0),
            link("Movie.2026.1080p.WEB-DL.H264", "https://cdn.example/avc.mkv", 1080, 4.0),
            link("Movie.2026.720p.WEBRip.x265", "https://cdn.example/small.mkv", 720, 1.0),
            link("Movie.2026.1080p.NZB", "https://indexer.example/get?id=1#pov-nzb", 1080, 3.0))
        val copies = LightCopy.from(links, film, 60_000_000_000L).map { it.url }
        // H.264 first, then the smaller one; never the film, an HDR/10-bit copy or a Usenet link.
        assertEquals(listOf("https://cdn.example/avc.mkv", "https://cdn.example/small.mkv"), copies)
        assertTrue(LightCopy.sameCut(7_200_000, 7_215_000))
        assertTrue(!LightCopy.sameCut(7_200_000, 8_100_000))                 // an extended cut
    }
}
