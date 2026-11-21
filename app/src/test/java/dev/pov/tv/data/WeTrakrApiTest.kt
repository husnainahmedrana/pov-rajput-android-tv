package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeTrakrApiTest {
    private class Memory : WeTrakrCredentials {
        val values = mutableMapOf<String, String>()
        @Synchronized override fun get(key: String) = values[key].orEmpty()
        @Synchronized override fun put(values: Map<String, String>) { this.values.putAll(values) }
    }
    private class Harness {
        val store = Memory()
        var time = 1_800_000_000_000L
        var clock = 0L
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject?>()
        var handler: (String, String, Map<String, String>, JSONObject?) -> String = { path, _, headers, _ ->
            assertEquals("1", headers["wetrakr-api-version"])
            when (path) {
                "/oauth/device/code" -> """{"device_code":"fixture-device-code","user_code":"ABC123","verification_url":"https://wetrakr.com/activate","expires_in":600,"interval":5}"""
                "/oauth/device/token", "/oauth/token/refresh" -> token().toString()
                else -> "[]"
            }
        }
        val api = client()
        init { api.setClientId("fixture-client-1234567890") }
        fun client() = WeTrakrApi(store, { path, method, headers, body ->
            synchronized(calls) { calls.add(path); bodies.add(body) }
            assertFalse("No installed client may send a client secret", body?.has("client_secret") == true)
            handler(path, method, headers, body)
        }, { time }, { clock })
        fun connect() { store.put(mapOf("access_token" to "fixture-access", "refresh_token" to "fixture-refresh", "expires_at" to (time + 1000000).toString(), "account_revision" to UUID.randomUUID().toString())) }
        fun advance(ms: Long) { time += ms; clock += ms }
        companion object { fun token() = JSONObject().put("access_token", "fixture-new-access").put("refresh_token", "fixture-new-refresh").put("expires_in", 604800) }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example")
    private val episode = PlaybackIdentity("tt7654321", "series", "Example Show", 0, 2)
    private fun failure(block: () -> Unit): Throwable = try { block(); throw AssertionError("Expected a failure") } catch (error: Exception) { error }

    @Test fun `personal playlist removal posts exact title identity and requires confirmation`() {
        val h = Harness(); h.connect()
        h.handler = { path, method, _, body ->
            assertEquals("/sync/lists/125/items/remove", path); assertEquals("POST", method)
            assertEquals(movie.imdbId, body!!.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getString("imdb"))
            """{"removed":{"total":1,"movies":1},"notFound":{"movies":[]},"errored":{"movies":[]}}"""
        }
        h.api.removeListItem("125", movie)
        h.handler = { _, _, _, _ -> """{"removed":{"total":0},"errored":{"movies":[{"error":"Denied"}]}}""" }
        assertTrue(failure { h.api.removeListItem("125", movie) } is ApiException)
        assertTrue(failure { h.api.removeListItem("../other", movie) } is IllegalArgumentException)
    }

    @Test fun `device flow honors interval and stores both tokens without a client secret`() {
        val h = Harness(); val auth = h.api.startDeviceAuth()
        assertFalse(h.api.pollDeviceAuth(auth)); assertEquals(1, h.calls.size)
        h.advance(5000); assertTrue(h.api.pollDeviceAuth(auth)); assertTrue(h.api.connected())
        assertEquals("fixture-new-refresh", h.store.get("refresh_token")); assertTrue(h.api.accountRevision().isNotBlank())
        assertEquals("fixture-client-1234567890", h.bodies[0]?.getString("client_id"))
        assertEquals("fixture-device-code", h.bodies[1]?.getString("code"))
    }
    @Test fun `only authorization pending retries a 400 response`() {
        val h = Harness(); val auth = h.api.startDeviceAuth()
        h.handler = { _, _, _, _ -> throw ApiException(400, "Pending", "authorization_pending") }
        h.advance(5000); assertFalse(h.api.pollDeviceAuth(auth))
        h.handler = { _, _, _, _ -> throw ApiException(400, "Bad request", "invalid_request") }
        h.advance(5000); assertTrue(failure { h.api.pollDeviceAuth(auth) } is ApiException)
        val count = h.calls.size; failure { h.api.pollDeviceAuth(auth) }; assertEquals(count, h.calls.size)
    }
    @Test fun `a network failure while the browser is open keeps the code for the next check`() {
        val h = Harness(); val auth = h.api.startDeviceAuth()
        h.handler = { _, _, _, _ -> throw ApiException(0, "The service could not be reached.") }
        h.advance(5000); assertTrue(failure { h.api.pollDeviceAuth(auth) } is ApiException)
        h.handler = { _, _, _, _ -> throw ApiException(503, "Unavailable") }
        h.advance(5000); assertTrue(failure { h.api.pollDeviceAuth(auth) } is ApiException)
        h.handler = { _, _, _, _ -> Harness.token().toString() }
        h.advance(5000); assertTrue(h.api.pollDeviceAuth(auth)); assertTrue(h.api.connected())
    }
    @Test fun `poll slowdown and server cooldown prevent early repeat requests`() {
        val h = Harness(); val auth = h.api.startDeviceAuth()
        h.handler = { _, _, _, _ -> throw ApiException(429, "Wait", "slow_down", 20) }
        h.advance(5000); assertFalse(h.api.pollDeviceAuth(auth)); val count = h.calls.size
        h.advance(10000); assertFalse(h.api.pollDeviceAuth(auth)); assertEquals(count, h.calls.size)
        h.handler = { _, _, _, _ -> Harness.token().toString() }
        h.advance(10000); assertTrue(h.api.pollDeviceAuth(auth))
    }
    @Test fun `dismissed expired or invalid-host device authorization cannot persist credentials`() {
        val h = Harness(); val auth = h.api.startDeviceAuth(); h.api.cancelDeviceAuth(auth)
        h.advance(5000); failure { h.api.pollDeviceAuth(auth) }; assertFalse(h.api.connected())
        val next = h.api.startDeviceAuth(); h.advance(601000); failure { h.api.pollDeviceAuth(next) }
        h.handler = { _, _, _, _ -> """{"device_code":"fixture-device","user_code":"ABC123","verification_url":"https://example.invalid/activate","expires_in":600,"interval":5}""" }
        failure { h.api.startDeviceAuth() }; assertFalse(h.api.connected())
    }
    @Test fun `cancel during code request invalidates its late response`() {
        val h = Harness(); val original = h.handler
        h.handler = { path, method, headers, body -> h.api.cancelDeviceAuth(); original(path, method, headers, body) }
        failure { h.api.startDeviceAuth() }; assertFalse(h.api.connected())
    }
    @Test fun `malformed token response cannot partially replace an account`() {
        val h = Harness(); h.connect(); val old = h.store.get("access_token")
        val auth = h.api.startDeviceAuth(); h.handler = { _, _, _, _ -> """{"access_token":"fixture-partial","expires_in":604800}""" }
        h.advance(5000); failure { h.api.pollDeviceAuth(auth) }
        assertEquals(old, h.store.get("access_token"))
    }
    @Test fun `concurrent clients serialize rotating refresh and prefer the new refresh field`() {
        val h = Harness(); h.connect(); h.store.put(mapOf("expires_at" to "0"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var refreshes = 0
        h.handler = { path, _, headers, _ ->
            if (path == "/oauth/token/refresh") {
                refreshes++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                Harness.token().put("new_refresh_token", "fixture-rotated-refresh").toString()
            } else { assertEquals("Bearer fixture-new-access", headers["Authorization"]); "[]" }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<List<MediaItemData>> { h.api.watchlist("movie") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val b = pool.submit<List<MediaItemData>> { h.client().watchlist("series") }
            release.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS)
            assertEquals(1, refreshes); assertEquals("fixture-rotated-refresh", h.store.get("refresh_token"))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `disconnect during refresh is immediate and rejects late tokens`() {
        val h = Harness(); h.connect(); h.store.put(mapOf("expires_at" to "0"))
        h.handler = { _, _, _, _ -> h.api.disconnect(); Harness.token().toString() }
        failure { h.api.watchlist("movie") }
        assertFalse(h.api.connected()); assertEquals("", h.store.get("access_token"))
    }
    @Test fun `ambiguous refresh response is never submitted a second time`() {
        val h = Harness(); h.connect(); h.store.put(mapOf("expires_at" to "0"))
        h.handler = { _, _, _, _ -> throw IOException("Synthetic lost response") }
        failure { h.api.watchlist("movie") }; failure { h.api.watchlist("movie") }
        assertEquals(1, h.calls.count { it == "/oauth/token/refresh" }); assertFalse(h.api.connected())
    }
    @Test fun `401 retry cannot send an old request under a replacement account`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, _ -> h.store.put(mapOf("account_revision" to "fixture-replacement-account", "access_token" to "fixture-other-access")); throw ApiException(401, "Expired") }
        failure { h.api.watchlist("movie") }; assertEquals(1, h.calls.size)
    }
    @Test fun `watchlist pages use required headers and cache subsequent reads`() {
        val h = Harness(); h.connect()
        h.handler = { path, method, headers, _ ->
            assertEquals("GET", method); assertEquals("1", headers["wetrakr-api-version"])
            assertEquals("fixture-client-1234567890", headers["wetrakr-api-key"]); assertEquals("Bearer fixture-access", headers["Authorization"])
            JSONArray().apply { repeat(if ("page=1&" in path) 100 else 1) { put(media(it)) } }.toString()
        }
        assertEquals(101, h.api.watchlist("movie").size); assertEquals(2, h.calls.size)
        assertEquals(101, h.api.watchlist("movie").size); assertEquals(2, h.calls.size)
        // Libraries keep their cache for 15 minutes (progress rows for 2): two minutes later it is still served from memory.
        h.advance(120001); h.api.watchlist("movie"); assertEquals(2, h.calls.size)
        h.advance(15 * 60_000L - 120_000L); h.api.watchlist("movie"); assertEquals(4, h.calls.size)
    }
    @Test fun `unknown IDs are not guessed and episode identities use parent show IDs`() {
        val h = Harness(); assertNull(h.api.media(JSONObject().put("id", 123).put("type", "movie").put("title", "Example")))
        val item = JSONObject().put("show", media(1).put("type", "show").put("ids", JSONObject().put("imdb", episode.imdbId)))
            .put("season_number", 0).put("number", 2)
        assertEquals(episode.copy(title = "Example 1"), h.api.identity(item))
        item.put("number", 0); assertNull(h.api.identity(item))
    }
    @Test fun `replaying an offline pause preserves time and exact episode without starting playback`() {
        val h = Harness(); h.connect(); var body = JSONObject()
        h.handler = { path, method, _, value -> assertEquals("/sync/tracking", path); assertEquals("POST", method); body = value!!; """{"added":{"total":1}}""" }
        val timestamp = java.time.Instant.ofEpochMilli(h.time - 10000).toString()
        assertTrue(h.api.restoreProgress(episode, 42.0, "pause", timestamp, h.api.accountRevision()))
        val show = body.getJSONArray("shows").getJSONObject(0)
        assertEquals(episode.imdbId, show.getJSONObject("ids").getString("imdb"))
        val season = show.getJSONArray("seasons").getJSONObject(0); assertEquals(0, season.getInt("number"))
        val ep = season.getJSONArray("episodes").getJSONObject(0)
        assertEquals(2, ep.getInt("number")); assertEquals("paused", ep.getString("status"))
        assertEquals(timestamp, ep.getString("paused_at")); assertEquals(42.0, ep.getDouble("progress"), 0.0)
        assertFalse(body.getBoolean("allow_rewatch"))
    }
    @Test fun `old account updates and ignored live scrobbles cannot be acknowledged as success`() {
        val h = Harness(); h.connect()
        assertFalse(h.api.progressForAccount(movie, 20.0, "start", "fixture-old-account")); assertTrue(h.calls.isEmpty())
        h.handler = { _, _, _, _ -> """{"action":"pause","ignored":true}""" }
        failure { h.api.progressForAccount(movie, 20.0, "pause", h.api.accountRevision()) }
    }
    @Test fun `duplicate offline watched retry is acknowledged without another rewatch`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, body -> assertFalse(body!!.getBoolean("allow_rewatch")); """{"errored":{"movies":[{"error":"Already watched!"}]}}""" }
        assertTrue(h.api.restoreProgress(movie, 100.0, "stop", java.time.Instant.ofEpochMilli(h.time - 1000).toString(), h.api.accountRevision()))
        failure { h.api.markWatched(movie) }
    }
    @Test fun `partial writes and unavailable titles never silently report success`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, _ -> """{"added":{"total":0},"notFound":{"movies":[{"ids":{"imdb":"tt1234567"}}]}}""" }
        failure { h.api.setWatchlist(movie, true) }
        h.handler = { _, _, _, _ -> "{}" }; failure { h.api.markWatched(movie) }
    }
    @Test fun `rate limit persists across client instances and disconnect does not clear it`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, _ -> throw ApiException(429, "Daily limit", retryAfterSeconds = 3600) }
        failure { h.api.watchlist("movie") }; val count = h.calls.size
        failure { h.client().watchlist("movie") }; assertEquals(count, h.calls.size)
        h.api.disconnect(); assertTrue(h.store.get("retry_at").toLong() > h.time)
    }
    @Test fun `late old application rate limit does not block a replacement registration`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, _ -> h.api.setClientId("fixture-different-client-id"); throw ApiException(429, "Old app limit", retryAfterSeconds = 3600) }
        failure { h.api.watchlist("movie") }
        assertEquals("", h.store.get("retry_at"))
    }
    private fun media(index: Int) = JSONObject().put("type", "movie").put("title", "Example $index").put("ids", JSONObject().put("imdb", "tt${1234567 + index}"))

    @Test fun `cancel removes exactly the selected episode and blocks stale scrobbles until new playback`() {
        val h = Harness(); h.connect(); val account = h.api.accountRevision()
        h.handler = { path, method, _, body ->
            assertEquals("/scrobble/playing", path); assertEquals("DELETE", method)
            assertEquals("tt7654321", body!!.getJSONObject("show").getJSONObject("ids").getString("imdb"))
            assertEquals(0, body.getJSONObject("episode").getInt("season")); assertEquals(2, body.getJSONObject("episode").getInt("number"))
            assertFalse(body.has("progress")); assertFalse(body.has("movies"))
            """{"action":"cancel","target":"episode"}"""
        }
        h.api.removeContinueWatching(episode)
        val count = h.calls.size
        assertFalse(h.api.progressForAccount(episode, 30.0, "stop", account))
        assertFalse(h.api.restoreProgress(episode, 30.0, "pause", "2026-09-01T10:00:00Z", account))
        assertEquals(count, h.calls.size)
        h.api.allowPlayback(episode)
        h.handler = { _, _, _, _ -> """{"action":"start"}""" }
        assertTrue(h.api.progressForAccount(episode, 30.0, "start", account))
    }
    @Test fun `cancel is idempotent only for no session and never claims other failures succeeded`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, _ -> throw ApiException(404, "None", "NO_SESSION") }
        h.api.removeContinueWatching(movie)
        h.api.allowPlayback(movie)
        h.handler = { _, _, _, _ -> throw ApiException(404, "Missing", "NOT_FOUND") }
        assertTrue(failure { h.api.removeContinueWatching(movie) } is ApiException)
        h.handler = { _, _, _, _ -> "{}" }
        assertTrue(failure { h.api.removeContinueWatching(movie) } is ApiException)
    }
    @Test fun `scrobble switch blocks live updates retries and previously queued work after reenable`() {
        val h = Harness(); h.connect(); val account = h.api.accountRevision(); val old = h.api.scrobbleRevision()
        h.api.setScrobblingEnabled(false)
        assertFalse(h.api.scrobblingEnabled())
        assertFalse(h.api.progressForAccount(movie, 50.0, "stop", account))
        assertFalse(h.api.restoreProgress(movie, 50.0, "pause", "2026-09-01T10:00:00Z", account))
        assertTrue(h.calls.isEmpty())
        h.api.setScrobblingEnabled(true)
        assertFalse(h.api.progressForAccount(movie, 50.0, "stop", account, old))
        h.handler = { _, _, _, _ -> """{"action":"pause"}""" }
        assertTrue(h.api.progressForAccount(movie, 50.0, "stop", account))
    }
    @Test fun `liked lists use likes pointers and public list contents rather than owned list endpoint`() {
        val h = Harness(); h.connect()
        h.handler = { path, _, headers, _ ->
            assertEquals("Bearer fixture-access", headers["Authorization"])
            when {
                path.startsWith("/sync/likes/lists") -> """[{"list":{"id":125,"name":"Example favourites"}},{"comment":{"id":5}},{"list":{"id":"../bad","name":"Invalid"}}]"""
                path.startsWith("/lists/125/items/all") -> """[{"type":"movie","title":"Example Movie","ids":{"imdb":"tt1234567"}},{"type":"person","ids":{"imdb":"nm1234567"}}]"""
                else -> error("Wrong endpoint")
            }
        }
        assertEquals(listOf("125" to "Example favourites"), h.api.likedLists())
        assertEquals("tt1234567", h.api.likedListPreview("125").single().imdbId)
        assertEquals("tt1234567", h.api.likedListItems("125").single().imdbId)
        val count = h.calls.size; h.api.likedListPreview("125"); h.api.likedLists(); assertEquals(count, h.calls.size)
        assertTrue(failure { h.api.likedListItems("../bad") } is IllegalArgumentException)
    }
    @Test fun `show watchlist uses the show root while cancellation always requires exact episode`() {
        val h = Harness(); h.connect()
        h.handler = { _, _, _, body ->
            val show = body!!.getJSONArray("shows").getJSONObject(0)
            assertEquals("planning", show.getString("status")); assertFalse(show.has("seasons"))
            """{"added":{"total":1}}"""
        }
        val show = PlaybackIdentity("tt7654321", "series", "Example Show")
        h.api.setWatchlist(show, true)
        assertTrue(failure { h.api.removeContinueWatching(show) } is IllegalArgumentException)
    }
}
