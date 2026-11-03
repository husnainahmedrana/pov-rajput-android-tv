package dev.pov.tv

import dev.pov.tv.data.AddonBadges
import dev.pov.tv.data.SourceItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceNameTest {
    @Test fun `CNCVerse links show the release name, with the site and server underneath`() {
        // A real CNCVerse Bridge stream for Resident Evil (2026).
        val cnc = SourceItem(
            title = "V-Cloud[FSL Server] Resident.Evil.2026.2160p.iTunes.WEB-DL.Hindi.DDP5.1.English.DDP5.1.SDR.Atmos.H.265-1VeGamovies.tw.mkv[8.8 GB]",
            provider = "Indian links", directUrl = "https://example.com/v.mkv", addon = AddonBadges.CNCVERSE,
            detail = "🎬VegaMoviesProvider ❄️ 4K • CNCVerse Bridge · 🍿 Resident Evil · 🎞️ WEB-DL • HEVC · 🛰️ Source: VegaMoviesProvider • V-Cloud[FSL Server] · 💾 8.8 GB")
        assertEquals("Resident.Evil.2026.2160p.iTunes.WEB-DL.Hindi.DDP5.1.English.DDP5.1.SDR.Atmos.H.265-1VeGamovies.tw.mkv", SourceBadges.name(cnc))
        assertEquals("VegaMovies · V-Cloud FSL Server", SourceBadges.origin(cnc))
        // Real PenguPlay streams: machine text after "|^|", or a file name that is only machine text.
        val penguDetail = "🐧 PenguPlay ❄️ 4K • 4KHDHub · R2 · 🍿 Resident Evil (2026) · 🎞️ 4K • MKV • WEB-DL • HDR10+ • DV • HEVC • DDP 5.1 Atmos • ~26.6 Mbps · 🛰️ Source: 4KHDHub · R2 · 💾 17.65 GB"
        val pengu = SourceItem("Resident Evil (2026) 2160p HDR DV MA WEB-DL HEVC x265 [Hindi-Tamil-Telugu AMZN DDP 5.1 640kbps + English DDP Atmos 5.1] ESub-(HONE-4kHdHub).mkv|^|4KMKVWEB-DLHDR10+DVHEVCDDP5.1Atmos~26.6Mbps4kmkvweb-dlhdr10+dvhevcddp5.1atmos~26.6mbps.pad-4KHDHub",
            "My pengu", directUrl = "https://example.com/p.mkv", addon = AddonBadges.PENGUPLAY, detail = penguDetail)
        assertEquals("Resident Evil (2026) 2160p HDR DV MA WEB-DL HEVC x265 [Hindi-Tamil-Telugu AMZN DDP 5.1 640kbps + English DDP Atmos 5.1] ESub-(HONE-4kHdHub).mkv", SourceBadges.name(pengu))
        assertEquals("4KHDHub · R2", SourceBadges.origin(pengu))
        val bare = SourceItem("4KWEB-DLHDR10+DVMKV~24.4Mbps4kweb-dlhdr10+dvmkv~24.4mbps.pad-MoviesDrives", "My pengu",
            directUrl = "https://example.com/q.mkv", addon = AddonBadges.PENGUPLAY,
            detail = "🐧 PenguPlay ❄️ 4K • MoviesDrives · FSL · 🍿 Resident Evil (2026) · 🎞️ 4K • WEB-DL • HDR10+ • DV • MKV • ~24.4 Mbps · 🛰️ Source: MoviesDrives · FSL · 💾 16.16 GB · 🎧 Audio: Hindi, English")
        assertEquals("Resident Evil (2026) · 4K · WEB-DL · HDR10+ · DV · MKV", SourceBadges.name(bare))
        assertEquals("MoviesDrives · FSL", SourceBadges.origin(bare))
        // Other add-ons keep their name and the add-on's name.
        val other = SourceItem("Movie.2026.2160p.WEB-DL.mkv", "Torrentio", infoHash = "a".repeat(40))
        assertEquals("Movie.2026.2160p.WEB-DL.mkv", SourceBadges.name(other))
        assertEquals("Torrentio", SourceBadges.origin(other))
    }
}
