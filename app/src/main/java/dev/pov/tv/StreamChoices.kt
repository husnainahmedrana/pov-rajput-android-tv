package dev.pov.tv

import android.os.Handler
import android.os.Looper
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SourceItem
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The links of the title that is playing, shared between the source search (MainActivity) and the player's Streams
 * panel. A fresh search fills it at once; playing from Continue Watching searches in the background so the panel is
 * usually ready by the time it is opened.
 */
internal object StreamChoices {
    @Volatile var key = ""
        private set
    /** In the viewer's sort order, links this TV cannot decode last. Null while searching or before any search. */
    @Volatile var items: List<SourceItem>? = null
        private set
    @Volatile var searching = false
        private set
    @Volatile var failed = false
        private set

    /** A link picked in the player and the position to continue from; MainActivity plays it once the player closes. */
    @Volatile var pendingSwitch: Triple<PlaybackIdentity, SourceItem, Long>? = null

    /** Starts a search for a title (set by MainActivity), for a player opened without one. */
    @Volatile var searcher: ((PlaybackIdentity) -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun keyOf(identity: PlaybackIdentity) = "${identity.imdbId}:${identity.season}:${identity.episode}"
    fun has(identity: PlaybackIdentity) = key == keyOf(identity) && (items != null || searching)

    fun begin(identity: PlaybackIdentity) { key = keyOf(identity); items = null; searching = true; failed = false; changed() }
    fun publish(identity: PlaybackIdentity, links: List<SourceItem>) { key = keyOf(identity); items = links; searching = false; failed = false; changed() }
    fun fail(identity: PlaybackIdentity) { if (key == keyOf(identity)) { searching = false; failed = true; changed() } }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = main.post { listeners.forEach { it() } }
}
