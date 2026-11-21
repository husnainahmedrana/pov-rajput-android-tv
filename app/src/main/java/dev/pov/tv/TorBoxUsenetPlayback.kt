package dev.pov.tv

import dev.pov.tv.data.ApiException
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.TorBoxApi
import dev.pov.tv.data.TorBoxUsenet
import dev.pov.tv.data.UsenetLinks
import dev.pov.tv.usenet.Nzb
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TorBox Usenet (Settings → Sources → Usenet, experimental, TorBox Pro): an indexer's link plays from TorBox.
 *
 * On play ([prepare]): the NZB is fetched by Nabrio (device cache first; otherwise one grab, with the viewer's own
 * headers such as SABnzbd's), TorBox is asked whether it has the release, and the NZB file is attached (at once when
 * cached) or uploaded to be fetched. Nabrio then waits, showing TorBox's progress, until the download is ready, and
 * plays the biggest video in it from TorBox's servers.
 *
 * Before play ([checkKnown]): links whose NZB this device already has, and their indexer links, are checked without
 * spending a grab; the cached ones get a CACHED · TORBOX USENET badge.
 */
internal object TorBoxUsenetPlayback {
    /** TorBox Usenet is on and TorBox is connected: Usenet links show TORBOX USENET and play through TorBox. */
    @Volatile var active = false
        private set
    /** Usenet links play through Deepbrid instead (chosen in Settings and Deepbrid connected). */
    @Volatile var deepbrid = false
        private set
    private val cached = ConcurrentHashMap.newKeySet<String>()

    fun refresh(store: SecureStore, connected: List<String>) {
        val route = dev.pov.tv.data.UsenetRoute.get(store)
        active = route == dev.pov.tv.data.UsenetRoute.TORBOX && "torbox" in connected
        deepbrid = route == dev.pov.tv.data.UsenetRoute.DEEPBRID && "deepbrid" in connected
    }

    /**
     * Deepbrid Usenet: the NZB (fetched by Nabrio with the viewer's headers) is uploaded to Deepbrid, which serves the
     * files out of the archive at once; the biggest video plays.
     */
    fun prepareDeepbrid(address: String, name: String, store: SecureStore, cache: File?, progress: (String) -> Unit): String {
        progress(L10n.tr(R.string.t_getting_the_nzb, "Getting the NZB…"))
        val nzb = UsenetPlayback.nzbFor(address, store, cache)
        progress(L10n.tr(R.string.t_sending_it_to_deepbrid, "Sending it to Deepbrid…"))
        val links = dev.pov.tv.data.LinkDebrids(store)
        val files = links.deepbridNzb(nzb.toByteArray(Charsets.UTF_8), name)
        val file = TorBoxUsenet.pickFile(files.map { dev.pov.tv.data.CloudFile(it.id, it.name, it.size) })
            ?: throw ApiException(0, L10n.tr(R.string.t_deepbrid_found_no_playable_video, "Deepbrid found no playable video in this release (it may be compressed or incomplete). Choose another link."))
        return links.deepbridFileUrl(files.first { it.id == file.id }.link)
    }
    fun isCached(address: String) = active && address in cached

    /** Checks the links TorBox can be asked about for free (no grab); returns true when any badge changed. */
    fun checkKnown(addresses: List<String>, torbox: TorBoxApi, cache: File?): Boolean {
        if (!active) return false
        val byHash = HashMap<String, MutableList<String>>()
        addresses.filter(UsenetLinks::isNzb).distinct().take(60).forEach { address ->
            val nzb = UsenetPlayback.cachedNzb(address, cache)
            val hashes = if (nzb != null) hashesOf(nzb, address).take(4) else TorBoxUsenet.hashes(ByteArray(0), emptyList(), UsenetLinks.nzbAddress(address)).takeLast(1)
            hashes.forEach { byHash.getOrPut(it) { mutableListOf() } += address }
        }
        if (byHash.isEmpty()) return false
        val found = runCatching { torbox.usenetCached(byHash.keys.toList()) }.getOrDefault(emptySet())
        val links = found.flatMap { byHash[it].orEmpty() }.toSet()
        return links.isNotEmpty() && cached.addAll(links)
    }

    private fun hashesOf(nzb: String, address: String): List<String> {
        val ids = runCatching { Nzb.parse(nzb).files.mapNotNull { it.segments.firstOrNull()?.messageId } }.getOrDefault(emptyList())
        return TorBoxUsenet.hashes(nzb.toByteArray(Charsets.UTF_8), ids, UsenetLinks.nzbAddress(address))
    }

    /**
     * The TorBox stream address for an indexer link, waiting (up to [WAIT_MS]) while TorBox fetches the release.
     * [progress] gets short status lines for the screen; an interrupted thread (Back, another link) stops the wait.
     */
    fun prepare(address: String, name: String, store: SecureStore, torbox: TorBoxApi, cache: File?, progress: (String) -> Unit): String {
        progress(L10n.tr(R.string.t_getting_the_nzb, "Getting the NZB…"))
        val nzb = UsenetPlayback.nzbFor(address, store, cache)
        val bytes = nzb.toByteArray(Charsets.UTF_8)
        progress(L10n.tr(R.string.t_checking_torbox, "Checking TorBox…"))
        val isCached = torbox.usenetCached(hashesOf(nzb, address)).isNotEmpty()
        val id = if (isCached) {
            try { torbox.addNzb(bytes, name, onlyIfCached = true) }
            catch (error: ApiException) { torbox.addNzb(bytes, name) }           // TorBox's cache answer can't always attach
        } else torbox.addNzb(bytes, name)
        if (isCached) cached += address
        val started = System.currentTimeMillis()
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
            val job = torbox.usenetJob(id) ?: throw ApiException(404, L10n.tr(R.string.t_torbox_removed_this_usenet_download, "TorBox removed this Usenet download."))
            val state = job.optString("download_state").lowercase()
            if (Regex("fail|error|expir|missing|abort|corrupt").containsMatchIn(state)) {
                val detail = listOf("error_message", "error", "detail").firstNotNullOfOrNull { job.optString(it).takeIf(String::isNotBlank) }
                throw ApiException(0, L10n.tr(R.string.t_torbox_couldn_t_fetch_this_release, "TorBox couldn't fetch this release (%1\$s). Choose another link.",
                    (detail ?: state).take(120)))
            }
            val ready = job.optBoolean("download_finished") && job.optBoolean("download_present", true)
            if (ready) {
                cached += address
                val folder = torbox.parseFolder(job, "usenet")
                val file = TorBoxUsenet.pickFile(folder.files)
                    ?: throw ApiException(0, L10n.tr(R.string.t_torbox_found_no_video_in_this_release, "TorBox found no video in this release."))
                return torbox.streamUrl("usenet", id, file.id)
            }
            val percent = (job.optDouble("progress", 0.0) * 100).toInt().coerceIn(0, 100)
            progress(if (percent > 0) L10n.tr(R.string.t_torbox_is_fetching_x, "TorBox is fetching this release · %1\$s%%", percent)
                else L10n.tr(R.string.t_waiting_for_torbox, "Waiting for TorBox…"))
            if (System.currentTimeMillis() - started > WAIT_MS) throw ApiException(0,
                L10n.tr(R.string.t_torbox_is_still_fetching, "TorBox is still fetching this release. It will be in your TorBox library when it's done."))
            Thread.sleep(POLL_MS)
        }
    }

    private const val POLL_MS = 2_000L
    private const val WAIT_MS = 15L * 60 * 1000
}
