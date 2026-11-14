package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** TorBox v1 API. These methods perform real requests and must run off the UI thread. */
class TorBoxApi internal constructor(
    private val store: TorBoxCredentials,
    private val transport: (String, String, Map<String, String>, JSONObject?) -> JSONObject = Net::json,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val multipart: (String, Map<String, String>, Map<String, String>) -> JSONObject = Net::multipart
) {
    constructor(store: SecureStore) : this(object : TorBoxCredentials {
        override fun get(key: String) = store.get(key)
        override fun put(key: String, value: String) { store.put(key, value) }
        override fun remove(key: String) { store.remove(key) }
    })

    private data class Pending(val started: Long, val expiresSeconds: Int, val intervalSeconds: Int,
        val credentialGeneration: Long, var lastPoll: Long = 0)
    private val devices = mutableMapOf<String, Pending>()
    private var authGeneration = 0L

    @Synchronized
    fun startDeviceAuth(): DeviceAuth {
        val (generation, account) = synchronized(authStateLock) { devices.clear(); ++authGeneration to credentialGeneration }
        val started = elapsedRealtime()
        val value = objectData(call("/user/auth/device/start?app=${Net.encode(dev.pov.tv.BuildConfig.APP_NAME)}", authenticated = false))
        val auth = DeviceAuth(
            required(value, "device_code"), value.optString("code", value.optString("user_code")),
            value.optString("verification_url", value.optString("friendly_verification_url")),
            value.optInt("interval", 5).coerceAtLeast(1), value.optInt("expires_in", 600).coerceAtLeast(1)
        )
        if (auth.userCode.isBlank() || !auth.verificationUrl.startsWith("https://")) malformed()
        synchronized(authStateLock) {
            if (generation != authGeneration || account != credentialGeneration) endedSession()
            devices[auth.deviceCode] = Pending(started, auth.expiresSeconds, auth.intervalSeconds, account)
        }
        return auth
    }

    /** Cancel without waiting on a device/token or account-verification network request. */
    fun cancelDeviceAuth(auth: DeviceAuth? = null) {
        synchronized(authStateLock) {
            if (auth == null) { devices.clear(); authGeneration++ }
            else devices.remove(auth.deviceCode)
        }
    }

    /** A late device or API-key verification must not silently undo Disconnect. */
    fun disconnect() = synchronized(authStateLock) {
        devices.clear(); authGeneration++; credentialGeneration++
        store.remove("torbox.token")
    }

    /** False means pending; an expired, declined, or failed authorization is an exception. */
    @Synchronized
    fun pollDeviceAuth(auth: DeviceAuth): Boolean {
        val now = elapsedRealtime()
        val state = synchronized(authStateLock) {
            devices[auth.deviceCode]?.takeIf { it.credentialGeneration == credentialGeneration }
        } ?: endedSession()
        if (now - state.started >= state.expiresSeconds.toLong() * 1000) {
            synchronized(authStateLock) { devices.remove(auth.deviceCode) }
            throw ApiException(410, L10n.tr(R.string.t_the_torbox_linking_code_has_expired, "The TorBox linking code has expired. Start linking again."))
        }
        if (state.lastPoll != 0L && now - state.lastPoll < state.intervalSeconds.toLong() * 1000) return false
        state.lastPoll = now
        val response = try {
            call("/user/auth/device/token", "POST", JSONObject().put("device_code", auth.deviceCode), authenticated = false)
        } catch (e: ApiException) {
            when (e.errorKey) {
                "DEVICE_CODE_NOT_USED", "authorization_pending" -> return false
                "ITEM_NOT_FOUND", "expired_token" -> throw ApiException(410, L10n.tr(R.string.t_the_torbox_linking_code_has_expired, "The TorBox linking code has expired. Start linking again."))
                "DEVICE_CODE_USED" -> throw ApiException(409, L10n.tr(R.string.t_this_torbox_linking_code_has_already, "This TorBox linking code has already been used. Start linking again."))
                else -> throw e
            }
        }
        val token = required(objectData(response), "access_token")
        synchronized(authStateLock) { if (devices[auth.deviceCode] !== state || state.credentialGeneration != credentialGeneration) endedSession() }
        // Verify the token before replacing a working account connection.
        verifyAccount(token)
        synchronized(authStateLock) {
            if (devices[auth.deviceCode] !== state || state.credentialGeneration != credentialGeneration) endedSession()
            store.put("torbox.token", token)
            credentialGeneration++
            devices.remove(auth.deviceCode)
        }
        return true
    }

    fun setApiKey(key: String) {
        val token = key.trim()
        if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() || it.code < 32 }) {
            throw ApiException(0, L10n.tr(R.string.t_enter_a_valid_torbox_api_key, "Enter a valid TorBox API key."))
        }
        val generation = synchronized(authStateLock) { devices.clear(); authGeneration++; ++credentialGeneration }
        verifyAccount(token)
        synchronized(authStateLock) {
            if (generation != credentialGeneration) {
                throw ApiException(401, L10n.tr(R.string.t_the_torbox_connection_changed_while_this, "The TorBox connection changed while this request was running. Try again with the current account."))
            }
            store.put("torbox.token", token)
        }
    }

    fun account(): JSONObject = verifyAccount(token())

    fun ownsTag(value: String): Boolean = store.get("torbox.token").let { it.isNotBlank() && tag(it) == value }

    /** Cached-only resolution: resolve direct URLs, then remove only a new entry from this request. */
    fun prepareCached(source: SourceItem, identity: PlaybackIdentity, selectedFile: CloudFile? = null,
        progress: (String) -> Unit = {}): PreparedSource.Files = synchronized(cachedLock) {
        fun trace(stage: String) { runCatching { progress(stage) } }
        val key = token()
        val deadline = elapsedRealtime() + 20_000
        val hash = source.infoHash.lowercase()
        require(hash.matches(Regex("[a-f0-9]{40}"))) { "Invalid TorBox source identifier." }
        trace("cache_recheck")
        check(hash in cached(listOf(hash))) { L10n.tr(R.string.t_this_source_is_no_longer_cached, "This source is no longer cached. Refresh sources.") }
        fun current() {
            check(store.get("torbox.token") == key) { L10n.tr(R.string.t_the_torbox_account_changed_select_the, "The TorBox account changed. Select the source again.") }
            check(elapsedRealtime() < deadline) { L10n.tr(R.string.t_torbox_source_preparation_took_too_long, "TorBox source preparation took too long. Refresh sources and try again.") }
        }
        val headers = mapOf("Authorization" to "Bearer $key")
        fun scoped(path: String, method: String = "GET", body: JSONObject? = null): Any? =
            unwrap(transport("$BASE$path", method, headers, body))
        val entries = mutableListOf<JSONObject>()
        val seen = mutableSetOf<Long>()
        var offset = 0
        while (true) {
            current()
            trace("library_snapshot")
            val page = scoped("/torrents/mylist?limit=1000&offset=$offset&bypass_cache=true")
            val items = when (page) { is JSONArray -> page; null, JSONObject.NULL -> JSONArray(); else -> malformed() }
            var added = 0
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val itemId = item.getLong("id")
                check(itemId >= 0) { L10n.tr(R.string.t_torbox_returned_an_invalid_library_identifier, "TorBox returned an invalid library identifier.") }
                if (seen.add(itemId)) { entries += item; added++ }
            }
            if (items.length() < 1000) break
            check(added > 0) { L10n.tr(R.string.t_could_not_verify_the_complete_torbox, "Could not verify the complete TorBox library. No cached transfer was created.") }
            offset += items.length()
            check(offset < 100_000) { L10n.tr(R.string.t_the_torbox_library_is_too_large, "The TorBox library is too large to verify safely.") }
        }
        val existingIds = entries.map { it.getLong("id") }.toSet()
        val existing = entries.firstOrNull { it.optString("hash").equals(hash, true) }?.getLong("id")
        current()
        trace("cached_entry")
        val id = existing ?: objectData(unwrap(multipart("$BASE/torrents/createtorrent", mapOf(
            "magnet" to "magnet:?xt=urn:btih:$hash", "seed" to "3", "allow_zip" to "false",
            "add_only_if_cached" to "true"
        ), headers))).optLong("torrent_id", -1)
        check(id >= 0) { L10n.tr(R.string.t_torbox_did_not_return_a_torrent, "TorBox did not return a torrent identifier.") }
        val owned = id !in existingIds && existing == null
        try {
            trace("cached_files")
            val raw = scoped("/torrents/mylist?id=$id&bypass_cache=true")
            val item = when (raw) {
                is JSONObject -> raw
                is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }.firstOrNull { it.optLong("id", -1) == id }
                else -> null
            } ?: throw IllegalStateException(L10n.tr(R.string.t_torbox_did_not_return_the_cached, "TorBox did not return the cached entry. Check your dashboard before retrying."))
            val folder = parseFolder(item, "torrents")
            check(folder.id == id) { L10n.tr(R.string.t_torbox_returned_a_different_transfer, "TorBox returned a different transfer.") }
            check(folder.ready) { L10n.tr(R.string.t_the_cached_source_is_not_ready, "The cached source is not ready. Refresh sources and try again.") }
            current()
            val eligible = EpisodeFilename.choices(folder.files.map { VideoCandidate(it.id, it.name, it.size) }, source.title, identity)
            // Large packs are choices, not a batch download-link request. Resolve one selection.
            val selected = if (selectedFile != null) {
                listOf(eligible.filter { it.name == selectedFile.name && it.size == selectedFile.size }.singleOrNull()
                    ?: throw IllegalStateException(L10n.tr(R.string.t_the_selected_cached_file_changed_or, "The selected cached file changed or is ambiguous. Refresh sources.")))
            } else if (eligible.size == 1) eligible else emptyList()
            val urls = selected.associate { file ->
                current()
                trace("cached_link")
                val url = scoped("/torrents/requestdl?token=${Net.encode(key)}&torrent_id=$id&file_id=${file.id}&redirect=false") as? String
                    ?: malformed()
                val uri = try { java.net.URI(url) } catch (_: Exception) { null }
                check(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { L10n.tr(R.string.t_torbox_did_not_return_a_secure, "TorBox did not return a secure playback link.") }
                file.id to url
            }
            current()
            val choices = if (selectedFile != null) selected else eligible
            PreparedSource.Files(folder, choices.map { CloudFile(it.id, it.name, it.size) }, identity,
                EpisodeFilename.preferredId(choices, source.title), urls, tag(key), hash)
        } finally {
            if (owned) {
                trace("remove_temporary_entry")
                // The captured account is deliberate: never send an old transfer ID to a newly linked account.
                val body = JSONObject().put("torrent_id", id).put("operation", "delete")
                try { scoped("/torrents/controltorrent", "POST", body) }
                catch (_: Exception) {
                    try { scoped("/torrents/controltorrent", "POST", body) }
                    catch (_: Exception) { throw IllegalStateException(L10n.tr(R.string.t_torbox_could_not_remove_the_temporary, "TorBox could not remove the temporary cached entry. Check your dashboard before retrying.")) }
                }
            }
        }
    }

    fun cloud(kind: String): List<CloudFolder> {
        checkKind(kind)
        val result = mutableListOf<CloudFolder>()
        val seen = mutableSetOf<Long>()
        var offset = 0
        while (true) {
            val data = call("/$kind/mylist?limit=1000&offset=$offset&bypass_cache=true")
            val array = when (data) {
                is JSONArray -> data
                JSONObject.NULL, null -> JSONArray()
                else -> malformed()
            }
            var added = 0
            for (i in 0 until array.length()) {
                val item = parseFolder(array.getJSONObject(i), kind)
                if (seen.add(item.id)) { result.add(item); added++ }
            }
            if (array.length() < 1000 || added == 0) break
            offset += array.length()
            if (offset >= 100_000) throw ApiException(0, L10n.tr(R.string.t_this_cloud_library_is_too_large, "This cloud library is too large to load at once."))
        }
        return result
    }

    fun folder(kind: String, id: Long): CloudFolder {
        checkKind(kind)
        require(id >= 0) { "Invalid TorBox folder." }
        val data = call("/$kind/mylist?id=$id&bypass_cache=true")
        val value = when (data) {
            is JSONObject -> data
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }.firstOrNull { it.optLong("id", -1) == id }
            else -> null
        } ?: throw ApiException(404, L10n.tr(R.string.t_this_torbox_folder_is_unavailable, "This TorBox folder is unavailable."))
        return parseFolder(value, kind)
    }

    fun streamUrl(kind: String, folderId: Long, fileId: Long): String {
        checkKind(kind)
        require(folderId >= 0 && fileId >= 0) { "Invalid TorBox file." }
        val idKey = when (kind) { "usenet" -> "usenet_id"; "webdl" -> "web_id"; else -> "torrent_id" }
        // TorBox requires the API key in this particular endpoint's query. Never log it.
        val value = call("/$kind/requestdl?token=${Net.encode(token())}&$idKey=$folderId&file_id=$fileId&redirect=false")
        val result = (value as? String).orEmpty()
        if (!result.startsWith("https://")) throw ApiException(0, L10n.tr(R.string.t_torbox_did_not_return_a_secure, "TorBox did not return a secure playback link."))
        return result
    }

    fun cached(hashes: List<String>): Set<String> {
        val normalized = hashes.map { it.lowercase() }.distinct()
        if (normalized.any { !it.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")) }) {
            throw ApiException(0, L10n.tr(R.string.t_a_source_contains_an_invalid_torrent, "A source contains an invalid torrent identifier."))
        }
        val result = mutableSetOf<String>()
        normalized.chunked(100).forEach { batch ->
            val value = call("/torrents/checkcached?format=list&list_files=false", "POST", JSONObject().put("hashes", JSONArray(batch)))
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    val entry = value.optJSONObject(i)
                    val hash = entry?.optString("hash")?.lowercase() ?: value.optString(i).lowercase()
                    if (hash in batch) result.add(hash)
                }
                is JSONObject -> value.keys().forEach { hash -> if (hash.lowercase() in batch) result.add(hash.lowercase()) }
                JSONObject.NULL, null -> Unit
                else -> malformed()
            }
        }
        return result
    }

    /** Adds only the selected source. No torrent is created merely by browsing results. */
    fun addMagnet(hash: String): Long {
        val value = hash.trim()
        if (!value.matches(Regex("[A-Fa-f0-9]{40}|[A-Za-z2-7]{32}"))) {
            throw ApiException(0, L10n.tr(R.string.t_a_source_contains_an_invalid_torrent, "A source contains an invalid torrent identifier."))
        }
        val response = multipart("$BASE/torrents/createtorrent", mapOf(
            "magnet" to "magnet:?xt=urn:btih:$value", "seed" to "3", "allow_zip" to "false"
        ), authorization())
        val data = objectData(unwrap(response))
        return data.optLong("torrent_id", -1).takeIf { it >= 0 }
            ?: throw ApiException(0, L10n.tr(R.string.t_torbox_did_not_return_a_torrent, "TorBox did not return a torrent identifier."))
    }

    private fun verifyAccount(token: String): JSONObject = objectData(unwrap(transport("$BASE/user/me", "GET", mapOf("Authorization" to "Bearer $token"), null)))

    private fun token(): String = store.get("torbox.token").takeIf { it.isNotBlank() }
        ?: throw ApiException(401, L10n.tr(R.string.t_connect_your_torbox_account_first, "Connect your TorBox account first."))

    private fun authorization(): Map<String, String> = mapOf("Authorization" to "Bearer ${token()}")

    private fun call(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true): Any? =
        unwrap(transport("$BASE$path", method, if (authenticated) authorization() else emptyMap(), body))

    private fun unwrap(json: JSONObject): Any? {
        if (!json.optBoolean("success", false)) {
            val error = json.optString("error").takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }.orEmpty()
            val message = when (error) {
                "DEVICE_CODE_NOT_USED" -> "Waiting for TorBox authorization."
                "ITEM_NOT_FOUND" -> "The requested TorBox item is unavailable."
                "BAD_TOKEN", "AUTH_ERROR", "UNAUTHORIZED" -> "Connect your TorBox account again."
                "DOWNLOAD_TOO_LARGE", "ACTIVE_LIMIT", "MONTHLY_LIMIT" -> "Your TorBox account limit prevents this request."
                else -> "TorBox could not complete this request."
            }
            throw ApiException(0, message, error)
        }
        return json.opt("data")
    }

    private fun objectData(value: Any?): JSONObject = value as? JSONObject ?: malformed()
    private fun required(value: JSONObject, key: String): String = value.optString(key).takeIf { it.isNotBlank() } ?: malformed()
    private fun checkKind(kind: String) { require(kind in setOf("torrents", "usenet", "webdl")) { "Invalid TorBox library type." } }
    private fun malformed(): Nothing = throw ApiException(0, L10n.tr(R.string.t_torbox_returned_an_unreadable_response, "TorBox returned an unreadable response."))
    private fun endedSession(): Nothing = throw ApiException(410, L10n.tr(R.string.t_this_torbox_linking_session_has_ended, "This TorBox linking session has ended. Choose Connect TorBox to get a new code."))

    internal fun parseFolder(value: JSONObject, kind: String): CloudFolder {
        val id = value.optLong("id", -1)
        if (id < 0) malformed()
        val files = value.optJSONArray("files") ?: JSONArray()
        val parsed = (0 until files.length()).mapNotNull { index ->
            files.optJSONObject(index)?.let { item ->
                val fileId = item.optLong("id", -1)
                if (fileId < 0) null else CloudFile(fileId,
                    item.optString("short_name", item.optString("name", "File ${fileId}")), item.optLong("size"))
            }
        }
        val ready = value.optBoolean("download_finished", false) && value.optBoolean("download_present", true)
        return CloudFolder(id, kind, value.optString("name", "Download $id"), ready, parsed)
    }

    companion object {
        private const val BASE = "https://api.torbox.app/v1/api"
        private val authStateLock = Any()
        private var credentialGeneration = 0L
        private val cachedLock = Any()
        private fun tag(key: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Narrow adapter permits offline authorization tests without Android Keystore. */
internal interface TorBoxCredentials {
    fun get(key: String): String
    fun put(key: String, value: String)
    fun remove(key: String) { put(key, "") }
}
