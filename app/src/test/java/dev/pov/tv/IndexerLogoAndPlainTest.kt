package dev.pov.tv

import dev.pov.tv.data.AddonBadges
import dev.pov.tv.data.NewsProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndexerLogoAndPlainTest {
    @Test fun `Prowlarr and NZBHydra2 get their own logos by name`() {
        assertEquals("prowlarr", AddonBadges.indexer("Prowlarr"))
        assertEquals("nzbhydra2", AddonBadges.indexer("NZBHydra2"))
        assertEquals("nzbhydra2", AddonBadges.indexer("My Hydra"))
        assertEquals("nzbgeek", AddonBadges.indexer("NZBgeek"))
    }

    @Test fun `allowing plain ports is off by default`() {
        assertFalse(NewsProviders.create("Test", "news.example.com", 563, true, "u", "p", 20, false).allowPlain)
    }
}
