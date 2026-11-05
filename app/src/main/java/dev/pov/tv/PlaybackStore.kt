package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaybackStore(context: Context, private val pendingKey: String = "pending") {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    data class Recent(val key: String, val title: String, val identity: PlaybackIdentity?, val position: Long, val duration: Long)
    data class Pending(val identity: PlaybackIdentity, val percent: Double, val action: String, val revision: String, val accountRevision: String = "", val timestamp: String = "", val scrobbleRevision: String = "")

    fun save(key: String, title: String, identity: PlaybackIdentity?, position: Long, duration: Long) = synchronized(lock) {
        if (position < 0 || duration <= 0) return@synchronized
        val entries = recent().filterNot { it.key == key }.toMutableList()
        entries.add(0, Recent(key, title, identity, position, duration))
        val json = JSONArray()
        entries.take(40).forEach {
            json.put(JSONObject().put("key", it.key).put("title", it.title)
                .put("identity", it.identity?.toJson()).put("position", it.position).put("duration", it.duration))
        }
        preferences.edit().putString("recent", json.toString()).apply()
        identity?.let { preferences.edit().putLong("launcher:${it.type}:${it.imdbId}",System.currentTimeMillis()).apply() }
        TvContinueWatching.request(appContext)
    }

    fun launcherPlayedAt(identity: PlaybackIdentity) = preferences.getLong("launcher:${identity.type}:${identity.imdbId}",0)

    fun recent(): List<Recent> = synchronized(lock) {
        try {
            val array = JSONArray(preferences.getString("recent", "[]"))
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Recent(item.getString("key"), item.getString("title"), item.optJSONObject("identity")?.let(PlaybackIdentity::fromJson),
                    item.optLong("position"), item.optLong("duration"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun hiddenToken(key: String, identity: PlaybackIdentity?): String = if (identity == null || identity.imdbId.isBlank()) "key:$key"
        else "media:${identity.type}:${identity.imdbId}:${identity.season}:${identity.episode}"

    fun isHidden(key: String = "", identity: PlaybackIdentity?): Boolean = synchronized(lock) {
        hiddenToken(key, identity) in preferences.getStringSet("hiddenContinue", emptySet()).orEmpty()
    }
    fun continueWatching(): List<Recent> = recent().filter { it.position < it.duration * 0.95 && !isHidden(it.key, it.identity) }

    /** Changes whenever the local Continue Watching row would look different (to the minute, like its "m left" captions). */
    fun continueRevision(): String = continueWatching().joinToString("|") { "${it.key}@${it.position / 60_000}/${it.duration / 60_000}" }

    /** Hides a local row and the same remote episode row, preserving resume/history data. */
    fun hide(key: String = "", identity: PlaybackIdentity?) = synchronized(lock) {
        val hidden = preferences.getStringSet("hiddenContinue", emptySet()).orEmpty().toMutableSet()
        hidden.add(hiddenToken(key, identity))
        check(preferences.edit().putStringSet("hiddenContinue", hidden).commit())
        TvContinueWatching.request(appContext)
    }
    /** A deliberate new playback session makes a hidden item visible again. Periodic saves do not. */
    fun show(key: String, identity: PlaybackIdentity?) = synchronized(lock) {
        identity?.let { TvContinueWatching.allow(appContext,it) }
        val hidden = preferences.getStringSet("hiddenContinue", emptySet()).orEmpty().toMutableSet()
        if (hidden.remove(hiddenToken(key, identity))) check(preferences.edit().putStringSet("hiddenContinue", hidden).commit())
        Unit
    }

    fun resume(key: String): Long = recent().find { it.key == key }?.let {
        if (it.position > 30_000 && it.duration > 0 && it.position < it.duration * 0.95) it.position else 0
    } ?: 0

    /** Each replacement gets a revision so an older network completion cannot remove it. */
    fun queue(identity: PlaybackIdentity, percent: Double, action: String, accountRevision: String = "", timestamp: String = java.time.Instant.now().toString(), scrobbleRevision: String = ""): String = synchronized(lock) {
        require(percent.isFinite() && percent in 0.0..100.0) { "Invalid playback progress." }
        require(action in setOf("start", "pause", "stop")) { "Invalid playback action." }
        val revision = UUID.randomUUID().toString()
        val kept = pendingLocked().filterNot { sameMedia(it.identity, identity) }.toMutableList()
        kept.add(Pending(identity, percent, action, revision, accountRevision, timestamp, scrobbleRevision))
        writePending(kept)
        revision
    }

    fun discard(identity: PlaybackIdentity) = synchronized(lock) {
        writePending(pendingLocked().filterNot { sameMedia(it.identity, identity) })
    }

    fun pending(): List<Pending> = synchronized(lock) { pendingLocked() }

    fun acknowledge(identity: PlaybackIdentity, revision: String) = synchronized(lock) {
        if (revision.isBlank()) return@synchronized
        val items = pendingLocked()
        val kept = items.filterNot { it.revision == revision && sameMedia(it.identity, identity) }
        if (kept.size != items.size) writePending(kept)
        Unit
    }

    /** Disconnecting or changing the tracking account must discard its unsent updates. */
    fun clearPending() = synchronized(lock) {
        check(preferences.edit().remove(pendingKey).commit()) { "Pending playback updates could not be removed." }
    }

    private fun pendingLocked(): List<Pending> {
        val array = try { JSONArray(preferences.getString(pendingKey, "[]")) }
            catch (_: Exception) { throw IllegalStateException("Saved playback updates could not be read.") }
        var migrated = false
        val result = (0 until array.length()).map { index ->
            try {
                val item = array.getJSONObject(index)
                val revision = item.optString("revision").ifBlank { migrated = true; UUID.randomUUID().toString() }
                Pending(PlaybackIdentity.fromJson(item.getJSONObject("identity")), item.getDouble("percent"), item.getString("action"), revision, item.optString("accountRevision"), item.optString("timestamp"), item.optString("scrobbleRevision"))
            } catch (_: Exception) {
                throw IllegalStateException("A saved playback update could not be read.")
            }
        }
        // Migrate old entries once, rather than generating a different revision on each read.
        if (migrated) writePending(result)
        return result
    }

    private fun writePending(items: List<Pending>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("identity", it.identity.toJson()).put("percent", it.percent)
                .put("action", it.action).put("revision", it.revision).put("accountRevision", it.accountRevision).put("timestamp", it.timestamp).put("scrobbleRevision", it.scrobbleRevision))
        }
        check(preferences.edit().putString(pendingKey, array.toString()).commit()) { "Playback updates could not be saved." }
    }

    private fun sameMedia(first: PlaybackIdentity, second: PlaybackIdentity): Boolean =
        first.imdbId == second.imdbId && first.type == second.type && first.season == second.season && first.episode == second.episode

    companion object {
        // MainActivity, PlayerActivity, and background retry use distinct store instances.
        private val lock = Any()
    }
}
