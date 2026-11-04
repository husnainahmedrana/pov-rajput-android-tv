package dev.pov.tv

import dev.pov.tv.data.SourceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceAudioBadgeTest {
    @Test fun `audio tags from real release names`() {
        // Plavio / 4KHDHub style names.
        assertEquals(listOf("DTS-HD MA", "DDP", "5.1"),
            SourceBadges.audio("Inception (2010) 2160p UHD BluRay REMUX DV HDR 10bit HEVC [Hindi DDP 5.1 + English DTS-HD MA 5.1] x265"))
        assertEquals(listOf("DDP", "5.1", "Atmos"), SourceBadges.audio("Resident.Evil.2026.2160p.iTunes.WEB-DL.Hindi.DDP5.1.English.DDP5.1.SDR.Atmos.H.265.mkv"))
        assertEquals(listOf("DD", "5.1"), SourceBadges.audio("Inception 2010.4K-2160p SDR 10Bit BluRay Hindi DD5.1-English 5.1 HEVC x265"))
        assertEquals(listOf("AAC", "5.1"), SourceBadges.audio("Inception.2010.2160p.HDR.10Bit.BluRay.HIN-ENG.x265.AAC.5.1.ESubs.mkv"))
        assertEquals(listOf("TrueHD", "7.1", "Atmos"), SourceBadges.audio("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FGT"))
        assertEquals(listOf("DDP", "5.1"), SourceBadges.audio("Show.S01E02.1080p.WEB-DL.DD+5.1.H.264"))
        assertEquals(listOf("AAC", "2.0"), SourceBadges.audio("Movie.2026.720p.WEBRip.x264.AAC2.0"))
    }

    @Test fun `numbers in titles, years and codecs are not channels or codecs`() {
        assertEquals(emptyList<String>(), SourceBadges.audio("Blade.Runner.2049.2017.1080p.BluRay.H.265-Odd"))
        assertEquals(emptyList<String>(), SourceBadges.audio("Movie.2025.10.12.1080p.WEB-DL.H.264.ADDons"))
    }

    @Test fun `badges are grouped and the size badge carries the bitrate for its heat colour`() {
        val source = SourceItem("Movie.2026.2160p.BluRay.REMUX.DV.HDR10+.HEVC.10bit.TrueHD.7.1.Atmos.mkv", "fixture",
            sizeBytes = 50_000_000_000L, qualityHeight = 2160)
        val badges = SourceBadges.from(source, runtimeMinutes = 120)
        assertEquals(listOf("4K", "50.00 GB · ≈56 Mbps", "DV · HDR10+", "TrueHD · 7.1 · Atmos", "BLU-RAY · REMUX · HEVC · 10bit"), badges.map { it.label })
        assertEquals(SourceBadgeTone.SIZE, badges[1].tone)
        assertEquals("#FF7A45" to "#FF2E4D", SourceBadges.gradient(badges[1]))
        // No runtime: no bitrate, so the size badge is plain Aurora.
        val plain = SourceBadges.from(source)[1]
        assertNull(plain.mbps)
        assertEquals("#22E1FF" to "#9B6BFF", SourceBadges.gradient(plain))
    }

    @Test fun `sunset and aurora both use an emerald 1080p, and the size badge keeps its heat in both`() {
        val fullHd = SourceBadges.from(SourceItem("Movie.1080p.mkv", "fixture", sizeBytes = 6_000_000_000L, qualityHeight = 1080), runtimeMinutes = 120)
        assertEquals("#6CF5B8" to "#18B67C", SourceBadges.gradient(fullHd[0], BadgeStyle.AURORA))
        assertEquals("#9CF2C4" to "#22A87A", SourceBadges.gradient(fullHd[0], BadgeStyle.SUNSET))
        assertEquals(SourceBadges.gradient(fullHd[1], BadgeStyle.AURORA), SourceBadges.gradient(fullHd[1], BadgeStyle.SUNSET))
        val unknown = SourceBadges.from(SourceItem("Movie.1080p.mkv", "fixture", sizeBytes = 6_000_000_000L, qualityHeight = 1080))[1]
        assertEquals("#FF8A3D" to "#FF3D9A", SourceBadges.gradient(unknown, BadgeStyle.SUNSET))
    }

    @Test fun `grouped badges keep their separate tags for the Elite style`() {
        val badges = SourceBadges.from(SourceItem("Movie.2160p.BluRay.REMUX.DV.HDR10+.HEVC.TrueHD.7.1.Atmos.mkv", "fixture", qualityHeight = 2160))
        assertEquals(listOf("DV", "HDR10+"), badges.first { it.tone == SourceBadgeTone.HDR }.parts)
        assertEquals(listOf("TrueHD", "7.1", "Atmos"), badges.first { it.tone == SourceBadgeTone.AUDIO }.parts)
    }

    @Test fun `color by type gives each kind of badge its own flat colour`() {
        val badges = SourceBadges.from(SourceItem("Movie.2160p.BluRay.DV.HEVC.TrueHD.7.1.mkv", "fixture", sizeBytes = 5_000_000_000L, qualityHeight = 2160))
        assertEquals(listOf("#FFE34D", "#7FD6FF", "#F59BFF", "#5FE3CF", "#8DB6FF"), badges.map { SourceBadges.flatColor(it) })
    }
}
