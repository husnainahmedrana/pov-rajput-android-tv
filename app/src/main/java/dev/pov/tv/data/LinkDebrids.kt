package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Real-Debrid, AllDebrid, Premiumize and Debrid-Link: an API key, a cache answer and direct HTTPS links.
 *
 * Premiumize answers cache questions up front. Real-Debrid, AllDebrid and Debrid-Link removed their
 * instant-availability APIs, so a link is checked by adding it and looking: a cached torrent is ready at
 * once, and an uncached one is removed again so a check never leaves a download behind.
 */
class LinkDebrids internal constructor(
    private val keyFor: (String) -> String,
    private val saveKey: (String, String) -> Unit,
    private val transport: LinkTransport = LinkTransport.NET,
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    constructor(store: SecureStore) : this({ store.get(DebridServices.credential(it)) }, { id, key -> store.put(DebridServices.credential(id), key) })

    fun connected(service: String) = keyFor(service).isNotBlank()

    fun setKey(service: String, value: String, cancelled: () -> Boolean = { false }) {
        if (cancelled()) return
        require(service in IDS)
        val key = value.trim()
        require(key.length in 8..512 && key.none { it.isWhitespace() || it.isISOControl() }) { "Enter your ${DebridServices.name(service)} API key." }
        check(validate(service, key)) { L10n.tr(R.string.t_x_could_not_validate_this_api, "%1\$s could not validate this API key.", DebridServices.name(service)) }
        if (cancelled()) return
        saveKey(service, key)
    }

    fun disconnect(service: String) { require(service in IDS); saveKey(service, "") }

    fun ownsTag(service: String, tag: String) = service in IDS && keyFor(service).let { it.isNotBlank() && tag(it) == tag }

    /** Up-front cache answers; only Premiumize offers them. */
    fun cached(service: String, hashes: List<String>): Set<String> {
        if (service != "premiumize" || hashes.isEmpty()) return emptySet()
        val key = credential(service)
        val query = hashes.joinToString("&") { "items[]=$it" }
        val response = json(transport.get(premiumizeUrl("cache/check?$query", key), emptyMap()))
        premiumizeOk(response)
        val answers = response.optJSONArray("response") ?: throw IllegalStateException(L10n.tr(R.string.t_premiumize_cache_availability_is_unknown, "Premiumize cache availability is unknown."))
        check(answers.length() == hashes.size) { L10n.tr(R.string.t_premiumize_cache_availability_is_unknown, "Premiumize cache availability is unknown.") }
        return hashes.filterIndexed { index, _ -> answers.optBoolean(index, false) }.toSet()
    }

    /** The cached files for this torrent, or null when the service does not have it cached. */
    fun prepare(service: String, source: SourceItem, identity: PlaybackIdentity): PreparedSource.Links? {
        val hash = source.infoHash.lowercase(Locale.ROOT)
        require(HASH.matches(hash)) { "This source has no valid torrent hash." }
        val key = credential(service)
        val files = when (service) {
            "realdebrid" -> try { realDebrid(key, hash, source, identity) }
                catch (error: ApiException) {
                    if (!RealDebridFilter.refusal(error)) throw error
                    // A name it refuses fails every time; any other refusal is Real-Debrid pausing adds on the account.
                    if (RealDebridFilter.refused(source)) { log(source, hash, "refused (name)"); throw IllegalStateException(refusedMessage()) }
                    log(source, hash, "refused (account paused)")
                    throw RealDebridPausedException(L10n.tr(R.string.t_rd_paused, "Real-Debrid is pausing adds on your account for a moment. Try again in a minute."))
                }
            "alldebrid" -> allDebrid(key, hash)
            "premiumize" -> premiumize(key, hash)
            "debridlink" -> debridLink(key, hash)
            "deepbrid" -> deepbrid(key, hash)
            else -> error(L10n.tr(R.string.t_unknown_debrid_service, "Unknown debrid service."))
        } ?: return null
        val eligible = try { EpisodeFilename.choices(files.map { VideoCandidate(it.id, it.name, it.size) }, source.title, identity) }
            catch (_: IllegalStateException) { return null }
        check(keyFor(service) == key) { L10n.tr(R.string.t_the_x_account_changed_please_try, "The %1\$s account changed. Please try again.", DebridServices.name(service)) }
        return PreparedSource.Links(service, files.filter { file -> eligible.any { it.id == file.id } }, identity,
            EpisodeFilename.preferredId(eligible, source.title), tag(key), source)
    }

    /** A playable HTTPS address for one prepared file. Real-Debrid and AllDebrid hand out hoster links that must be unlocked first. */
    fun streamUrl(prepared: PreparedSource.Links, file: DebridFile): String {
        check(ownsTag(prepared.service, prepared.accountTag)) { L10n.tr(R.string.t_the_x_account_changed_select_the, "The %1\$s account changed. Select the source again.", DebridServices.name(prepared.service)) }
        val key = credential(prepared.service)
        val url = when (prepared.service) {
            "realdebrid" -> unrestrict(key, file.link)
            "alldebrid" -> {
                val data = allDebridData(transport.get("$ALL_DEBRID/v4/link/unlock?agent=$AGENT&link=${Net.encode(file.link)}", bearer(key)))
                data.optString("link").ifBlank {
                    throw IllegalStateException(L10n.tr(R.string.t_alldebrid_is_still_preparing_this_file, "AllDebrid is still preparing this file. Try again in a moment."))
                }
            }
            else -> file.link
        }
        require(isHttps(url)) { "${DebridServices.name(prepared.service)} did not return a supported HTTPS playback link." }
        return url
    }

    private fun validate(service: String, key: String): Boolean = when (service) {
        "realdebrid" -> json(transport.get("$REAL_DEBRID/user", bearer(key))).has("id")
        "alldebrid" -> allDebridData(transport.get("$ALL_DEBRID/v4/user?agent=$AGENT", bearer(key))).optJSONObject("user") != null
        "premiumize" -> json(transport.get(premiumizeUrl("account/info", key), emptyMap())).optString("status") == "success"
        "debridlink" -> json(transport.get("$DEBRID_LINK/account/infos", bearer(key))).optBoolean("success")
        "deepbrid" -> json(transport.get("$DEEPBRID/user", bearer(key))).let { deepbridOk(it) && it.length() > 0 }
        else -> false
    }

    private fun realDebrid(key: String, hash: String, source: SourceItem, identity: PlaybackIdentity): List<DebridFile>? {
        val auth = bearer(key)
        val id = json(transport.form("POST", "$REAL_DEBRID/torrents/addMagnet", auth, listOf("magnet" to magnet(hash)))).optString("id")
        check(TORRENT_ID.matches(id)) { L10n.tr(R.string.t_real_debrid_did_not_accept_this, "Real-Debrid did not accept this link.") }
        var keep = false
        try {
            var info = waitFor(4_000) { json(transport.get("$REAL_DEBRID/torrents/info/$id", auth)).takeIf { it.optString("status") != "magnet_conversion" } }
                ?: return null
            var wantedIds = emptySet<Long>()
            if (info.optString("status") == "waiting_files_selection") {
                val all = info.optJSONArray("files") ?: return null
                val files = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
                    .map { VideoCandidate(it.optLong("id"), it.optString("path"), it.optLong("bytes")) }
                // This title's videos must be in it; then every video is selected, as Debrid Media Manager does: a season
                // pack is cached with all its episodes, and one episode on its own may not be ready at once.
                val wanted = try { EpisodeFilename.choices(files, source.title, identity) } catch (_: IllegalStateException) { log(source, hash, "no file for this title"); return null }
                wantedIds = wanted.map { it.id }.toSet()
                val videos = EpisodeFilename.videos(files).ifEmpty { wanted }
                transport.form("POST", "$REAL_DEBRID/torrents/selectFiles/$id", auth, listOf("files" to videos.joinToString(",") { it.id.toString() }))
                info = waitFor(4_000) { json(transport.get("$REAL_DEBRID/torrents/info/$id", auth)).takeIf { it.optString("status") != "waiting_files_selection" && it.optString("status") != "magnet_conversion" } }
                    ?: return null
            }
            if (info.optString("status") != "downloaded") { log(source, hash, "not cached (${info.optString("status")})", info.optString("filename")); return null }
            val all = info.optJSONArray("files") ?: return null
            val selected = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it.optInt("selected") == 1 }.sortedBy { it.optLong("id") }
            val links = info.optJSONArray("links") ?: return null
            // Links follow the selected files in order; a mismatch means the files were packed into an archive.
            if (links.length() != selected.size) return null
            // Real-Debrid's copyright filter also judges each file's own name at unrestrict time: a file named like
            // that never plays, and a torrent that looks cached may still be refused, so ask once for the main file.
            val files = selected.mapIndexed { index, file -> DebridFile(file.optLong("id"), file.optString("path").substringAfterLast('/'), file.optLong("bytes"), links.getString(index)) }
                .filterNot { RealDebridFilter.refusedName(it.name) }
            // Ask once for the file that will play (this title's, when a whole pack was selected).
            val main = files.filter { wantedIds.isEmpty() || it.id in wantedIds }.maxByOrNull { it.size } ?: files.maxByOrNull { it.size } ?: return null
            try { unrestrict(key, main.link) }
            catch (error: ApiException) { if (RealDebridFilter.refusal(error)) { log(source, hash, "refused (file name)", main.name); return null } else throw error }
            keep = true
            log(source, hash, "ready (${files.size} file${if (files.size == 1) "" else "s"})", info.optString("filename"))
            return files
        } finally {
            if (!keep) runCatching { transport.delete("$REAL_DEBRID/torrents/delete/$id", auth) }
        }
    }

    /** One line per Real-Debrid try (no key): which torrent, and what Real-Debrid said. */
    private fun log(source: SourceItem, hash: String, outcome: String, torrent: String = "") {
        runCatching { android.util.Log.i(LOG_TAG, "${hash.take(8)} ${outcome}: ${(torrent.ifBlank { source.torrentName.ifBlank { source.title } }).take(120)}") }
    }

    private fun unrestrict(key: String, link: String): String = try {
        json(transport.form("POST", "$REAL_DEBRID/unrestrict/link", bearer(key), listOf("link" to link))).optString("download")
    } catch (error: ApiException) {
        if (RealDebridFilter.refusal(error)) throw ApiException(451, refusedMessage(), "infringing_file") else throw error
    }

    private fun refusedMessage() = L10n.tr(R.string.t_real_debrid_refuses_this_release, "Real-Debrid refuses this release because of its name (copyright filter). Pick another link.")

    private fun allDebrid(key: String, hash: String): List<DebridFile>? {
        val auth = bearer(key)
        val magnet = allDebridData(transport.form("POST", "$ALL_DEBRID/v4/magnet/upload?agent=$AGENT", auth, listOf("magnets[]" to hash)))
            .optJSONArray("magnets")?.optJSONObject(0) ?: return null
        if (magnet.has("error")) return null
        val id = magnet.optLong("id")
        if (!magnet.optBoolean("ready")) {
            runCatching { transport.get("$ALL_DEBRID/v4/magnet/delete?agent=$AGENT&id=$id", auth) }
            return null
        }
        val entries = allDebridData(transport.form("POST", "$ALL_DEBRID/v4/magnet/files?agent=$AGENT", auth, listOf("id[]" to id.toString())))
            .optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files") ?: return null
        val files = mutableListOf<DebridFile>()
        fun walk(array: JSONArray) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                entry.optJSONArray("e")?.let(::walk)
                val link = entry.optString("l")
                if (link.isNotBlank()) files += DebridFile(files.size.toLong(), entry.optString("n"), entry.optLong("s"), link)
            }
        }
        walk(entries)
        return files.ifEmpty { null }
    }

    private fun premiumize(key: String, hash: String): List<DebridFile>? {
        val response = json(transport.form("POST", premiumizeUrl("transfer/directdl", key), emptyMap(), listOf("src" to magnet(hash))))
        if (response.optString("status") != "success") return null
        val content = response.optJSONArray("content") ?: return null
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }.mapIndexed { index, file ->
            DebridFile(index.toLong(), file.optString("path").substringAfterLast('/'), file.optLong("size"), file.optString("link"))
        }.filter { isHttps(it.link) }.ifEmpty { null }
    }

    private fun debridLink(key: String, hash: String): List<DebridFile>? {
        val auth = bearer(key)
        val response = json(transport.json("POST", "$DEBRID_LINK/seedbox/add", auth, JSONObject().put("url", magnet(hash)).put("async", false)))
        if (!response.optBoolean("success")) return null
        val value = response.optJSONObject("value") ?: return null
        val id = value.optString("id")
        val files = value.optJSONArray("files")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
        val ready = value.optInt("downloadPercent") == 100 && files.isNotEmpty() && files.all { isHttps(it.optString("downloadUrl")) }
        if (!ready) {
            if (TORRENT_ID.matches(id)) runCatching { transport.delete("$DEBRID_LINK/seedbox/$id/remove", auth) }
            return null
        }
        return files.mapIndexed { index, file -> DebridFile(index.toLong(), file.optString("name"), file.optLong("size"), file.optString("downloadUrl")) }
    }

    /**
     * Deepbrid (experimental): no cache API, so the magnet is added and read for a few seconds; a cached torrent is
     * "downloaded" at once and lists one short link per file. Those links carry no names and redirect to the file, so
     * each is followed (the key goes only to Deepbrid's own host) to learn its name and size. A torrent that isn't ready,
     * or fails, is removed again unless it was already on the account.
     */
    private fun deepbrid(key: String, hash: String): List<DebridFile>? {
        val auth = bearer(key)
        val had = deepbridIds(auth)
        val added = json(transport.form("POST", "$DEEPBRID/torrents/add", auth, listOf("magnet" to magnet(hash))))
        if (!deepbridOk(added)) return null
        val id = sequenceOf(added.opt("id"), added.optJSONObject("torrent")?.opt("id"))
            .mapNotNull { value -> value?.toString()?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,40}")) && it != "0" } }.firstOrNull()
            ?: return null
        fun remove() { if (id !in had) runCatching { transport.delete("$DEEPBRID/torrents/delete/${Net.encode(id)}", auth) } }
        val links = try {
            waitFor(DEEPBRID_WAIT_MS) {
                val info = json(transport.get("$DEEPBRID/torrents/info?id=${Net.encode(id)}", auth))
                when (info.optString("status")) {
                    "downloaded" -> info.optJSONArray("links")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter(::isHttps) }
                        ?.takeIf { it.isNotEmpty() }
                    "error", "dead", "magnet_error", "virus" -> emptyList()
                    else -> null
                }
            }
        } catch (error: InterruptedException) { remove(); throw error }
        if (links.isNullOrEmpty()) { remove(); return null }
        return links.take(60).mapIndexedNotNull { index, link ->
            transport.follow(link, if (isDeepbridHost(link)) auth else emptyMap())?.let { (url, name, size) -> DebridFile(index.toLong(), name, size, url) }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Deepbrid Usenet: uploads an NZB file (never the indexer link) and returns the files Deepbrid serves from it.
     * Deepbrid reads the NZB at once and streams straight out of the archive, so there is no waiting; a file it can't
     * serve that way (missing volumes, a compressed archive) comes without a link and is left out.
     */
    fun deepbridNzb(nzb: ByteArray, name: String): List<DebridFile> {
        val key = credential("deepbrid")
        val response = json(transport.upload("$DEEPBRID/usenet/add", bearer(key), "nzb_file", "${name.take(150)}.nzb", nzb))
        check(deepbridOk(response)) {
            response.optString("message").takeIf { it.isNotBlank() && it != "OK" }?.let { "Deepbrid: $it" }
                ?: L10n.tr(R.string.t_deepbrid_could_not_read_this_nzb, "Deepbrid couldn't read this NZB. Choose another link.")
        }
        val files = response.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { files.optJSONObject(it) }.mapIndexedNotNull { index, file ->
            val link = file.optString("download_url")
            if (!isHttps(link)) null else DebridFile(index.toLong(), file.optString("filename"), file.optLong("filesize"), link)
        }
    }

    /** A Deepbrid short link (Usenet or torrent) followed to the file's own address; the key goes only to Deepbrid. */
    fun deepbridFileUrl(link: String): String {
        val key = credential("deepbrid")
        return transport.follow(link, if (isDeepbridHost(link)) bearer(key) else emptyMap())?.first?.takeIf(::isHttps)
            ?: throw IllegalStateException(L10n.tr(R.string.t_deepbrid_did_not_return_a_playable, "Deepbrid did not return a playable link."))
    }

    /**
     * Adds an uncached torrent to Deepbrid and leaves it downloading there (the viewer asked for it). Playing the link
     * again once Deepbrid has it finds it ready, and it is never removed by a later check because it is the account's.
     */
    fun deepbridKeep(hash: String) {
        val value = hash.lowercase(Locale.ROOT)
        require(HASH.matches(value)) { "This source has no valid torrent hash." }
        val added = json(transport.form("POST", "$DEEPBRID/torrents/add", bearer(credential("deepbrid")), listOf("magnet" to magnet(value))))
        check(deepbridOk(added)) {
            added.optString("message").takeIf { it.isNotBlank() && it != "OK" }?.let { "Deepbrid: $it" }
                ?: L10n.tr(R.string.t_deepbrid_did_not_take_this_torrent, "Deepbrid did not take this torrent.")
        }
    }

    private fun deepbridIds(auth: Map<String, String>): Set<String> = try {
        val list = json(transport.get("$DEEPBRID/torrents/info", auth))
        list.keys().asSequence().mapNotNull { list.optJSONObject(it)?.opt("id")?.toString() }.toSet()
    } catch (_: Exception) { emptySet() }

    /** Every Deepbrid answer carries "error": 0 (or nothing) on success. */
    private fun deepbridOk(response: JSONObject): Boolean = when (val error = response.opt("error")) {
        null, JSONObject.NULL, "", false -> true
        else -> error.toString().toIntOrNull() == 0
    }

    private fun <T : Any> waitFor(limitMs: Long, attempt: () -> T?): T? {
        val deadline = now() + limitMs * 1_000_000
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException(L10n.tr(R.string.t_source_preparation_cancelled, "Source preparation cancelled."))
            attempt()?.let { return it }
            if (now() >= deadline) return null
            pause(700)
        }
    }

    private fun credential(service: String) = keyFor(service).also { check(it.isNotBlank()) { L10n.tr(R.string.t_connect_x_in_settings_first, "Connect %1\$s in Settings first.", DebridServices.name(service)) } }
    private fun premiumizeUrl(path: String, key: String) = "$PREMIUMIZE/$path${if ('?' in path) "&" else "?"}apikey=${Net.encode(key)}"
    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")
    private fun json(text: String): JSONObject = try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException(L10n.tr(R.string.t_the_debrid_service_returned_an_unreadable, "The debrid service returned an unreadable response.")) }

    private fun premiumizeOk(response: JSONObject) =
        check(response.optString("status") == "success") { L10n.tr(R.string.t_premiumize_could_not_complete_the_request, "Premiumize could not complete the request. Check your API key and plan.") }

    private fun allDebridData(text: String): JSONObject {
        val response = json(text)
        check(response.optString("status") == "success") {
            when (response.optJSONObject("error")?.optString("code")) {
                "AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY" -> "AllDebrid did not accept this API key."
                "AUTH_BLOCKED" -> "AllDebrid blocked this sign-in. Approve it in the email AllDebrid sent you, then try again."
                "MUST_BE_PREMIUM" -> "AllDebrid needs a premium account for this."
                else -> "AllDebrid could not complete the request."
            }
        }
        return response.optJSONObject("data") ?: JSONObject()
    }

    companion object {
        val IDS = listOf("realdebrid", "alldebrid", "premiumize", "debridlink", "deepbrid")
        /** Services that can only tell whether a link is cached by trying it. */
        val CHECK_ON_PLAY = setOf("realdebrid", "alldebrid", "debridlink", "deepbrid")
        /** How many links a check-on-play attempt tries before giving up. */
        const val CHECK_LIMIT = 7
        /**
         * Real-Debrid refuses every add on an account for a while after a few new torrents in a row, so it gets fewer
         * tries; the other services keep [CHECK_LIMIT].
         */
        const val REAL_DEBRID_CHECK_LIMIT = 3
        fun checkLimit(services: List<String>) = if (RealDebridFilter.SERVICE in services) REAL_DEBRID_CHECK_LIMIT else CHECK_LIMIT
        const val LOG_TAG = "PovDebrid"
        private const val REAL_DEBRID = "https://api.real-debrid.com/rest/1.0"
        private const val ALL_DEBRID = "https://api.alldebrid.com"
        private const val PREMIUMIZE = "https://www.premiumize.me/api"
        private const val DEBRID_LINK = "https://debrid-link.com/api/v2"
        /** Deepbrid's REST API (key from deepbrid.com/devices, sent as a Bearer token). */
        private const val DEEPBRID = "https://www.deepbrid.com/api/v1"
        /** A cached torrent shows as downloaded within a few seconds of the add. */
        private const val DEEPBRID_WAIT_MS = 6_000L
        internal fun isDeepbridHost(link: String) = try {
            java.net.URI(link).host?.lowercase()?.let { it == "deepbrid.com" || it.endsWith(".deepbrid.com") } == true
        } catch (_: Exception) { false }
        private const val AGENT = "povrajput"
        private val HASH = Regex("[a-f0-9]{40}")
        private val TORRENT_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"
        private fun tag(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun isHttps(value: String) = try {
            val uri = URI(value); uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        } catch (_: Exception) { false }
    }
}

/** Requests go through [Net]; tests replace this with canned responses. */
internal interface LinkTransport {
    fun get(url: String, headers: Map<String, String>): String
    fun delete(url: String, headers: Map<String, String>): String
    fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>): String
    fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject): String
    /**
     * Follows a short download link's redirects (sending [headers] only to the first host) and returns the final HTTPS
     * address with the file's name and size, or null when it leads nowhere usable. Reads no more than the headers.
     */
    fun follow(url: String, headers: Map<String, String>): Triple<String, String, Long>? = LinkFollower.follow(url, headers)
    /** A form post with one file part (an NZB for Deepbrid). */
    fun upload(url: String, headers: Map<String, String>, field: String, fileName: String, bytes: ByteArray): String =
        Net.multipart(url, emptyMap(), headers, mapOf(field to (fileName to bytes))).toString()

    companion object {
        val NET = object : LinkTransport {
            override fun get(url: String, headers: Map<String, String>) = Net.raw(url, "GET", headers)
            override fun delete(url: String, headers: Map<String, String>) = Net.raw(url, "DELETE", headers)
            override fun form(method: String, url: String, headers: Map<String, String>, fields: List<Pair<String, String>>) = Net.form(url, method, headers, fields)
            override fun json(method: String, url: String, headers: Map<String, String>, body: JSONObject) = Net.raw(url, method, headers, body)
        }
    }
}

data class DebridFile(val id: Long, val name: String, val size: Long, val link: String)

/** Redirect walking for [LinkTransport.follow]: a ranged GET of one byte at each hop, so no file is downloaded. */
internal object LinkFollower {
    private val FILENAME = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)

    fun follow(start: String, headers: Map<String, String>): Triple<String, String, Long>? {
        var address = start
        val firstHost = runCatching { java.net.URL(start).host }.getOrNull() ?: return null
        repeat(10) {
            val url = java.net.URL(address)
            if (url.protocol != "https") return null
            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Range", "bytes=0-0")
                if (url.host == firstHost) headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    address = java.net.URL(url, connection.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val size = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                val named = FILENAME.find(connection.getHeaderField("Content-Disposition").orEmpty())?.groupValues?.get(1)
                val raw = named ?: url.path.substringAfterLast('/')
                val name = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                return Triple(address, name.ifBlank { "video" }, size)
            } finally { connection.disconnect() }
        }
        return null
    }
}

/** Real-Debrid is refusing every add on the account for a while (its 451 on names it otherwise takes). */
class RealDebridPausedException(message: String) : IllegalStateException(message)
