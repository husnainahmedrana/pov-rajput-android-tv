package dev.pov.tv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.datasource.DefaultHttpDataSource
import dev.pov.tv.data.Easynews
import dev.pov.tv.data.MediaFlowProxy
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.StreamSubtitle

/**
 * Play with (Settings → Playback): the built-in player, or another app on this device such as Kodi, Dune HD Media
 * Center or VLC. Those players bring their own video pipeline (Dolby Vision Profile 7 on Amlogic boxes, their own
 * audio passthrough), which Android's MediaCodec doesn't offer apps.
 *
 * Every link is served to the other app from this device by [LocalStreamServer], plain debrid links too: that is how
 * Nabrio knows where the other app is in the video (the byte it reads, against the video's length from its header),
 * for Continue Watching and Trakt/Simkl. Usenet (news providers, RAR, 7z), Easynews (saved login), MediaFlow Proxy and
 * links that need request headers work the same way, without any login in the address the other app gets.
 *
 * Players that close at the end and report where they stopped (MX Player, Just Player, VLC) return straight to Nabrio
 * with the exact position. Kodi and Dune HD Media Center stay open; when they stop reading for a few seconds the video
 * has stopped, so progress is saved and Nabrio comes back to the front (Android allows that only with "Display over
 * other apps").
 */
internal object ExternalPlayer {
    const val BUILT_IN = ""
    const val ASK = "ask"
    const val REQUEST = 704
    private const val VLC = "org.videolan.vlc"
    private const val PREFS = "pov_player"
    private const val KEY = "external_player"

    /** [BUILT_IN], [ASK] or the package name of the chosen app. */
    fun choice(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, BUILT_IN).orEmpty()
    fun set(context: Context, value: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply() }
    fun enabled(context: Context): Boolean = choice(context) != BUILT_IN

    /** Apps on this device that open a video address, by label (Nabrio itself left out). */
    fun players(context: Context): List<Pair<String, String>> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://127.0.0.1/video.mkv"), "video/*")
        val pm = context.packageManager
        val found = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        return found.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }

    fun label(context: Context, value: String = choice(context)): String = when (value) {
        BUILT_IN -> context.getString(R.string.t_built_in_player)
        ASK -> context.getString(R.string.t_ask_each_time)
        else -> players(context).firstOrNull { it.first == value }?.second ?: value
    }

    /** True when Android lets Nabrio come back to the front by itself after another app stops playing. */
    fun canReturn(context: Context): Boolean = Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(context)

    /** Opens Android's "Display over other apps" page for Nabrio; false when this device has no such page. */
    fun openReturnSettings(activity: Activity): Boolean = runCatching {
        activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    }.isSuccess

    private var server: LocalStreamServer? = null
    @Volatile private var tracked: ExternalProgress? = null
    /** Set from MainActivity's onResume/onPause: Nabrio is on screen. */
    @Volatile var appVisible = false
    /** A video in another app ended since Nabrio was last on screen: its rows need refreshing. */
    @Volatile var endedUnseen = false

    /**
     * Opens [url] in the chosen app. Returns false (and the caller plays it in the built-in player) when there is no
     * chosen app any more. Called on the main thread.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun launch(activity: Activity, url: String, headers: Map<String, String>, title: String, mediaName: String, resumeMs: Long,
               debridService: String?, origin: String, subtitles: List<StreamSubtitle> = emptyList(),
               key: String = "", identity: PlaybackIdentity? = null, runtimeMs: Long? = null,
               status: (String, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }, failed: (Exception) -> Unit = {}): Boolean {
        val chosen = choice(activity)
        if (chosen == BUILT_IN) return false
        if (chosen != ASK && players(activity).none { it.first == chosen }) return false
        val store = SecureStore(activity.applicationContext)
        val routed = MediaFlowProxy(store).route(url, headers, PlaybackDebrid.normalize(debridService), MediaFlowProxy.Origin.decode(origin))
        // The release's own name (the link list joins it with its details line): other players show it as the title.
        val name = mediaName.substringBefore(" · ").lineSequence().first().trim().ifBlank { title }
        finishTracking(activity)
        val served = serve(activity, store, routed.url, routed.headers, name)
        tracked = ExternalProgress(activity.applicationContext, served, key, title, identity, runtimeMs)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(served.address), LocalStreamServer.contentType(LocalStreamServer.safeName(name)))
            .putExtra("title", title).putExtra(Intent.EXTRA_TITLE, title)
            .putExtra("return_result", true).putExtra("from_start", resumeMs <= 0)
        // Where to start: MX Player and Just Player read "position" as a whole number of ms, VLC as a long; Kodi takes
        // none from other apps and is sought through its remote control instead ([KodiResume]).
        if (chosen == VLC) view.putExtra("position", resumeMs.coerceAtLeast(0))
        else view.putExtra("position", resumeMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        addSubtitles(view, orderSubtitles(subtitles, SubtitlePreferences(activity).language))
        if (chosen != ASK) view.setPackage(chosen)
        // The stream is opened (and its start read) first, so the other app's first request is answered at once; then
        // the app opens. [status] shows the wait, [cancelled] (Back) stops it, [failed] says why it couldn't open.
        // [status] gets a line and the share read (-1 while connecting, before the first bytes).
        status(activity.getString(if (dev.pov.tv.data.UsenetLinks.isNzb(url)) R.string.t_connecting_to_your_news_provider
            else R.string.t_opening_the_stream), -1)
        Thread({
            val error = try {
                served.warm(WARM_BYTES) { percent ->
                    activity.runOnUiThread { if (!cancelled()) status(activity.getString(R.string.t_opening_the_stream), percent) }
                }
                runCatching { served.durationMs() }; null
            } catch (error: Exception) { error }
            activity.runOnUiThread {
                if (cancelled() || activity.isFinishing || activity.isDestroyed) { stop(activity); return@runOnUiThread }
                if (error != null) { stop(activity); failed(error); return@runOnUiThread }
                try {
                    // For a result, in Nabrio's own task: a player that closes at the end lands back on this page.
                    launchedAt = SystemClock.elapsedRealtime()
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(if (chosen == ASK) Intent.createChooser(view, activity.getString(R.string.t_play_with)) else view, REQUEST)
                    status("", 100)
                    if (chosen == KodiResume.PACKAGE) {
                        KodiResume.start(activity, served, resumeMs)
                        // Stop in Kodi brings the viewer back to this page.
                        KodiResume.watchStop(activity, generation = { tracked?.let { server === served } ?: false }) {
                            tick(activity.applicationContext); bringBack(activity.applicationContext)
                        }
                    }
                } catch (launch: Exception) { stop(activity); failed(launch) }
            }
        }, "external-warm").start()
        return true
    }

    /** When the other app was opened: Kodi and Dune open in their own task, and Android reports them "closed" at once. */
    @Volatile private var launchedAt = 0L
    private const val WARM_BYTES = 4 * 1024 * 1024

    /**
     * The other app closed and came back to Nabrio. MX Player and Just Player send "position"/"duration" (and
     * "end_by"), VLC "extra_position"/"extra_duration": exact values beat the estimate from the bytes read.
     */
    fun returned(context: Context, data: Intent?) {
        val progress = tracked ?: return
        // An answer within seconds and without a position is Android's for an app that opened in its own task (Kodi,
        // Dune), not the end of the video: tracking carries on from the bytes it reads.
        val reported = data?.extras?.let { it.containsKey("position") || it.containsKey("extra_position") } == true
        if (!reported && SystemClock.elapsedRealtime() - launchedAt < INSTANT_RESULT_MS) return
        fun number(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
            data?.extras?.get(name)?.let { (it as? Number)?.toLong() }
        }
        val duration = number("duration", "extra_duration")?.takeIf { it > 0 }
        val position = if (data?.getStringExtra("end_by") == "playback_completion") duration
            else number("position", "extra_position")?.takeIf { it >= 0 }
        progress.finish(position, duration)
        endedUnseen = true
    }

    /** Called every few seconds by [ExternalStreamService]: saves progress and notices the other app stopping. */
    internal fun tick(context: Context) {
        val progress = tracked ?: return
        if (progress.tick() && !appVisible) bringBack(context)
    }

    private fun bringBack(context: Context) {
        endedUnseen = true
        if (!canReturn(context)) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /**
     * Subtitles from the viewer's add-ons, the chosen subtitle language first ("" and "off" keep the add-ons' order;
     * "off" sends none). Subtitles inside the video file need nothing: every player shows those itself.
     */
    fun orderSubtitles(subtitles: List<StreamSubtitle>, language: String): List<StreamSubtitle> {
        if (language == "off") return emptyList()
        val usable = subtitles.filter { Uri.parse(it.url).scheme in setOf("http", "https") }.distinctBy { it.url }.take(MAX_SUBTITLES)
        if (language.isEmpty()) return usable
        fun matches(code: String) = code.isNotBlank() && (code.equals(language, true) ||
            runCatching { java.util.Locale(code).isO3Language.equals(java.util.Locale(language).isO3Language, true) }.getOrDefault(false))
        return usable.filter { matches(it.language) } + usable.filterNot { matches(it.language) }
    }

    /**
     * The extras other players read: MX Player and Just Player take a list ("subs", the first switched on), VLC one
     * ("subtitles_location"). Kodi and Dune HD Media Center take none from other apps; they find subtitles themselves.
     */
    private fun addSubtitles(view: Intent, subtitles: List<StreamSubtitle>) {
        if (subtitles.isEmpty()) return
        val uris = subtitles.map { Uri.parse(it.url) }
        view.putExtra("subs", uris.toTypedArray<android.os.Parcelable>())
            .putExtra("subs.name", subtitles.map { listOf(it.language, it.source).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Subtitles" } }.toTypedArray())
            .putExtra("subs.enable", arrayOf<android.os.Parcelable>(uris.first()))
            .putExtra("subtitles_location", subtitles.first().url)
    }

    private const val MAX_SUBTITLES = 20
    private const val INSTANT_RESULT_MS = 6_000L

    /**
     * The same sources the built-in player reads, outside the player: Usenet (news providers), Easynews with the saved
     * login, and plain links with their request headers. Used by the local stream server and built-in seek previews.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun sources(context: Context, store: SecureStore, headers: Map<String, String>): androidx.media3.datasource.DataSource.Factory {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(60_000).setDefaultRequestProperties(headers)
        return EasynewsRoutingDataSource.Factory(http, { Easynews.connections(store) },
            usenet = { address -> UsenetPlayback.session(address, store, app.cacheDir) { note ->
                main.post { android.widget.Toast.makeText(app, note, android.widget.Toast.LENGTH_LONG).show() } } }) {
            Easynews.credentials(store)?.let { (user, password) -> runCatching { Easynews.auth(user, password) }.getOrNull() }
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun serve(context: Context, store: SecureStore, url: String, headers: Map<String, String>, name: String): LocalStreamServer {
        val app = context.applicationContext
        val source = sources(app, store, headers)
        synchronized(this) {
            server?.close()
            val made = LocalStreamServer(source, Uri.parse(url), name) { android.util.Log.i("PovExternal", it) }
            server = made
            runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ExternalStreamService::class.java)) }
            return made
        }
    }

    /** The last video in another app: its progress is final (saved, scrobble stopped) before anything else plays. */
    private fun finishTracking(context: Context?) {
        tracked?.let { it.finish(null, null); tracked = null }
    }

    /** Ends the stream served to another app (Stop in the notification, the built-in player starting, idle). */
    fun stop(context: Context?) {
        finishTracking(context)
        val running = synchronized(this) { server.also { server = null } } ?: return
        running.close()
        context?.applicationContext?.let { runCatching { it.stopService(Intent(it, ExternalStreamService::class.java)) } }
        // The news connections would otherwise stay with the account until they time out.
        UsenetSessions.close()
    }

    /** Milliseconds since the served stream was last read, or null when nothing is served or a read is going on. */
    internal fun idleMs(): Long? = synchronized(this) { server }?.let { if (it.busy > 0) 0L else System.currentTimeMillis() - it.lastActive.get() }
}

/**
 * Progress of one video playing in another app, saved like the built-in player's: Continue Watching (every 15 s and at
 * the end) and the chosen tracker's scrobbles (start when reading begins, stop when it ends, start again if it resumes).
 */
internal class ExternalProgress(
    context: Context,
    private val server: LocalStreamServer,
    private val key: String,
    private val title: String,
    private val identity: PlaybackIdentity?,
    private val runtimeMs: Long?,
) {
    private val store = SecureStore(context)
    private val history = PlaybackStore(context)
    /** The main tracker and the other signed-in ones that also keep watch history, each with its own offline queue. */
    private val trackers: List<Pair<dev.pov.tv.data.Tracker, PlaybackStore>> by lazy {
        dev.pov.tv.data.Trackers.historyTargets(store).map { id -> dev.pov.tv.data.Trackers.create(store, id) to PlaybackStore(context, dev.pov.tv.data.Trackers.outboxKey(id)) }
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var started = false
    private var playing = false
    private var finished = false
    private var lastSave = 0L
    private var idleSince = 0L
    private var duration: Long? = null

    /** Updates progress; true the moment the other app stops reading (the video stopped or ended). */
    @Synchronized fun tick(): Boolean {
        if (finished) return false
        val now = System.currentTimeMillis()
        if (!started && server.playhead >= 0) {
            started = true; playing = true
            worker.execute {
                duration = server.durationMs() ?: runtimeMs
                runCatching { history.show(key, identity) }
                identity?.let { media -> trackers.forEach { (tracker, _) -> runCatching { tracker.allowPlayback(media) } } }
                scrobble("start", percent() ?: 0.0)
            }
            return false
        }
        if (!started) return false
        if (server.busy > 0) {
            idleSince = 0
            if (!playing) { playing = true; worker.execute { scrobble("start", percent() ?: 0.0) } }
            if (now - lastSave >= SAVE_MS) { lastSave = now; worker.execute { save(position()) } }
            return false
        }
        if (idleSince == 0L) idleSince = now
        if (playing && now - idleSince >= STOPPED_MS) {
            playing = false
            worker.execute { val at = position(); save(at); scrobble("stop", percent(at) ?: 0.0) }
            return true
        }
        return false
    }

    /** The other app is done with this video ([position]/[length] when it said where it stopped). */
    @Synchronized fun finish(position: Long?, length: Long?) {
        if (finished) return
        finished = true
        if (!started && position == null) { worker.shutdown(); return }
        length?.let { duration = it }
        val wasPlaying = playing || position != null
        playing = false
        worker.execute {
            if (duration == null) duration = server.durationMs() ?: runtimeMs
            val at = position ?: position()
            save(at)
            if (wasPlaying) scrobble("stop", percent(at) ?: 0.0)
        }
        worker.shutdown()
    }

    private fun position(): Long? {
        val total = duration ?: return null
        val size = server.knownSize.takeIf { it > 0 } ?: return null
        val head = server.playhead.takeIf { it >= 0 } ?: return null
        return (head.toDouble() / size * total).toLong().coerceIn(0, total)
    }

    private fun percent(at: Long? = position()): Double? {
        val total = duration?.takeIf { it > 0 } ?: return null
        return ((at ?: return null).toDouble() * 100 / total).coerceIn(0.0, 100.0)
    }

    private fun save(at: Long?) {
        val total = duration ?: return
        if (key.isBlank() || at == null) return
        runCatching { history.save(key, title, identity, at, total) }
    }

    /** The same calls as the built-in player's scrobbles, through the tracker's offline queue. */
    private fun scrobble(action: String, percent: Double) {
        val media = identity ?: return
        if (history.isHidden(identity = media)) return
        for ((tracker, outbox) in trackers) try {
            if (!tracker.scrobblingEnabled() || !tracker.connected()) continue
            val account = tracker.accountRevision()
            val scrobbleRevision = tracker.scrobbleRevision()
            val revision = if (action != "start") runCatching { outbox.queue(media, percent, action, account, scrobbleRevision = scrobbleRevision) }.getOrNull() else null
            if (tracker.progressForAccount(media, percent, action, account, scrobbleRevision) && revision != null) outbox.acknowledge(media, revision)
        } catch (_: Exception) { /* The queue retries a final state later. */ }
    }

    private companion object {
        const val SAVE_MS = 15_000L
        /** Kodi and Dune keep the connection open while paused; closed for this long means the video stopped. */
        const val STOPPED_MS = 8_000L
    }
}

/** Keeps Nabrio running while another app plays a video it serves; Stop ends it, and it ends itself when left idle. */
class ExternalStreamService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            ExternalPlayer.tick(this@ExternalStreamService)
            val idle = ExternalPlayer.idleMs()
            if (idle == null || idle > IDLE_STOP_MS) { ExternalPlayer.stop(this@ExternalStreamService); stopSelf(); return }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { ExternalPlayer.stop(this); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.t_external_player), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ExternalStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.pov_ic_screen)
            .setContentTitle(getString(R.string.t_streaming_to_another_player))
            .setContentText(getString(R.string.t_nabrio_keeps_serving_this_video))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.t_stop), stop).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) { /* Not allowed right now: the stream still works while Nabrio stays in memory. */ }
        handler.removeCallbacks(check); handler.postDelayed(check, CHECK_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(check); super.onDestroy() }

    private companion object {
        const val CHANNEL = "external_player"
        const val NOTIFICATION = 7301
        const val ACTION_STOP = "dev.pov.tv.STOP_EXTERNAL_STREAM"
        const val CHECK_MS = 4_000L
        /** A paused video in Kodi still holds its connection; this long with none open means it's gone. */
        const val IDLE_STOP_MS = 30L * 60 * 1000
    }
}
