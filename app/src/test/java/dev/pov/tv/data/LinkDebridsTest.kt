package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkDebridsTest {
    private val hash = "a".repeat(40)
    private val movie = PlaybackIdentity("tt1234567", "movie", "Movie")
    private val source = SourceItem("Movie.2024.1080p.mkv", "Torrentio", hash)

    /** Answers by URL prefix and records every request. */
    private class Fake(val answers: Map<String, String>) : LinkTransport {
        val calls = mutableListOf<String>()
        var getHeaders = emptyMap<String, String>()
        var failure: ApiException? = null
        var onGet: () -> Unit = {}
        private fun answer(method: String, url: String): String {
            calls += "$method $url"
            return answers.entries.firstOrNull { (prefix, _) -> "$method $url".startsWith(prefix) }?.value ?: "{}"
        }
        override fun get(url: String, headers: Map<String, String>): String {
            getHeaders = headers
            onGet()
            failure?.let { throw it }
            return answer("GET", url)
        }
        override fun delete(url: String, headers: Map<String, String>) = answer("DELETE", url)
        /** Requests ("METHOD url" prefix) that fail with this error, like Real-Debrid's 451 copyright refusal. */
        var refuse = emptyMap<String, ApiException>()
        override fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>): String {
            refuse.entries.firstOrNull { (prefix, _) -> "$method $url".startsWith(prefix) }?.let { calls += "$method $url"; throw it.value }
            return answer(method, url)
        }
        override fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject) = answer(method, url)
    }

    private fun api(fake: Fake) = LinkDebrids({ "key-12345678" }, { _, _ -> }, fake, now = { 0L }, pause = {})

    @Test fun `pasted real-debrid key is trimmed validated saved and connected on reload`() {
        val credentials = mutableMapOf<String, String>()
        val fake = Fake(mapOf("GET https://api.real-debrid.com/rest/1.0/user" to """{"id":123,"username":"test"}"""))
        fun client() = LinkDebrids({ credentials[it].orEmpty() }, { id, key -> credentials[id] = key }, fake)
        assertFalse(client().connected("realdebrid"))
        client().setKey("realdebrid", " \nsynthetic-test-key\r\n ")
        assertEquals(mapOf("Authorization" to "Bearer synthetic-test-key"), fake.getHeaders)
        assertEquals("synthetic-test-key", credentials["realdebrid"])
        assertTrue(client().connected("realdebrid"))
        assertEquals(listOf("GET https://api.real-debrid.com/rest/1.0/user"), fake.calls)
    }

    @Test fun `failed validation never replaces a saved key`() {
        var saved = "previous-test-key"
        val client = LinkDebrids({ saved }, { _, key -> saved = key }, Fake(emptyMap()))
        assertThrows(IllegalStateException::class.java) { client.setKey("realdebrid", "replacement-test-key") }
        assertEquals("previous-test-key", saved)
    }

    @Test fun `network or authorization failure never marks an unsaved key connected`() {
        for (code in listOf(0, 401, 403, 429, 503)) {
            var saved = ""
            val fake = Fake(emptyMap()).apply { failure = ApiException(code, "Test failure") }
            val client = LinkDebrids({ saved }, { _, key -> saved = key }, fake)
            assertThrows(ApiException::class.java) { client.setKey("realdebrid", "synthetic-test-key") }
            assertEquals("", saved)
            assertFalse(client.connected("realdebrid"))
        }
    }

    @Test fun `cancel during account validation preserves the previous key`() {
        var saved = "previous-test-key"
        var cancelled = false
        val fake = Fake(mapOf("GET https://api.real-debrid.com/rest/1.0/user" to """{"id":123}"""))
            .apply { onGet = { cancelled = true } }
        val client = LinkDebrids({ saved }, { _, key -> saved = key }, fake)
        client.setKey("realdebrid", "replacement-test-key") { cancelled }
        assertEquals(1, fake.calls.size)
        assertEquals("previous-test-key", saved)
    }

    @Test fun `cancelled connection never starts validation or writes credentials`() {
        val fake = Fake(emptyMap())
        val client = LinkDebrids({ "" }, { _, _ -> fail("Cancelled request must not save") }, fake)
        client.setKey("realdebrid", "synthetic-test-key") { true }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `real-debrid plays a cached torrent and keeps it`() {
        val fake = Fake(mapOf(
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to """{"id":"T1"}""",
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/T1" to
                """{"status":"downloaded","files":[{"id":1,"path":"/Movie.2024.1080p.mkv","bytes":5,"selected":1}],"links":["https://real-debrid.com/d/X"]}""",
            "POST https://api.real-debrid.com/rest/1.0/unrestrict/link" to """{"download":"https://cdn.real-debrid.com/file.mkv"}"""))
        val prepared = api(fake).prepare("realdebrid", source, movie)!!
        assertEquals(listOf("Movie.2024.1080p.mkv"), prepared.files.map { it.name })
        assertEquals("https://cdn.real-debrid.com/file.mkv", api(fake).streamUrl(prepared, prepared.files.single()))
        assertTrue(fake.calls.none { it.startsWith("DELETE") })
    }

    private fun infringing() = ApiException(451, "The service request failed (HTTP 451).", "infringing_file")

    @Test fun `real-debrid skips and removes a cached torrent its copyright filter refuses at unrestrict`() {
        val fake = Fake(mapOf(
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to """{"id":"T3"}""",
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/T3" to
                """{"status":"downloaded","files":[{"id":1,"path":"/Movie.2024.1080p.mkv","bytes":5,"selected":1}],"links":["https://real-debrid.com/d/X"]}""")
        ).apply { refuse = mapOf("POST https://api.real-debrid.com/rest/1.0/unrestrict/link" to infringing()) }
        assertNull(api(fake).prepare("realdebrid", source, movie))
        assertTrue("DELETE https://api.real-debrid.com/rest/1.0/torrents/delete/T3" in fake.calls)
    }

    @Test fun `real-debrid add refusal of a refused name reads as copyright, of a clean name as the account pause`() {
        val fake = Fake(emptyMap()).apply { refuse = mapOf("POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to infringing()) }
        val named = assertThrows(IllegalStateException::class.java) {
            api(fake).prepare("realdebrid", SourceItem("Movie.2024.1080p.mkv", "Torrentio", hash, torrentName = "Movie [2024] WEB-DL 1080p"), movie)
        }
        assertTrue(named !is RealDebridPausedException && named.message!!.contains("copyright"))
        assertThrows(RealDebridPausedException::class.java) { api(fake).prepare("realdebrid", source, movie) }
    }

    @Test fun `real-debrid selects every video of a pack and plays this episode`() {
        val episode = PlaybackIdentity("tt9813792", "series", "From", 1, 1)
        val fake = Fake(mapOf(
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to """{"id":"T5"}""",
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/T5" to
                """{"status":"waiting_files_selection","files":[{"id":1,"path":"/From.S01E01.mkv","bytes":9,"selected":0},{"id":2,"path":"/From.S01E02.mkv","bytes":8,"selected":0},{"id":3,"path":"/info.nfo","bytes":1,"selected":0}],"links":[]}"""))
        val calls = mutableListOf<List<Pair<String, String>>>()
        var clock = 0L
        val api = LinkDebrids({ "key-12345678" }, { _, _ -> }, object : LinkTransport by fake {
            override fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>): String {
                if (url.contains("selectFiles")) calls += fields
                return fake.form(method, url, headers, fields)
            }
        }, now = { clock += 1_000_000_000L; clock }, pause = {})
        api.prepare("realdebrid", SourceItem("From.S01E01.mkv", "Torrentio", hash), episode)
        assertEquals(listOf(listOf("files" to "1,2")), calls)
    }

    @Test fun `real-debrid drops pack files whose own names it refuses`() {
        val fake = Fake(mapOf(
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to """{"id":"T4"}""",
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/T4" to
                """{"status":"downloaded","files":[{"id":1,"path":"/Movie.2024.1080p.WEB-DL.mkv","bytes":9,"selected":1},{"id":2,"path":"/Movie.2024.1080p.BluRay.x265.mkv","bytes":5,"selected":1}],"links":["https://real-debrid.com/d/A","https://real-debrid.com/d/B"]}""",
            "POST https://api.real-debrid.com/rest/1.0/unrestrict/link" to """{"download":"https://cdn.real-debrid.com/b.mkv"}"""))
        val prepared = api(fake).prepare("realdebrid", SourceItem("Movie 2024 pack", "Torrentio", hash), movie)!!
        assertEquals(listOf("Movie.2024.1080p.BluRay.x265.mkv"), prepared.files.map { it.name })
    }

    @Test fun `real-debrid removes a torrent that is not cached`() {
        val fake = Fake(mapOf(
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to """{"id":"T2"}""",
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/T2" to """{"status":"downloading","files":[],"links":[]}"""))
        assertNull(api(fake).prepare("realdebrid", source, movie))
        assertTrue("DELETE https://api.real-debrid.com/rest/1.0/torrents/delete/T2" in fake.calls)
    }

    @Test fun `alldebrid uses the ready flag and deletes uncached magnets`() {
        val cached = Fake(mapOf(
            "POST https://api.alldebrid.com/v4/magnet/upload" to """{"status":"success","data":{"magnets":[{"id":7,"ready":true}]}}""",
            "POST https://api.alldebrid.com/v4/magnet/files" to
                """{"status":"success","data":{"magnets":[{"files":[{"n":"Movie","e":[{"n":"Movie.2024.1080p.mkv","s":9,"l":"https://alldebrid.com/f/Y"}]}]}]}}"""))
        assertEquals("https://alldebrid.com/f/Y", api(cached).prepare("alldebrid", source, movie)!!.files.single().link)
        val uncached = Fake(mapOf("POST https://api.alldebrid.com/v4/magnet/upload" to
            """{"status":"success","data":{"magnets":[{"id":8,"ready":false}]}}"""))
        assertNull(api(uncached).prepare("alldebrid", source, movie))
        assertTrue(uncached.calls.any { it.startsWith("GET https://api.alldebrid.com/v4/magnet/delete") && it.endsWith("id=8") })
    }

    @Test fun `premiumize answers the cache up front`() {
        val other = "b".repeat(40)
        val fake = Fake(mapOf("GET https://www.premiumize.me/api/cache/check" to """{"status":"success","response":[false,true]}"""))
        assertEquals(setOf(other), api(fake).cached("premiumize", listOf(hash, other)))
        assertEquals(emptySet<String>(), api(fake).cached("realdebrid", listOf(hash)))
    }

    @Test fun `debrid-link removes a seedbox entry that is still downloading`() {
        val fake = Fake(mapOf("POST https://debrid-link.com/api/v2/seedbox/add" to
            """{"success":true,"value":{"id":"S1","downloadPercent":12,"files":[{"name":"Movie.2024.1080p.mkv","downloadUrl":""}]}}"""))
        assertNull(api(fake).prepare("debridlink", source, movie))
        assertTrue("DELETE https://debrid-link.com/api/v2/seedbox/S1/remove" in fake.calls)
    }

    @Test fun `every service has a name, a credential and a place in the priority list`() {
        LinkDebrids.IDS.forEach { id ->
            assertTrue(DebridServices.name(id).isNotBlank())
            assertEquals("$id.key", DebridServices.credential(id))
        }
        assertEquals(listOf("torbox", "offcloud") + LinkDebrids.IDS, DebridServices.parsePriority("torbox,offcloud", ""))
    }
}
