package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkRulesTest {
    @Test fun `release group is read only from the release tag`() {
        assertEquals("FLUX", LinkRules.releaseGroup("Movie.2024.1080p.WEB-DL.DDP5.1.H.264-FLUX.mkv"))
        assertEquals("PSA", LinkRules.releaseGroup("Movie.2024.1080p.x265-PSA[TGx]"))
        assertEquals("SubsPlease", LinkRules.releaseGroup("[SubsPlease] Show - 01 (1080p) [ABCD1234].mkv"))
        assertEquals("Tigole", LinkRules.releaseGroup("Movie (2020) (1080p BluRay x265 10bit Tigole)"))
        // WEB-DL in the middle of a name is not a group.
        assertNull(LinkRules.releaseGroup("Movie 2024 1080p WEB-DL x264"))
    }

    @Test fun `own words rank first in their order, then switches, then trusted groups, then the pattern`() {
        val rules = LinkRules.Rules(preferWords = listOf("PSA", "FLUX"), preferPresets = setOf("remux", LinkRules.GROUPS), preferPattern = "x265.*10bit")
        val psa = rules.rank("Movie.2024.1080p.x265-PSA.mkv")
        val flux = rules.rank("Movie.2024.2160p.WEB-DL-FLUX.mkv")
        val remux = rules.rank("Movie.2024.2160p.BluRay.REMUX-XYZ.mkv")
        val group = rules.rank("Movie.2024.1080p.WEB-DL-NTb.mkv")
        val pattern = rules.rank("Movie.2024.1080p.x265.10bit-ABC.mkv")
        val none = rules.rank("Movie.2024.720p.HDTV-XYZ.mkv")
        assertTrue(psa < flux && flux < remux && remux < group && group < pattern && pattern < none)
        assertEquals(Int.MAX_VALUE, none)
    }

    @Test fun `words match whole words in any case, and group sets switch off`() {
        val rules = LinkRules.Rules(preferWords = listOf("psa"))
        assertTrue(rules.preferred("Movie-PSA.mkv"))
        assertFalse(rules.preferred("PSALM.2024.1080p.mkv"))
        val noWeb = LinkRules.Rules(preferPresets = setOf(LinkRules.GROUPS), groupSets = setOf("hevc"))
        assertFalse(noWeb.preferred("Movie.WEB-DL-FLUX.mkv"))
        assertTrue(noWeb.preferred("Movie.x265-PSA.mkv"))
        // A group name that is an ordinary word only counts as the release tag.
        val bluray = LinkRules.Rules(preferPresets = setOf(LinkRules.GROUPS), groupSets = setOf("bluray"))
        assertFalse(bluray.preferred("Don.2006.1080p.WEB-DL-XYZ.mkv"))
        assertTrue(bluray.preferred("Movie.2006.1080p.BluRay.x264-DON.mkv"))
    }

    @Test fun `filters hide by switch, word and pattern`() {
        val rules = LinkRules.Rules(hidePresets = setOf("threed", "sample"), hideWords = listOf("YIFY"), hidePattern = "hindi.*dub")
        assertTrue(rules.hides("Movie.2024.3D.HSBS.1080p.mkv"))
        assertTrue(rules.hides("Movie.2024.1080p.Sample.mkv"))
        assertTrue(rules.hides("Movie.2024.1080p.WEBRip.YIFY.mp4"))
        assertTrue(rules.hides("Movie 2024 Hindi Dubbed 720p"))
        assertFalse(rules.hides("Movie.2024.1080p.WEB-DL-FLUX.mkv"))
        assertTrue(rules.hideActive)
        assertFalse(LinkRules.Rules().hideActive)
    }

    @Test fun `hidden release groups only count when switched on and only as the release tag`() {
        val off = LinkRules.Rules()
        assertFalse(off.hides("Movie.2024.1080p.WEBRip.x264-YIFY.mp4"))
        val on = LinkRules.Rules(hidePresets = setOf(LinkRules.HIDE_GROUPS))
        assertTrue(on.hides("Movie.2024.1080p.WEBRip.x264-YIFY.mp4"))
        assertFalse(on.hides("Evo.Morales.2024.1080p.WEB-DL-FLUX.mkv"))
        assertFalse(on.copy(hideGroupSets = emptySet()).hides("Movie.2024.1080p.WEBRip.x264-YIFY.mp4"))
        assertEquals(on, LinkRules.Rules.fromJson(on.toJson()))
        assertTrue(LinkRules.Rules(hidePresets = setOf("remux")).hides("Movie.2024.2160p.BluRay.REMUX-FraMeSToR.mkv"))
    }

    @Test fun `movie links naming another year are another film with the same name`() {
        assertTrue(LinkRules.otherYear("Street.Fighter.1994.1080p.BluRay.x264-GROUP.mkv", "Street Fighter", 2026))
        assertTrue(LinkRules.otherYear("Street Fighter (1994) 720p", "Street Fighter", 2026))
        assertFalse(LinkRules.otherYear("Street.Fighter.2026.HDCAM.x264", "Street Fighter", 2026))
        assertFalse(LinkRules.otherYear("Street.Fighter.2025.1080p.WEB-DL", "Street Fighter", 2026))
        assertFalse(LinkRules.otherYear("Street Fighter 1080p WEB-DL", "Street Fighter", 2026))
        assertFalse(LinkRules.otherYear("1917.2019.2160p.UHD.BluRay.mkv", "1917", 2019))
        assertFalse(LinkRules.otherYear("Blade.Runner.2049.2017.1080p.mkv", "Blade Runner 2049", 2017))
        assertTrue(LinkRules.otherYear("Blade.Runner.1982.Final.Cut.1080p.mkv", "Blade Runner 2049", 2017))
        assertTrue(LinkRules.Rules().hideOtherYears)
        assertFalse(LinkRules.Rules.fromJson(LinkRules.Rules(hideOtherYears = false).toJson()).hideOtherYears)
    }

    @Test fun `a switched-off group stays in its list but no longer counts`() {
        val on = LinkRules.Rules(preferPresets = setOf(LinkRules.GROUPS), groupSets = setOf("web1"))
        assertTrue(on.preferred("Movie.WEB-DL-FLUX.mkv"))
        val off = on.copy(disabledGroups = mapOf("web1" to setOf("flux")))
        assertFalse(off.preferred("Movie.WEB-DL-FLUX.mkv"))
        assertTrue(off.preferred("Movie.WEB-DL-NTb.mkv"))
        assertEquals(off.groups(LinkRules.GROUP_SETS.first { it.id == "web1" }).size, on.groups(LinkRules.GROUP_SETS.first { it.id == "web1" }).size)
        assertEquals(off, LinkRules.Rules.fromJson(off.toJson()))
        assertTrue(off.matchesOwnWord("x") == false)
    }

    @Test fun `qualities can be switched off and the old 720p switch carries over`() {
        val rules = LinkRules.Rules(hiddenQualities = setOf("720", "sd"))
        assertTrue(rules.hidesQuality(720)); assertTrue(rules.hidesQuality(480)); assertFalse(rules.hidesQuality(1080)); assertFalse(rules.hidesQuality(null))
        assertEquals(rules, LinkRules.Rules.fromJson(rules.toJson()))
        val old = LinkRules.Rules.fromJson("""{"hidePresets":["lowres","threed"]}""")
        assertEquals(setOf("720", "sd"), old.hiddenQualities); assertEquals(setOf("threed"), old.hidePresets)
    }

    @Test fun `bad patterns are refused with a reason and rules survive saving`() {
        assertTrue(LinkRules.pattern("x265(").isFailure)
        assertTrue(LinkRules.pattern("a".repeat(LinkRules.MAX_PATTERN + 1)).isFailure)
        assertNull(LinkRules.pattern("  ").getOrThrow())
        val rules = LinkRules.Rules(preferWords = listOf("PSA"), preferPresets = setOf("hevc"), groupSets = setOf("web1"),
            customGroups = mapOf("web1" to listOf("FLUX", "NTb")), preferPattern = "x265", hidePresets = setOf("dubbed"), hideWords = listOf("YIFY"), hidePattern = "cam")
        assertEquals(rules, LinkRules.Rules.fromJson(rules.toJson()))
        assertEquals(LinkRules.Rules(), LinkRules.Rules.fromJson("not json"))
        // A broken saved pattern never matches instead of breaking the list.
        assertFalse(LinkRules.Rules(hidePattern = "x265(").hides("x265("))
    }

    @Test fun `the Preferred row sorts within its place in Link sorting`() {
        fun item(title: String, height: Int) = SourceItem(title = title, provider = "p", directUrl = "https://x.example/$title", qualityHeight = height)
        val rules = LinkRules.Rules(preferWords = listOf("PSA"))
        val rank: (SourceItem) -> Int = { rules.rank(it.title) }
        val links = listOf(item("Movie.2160p-ABC", 2160), item("Movie.1080p-PSA", 1080), item("Movie.1080p-XYZ", 1080))
        // Default order: quality first, so 4K leads, then the PSA 1080p before the other 1080p.
        assertEquals(listOf("Movie.2160p-ABC", "Movie.1080p-PSA", "Movie.1080p-XYZ"), SourceSortOrder(preferredRank = rank).sorted(links).map { it.title })
        // Preferred above Quality: PSA wins whatever the quality.
        assertEquals("Movie.1080p-PSA", SourceSortOrder(priority = listOf("preferred", "quality", "size"), preferredRank = rank).sorted(links).first().title)
    }
}
