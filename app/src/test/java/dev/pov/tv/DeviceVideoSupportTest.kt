package dev.pov.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeviceVideoSupportTest {
    // A strong phone: 4K hardware HEVC with Main 10, 4K VP9, software-only AV1, no Dolby Vision.
    private val strong = DeviceVideoSupport.Caps(mapOf("hevc" to 2160, "avc" to 2160, "vp9" to 2160, "av1" to 1080), hevc10 = true, dolbyVision = false)
    // A budget phone: 1080p hardware only, 8-bit HEVC, no AV1 decoder at all.
    private val budget = DeviceVideoSupport.Caps(mapOf("hevc" to 1080, "avc" to 1080), hevc10 = false, dolbyVision = false)
    private fun problem(name: String, height: Int?, caps: DeviceVideoSupport.Caps) = DeviceVideoSupport.problem(name, height, caps)

    @Test fun `ordinary links on a capable phone are never marked`() {
        assertNull(problem("Movie.2010.2160p.WEB-DL.HDR10.HEVC", 2160, strong))
        assertNull(problem("Movie.2010.1080p.BluRay.x264", 1080, budget))
        assertNull(problem("Movie 1080p 10bit HEVC", 1080, budget))
        assertNull(problem("Movie 2160p", 2160, strong))
        assertNull(problem("anything", 2160, DeviceVideoSupport.Caps(emptyMap(), false, false)))
    }

    @Test fun `Dolby Vision without an HDR10 picture needs a DV decoder, DV with HDR10 does not`() {
        assertTrue(problem("Show.S01E01.2160p.WEB-DL.DV.HEVC", 2160, strong)!!.startsWith("DOLBY VISION ONLY"))
        assertNull(problem("Movie.2160p.BluRay.REMUX.DV.HDR10.HEVC", 2160, strong))
        assertNull(problem("Show.S01E01.2160p.WEB-DL.DV.HEVC", 2160, strong.copy(dolbyVision = true)))
    }

    @Test fun `a budget phone marks 4K, 10-bit 4K and codecs it has no decoder for`() {
        assertEquals("ABOVE THIS TV'S 1080p HEVC LIMIT", problem("Movie 2160p HEVC", 2160, budget))
        assertEquals("AV1 · NOT SUPPORTED ON THIS TV", problem("Movie 1080p AV1", 1080, budget))
        assertEquals("ABOVE THIS TV'S 1080p AV1 LIMIT", problem("Movie 2160p AV1", 2160, strong))
        assertTrue(problem("Movie 1440p HDR HEVC", 1440, budget.copy(maxHeight = mapOf("hevc" to 2160)))!!.startsWith("10-BIT HEVC"))
    }

    @Test fun `episode air dates read as aired or airs`() {
        val today = LocalDate.of(2026, 11, 7)
        assertEquals("Aired 20 January 2008", EpisodePresentation.airLine(LocalDate.of(2008, 1, 20), today))
        assertEquals("Airs 25 June 2027", EpisodePresentation.airLine(LocalDate.of(2027, 6, 25), today))
        assertEquals("", EpisodePresentation.airLine(null, today))
        assertEquals(LocalDate.of(2008, 1, 21), EpisodePresentation.releaseDate("2008-01-21T05:00:00.000Z"))
        assertEquals("20 Jan 2008", EpisodePresentation.shortAirDate(LocalDate.of(2008, 1, 20)))
    }
}
