package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** API credentials and transfer IDs remain in the existing Keystore-backed account store. */
class OffcloudApi internal constructor(
    private val account: OffcloudAccount,
    private val transport: (String, String, JSONObject?, String) -> String = ::offcloudRequest,
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    constructor(store: SecureStore) : this(object : OffcloudAccount {
        override fun key() = store.get("offcloud.key")
        override fun transfers() = JSONObject(store.get("offcloud.transfers", "{}"))
        override fun save(key: String, transfers: JSONObject) = store.putAll(mapOf("offcloud.key" to key, "offcloud.transfers" to transfers.toString()))
    })

    fun connected(): Boolean = synchronized(lock) { account.key().isNotBlank() }

    fun owns(prepared: PreparedSource.OffcloudFiles): Boolean = synchronized(lock) {
        ownsTag(prepared.accountTag)
    }

    fun ownsTag(accountTag: String): Boolean = synchronized(lock) {
        account.key().isNotBlank() && tag(account.key()) == accountTag
    }

    fun cloud(): List<OffcloudDownload> {
        val key = credential()
        val response = call("cloud/history", key, null, "GET") as? JSONArray
            ?: throw IllegalStateException(L10n.tr(R.string.t_offcloud_did_not_return_your_cloud, "Offcloud did not return your cloud library."))
        checkCurrent(key)
        return (0 until response.length()).mapNotNull { index ->
            val item = response.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("requestId")
            if (!REQUEST_ID.matches(id)) return@mapNotNull null
            OffcloudDownload(id, item.optString("fileName").ifBlank { L10n.tr(R.string.t_cloud_download, "Cloud download") }, item.optString("status"), tag(key))
        }
    }

    fun cloudFiles(download: OffcloudDownload): List<OffcloudFile> {
        val key = credential()
        check(ownsTag(download.accountTag)) { L10n.tr(R.string.t_the_offcloud_account_changed_refresh_your_2, "The Offcloud account changed. Refresh your cloud library.") }
        require(REQUEST_ID.matches(download.id)) { "Invalid Offcloud transfer identifier." }
        val files = parseFiles(call("cloud/explore/${download.id}", key, null, "GET"))
            .filter { VIDEO.containsMatchIn(it.name) }
        checkCurrent(key)
        return files
    }

    fun setApiKey(value: String, cancelled: () -> Boolean = { false }) {
        val key = value.trim()
        require(key.length in 8..512 && key.none { it.isWhitespace() || it.isISOControl() }) { "Enter your Offcloud API key." }
        val previous = synchronized(lock) { account.key() }
        val response = call("account/info", key, null, "GET") as? JSONObject
        check(response != null && response.optString("user_id").isNotBlank()) { L10n.tr(R.string.t_offcloud_could_not_validate_this_api, "Offcloud could not validate this API key.") }
        synchronized(lock) {
            check(!cancelled()) { L10n.tr(R.string.t_offcloud_connection_cancelled, "Offcloud connection cancelled.") }
            check(account.key() == previous) { L10n.tr(R.string.t_the_offcloud_account_changed_please_try, "The Offcloud account changed. Please try again.") }
            account.save(key, if (previous == key) account.transfers() else JSONObject())
        }
    }

    fun disconnect() = synchronized(lock) { account.save("", JSONObject()) }

    fun cached(hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val key = credential()
        val response = call("cache/info", key, JSONObject()
            .put("urls", JSONArray(hashes.map { "magnet:?xt=urn:btih:$it" }))
            .put("includeFiles", false)) as? JSONArray
            ?: throw IllegalStateException(L10n.tr(R.string.t_offcloud_returned_an_unreadable_cache_response, "Offcloud returned an unreadable cache response."))
        check(response.length() == hashes.size) { L10n.tr(R.string.t_offcloud_cache_availability_is_unknown, "Offcloud cache availability is unknown.") }
        checkCurrent(key)
        return hashes.filterIndexed { index, _ -> response.optJSONObject(index)?.optBoolean("cached", false) == true }.toSet()
    }

    fun prepare(source: SourceItem, identity: PlaybackIdentity): PreparedSource.OffcloudFiles {
        val hash = source.infoHash.lowercase(Locale.ROOT)
        require(Regex("[a-f0-9]{40}").matches(hash)) { "This source has no valid torrent hash." }
        val key = credential()
        // Resolve cached files directly: this endpoint does not create an account transfer.
        val available = hash in cached(listOf(hash))
        checkCurrent(key)
        if (available) {
            val files = parseFiles(call("cache/download", key, JSONObject().put("url", "magnet:?xt=urn:btih:$hash")))
            val eligible = EpisodeFilename.choices(files.map { VideoCandidate(it.id, it.name, it.size) }, source.title, identity)
            checkCurrent(key)
            return PreparedSource.OffcloudFiles(null, files.filter { file -> eligible.any { it.id == file.id } }, identity,
                EpisodeFilename.preferredId(eligible, source.title), tag(key))
        }
        check(!source.cached) { L10n.tr(R.string.t_this_source_is_no_longer_cached_3, "This source is no longer cached. Refresh sources before starting a cloud download.") }
        // Navigating away and selecting the same source again can overlap worker requests.
        // Serialize transfer creation separately from the short account lock used by the UI.
        val requestId = synchronized(transferLock) {
            checkCurrent(key)
            val existing = synchronized(lock) { account.transfers().optString(hash) }
            if (existing.isNotBlank()) existing else {
                val created = call("cloud", key, JSONObject().put("url", "magnet:?xt=urn:btih:$hash")) as? JSONObject
                    ?: throw IllegalStateException(L10n.tr(R.string.t_offcloud_did_not_return_a_transfer, "Offcloud did not return a transfer."))
                val id = created.optString("requestId")
                require(REQUEST_ID.matches(id)) { "Offcloud did not return a valid transfer identifier." }
                synchronized(lock) {
                    check(account.key() == key) { L10n.tr(R.string.t_the_offcloud_account_changed_please_try, "The Offcloud account changed. Please try again.") }
                    account.save(key, account.transfers().put(hash, id))
                }
                id
            }
        }
        require(REQUEST_ID.matches(requestId)) { "The saved Offcloud transfer identifier is invalid." }
        val deadline = now() + 20_000_000_000L
        do {
            if (Thread.currentThread().isInterrupted) throw InterruptedException(L10n.tr(R.string.t_source_preparation_cancelled, "Source preparation cancelled."))
            checkCurrent(key)
            val response = call("cloud/status", key, JSONObject().put("requestId", requestId)) as? JSONObject
                ?: throw IllegalStateException(L10n.tr(R.string.t_offcloud_returned_an_unreadable_status, "Offcloud returned an unreadable status."))
            val status = response.optJSONObject("status") ?: response
            when (status.optString("status").lowercase(Locale.ROOT)) {
                "downloaded" -> {
                    val files = parseFiles(call("cloud/explore/$requestId", key, null, "GET"))
                    val eligible = EpisodeFilename.choices(files.map { VideoCandidate(it.id, it.name, it.size) }, source.title, identity)
                    checkCurrent(key)
                    return PreparedSource.OffcloudFiles(requestId, files.filter { file -> eligible.any { it.id == file.id } }, identity,
                        EpisodeFilename.preferredId(eligible, source.title), tag(key))
                }
                "error", "canceled", "cancelled", "failed" -> throw IllegalStateException(L10n.tr(R.string.t_this_offcloud_transfer_failed_check_it, "This Offcloud transfer failed. Check it on offcloud.com, then retry or choose another source."))
                "created", "queued", "downloading", "processing" -> Unit
                else -> throw IllegalStateException(L10n.tr(R.string.t_offcloud_returned_an_unknown_download_status, "Offcloud returned an unknown download status. Check your cloud account."))
            }
            if (now() >= deadline) break
            pause(1_500)
        } while (now() < deadline)
        throw IllegalStateException(L10n.tr(R.string.t_this_download_is_still_processing_in, "This download is still processing in Offcloud. Wait for it to finish, then select this source again; %1\$s will reuse the same transfer.", dev.pov.tv.BuildConfig.APP_SHORT))
    }

    private fun credential(): String = synchronized(lock) {
        account.key().also { check(it.isNotBlank()) { L10n.tr(R.string.t_connect_offcloud_in_settings_first, "Connect Offcloud in Settings first.") } }
    }

    private fun checkCurrent(key: String) = synchronized(lock) {
        check(account.key() == key) { L10n.tr(R.string.t_the_offcloud_account_changed_please_try, "The Offcloud account changed. Please try again.") }
    }

    private fun call(path: String, key: String, fields: JSONObject?, method: String = "POST"): Any {
        val result = try { JSONTokener(transport(path, key, fields, method)).nextValue() }
        catch (error: ApiException) { throw error }
        catch (_: Exception) { throw IllegalStateException(L10n.tr(R.string.t_offcloud_returned_an_unreadable_response, "Offcloud returned an unreadable response.")) }
        if (result is JSONObject) {
            if (result.has("error") && result.opt("error") != false && !result.isNull("error") && result.optString("error").isNotBlank()) {
                throw IllegalStateException(L10n.tr(R.string.t_offcloud_could_not_complete_the_request, "Offcloud could not complete the request. Check your API key, account, and selected download."))
            }
            if (result.optString("not_available").isNotBlank()) {
                throw IllegalStateException(L10n.tr(R.string.t_your_offcloud_account_does_not_currently, "Your Offcloud account does not currently allow this download. Check your plan or usage limits."))
            }
        }
        return result
    }

    companion object {
        internal fun offcloudRequest(path: String, key: String, body: JSONObject?, method: String): String {
            val suffix = if (path.startsWith("cloud/explore/")) "?format=detailed" else ""
            return Net.raw("https://offcloud.com/api/$path$suffix", method,
                mapOf("Authorization" to "Bearer $key"), body)
        }
        private val lock = Any()
        private val transferLock = Any()
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val VIDEO = Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|ts|m2ts|mts|webm|mpg|mpeg|wmv|flv)$")
        private fun tag(key: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        internal fun parseFiles(payload: Any): List<OffcloudFile> {
            val array = when (payload) {
                is JSONArray -> payload
                is JSONObject -> payload.optJSONArray("files")
                else -> null
            } ?: throw IllegalStateException(L10n.tr(R.string.t_offcloud_did_not_return_a_file, "Offcloud did not return a file list."))
            val files = (0 until array.length()).map { index ->
                val entry = array.optJSONObject(index)
                val url = entry?.optString("url") ?: array.optString(index)
                val uri = try { URI(url) } catch (_: Exception) { null }
                require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    "Offcloud returned an unsupported playback link."
                }
                val name = entry?.optString("name")?.ifBlank { entry.optString("path") }
                    ?.takeIf { it.isNotBlank() } ?: try {
                        URLDecoder.decode(uri!!.rawPath.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                    } catch (_: Exception) { "Video" }
                OffcloudFile(index.toLong(), name, entry?.optLong("size", 0) ?: 0, url)
            }
            return files.distinctBy { it.url }
        }
    }
}

internal interface OffcloudAccount {
    fun key(): String
    fun transfers(): JSONObject
    fun save(key: String, transfers: JSONObject)
}

data class OffcloudFile(val id: Long, val name: String, val size: Long, val url: String)
data class OffcloudDownload(val id: String, val name: String, val status: String, val accountTag: String)
