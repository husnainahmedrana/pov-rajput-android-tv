package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.CloudFile
import dev.pov.tv.data.DebridServices
import dev.pov.tv.data.LinkDebrids
import dev.pov.tv.data.OffcloudApi
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.PreparedSource
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceResolver
import dev.pov.tv.data.TorBoxApi

/**
 * Prepares a link picked in the player's Streams panel inside the player, so the video switches without leaving it.
 * It follows the source list's rules (cached links, check-on-play services, TorBox/Offcloud/link-debrid files); a pack
 * with several files plays its preferred file, or asks which one. Runs on a background thread.
 */
internal class StreamSwitcher(context: Context) {
    private val store = SecureStore(context.applicationContext)
    private val torbox = TorBoxApi(store)
    private val resolver = SourceResolver(store, torbox)

    sealed interface Result {
        data class Ready(val url: String, val headers: Map<String, String>, val service: String?, val name: String, val size: Long) : Result
        data class ChooseFile(val prepared: PreparedSource, val files: List<Triple<Long, String, Long>>) : Result
        data class Failed(val message: String) : Result
    }

    fun prepare(source: SourceItem, identity: PlaybackIdentity, ordered: List<SourceItem>): Result {
        val prepared = if (!source.cached && source.directUrl.isEmpty()) {
            if (DebridServices.connected(store).none { it in LinkDebrids.CHECK_ON_PLAY })
                return Result.Failed("This link isn't cached on your debrid services. Pick a cached link.")
            resolver.prepareFirstCached(ordered.dropWhile { it != source }.ifEmpty { listOf(source) }, identity)
                ?: return Result.Failed("None of the next links are cached. Pick another link.")
        } else resolver.prepare(source, identity)
        return file(prepared, source, null)
    }

    /** The file to play from [prepared]: [fileId] when the viewer chose one, else the only or preferred file. */
    fun file(prepared: PreparedSource, source: SourceItem, fileId: Long?): Result = when (prepared) {
        is PreparedSource.Direct -> Result.Ready(prepared.url, prepared.headers, null,
            source.title.lineSequence().firstOrNull().orEmpty(), source.sizeBytes ?: 0L)
        is PreparedSource.OffcloudFiles -> {
            val chosen = pick(prepared.files, fileId, prepared.preferredFileId) { it.id }
            when {
                chosen == null -> Result.ChooseFile(prepared, prepared.files.map { Triple(it.id, it.name, it.size) })
                !OffcloudApi(store).owns(prepared) -> Result.Failed("Connect Offcloud and pick the link again.")
                else -> Result.Ready(chosen.url, emptyMap(), "offcloud", chosen.name, chosen.size)
            }
        }
        is PreparedSource.Links -> {
            val chosen = pick(prepared.files, fileId, prepared.preferredFileId) { it.id }
            if (chosen == null) Result.ChooseFile(prepared, prepared.files.map { Triple(it.id, it.name, it.size) })
            else Result.Ready(resolver.streamUrl(prepared, chosen), emptyMap(), prepared.service, chosen.name, chosen.size)
        }
        is PreparedSource.Files -> {
            val chosen = pick(prepared.files, fileId, prepared.preferredFileId) { it.id }
            if (chosen == null) Result.ChooseFile(prepared, prepared.files.map { Triple(it.id, it.name, it.size) })
            else Result.Ready(torBoxUrl(prepared, chosen), emptyMap(), "torbox", chosen.name, chosen.size)
        }
    }

    private fun torBoxUrl(prepared: PreparedSource.Files, file: CloudFile): String {
        val tag = prepared.accountTag ?: return torbox.streamUrl(prepared.folder.kind, prepared.folder.id, file.id)
        check(torbox.ownsTag(tag)) { "The TorBox account changed. Pick the link again." }
        prepared.resolvedUrls[file.id]?.let { return it }
        val hash = checkNotNull(prepared.sourceInfoHash) { "Pick the link again." }
        val resolved = torbox.prepareCached(SourceItem(file.name, "TorBox", hash, cached = true), prepared.identity, selectedFile = file)
        check(resolved.accountTag == tag && torbox.ownsTag(tag)) { "The TorBox account changed. Pick the link again." }
        return resolved.resolvedUrls.values.single()
    }

    private fun <T> pick(files: List<T>, chosen: Long?, preferred: Long?, id: (T) -> Long): T? = when {
        chosen != null -> files.firstOrNull { id(it) == chosen }
        files.size == 1 -> files.single()
        preferred != null -> files.firstOrNull { id(it) == preferred }
        else -> null
    }
}
