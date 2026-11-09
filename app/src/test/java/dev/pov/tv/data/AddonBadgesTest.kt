package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonBadgesTest {
    @Test fun `known by address whatever the add-on is called`() {
        assertEquals(AddonBadges.PENGUPLAY, AddonBadges.of("https://pengu.uk/eyJzb3VyY2UiOiIxIn0", "My streams"))
        assertEquals(AddonBadges.CNCVERSE, AddonBadges.of("https://cncverse.dpdns.org/abc123", "Indian"))
        assertEquals(AddonBadges.PLAVIO, AddonBadges.of("https://hd.plavio.site/c/eyJzb3VyY2VzIjpbIlBsYXZpb0hEIl19", "Movies"))
        assertEquals(AddonBadges.PENGUPLAY, AddonBadges.of("https://self.hosted.example", "PenguPlay"))
        assertEquals(AddonBadges.DEBRIDIO, AddonBadges.of("https://addon.debridio.com/eyJwcm92aWRlciI6InRvcmJveCJ9", "My scraper"))
        assertEquals(AddonBadges.DEBRIDIO, AddonBadges.of("https://other.example", "Debridio"))
        assertNull(AddonBadges.of("https://debridio.com.evil.example", "Other"))
        assertEquals(AddonBadges.TORRENTIO, AddonBadges.of("https://torrentio.strem.fun/torbox=abc|qualityfilter=4k", "Streams"))
        assertEquals(AddonBadges.COMET, AddonBadges.of("https://comet.elfhosted.com/eyJ4Ijox/manifest.json", "Streams"))
        assertEquals(AddonBadges.STREMTHRU, AddonBadges.of("https://stremthru.13377001.xyz/stremio/store/eyJ4/manifest.json", "Store"))
        assertEquals(AddonBadges.METEOR, AddonBadges.of("https://meteorfortheweebs.midnightignite.me/stremio/abc/manifest.json", "Anime"))
        assertEquals(AddonBadges.COMET, AddonBadges.of("https://my.box.example", "Comet | ElfHosted"))
        assertNull(AddonBadges.of("https://cometary.example", "Streams"))
        assertNull(AddonBadges.of("https://mediafusion.example", "MediaFusion"))
        assertNull(AddonBadges.of("https://notpengu.uk.evil.example", "Other"))
    }
}
