package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Trakt (api.trakt.tv) with Nabrio's registered app. Device sign-in needs only the public Client ID: Trakt issues no
 * client secret to apps that sign users in on their own devices, and none is stored or bundled.
 *
 * Trakt's limits are per user: 500 GETs every 5 minutes and one POST/PUT/DELETE per second. Reads are cached (progress
 * rows for 2 minutes, libraries and lists for 15), the same read is never sent twice at once, writes are spaced a
 * second apart, and a 429's Retry-After pauses every request until it passes.
 */
class TraktApi internal constructor(
    private val credentials: WeTrakrCredentials,
    private val transport: (String, String, Map<String, String>, JSONObject?) -> String = { path, method, headers, body -> Net.raw(BASE + path, method, headers, body) },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val pause: (Long) -> Unit = Thread::sleep
) : Tracker {
    override val name = "Trakt"
    constructor(store: SecureStore) : this(object : WeTrakrCredentials {
        override fun get(key: String) = store.get("trakt.$key")
        override fun put(values: Map<String, String>) = store.putAll(values.mapKeys { "trakt.${it.key}" })
    })
    private data class Pending(val auth: DeviceAuth, val generation: Long, val started: Long, var lastPoll: Long, var interval: Long)
    @Volatile private var pending: Pending? = null
    private var attempt = 0L
    override fun signedIn() = credentials.get("access_token").isNotBlank() && credentials.get("reauth") != "true"
    override fun enabled() = credentials.get("off") != "true"
    override fun setEnabled(on: Boolean) = credentials.put(mapOf("off" to if (on) "" else "true"))
    override fun accountRevision() = credentials.get("account_revision")

    override fun disconnect(): Unit = synchronized(stateLock) {
        generation++; attempt++; pending = null; cache.clear(); credentials.put(emptyAccount())
    }
    override fun cancelDeviceAuth(auth: DeviceAuth?): Unit = synchronized(stateLock) {
        if (auth == null || pending?.auth?.deviceCode == auth.deviceCode) { attempt++; pending = null }
    }
    override fun startDeviceAuth(): DeviceAuth {
        val (version, request) = synchronized(stateLock) { pending = null; generation to ++attempt }
        val start = elapsed()
        val result = parseObject(call("/oauth/device/code", "POST", JSONObject().put("client_id", CLIENT_ID)))
        val seconds = result.optInt("expires_in")
        val interval = result.optInt("interval")
        val url = required(result, "verification_url")
        val uri = try { URI(url) } catch (_: Exception) { malformed() }
        val host = uri.host.orEmpty()
        if (uri.scheme != "https" || !(host == "trakt.tv" || host.endsWith(".trakt.tv")) || uri.userInfo != null || uri.fragment != null ||
            seconds !in 1..86400 || interval !in 1..300) malformed()
        val auth = DeviceAuth(required(result, "device_code"), required(result, "user_code"), url, interval, seconds)
        synchronized(stateLock) {
            if (version != generation || request != attempt) ended()
            pending = Pending(auth, version, start, start, interval.toLong())
        }
        return auth
    }
    @Synchronized override fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val state = synchronized(stateLock) { pending?.takeIf { it.auth.deviceCode == auth.deviceCode && it.generation == generation } ?: ended() }
        val time = elapsed()
        if (time - state.started >= auth.expiresSeconds * 1000L) { cancelDeviceAuth(auth); ended() }
        if (time - state.lastPoll < state.interval * 1000L) return false
        state.lastPoll = time
        val result = try { parseObject(call("/oauth/device/token", "POST", JSONObject().put("code", auth.deviceCode).put("client_id", CLIENT_ID))) }
        catch (error: ApiException) {
            // Trakt answers 400 while the code waits for approval and 429 when polled too fast.
            if (error.code == 400) return false
            if (error.code == 429) { state.interval += 5; return false }
            // No network (a phone blocks it while the browser is in front) or a server hiccup: the code is still valid, keep it.
            if (error.code == 0 || error.code >= 500) throw error
            cancelDeviceAuth(auth); throw error
        }
        synchronized(stateLock) {
            if (pending !== state || generation != state.generation) ended()
            saveTokens(result, newAccount = true); generation++; attempt++; pending = null; cache.clear()
        }
        return true
    }
    override fun scrobblingEnabled() = credentials.get("scrobbling") != "false"
    override fun scrobbleRevision() = credentials.get("scrobble_revision")
    override fun setScrobblingEnabled(enabled: Boolean): Unit = synchronized(stateLock) {
        credentials.put(mapOf("scrobbling" to enabled.toString(), "scrobble_revision" to UUID.randomUUID().toString()))
    }
    private fun suppressionKey(identity: PlaybackIdentity) = "suppressed.${identity.type}.${identity.imdbId}.${identity.season}.${identity.episode}"
    override fun allowPlayback(identity: PlaybackIdentity): Unit = synchronized(stateLock) { credentials.put(mapOf(suppressionKey(identity) to "")) }

    /** Trakt removes paused progress by its playback id, so the row's id is looked up first. Never touches history. */
    override fun removeContinueWatching(identity: PlaybackIdentity, revision: String): Unit = synchronized(refreshLock) {
        if (revision.isBlank() || accountRevision() != revision || !connected()) ended()
        validate(identity)
        val kind = if (identity.type == "movie") "movies" else "episodes"
        val ids = pages("$PLAYING$kind", paginated = false).filter { item ->
            identity(item)?.let { it.imdbId == identity.imdbId && it.season == identity.season && it.episode == identity.episode } == true
        }.mapNotNull { item -> item.optLong("id").takeIf { it > 0 } }
        for (id in ids) try { request("/sync/playback/$id", "DELETE") } catch (error: ApiException) { if (error.code != 404) throw error }
        synchronized(stateLock) {
            if (accountRevision() != revision || !connected()) ended()
            credentials.put(mapOf(suppressionKey(identity) to revision))
        }
        forget(PLAYING)
    }

    override fun likedLists(): List<Pair<String, String>> = pages("/users/likes/lists").mapNotNull { entry ->
        val list = entry.optJSONObject("list") ?: return@mapNotNull null
        list.optJSONObject("ids")?.optLong("trakt")?.takeIf { it > 0 }?.let { it.toString() to list.optString("name", "List") }
    }.distinctBy { it.first }
    override fun likedListItems(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid Trakt list." }
        return pages("/lists/$id/items/movie,show").mapNotNull(::listMedia)
    }
    override fun likedListPreview(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid Trakt list." }
        val path = "/lists/$id/items/movie,show?page=1&limit=20"
        return once(path) {
            val array = try { JSONArray(request(path)) } catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }.mapNotNull(::listMedia)
    }
    override fun lists(): List<Pair<String, String>> = pages("/users/me/lists", paginated = false).mapNotNull { record ->
        record.optJSONObject("ids")?.optLong("trakt")?.takeIf { it > 0 }?.let { it.toString() to record.optString("name", "List") }
    }
    override fun listItems(id: String): List<MediaItemData> {
        require(id.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid Trakt list." }
        return pages("/users/me/lists/$id/items/movie,show").mapNotNull(::listMedia)
    }
    override fun removeListItem(listId: String, identity: PlaybackIdentity) {
        require(listId.matches(Regex("[1-9][0-9]{0,18}")))
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series"))
        val target = if (identity.type == "movie") "movies" else "shows"
        val body = JSONObject().put(target, JSONArray().put(ids(identity)))
        val result = parseObject(request("/users/me/lists/$listId/items/remove", "POST", body))
        if (notFound(result) || (result.optJSONObject("deleted")?.optInt(target) ?: 0) <= 0)
            throw ApiException(0, L10n.tr(R.string.t_trakt_could_not_confirm_list_removal, "Trakt could not confirm list removal. Refresh the list and try again."))
        cache.clear()
    }
    override fun watchlist(type: String): List<MediaItemData> {
        require(type in setOf("movie", "series")) { "Invalid Trakt watchlist type." }
        return pages("/sync/watchlist/${if (type == "movie") "movies" else "shows"}/added/desc").mapNotNull(::listMedia)
    }

    /** Movies and episodes are read side by side; token refresh is already serialized. Most recently paused first. */
    override fun continueWatching(): List<Pair<PlaybackIdentity, Double>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            return listOf("movies", "episodes").map { kind -> pool.submit(java.util.concurrent.Callable {
                pages("$PLAYING$kind", paginated = false).mapNotNull { item ->
                    val progress = item.optDouble("progress", Double.NaN)
                    if (!progress.isFinite() || progress <= 0 || progress >= 95) return@mapNotNull null
                    identity(item)?.let { Triple(it, progress, item.optString("paused_at")) }
                }
            }) }.flatMap { task ->
                try { task.get() } catch (error: java.util.concurrent.ExecutionException) { throw (error.cause as? Exception ?: error) }
            }.sortedByDescending { it.third }.map { (identity, progress, _) -> identity to progress }
        } finally { pool.shutdownNow() }
    }
    /** Trakt's Up Next. Apps without that access get an empty row instead of an error. */
    override fun nextEpisodes(): List<PlaybackIdentity> = try {
        pages(UP_NEXT, maxPages = 2).mapNotNull { row ->
            val show = row.optJSONObject("show") ?: return@mapNotNull null
            val next = row.optJSONObject("progress")?.optJSONObject("next_episode") ?: return@mapNotNull null
            val media = media(show, "series") ?: return@mapNotNull null
            val season = next.optInt("season", -1); val number = next.optInt("number", -1)
            if (season < 0 || number <= 0) null else PlaybackIdentity(media.imdbId, "series", media.title, season, number)
        }
    } catch (error: ApiException) { if (error.code in setOf(401, 403, 404, 501)) emptyList() else throw error }

    /** Playback rows carry `movie`, or `episode` plus its `show`. */
    internal fun identity(item: JSONObject): PlaybackIdentity? {
        item.optJSONObject("movie")?.let { movie -> return media(movie, "movie")?.let { PlaybackIdentity(it.imdbId, "movie", it.title) } }
        val show = media(item.optJSONObject("show") ?: return null, "series") ?: return null
        val episode = item.optJSONObject("episode") ?: return null
        val season = episode.optInt("season", -1); val number = episode.optInt("number", -1)
        if (season < 0 || number <= 0) return null
        return PlaybackIdentity(show.imdbId, "series", show.title, season, number)
    }
    /** List and watchlist rows: `{type, movie|show}`. Artwork comes from the app's own poster lookup by IMDb id. */
    internal fun listMedia(row: JSONObject): MediaItemData? = when (row.optString("type")) {
        "movie" -> row.optJSONObject("movie")?.let { media(it, "movie") }
        "show" -> row.optJSONObject("show")?.let { media(it, "series") }
        else -> null
    }
    internal fun media(item: JSONObject, type: String): MediaItemData? {
        val id = item.optJSONObject("ids")?.optString("imdb").orEmpty()
        if (!id.matches(Regex("tt[0-9]{5,12}"))) return null
        val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val year = item.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty()
        return MediaItemData(id, type, title, year)
    }
    override fun refreshCaches() = cache.clear()
    override fun refreshContinueWatching() = forget(PLAYING, UP_NEXT)

    override fun watchedStamp(): String {
        val activity = parseObject(request("/sync/last_activities"))
        return listOf("movies", "episodes").joinToString("|") { activity.optJSONObject(it)?.optString("watched_at").orEmpty() }
    }
    /** One read each of watched movies and shows (with their seasons), only when [watchedStamp] moved. */
    override fun watched(): WatchedState {
        fun rows(path: String): List<JSONObject> {
            val array = try { JSONArray(request(path)) } catch (error: ApiException) { throw error } catch (_: Exception) { malformed() }
            return (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        fun imdb(item: JSONObject?) = item?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
        val movies = rows("/sync/watched/movies").mapNotNull { imdb(it.optJSONObject("movie")) }.toSet()
        val shows = mutableSetOf<String>(); val episodes = mutableSetOf<String>()
        rows("/sync/watched/shows?extended=full").forEach { row ->
            val show = row.optJSONObject("show"); val id = imdb(show) ?: return@forEach
            var regular = 0
            val seasons = row.optJSONArray("seasons") ?: JSONArray()
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val number = season.optInt("number", -1); if (number < 0) continue
                val list = season.optJSONArray("episodes") ?: continue
                for (e in 0 until list.length()) {
                    val episode = list.optJSONObject(e)?.optInt("number", -1) ?: -1
                    if (episode > 0) { episodes += WatchedState.key(id, number, episode); if (number > 0) regular++ }
                }
            }
            val aired = show?.optInt("aired_episodes", 0) ?: 0
            if (aired in 1..regular) shows += id
        }
        return WatchedState(movies, shows, episodes)
    }

    override fun setWatchlist(identity: PlaybackIdentity, add: Boolean) = synchronized(refreshLock) {
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series"))
        val target = if (identity.type == "movie") "movies" else "shows"
        val result = parseObject(request(if (add) "/sync/watchlist" else "/sync/watchlist/remove", "POST", JSONObject().put(target, JSONArray().put(ids(identity)))))
        if (notFound(result)) rejected()
        cache.clear()
    }
    override fun markWatched(identity: PlaybackIdentity) = synchronized(refreshLock) { addHistory(identity, null); Unit }
    override fun markSeasonWatched(show: PlaybackIdentity, season: Int, episodes: List<Int>) = synchronized(refreshLock) {
        require(show.imdbId.matches(Regex("tt[0-9]{5,12}")) && season >= 0 && episodes.isNotEmpty() && episodes.all { it > 0 })
        val list = JSONArray().also { array -> episodes.distinct().forEach { array.put(JSONObject().put("number", it)) } }
        val body = JSONObject().put("shows", JSONArray().put(ids(show).put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", list)))))
        val result = parseObject(request("/sync/history", "POST", body))
        if (notFound(result)) throw ApiException(0, L10n.tr(R.string.t_trakt_could_not_mark_every_episode, "Trakt could not mark every episode. Refresh your library before retrying."))
        cache.clear()
    }
    /** Adds one play. Trakt keeps every play it is sent, so callers must not resend one that was confirmed. */
    private fun addHistory(identity: PlaybackIdentity, watchedAt: String?) {
        validate(identity)
        val item = ids(identity)
        val body = if (identity.type == "movie") JSONObject().put("movies", JSONArray().put(item.also { if (watchedAt != null) it.put("watched_at", watchedAt) }))
        else {
            val episode = JSONObject().put("number", identity.episode).also { if (watchedAt != null) it.put("watched_at", watchedAt) }
            JSONObject().put("shows", JSONArray().put(item.put("seasons", JSONArray().put(JSONObject().put("number", identity.season).put("episodes", JSONArray().put(episode))))))
        }
        val result = parseObject(request("/sync/history", "POST", body))
        if (notFound(result)) rejected()
        cache.clear()
    }

    /** Replays a pause or finish saved while offline: a finish is logged at its real time, a pause as paused progress. */
    override fun restoreProgress(identity: PlaybackIdentity, percent: Double, action: String, timestamp: String, revision: String, expectedScrobbleRevision: String): Boolean = synchronized(refreshLock) {
        if (!connected() || !scrobblingEnabled() || credentials.get(suppressionKey(identity)) == revision || revision.isBlank() || accountRevision() != revision) return false
        if (expectedScrobbleRevision != scrobbleRevision()) return false
        require(percent.isFinite() && percent in 0.0..100.0 && action in setOf("pause", "stop"))
        require(java.time.Instant.parse(timestamp).toEpochMilli() <= now() + 60_000) { "Invalid saved Trakt playback time." }
        val watched = action == "stop" && percent >= 95
        if (!watched && percent < 1) return true
        if (watched) addHistory(identity, timestamp) else scrobble(identity, percent.coerceIn(1.0, 99.99), "pause")
        true
    }
    override fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String, expectedScrobbleRevision: String): Boolean = synchronized(refreshLock) {
        if (!connected() || !scrobblingEnabled() || credentials.get(suppressionKey(identity)) == revision || revision.isBlank() || accountRevision() != revision) return false
        if (expectedScrobbleRevision != scrobbleRevision()) return false
        validate(identity); require(percent.isFinite() && action in setOf("start", "pause", "stop"))
        scrobble(identity, percent.coerceIn(0.0, 100.0), action)
        true
    }
    private fun scrobble(identity: PlaybackIdentity, percent: Double, action: String) {
        val media = ids(identity)
        val body = (if (identity.type == "movie") JSONObject().put("movie", media)
            else JSONObject().put("show", media).put("episode", JSONObject().put("season", identity.season).put("number", identity.episode)))
            .put("progress", percent).put("app_version", APP_VERSION)
        val result = try { parseObject(request("/scrobble/$action", "POST", body)) } catch (error: ApiException) {
            // 409: this title was just scrobbled (Trakt refuses a duplicate). 422: under 1%, which Trakt ignores.
            if (error.code == 409 || error.code == 422) { forget(PLAYING, UP_NEXT); return }
            throw error
        }
        val accepted = if (action == "stop") setOf("pause", "scrobble") else setOf(action)
        if (result.optString("action") !in accepted) throw ApiException(0, L10n.tr(R.string.t_trakt_did_not_accept_this_playback, "Trakt did not accept this playback update."))
        // A finished play changes watched history and watchlists; a start or pause only the progress rows.
        if (result.optString("action") == "scrobble") cache.clear() else forget(PLAYING, UP_NEXT)
    }

    private fun ids(identity: PlaybackIdentity) = JSONObject().put("ids", JSONObject().put("imdb", identity.imdbId))
    private fun notFound(result: JSONObject): Boolean {
        val missing = result.optJSONObject("not_found") ?: return false
        return missing.keys().asSequence().any { (missing.optJSONArray(it)?.length() ?: 0) > 0 }
    }
    private fun rejected(): Nothing = throw ApiException(0, L10n.tr(R.string.t_trakt_could_not_apply_this_update, "Trakt could not apply this update. Refresh your library to check its current state."))

    private fun saveTokens(result: JSONObject, newAccount: Boolean = false) {
        val access = required(result, "access_token")
        val refresh = required(result, "refresh_token")
        val lifetime = result.optLong("expires_in")
        if (lifetime !in 1..31_536_000) malformed()
        val values = mutableMapOf("access_token" to access, "refresh_token" to refresh,
            "expires_at" to (now() + lifetime * 1000).toString(), "refresh_pending" to "", "reauth" to "")
        if (newAccount) values["account_revision"] = UUID.randomUUID().toString()
        credentials.put(values)
    }
    private data class Snapshot(val access: String, val refresh: String, val revision: String, val generation: Long)
    private fun snapshot() = synchronized(stateLock) { Snapshot(credentials.get("access_token"), credentials.get("refresh_token"), accountRevision(), generation) }
    private fun ensureCurrent(value: Snapshot) {
        if (value.generation != generation || value.revision != accountRevision() ||
            value.access != credentials.get("access_token") || value.refresh != credentials.get("refresh_token")) ended()
    }
    /** Refresh tokens are single-use: a refresh that may have reached Trakt is never sent twice (the account is relinked instead). */
    private fun accessToken(revision: String, force: String? = null): String = synchronized(refreshLock) {
        val snapshot = snapshot()
        if (snapshot.revision != revision) ended()
        if (!connected()) throw ApiException(401, L10n.tr(R.string.t_connect_trakt_in_settings_first, "Connect Trakt in Settings first."))
        val expires = credentials.get("expires_at").toLongOrNull() ?: 0
        if (if (force != null) snapshot.access != force else expires > now() + 60 * 60_000) return@synchronized snapshot.access
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(snapshot.refresh.toByteArray()).joinToString("") { "%02x".format(it) }
        synchronized(stateLock) {
            ensureCurrent(snapshot)
            if (snapshot.refresh.isBlank() || credentials.get("refresh_pending") == fingerprint) {
                credentials.put(mapOf("reauth" to "true")); throw ApiException(401, L10n.tr(R.string.t_link_trakt_again_to_renew_this, "Link Trakt again to renew this session."))
            }
            credentials.put(mapOf("refresh_pending" to fingerprint))
        }
        val body = JSONObject().put("refresh_token", snapshot.refresh).put("client_id", CLIENT_ID)
            .put("redirect_uri", REDIRECT_URI).put("grant_type", "refresh_token")
        val result = try { parseObject(call("/oauth/token", "POST", body)) }
        catch (error: ApiException) {
            synchronized(stateLock) {
                ensureCurrent(snapshot)
                if (error.code == 429 || error.code >= 500 || error.code == 0) credentials.put(mapOf("refresh_pending" to ""))
                if (error.code in setOf(400, 401, 403)) credentials.put(mapOf("reauth" to "true"))
            }
            throw error
        }
        synchronized(stateLock) { ensureCurrent(snapshot); saveTokens(result) }
        required(result, "access_token")
    }
    private fun call(path: String, method: String, body: JSONObject?, access: String? = null): String {
        val until = credentials.get("retry_at").toLongOrNull() ?: 0
        if (now() < until) throw waitError(until)
        val headers = mutableMapOf("trakt-api-version" to "2", "trakt-api-key" to CLIENT_ID, "User-Agent" to "Nabrio/$APP_VERSION")
        if (access != null) headers["Authorization"] = "Bearer $access"
        if (method != "GET") synchronized(writeLock) {
            // One write per second per user.
            val wait = lastWrite + 1_050 - elapsed()
            if (wait > 0) pause(wait)
            lastWrite = elapsed()
        }
        try { return transport(path, method, headers, body) }
        catch (error: ApiException) {
            if (error.code == 429) {
                // A write limit lasts a second; a read limit up to five minutes. Retry-After says which.
                val fallback = if (path == "/oauth/device/code") 60 else if (method == "GET") 30 else 2
                val retryAt = now() + maxOf(fallback, error.retryAfterSeconds) * 1000L
                if (method == "GET" || error.retryAfterSeconds > 5) credentials.put(mapOf("retry_at" to retryAt.toString()))
                throw waitError(retryAt)
            }
            if (error.code == 420) throw ApiException(420, L10n.tr(R.string.t_trakt_account_limit, "Your Trakt account has reached its list or watchlist limit. Remove items on trakt.tv or upgrade to VIP."))
            throw error
        }
    }
    override fun waitMessage(): String? {
        val until = credentials.get("retry_at").toLongOrNull() ?: return null
        return if (now() < until) waitError(until).message else null
    }
    private fun waitError(until: Long) = ApiException(429, L10n.tr(R.string.t_trakt_asked_us_to_wait, "Trakt asked us to wait before making more requests. Try again in %1\$s seconds.", ((until - now() + 999) / 1000).coerceAtLeast(1).toString()),
        retryAfterSeconds = ((until - now() + 999) / 1000).toInt())
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): String {
        val current = snapshot()
        val revision = current.revision
        val access = accessToken(revision)
        val result = try { call(path, method, body, access) } catch (error: ApiException) {
            if (error.code != 401) throw error
            call(path, method, body, accessToken(revision, access))
        }
        if (revision != accountRevision() || !connected()) ended()
        return result
    }
    private data class Cached(val at: Long, val records: List<JSONObject>)
    /** A cached read, fetched once even when several rows ask for it at the same moment. */
    private fun once(path: String, load: () -> List<JSONObject>): List<JSONObject> {
        val revision = accountRevision()
        if (!connected() || revision.isBlank()) throw ApiException(401, L10n.tr(R.string.t_connect_trakt_in_settings_first, "Connect Trakt in Settings first."))
        val key = "$revision:$path"
        cache[key]?.takeIf { elapsed() - it.at in 0..ttl(path) }?.let { return it.records }
        synchronized(inFlight.computeIfAbsent(key) { Any() }) {
            cache[key]?.takeIf { elapsed() - it.at in 0..ttl(path) }?.let { return it.records }
            return load().also { cache[key] = Cached(elapsed(), it) }
        }
    }
    private fun pages(path: String, paginated: Boolean = true, maxPages: Int = 20): List<JSONObject> = once(path) {
        val records = mutableListOf<JSONObject>()
        for (page in 1..maxPages) {
            val raw = request(if (paginated) path + (if ('?' in path) "&" else "?") + "page=$page&limit=$PAGE" else path)
            val array = try { JSONArray(raw) } catch (_: Exception) { malformed() }
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(records::add)
            if (!paginated || array.length() < PAGE) return@once records
        }
        if (maxPages < 20) records
        else throw ApiException(0, L10n.tr(R.string.t_this_trakt_list_is_too_large, "This Trakt list is too large to load at once. Open it on trakt.tv."))
    }
    private fun forget(vararg prefixes: String) { cache.keys.removeIf { key -> prefixes.any { key.substringAfter(':').startsWith(it) } } }
    private fun ttl(path: String) = if (path.startsWith(PLAYING) || path.startsWith(UP_NEXT)) 120_000L else 15 * 60_000L

    private fun validate(identity: PlaybackIdentity) {
        require(identity.imdbId.matches(Regex("tt[0-9]{5,12}")) && identity.type in setOf("movie", "series")) { "A matched movie or TV episode is required for Trakt." }
        if (identity.type == "series") require(identity.season >= 0 && identity.episode > 0) { "Select a specific episode first." }
    }
    private fun required(objectValue: JSONObject, key: String): String {
        val value = objectValue.opt(key) as? String ?: malformed()
        if (value.isBlank() || value == "null" || value.length > 8192 || value.any { it.code < 32 || it.code == 127 }) malformed()
        return value
    }
    private fun parseObject(raw: String) = try { JSONObject(raw) } catch (_: Exception) { malformed() }
    private fun malformed(): Nothing = throw ApiException(0, L10n.tr(R.string.t_trakt_returned_an_unreadable_response, "Trakt returned an unreadable response. Try again later."))
    private fun ended(): Nothing = throw ApiException(410, L10n.tr(R.string.t_this_trakt_connection_changed_or_expired, "This Trakt connection changed or expired. Link it again in Settings."))
    private fun emptyAccount() = listOf("access_token", "refresh_token", "expires_at", "account_revision", "refresh_pending", "reauth", "retry_at").associateWith { "" }
    companion object {
        private const val BASE = "https://api.trakt.tv"
        /** Nabrio's registered Trakt application (public identifier, not a secret). */
        const val CLIENT_ID = "JWc74ppobTF-CD0hkGSkCvzj2PSUaqpeAXBrvSmX0tM"
        /** Must match the app's settings on Trakt; sent with token refreshes. */
        private const val REDIRECT_URI = "https://nabrio.rajput.cc/trakt/callback"
        private val APP_VERSION get() = dev.pov.tv.BuildConfig.VERSION_NAME
        private const val PAGE = 100
        private const val PLAYING = "/sync/playback/"
        private const val UP_NEXT = "/sync/progress/up_next"
        private val stateLock = Any()
        private val refreshLock = Any()
        private val writeLock = Any()
        @Volatile private var lastWrite = 0L
        private var generation = 0L
        private val cache = ConcurrentHashMap<String, Cached>()
        private val inFlight = ConcurrentHashMap<String, Any>()
    }
}
