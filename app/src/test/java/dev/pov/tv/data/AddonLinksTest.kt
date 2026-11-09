package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonLinksTest {
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    @Test fun `stremio links opened from a page`() {
        assertEquals("stremio://addon.debridio.com/abc/manifest.json", AddonLinks.from(view, "stremio://addon.debridio.com/abc/manifest.json", null))
        assertNull(AddonLinks.from(view, "stremio://", null))
        assertNull(AddonLinks.from(view, "https://example.com/manifest.json", null))
        assertNull(AddonLinks.from(view, "dev.pov.tv.rajput://continue/tt1", null))
    }

    @Test fun `links shared as text`() {
        assertEquals("https://torrentio.strem.fun/torbox=x|qualityfilter=4k/manifest.json",
            AddonLinks.from(send, null, "Torrentio https://torrentio.strem.fun/torbox=x|qualityfilter=4k/manifest.json"))
        assertEquals("stremio://comet.elfhosted.com/eyJ/manifest.json", AddonLinks.from(send, null, "Install: stremio://comet.elfhosted.com/eyJ/manifest.json."))
        assertNull(AddonLinks.from(send, null, "just some words"))
        assertNull(AddonLinks.from(send, null, "http://insecure.example/manifest.json"))
        assertNull(AddonLinks.from(null, null, "https://example.com"))
    }

    @Test fun `percent-encoded JSON settings and a Torrentio link with settings are accepted as they are`() {
        val plavio = "stremio://hd.plavio.example/%7B%22source_A%22%3A%22on%22%2C%22en%22%3A%22on%22%7D/manifest.json"
        val link = AddonLinks.from(view, plavio, null)!!
        val saved = SourceProviders.endpoint(AddonLinks.https(link))
        assertEquals(plavio.removePrefix("stremio://").removeSuffix("/manifest.json"), saved.removePrefix("https://"))
        assertEquals(AddonBadges.PLAVIO, AddonBadges.of(saved, "PlavioHD")) // known by name off its real host
        assertEquals("https://torrentio.strem.fun/providers=yts,eztv%7Csort=quality%7Cdebridoptions=nodownloadlinks",
            SourceProviders.endpoint("https://torrentio.strem.fun/providers=yts,eztv|sort=quality|debridoptions=nodownloadlinks/manifest.json"))
        assertEquals("https://a.example/%7B%22x%22:1%7D", SourceProviders.endpoint("https://a.example/{\"x\":1}"))
    }

    @Test fun `https form and manifest address`() {
        assertEquals("https://a.example/x/manifest.json", AddonLinks.https("stremio://a.example/x/manifest.json"))
        assertEquals("https://a.example/x/manifest.json", AddonLinks.manifest("https://a.example/x/"))
        assertEquals("https://a.example/x/manifest.json", AddonLinks.manifest("stremio://a.example/x/manifest.json"))
        assertEquals("https://torrentio.strem.fun/sort=quality%7Clang=en/manifest.json", AddonLinks.manifest("https://torrentio.strem.fun/sort=quality|lang=en/manifest.json"))
    }
}
