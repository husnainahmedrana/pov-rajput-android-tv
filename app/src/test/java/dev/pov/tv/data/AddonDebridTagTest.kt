package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonDebridTagTest {
    private fun tag(service: String, cached: Boolean?) = AddonDebridTag.Tag(service, cached)

    @Test fun `bracket tags as Torrentio, StremThru and Comet write them`() {
        assertEquals(tag("realdebrid", true), AddonDebridTag.of("[RD+] Torrentio\n4k"))
        assertEquals(tag("torbox", true), AddonDebridTag.of("[TB+] Store 1080p"))
        assertEquals(tag("torbox", true), AddonDebridTag.of("[TB⚡] Comet 2160p"))
        assertEquals(tag("realdebrid", false), AddonDebridTag.of("[RD download] Torrentio\n1080p"))
        assertEquals(tag("premiumize", false), AddonDebridTag.of("[PM ⏳] Comet"))
        assertEquals(tag("offcloud", null), AddonDebridTag.of("[OC] Debridio 4K"))
        assertEquals(tag("debridlink", true), AddonDebridTag.of("[dl+] something"))
    }

    @Test fun `marks beside the code as MediaFusion writes them`() {
        assertEquals(tag("torbox", true), AddonDebridTag.of("MediaFusion ⚡️ TB 4K"))
        assertEquals(tag("alldebrid", false), AddonDebridTag.of("MediaFusion ⏳ AD"))
        assertEquals(tag("realdebrid", true), AddonDebridTag.of("Debridio RD+ 1080p"))
    }

    @Test fun `full service names`() {
        assertEquals(tag("torbox", true), AddonDebridTag.of("Debridio TorBox ⚡"))
        assertEquals(tag("realdebrid", null), AddonDebridTag.of("Real-Debrid 4K"))
        assertEquals(tag("premiumize", false), AddonDebridTag.of("Premiumize (download)"))
    }

    @Test fun `plain links and look-alike words name no service`() {
        assertNull(AddonDebridTag.of("PenguPlay 1080p"))
        assertNull(AddonDebridTag.of("HDR+ 2160p ATMOS"))
        assertNull(AddonDebridTag.of("[4K] WEB-DL"))
        assertNull(AddonDebridTag.of("TBS+ broadcast"))
        assertNull(AddonDebridTag.of(""))
    }
}
