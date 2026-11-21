package dev.pov.tv.data

/**
 * A watch-tracking service: WeTrakr or Simkl. One is chosen in Settings and supplies Home's Continue Watching,
 * watchlists and Up Next, receives scrobbles, and is the target of watchlist and watched actions.
 */
interface Tracker {
    val name: String
    /** Personal and liked lists. Simkl has no list API yet, so its screens hide them. */
    val supportsLists: Boolean get() = true

    /** Signed in, whether tracking is switched on or off. */
    fun signedIn(): Boolean
    /** Signed in and switched on: only then are rows, badges, scrobbles and list actions used. */
    fun connected(): Boolean = signedIn() && enabled()
    /** Off keeps the sign-in but stops everything the service does in the app. */
    fun enabled(): Boolean
    fun setEnabled(on: Boolean)
    /** Changes whenever the linked account changes, so cached rows and queued scrobbles never cross accounts. */
    fun accountRevision(): String
    fun disconnect()
    fun startDeviceAuth(): DeviceAuth
    fun pollDeviceAuth(auth: DeviceAuth): Boolean
    fun cancelDeviceAuth(auth: DeviceAuth? = null)

    fun scrobblingEnabled(): Boolean
    fun scrobbleRevision(): String
    fun setScrobblingEnabled(enabled: Boolean)
    /** A deliberate new play lifts the block set by [removeContinueWatching]. */
    fun allowPlayback(identity: PlaybackIdentity)
    fun progressForAccount(identity: PlaybackIdentity, percent: Double, action: String, revision: String,
                           expectedScrobbleRevision: String = scrobbleRevision()): Boolean
    /** Replays a queued pause/stop saved while offline; [timestamp] is when it happened. */
    fun restoreProgress(identity: PlaybackIdentity, percent: Double, action: String, timestamp: String, revision: String,
                        expectedScrobbleRevision: String = scrobbleRevision()): Boolean

    fun continueWatching(): List<Pair<PlaybackIdentity, Double>>
    /** Forgets cached library reads, so the next one asks the service again (e.g. after the player closes). */
    fun refreshCaches()
    /** Forgets only the progress reads (Continue Watching, Up Next), e.g. after the player closes. */
    fun refreshContinueWatching() = refreshCaches()
    /** A message while the service has asked the app to wait (rate limit or daily quota); no request is sent then. */
    fun waitMessage(): String? = null
    fun removeContinueWatching(identity: PlaybackIdentity, revision: String = accountRevision())
    fun nextEpisodes(): List<PlaybackIdentity>
    fun watchlist(type: String): List<MediaItemData>
    fun setWatchlist(identity: PlaybackIdentity, add: Boolean)
    fun markWatched(identity: PlaybackIdentity)
    fun markSeasonWatched(show: PlaybackIdentity, season: Int, episodes: List<Int>) {
        episodes.distinct().forEach { markWatched(show.copy(type = "series", season = season, episode = it)) }
    }

    /** A cheap stamp that moves whenever watched history changes, so the full history is only fetched when needed. */
    fun watchedStamp(): String
    /** Watched movies, fully watched shows and watched episodes, for the ✓ badges. */
    fun watched(): WatchedState

    fun lists(): List<Pair<String, String>>
    fun listItems(id: String): List<MediaItemData>
    fun removeListItem(listId: String, identity: PlaybackIdentity)
    fun likedLists(): List<Pair<String, String>>
    fun likedListItems(id: String): List<MediaItemData>
    fun likedListPreview(id: String): List<MediaItemData>
}

/** The tracker chosen in Settings. WeTrakr stays the default for existing installs. */
object Trackers {
    const val KEY = "tracker.service"
    const val SIMKL = "simkl"
    const val WETRAKR = "wetrakr"
    const val TRAKT = "trakt"
    val ALL = listOf(WETRAKR, TRAKT, SIMKL)
    fun selected(store: SecureStore) = store.get(KEY).takeIf { it in ALL } ?: WETRAKR
    fun select(store: SecureStore, id: String) = store.put(KEY, id.takeIf { it in ALL } ?: WETRAKR)
    /** Each service keeps its own offline scrobble queue. */
    fun outboxKey(id: String) = when (id) { SIMKL -> "simkl.pending"; TRAKT -> "trakt.pending"; else -> "wetrakr.pending" }
    fun create(store: SecureStore, id: String): Tracker = when (id) { SIMKL -> SimklApi(store); TRAKT -> TraktApi(store); else -> WeTrakrApi(store) }
    /**
     * The main service supplies Continue Watching, Up Next, watchlists and lists. Every other service the viewer
     * signed in to also gets watch history (scrobbles and marked-watched titles) unless switched off here.
     */
    fun historyKey(id: String) = "tracker.history.$id"
    fun sendsHistory(store: SecureStore, id: String) = id == selected(store) || store.get(historyKey(id)) != "false"
    /** Main first, then the others that send history; callers still check [Tracker.connected]. */
    fun historyTargets(store: SecureStore): List<String> = selected(store).let { main -> listOf(main) + ALL.filter { it != main && sendsHistory(store, it) } }
}
