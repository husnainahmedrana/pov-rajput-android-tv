package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsProviderLogoTest {
    @Test fun `the provider's name decides before its server`() {
        // Tweaknews sold through a Newshosting deal still shows the Tweaknews logo.
        assertEquals("tweaknews", AddonBadges.newsProvider("Tweaknews", "news.newshosting.com"))
        assertEquals("newshosting", AddonBadges.newsProvider("My account", "news.newshosting.com"))
        assertEquals("eweka", AddonBadges.newsProvider("Eweka", ""))
    }

    @Test fun `the provider mentioned first wins`() {
        assertEquals("tweaknews", AddonBadges.newsProvider("Tweaknews (Newshosting deal)"))
        assertEquals("newshosting", AddonBadges.newsProvider("Newshosting + Tweaknews"))
        assertEquals(null, AddonBadges.newsProvider("Some provider"))
    }
}
