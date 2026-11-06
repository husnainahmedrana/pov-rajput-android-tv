package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StremThruServerTest {
    @Test fun `a bare StremThru server address becomes its Torz torrent search, other addresses stay as typed`() {
        val asked = mutableListOf<String>()
        val stremthru = { url: String -> asked += url; JSONObject().put("id", "com.example.stremthru.torz").put("name", "StremThru Torz") }
        assertEquals("https://stremthru.example/stremio/torz/${SourceProviders.STREMTHRU_TORZ_P2P}",
            SourceProviders.resolveServer("https://stremthru.example/", stremthru))
        assertEquals(listOf("https://stremthru.example/stremio/torz/manifest.json"), asked)
        // The settings carry no key: just the P2P store.
        assertEquals("""{"stores":[{"c":"p2p"}]}""", String(java.util.Base64.getUrlDecoder().decode(SourceProviders.STREMTHRU_TORZ_P2P)))
        // An add-on address (it has a path) is never looked up or changed.
        asked.clear()
        assertEquals("https://torrentio.strem.fun/providers=yts", SourceProviders.resolveServer("https://torrentio.strem.fun/providers=yts/manifest.json", stremthru))
        assertEquals(emptyList<String>(), asked)
        // A bare address that isn't StremThru (Torrentio), or a server that can't be reached, stays as typed.
        assertEquals("https://torrentio.strem.fun", SourceProviders.resolveServer("https://torrentio.strem.fun", { JSONObject().put("id", "com.stremio.torrentio.addon") }))
        assertEquals("https://down.example", SourceProviders.resolveServer("https://down.example/") { error("offline") })
    }
}
