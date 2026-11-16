package dev.pov.tv.data

import org.junit.Assert.*
import org.junit.Test

class MediaFlowProxyTest {
    private fun proxy(): MediaFlowProxy {
        val saved = mutableMapOf<String, String>()
        return MediaFlowProxy({ saved[it].orEmpty() }, { saved.putAll(it) })
    }
    private fun ready() = proxy().apply { saveServer("mfp.example.com/", "pw&1"); setOn(true) }
    private val rd = "https://abc.download.real-debrid.com/d/X/Movie.mkv"

    @Test fun `server address gets https and loses the trailing slash`() {
        assertEquals("https://mfp.example.com", MediaFlowProxy.server(" mfp.example.com/ "))
        assertEquals("http://192.168.1.5:8888", MediaFlowProxy.server("http://192.168.1.5:8888/"))
        assertThrows(IllegalArgumentException::class.java) { MediaFlowProxy.server("ftp://x") }
        assertThrows(IllegalArgumentException::class.java) { MediaFlowProxy.server("https://u:p@x.com") }
    }

    @Test fun `only ticked debrid services go through the proxy`() {
        val proxy = ready()
        assertFalse(proxy.route(rd, emptyMap(), "realdebrid", MediaFlowProxy.Origin()).proxied)
        proxy.setDebrid("realdebrid", true)
        val route = proxy.route(rd, emptyMap(), "realdebrid", MediaFlowProxy.Origin())
        assertTrue(route.proxied)
        assertEquals("https://mfp.example.com/proxy/stream?d=" + Net.encode(rd) + "&api_password=pw%261", route.url)
        assertFalse(proxy.route(rd, emptyMap(), "torbox", MediaFlowProxy.Origin()).proxied)
    }

    @Test fun `an add-on link served by a ticked service is proxied too`() {
        val proxy = ready().apply { setDebrid("realdebrid", true) }
        assertTrue(proxy.route("https://torrentio.strem.fun/resolve/x", emptyMap(), null, MediaFlowProxy.Origin("p1", "realdebrid")).proxied)
    }

    @Test fun `ticked add-ons go through the proxy and their headers ride along`() {
        val proxy = ready().apply { setAddon("p1", true) }
        val route = proxy.route("https://cdn.example.com/v/index.m3u8", mapOf("Referer" to "https://site/", "Bad Header" to "x"), null, MediaFlowProxy.Origin("p1"))
        assertTrue(route.proxied)
        assertTrue(route.url.startsWith("https://mfp.example.com/proxy/hls/manifest.m3u8?d="))
        assertTrue(route.url.contains("&h_referer=" + Net.encode("https://site/")))
        assertFalse(route.url.contains("Bad"))
        assertTrue(route.headers.isEmpty())
        assertFalse(proxy.route("https://cdn.example.com/v.mkv", emptyMap(), null, MediaFlowProxy.Origin("p2")).proxied)
    }

    @Test fun `usenet never goes through the proxy`() {
        val proxy = ready().apply { setAddon("aio", true); setDebrid("torbox", true) }
        assertFalse(proxy.route("https://aio.example.com/usenet/x.mkv", emptyMap(), null, MediaFlowProxy.Origin("aio", usenet = true)).proxied)
        assertFalse(proxy.route("https://indexer.example.com/getnzb/1#pov-nzb", emptyMap(), null, MediaFlowProxy.Origin("aio")).proxied)
        assertFalse(proxy.route("https://members.easynews.com/dl/x.mkv", emptyMap(), null, MediaFlowProxy.Origin("aio")).proxied)
    }

    @Test fun `switched off, unset or local links stay direct`() {
        val proxy = ready().apply { setDebrid("realdebrid", true) }
        assertFalse(proxy.route("http://192.168.1.20:8080/v.mkv", emptyMap(), "realdebrid", MediaFlowProxy.Origin()).proxied)
        assertFalse(proxy.route("http://127.0.0.1:9000/v.mkv", emptyMap(), "realdebrid", MediaFlowProxy.Origin()).proxied)
        proxy.setOn(false)
        assertFalse(proxy.route(rd, emptyMap(), "realdebrid", MediaFlowProxy.Origin()).proxied)
        proxy.setOn(true); proxy.forget()
        assertFalse(proxy.route(rd, emptyMap(), "realdebrid", MediaFlowProxy.Origin()).proxied)
    }

    @Test fun `api calls of ticked services are relayed with their key, body type and no compression`() {
        val proxy = ready().apply { setDebrid("realdebrid", true) }
        val (url, headers) = proxy.forward("https://api.real-debrid.com/rest/1.0/torrents/addMagnet",
            mapOf("Authorization" to "Bearer k 1"), "application/x-www-form-urlencoded")!!
        assertEquals("https://mfp.example.com/proxy/forward?d=" + Net.encode("https://api.real-debrid.com/rest/1.0/torrents/addMagnet") +
            "&api_password=pw%261&h_accept-encoding=identity&h_content-type=application%2Fx-www-form-urlencoded&h_authorization=Bearer%20k%201", url)
        assertEquals(mapOf("Accept-Encoding" to "identity"), headers)
    }

    @Test fun `api calls of unticked services, other hosts or a switched-off proxy go direct`() {
        val proxy = ready().apply { setDebrid("realdebrid", true) }
        assertNull(proxy.forward("https://api.torbox.app/v1/api/user/me", emptyMap(), null))
        assertNull(proxy.forward("https://v3-cinemeta.strem.io/meta/movie/tt1.json", emptyMap(), null))
        proxy.setDebrid("torbox", true)
        assertNotNull(proxy.forward("https://api.torbox.app/v1/api/user/me", emptyMap(), null))
        proxy.setOn(false)
        assertNull(proxy.forward("https://api.real-debrid.com/rest/1.0/user", emptyMap(), null))
    }

    @Test fun `a plain http server never carries debrid keys`() {
        val proxy = proxy().apply { saveServer("http://192.168.1.5:8888", "pw"); setOn(true); setDebrid("premiumize", true) }
        assertThrows(ApiException::class.java) { proxy.forward("https://www.premiumize.me/api/account/info?apikey=x", emptyMap(), null) }
    }

    @Test fun `list routing matches the player's choice`() {
        val proxy = ready().apply { setDebrid("realdebrid", true); setAddon("p1", true) }
        val hash = "a".repeat(40)
        assertTrue(proxy.routes(SourceItem("x", "Torrentio", hash, providerId = "p9"), "realdebrid"))
        assertFalse(proxy.routes(SourceItem("x", "Torrentio", hash, providerId = "p9"), "torbox"))
        assertTrue(proxy.routes(SourceItem("x", "Torrentio", hash, providerId = "p1"), "torbox"))
        assertFalse(proxy.routes(SourceItem("x", "AIOStreams", directUrl = "https://aio/x", providerId = "p1", usenet = true), null))
        assertTrue(proxy.routes(SourceItem("x", "Torrentio", directUrl = "https://t/x", providerId = "p9", servedBy = "realdebrid"), null))
    }

    @Test fun `origin survives being saved`() {
        val origin = MediaFlowProxy.Origin("p1", "torbox", true)
        assertEquals(origin, MediaFlowProxy.Origin.decode(origin.encode()))
        assertEquals(MediaFlowProxy.Origin(), MediaFlowProxy.Origin.decode(null))
        assertNull(MediaFlowProxy.Origin.decode("p1|evil|").servedBy)
    }

    @Test fun `test reads the server ip and names a wrong password`() {
        val proxy = ready()
        assertEquals("1.2.3.4", proxy.test { url -> assertTrue(url.endsWith("/proxy/ip?api_password=pw%261")); """{"ip":"1.2.3.4"}""" })
        assertThrows(IllegalStateException::class.java) { proxy.test { throw ApiException(403, "no") } }
    }
}
