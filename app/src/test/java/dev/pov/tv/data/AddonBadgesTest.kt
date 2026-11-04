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
        assertNull(AddonBadges.of("https://torrentio.strem.fun/qualityfilter=4k", "Torrentio"))
        assertNull(AddonBadges.of("https://notpengu.uk.evil.example", "Other"))
    }
}
