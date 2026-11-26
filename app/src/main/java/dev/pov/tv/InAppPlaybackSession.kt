package dev.pov.tv

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.WeTrakrApi
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * One in-app playback session shared by browsing and fullscreen.
 * No background service: playback pauses when all application activities leave the foreground.
 * Views borrow the player; destroying an Activity does not destroy the session.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object InAppPlaybackSession {
    private var application: Application? = null
    private var player: Player? = null
    /** The loopback server mpv reads the link from (Usenet, Easynews, headers, the proxy route); null with ExoPlayer. */
    private var mpvServer: LocalStreamServer? = null
    /** True while mpv plays this video (Settings → Player engine, or the Auto switch after a format failure). */
    var usingMpv = false; private set
    private var mediaSession: MediaSession? = null
    private var targetView: WeakReference<PlayerView>? = null
    private lateinit var history: PlaybackStore
    private lateinit var secureStore: SecureStore
    private val trackerApis = java.util.concurrent.ConcurrentHashMap<String, dev.pov.tv.data.Tracker>()
    private val trackerOutboxes = java.util.concurrent.ConcurrentHashMap<String, PlaybackStore>()
    /** Scrobbles go to the tracker chosen in Settings, each with its own offline queue. */
    private val selectedTracker get() = dev.pov.tv.data.Trackers.selected(secureStore)
    private val wetrakr: dev.pov.tv.data.Tracker get() = trackerApi(selectedTracker)
    private fun trackerApi(id: String) = trackerApis.computeIfAbsent(id) { dev.pov.tv.data.Trackers.create(secureStore, id) }
    private fun trackerOutbox(id: String) = trackerOutboxes.computeIfAbsent(id) { PlaybackStore(application!!, dev.pov.tv.data.Trackers.outboxKey(id)) }
    var sourceUrl = ""
        private set
    private var sourceKey = ""
    private var sourceHeaders = emptyMap<String, String>()
    /** Subtitles the link's add-on sent with the stream. */
    private var streamSubtitles = emptyList<dev.pov.tv.data.StreamSubtitle>()
    /** MediaFlowProxy.Origin of the link, encoded; [proxied] is true while it streams through the viewer's MediaFlow Proxy. */
    private var sourceOrigin = ""
    var proxied = false; private set
    private var playbackAccount: String? = null
    private var lastLinkSaved = false
    /** The automatic forced-subtitle check runs once per video. */
    private var subtitlesChecked = false
    private var externalSubtitlesChecked = false
    /** The subtitles Nabrio added to this video (track ids "pov-sub:<index>"), and the one auto sync last handled. */
    private var addedSubtitles: List<dev.pov.tv.data.ExternalSubtitle> = emptyList()
    /**
     * Extra online subtitles found for a video that already has the viewer's language built in. Adding a subtitle
     * reloads the video (Media3 rebuilds the source), which made every start reopen the file once more for choices
     * nobody had asked for; they are added when the viewer opens the subtitle menu instead ([addPendingSubtitles]).
     */
    private var pendingSubtitles: (() -> Unit)? = null
    private var syncedSubtitle = ""
    const val WYZIE_KEY = "subtitles.wyzie_key"
    const val SUBDL_KEY = "subtitles.subdl_key"
    var debridService: String? = null
    /** The add-on whose own badge shows for this video (PenguPlay), when it has one. */
    var sourceAddon: String? = null
        private set
    private var identity: PlaybackIdentity? = null
    var title: String = ""
    /** File name/release text and size of the link being played, for the player's badges. */
    var mediaName: String = ""
    var mediaSize: Long? = null
        private set
    var error: PlaybackException? = null
        private set
    val playerOrNull: Player? get() = player
    internal val playbackIdentity: PlaybackIdentity? get() = identity
    internal fun secureValue(key: String): String = if (::secureStore.isInitialized) secureStore.get(key) else ""
    internal val volumeBoost = SessionVolumeBoost(
        dispatchToMain = { task -> handler.post { task() } },
        onStateChanged = { notifyChanged() },
    )
    private var remotePercent = 0.0
    private var releasing = false
    private var lastSyncAction = ""
    private var lastSyncAt = 0L
    /** Titles finished (watched) in the player since Home last looked: Home marks them ✓ and re-reads the tracker. */
    val finished = java.util.concurrent.ConcurrentLinkedQueue<dev.pov.tv.data.PlaybackIdentity>()
    private var resumeAfterBackground = false
    private var backgroundPause = false
    private val listeners = linkedSetOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private const val MIN_SERVER_SIZE = 50L * 1024 * 1024
    private const val QUIET_RETRY_WINDOW_MS = 15_000L
    private const val QUIET_RETRY_DELAY_MS = 1_500L
    private const val MAX_QUIET_RETRIES = 3
    private const val SOURCE_RETRY_DELAY_MS = 1_000L
    private const val MAX_SOURCE_RETRIES = 2
    /** Quiet re-opens per video after a "stuck playing" error before it is shown. */
    private const val MAX_STUCK_RETRIES = 3
    private const val CACHED_CONNECT_TIMEOUT_MS = 8_000
    /** Device recoveries (decoders, audio output) per video before the error is shown. */
    private const val MAX_DEVICE_RECOVERIES = 5
    private const val AIO_SOURCE_RETRIES = 6
    private const val AIO_RETRY_DELAY_MS = 2_000L
    private val surfaceAttachment = SurfaceReadyAttachment<Player, PlayerView>(
        currentSession = { player?.takeUnless { releasing } },
        isReady = { view ->
            view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                (view.videoSurfaceView as? SurfaceView)?.holder?.surface?.isValid == true
        },
        observeReadiness = { view, ready -> observeSurfaceReadiness(view, ready) },
        attach = { exo, view ->
            val previous = targetView?.get()
            if (previous === view) {
                if (view.player !== exo) view.player = exo
            } else PlayerView.switchTargetView(exo, previous, view)
            previous?.keepScreenOn = false
            targetView = WeakReference(view)
            SubtitlePreferences(view.context).applyTo(view)
            view.keepScreenOn = exo.isPlaying
            // Pending attachment can finish after Activity.onStart. Refresh controller scheduling
            // only after ownership is committed; same-target reentry below does not notify again.
            notifyChanged()
        },
    )
    private val syncWorker = Executors.newSingleThreadExecutor()
    private val checkpoint = object : Runnable {
        override fun run() {
            if (player == null || releasing) return
            saveLocal()
            handler.postDelayed(this, 15_000)
        }
    }
    private val foregroundGate = PlaybackForegroundGate(
        schedule = { handler.postDelayed(it, 350) },
        cancel = { handler.removeCallbacks(it) },
        onBackground = {
            val current = player
            resumeAfterBackground = current != null && current.playWhenReady &&
                current.playbackState != Player.STATE_ENDED && current.playerError == null && error == null
            backgroundPause = true
            current?.pause()
            backgroundPause = false
            inBackground = true
            handler.removeCallbacks(checkpoint)
        },
        onForeground = {
            inBackground = false
            foregroundAt = android.os.SystemClock.elapsedRealtime()
            val exo = player
            if (exo != null && networkRetryPending) {
                // Some phones (e.g. Transsion) freeze background apps and cut their network, so a buffer load
                // fails while away. Back in front, the same link continues from where it stopped.
                networkRetryPending = false
                exo.prepare()
            }
            if (resumeAfterBackground && error == null && player?.playerError == null && player?.playbackState != Player.STATE_ENDED) player?.play()
            resumeAfterBackground = false
            restartCheckpoint()
        }
    )
    private var inBackground = false
    private var foregroundAt = 0L
    private var networkRetryPending = false
    private var quietRetries = 0
    /** Quiet retries after a source error in the foreground; reset once the video is playing again. */
    private var sourceRetries = 0
    /** Quiet re-opens after the player said it was stuck (playing, clock not moving); per video, not reset by READY. */
    private var stuckRetries = 0
    /** The playing link comes from AIOStreams (its add-on tag), which gets longer waits and more quiet retries. */
    private val aioStreams get() = sourceAddon?.startsWith(dev.pov.tv.data.AddonBadges.AIOSTREAMS) == true
    /** Set when parallel Easynews downloads keep failing for the current video; cleared when another video starts. */
    @Volatile private var easynewsSingle = false
    /** A saved Continue Watching link on its first try: fail fast so a dead one falls through to a fresh search. */
    @Volatile private var cachedReplay = false
    private fun isNetworkFailure(failure: PlaybackException) =
        (failure.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            failure.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) && !refusedUsenet(failure)

    /** A Usenet link already found unplayable this session: no retry of any kind brings it back. */
    private fun refusedUsenet(error: Throwable?): Boolean {
        var cause = error
        while (cause != null) { if (cause is dev.pov.tv.usenet.UsenetRefusedException) return true; cause = cause.cause }
        return false
    }

    fun initialize(context: Context) {
        if (application != null) return
        val app = context.applicationContext as Application
        application = app
        history = PlaybackStore(app)
        secureStore = SecureStore(app)
        dev.pov.tv.data.MediaFlowProxy.install(secureStore)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundGate.started(activity) }
            override fun onActivityStopped(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityDestroyed(activity: Activity) { foregroundGate.stopped(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    fun hasActive(): Boolean = player != null
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** Returns false for an invalid link without disturbing an existing session. */
    /** The intent the playing link was started with (the first link, or one picked in Streams): mpv reopens it. */
    @Volatile var startIntent: Intent? = null; private set

    internal fun start(context: Context, intent: Intent, saved: Bundle? = null): Boolean {
        startIntent = intent
        initialize(context)
        val address = intent.getStringExtra("url").orEmpty()
        val uri = try { Uri.parse(address) } catch (_: RuntimeException) { null }
        if (uri == null || uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        stopInternal(notify = false)
        sourceUrl = address
        easynewsSingle = false
        failedDecoders.clear(); deviceRecoveries = 0
        skipSegments = emptyList(); skipLookupFor = ""
        sourceKey = intent.getStringExtra("key").orEmpty()
        cachedReplay = saved == null && intent.getBooleanExtra("cached_playback", false)
        sourceHeaders = runCatching { JSONObject(intent.getStringExtra("headers") ?: "{}").let { json ->
            json.keys().asSequence().associateWith { json.getString(it) } } }.getOrDefault(emptyMap())
        playbackAccount = saved?.getString("playback_account") ?: intent.getStringExtra("playback_account")
        lastLinkSaved = false
        debridService = PlaybackDebrid.normalize(saved?.getString("debrid_service") ?: intent.getStringExtra("debrid_service"))
        // The loading card's first line: Usenet links report their own steps; the rest name where the video comes from.
        if (saved == null) { if (dev.pov.tv.data.UsenetLinks.isNzb(sourceUrl)) UsenetStartTimes.begin() else UsenetStartTimes.cancel() }
        PlaybackStatus.reset(if (dev.pov.tv.data.UsenetLinks.isNzb(sourceUrl)) null
            else L10n.tr(R.string.t_status_connecting_x, "Connecting to %1\$s",
                debridService?.let(dev.pov.tv.data.DebridServices::name) ?: L10n.tr(R.string.t_status_video_server, "the video server")))
        sourceAddon = saved?.getString("source_addon") ?: intent.getStringExtra("source_addon")
        sourceOrigin = saved?.getString("source_origin") ?: intent.getStringExtra("source_origin").orEmpty()
        streamSubtitles = dev.pov.tv.data.StreamSubtitle.fromJson(intent.getStringExtra("stream_subtitles"))
        title = intent.getStringExtra("title").orEmpty()
        if (saved == null) SubtitleSync.reset()
        subtitlesChecked = saved != null
        latinAudioChecked = saved != null
        externalSubtitlesChecked = false
        pendingSubtitles = null
        if (saved == null) { addedSubtitles = emptyList(); syncedSubtitle = "" }
        mediaName = intent.getStringExtra("media_name").orEmpty().take(4000)
        mediaSize = intent.getLongExtra("media_size", 0L).takeIf { it > 0 }
        identity = try { intent.getStringExtra("identity")?.let { PlaybackIdentity.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
        // A Usenet name-search link has no catalog title: no history, Continue Watching or trackers.
        if (saved == null && !dev.pov.tv.data.UsenetSearch.isNameSearch(identity)) { history.show(sourceKey, identity); identity?.let(wetrakr::allowPlayback) }
        val position = (saved?.getLong("position") ?: intent.getLongExtra("resume", 0L)).coerceAtLeast(0)
        remotePercent = saved?.getDouble("remotePercent") ?: if (position == 0L) intent.getDoubleExtra("resume_percent", 0.0) else 0.0
        if (!remotePercent.isFinite() || remotePercent !in 0.0..100.0) remotePercent = 0.0
        val shouldPlay = saved?.getBoolean("playWhenReady", true) ?: intent.getBooleanExtra("playWhenReady", true)
        val parameters = try { saved?.getBundle("tracks")?.let(TrackSelectionParameters::fromBundle) } catch (_: RuntimeException) { null }
        releasing = false
        error = null
        decodeForSpeed = false; encodedOutput = false; passthroughParked = false
        lastSyncAction = ""
        lastSyncAt = 0L
        val gainProcessor = volumeBoost.createAudioProcessor()
        // Settings → Audio → Audio passthrough: formats the viewer chose to decode in the app (static on some sound bars).
        val passthroughMode = AudioPassthrough.mode(application!!)
        // Volume boost by default (Settings → Audio & Subtitles); the player's Volume boost still changes it per video.
        VolumeBoostPreferences(application!!).defaultDb.takeIf { it > 0 }?.let { volumeBoost.setGain(it) }
        val renderers = object : DefaultRenderersFactory(application!!) {
            init {
                setMediaCodecSelector(skipFailedDecoders)
                setEnableDecoderFallback(true)
                // Software audio decoders (FFmpeg) are tried only after the device's own
                // decoders and passthrough, so links that already play are unaffected.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            }
            override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
                val builder = androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory).setMediaCodecSelector(mediaCodecSelector)
                    .setEnableDecoderFallback(enableDecoderFallback).setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEventHandler(eventHandler).setEventListener(eventListener).setMaxDroppedFramesToNotify(50)
                out.add(DolbyVisionVideoRenderer(builder, context, VideoCompatibilityPreferences(context).mode))
            }
            override fun buildTextRenderers(context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: Looper,
                extensionRendererMode: Int, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
                val built = ArrayList<androidx.media3.exoplayer.Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
                built.mapTo(out, ::SubtitleSyncRenderer)
            }
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                    .build()
                return object : ForwardingAudioSink(sink) {
                    private var encodedRoute = false
                    // While a speed other than 1x is chosen, for a format whose passthrough this device advertised
                    // but then refused to open, or for a format the viewer chose to decode in Settings, refuse
                    // passthrough so the renderer decodes to PCM.
                    private fun decode(format: Format) = format.sampleMimeType != MimeTypes.AUDIO_RAW &&
                        (decodeForSpeed || format.sampleMimeType in refusedPassthrough ||
                            AudioPassthrough.decodes(passthroughMode, format.sampleMimeType))
                    override fun supportsFormat(format: Format) = !decode(format) && super.supportsFormat(format)
                    override fun getFormatSupport(format: Format) =
                        if (decode(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
                    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
                        super.configure(audioSinkConfig)
                        // Keep device-supported encoded passthrough playable; it bypasses PCM DSP.
                        encodedRoute = audioSinkConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW
                        encodedOutput = encodedRoute
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                    }
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                        // A pending format switch may drain old PCM during this call. The selected
                        // encoded route still must finish with an honest bypass status.
                        if (encodedRoute) gainProcessor.onEncodedPassthrough()
                        return handled
                    }
                }
            }
        }
        // Direct links often redirect http <-> https (e.g. 307 to a CDN); ExoPlayer refuses
        // cross-protocol redirects by default, while Stremio and VLC follow them.
        val playing = sourceUrl
        // Chosen debrid services and add-ons stream through the viewer's MediaFlow Proxy; the saved link stays the original.
        val routed = dev.pov.tv.data.MediaFlowProxy(secureStore).route(playing, sourceHeaders, debridService,
            dev.pov.tv.data.MediaFlowProxy.Origin.decode(sourceOrigin))
        proxied = routed.proxied
        // Debrid and Usenet-backed links can take well over the default 8 s to send their first bytes.
        // AIOStreams (Usenet especially) fetches a seek's new spot from the news server first: it gets twice as long.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(if (cachedReplay) CACHED_CONNECT_TIMEOUT_MS else 15_000).setReadTimeoutMs(if (aioStreams) 60_000 else 30_000)
            .setTransferListener(sizeFromServer(playing, routed.url))
            .setDefaultRequestProperties(routed.headers)
        // Debrid and direct videos over several connections: one connection to a far CDN is often capped below the
        // film's bitrate (Kodi, asking in parallel through Nabrio, played the same Offcloud remux smoothly).
        val fast = ParallelHttpDataSource.Factory(http, routed.headers, connections = PlaybackConnections.count(application!!),
            listener = sizeFromServer(playing, routed.url))
        val playerSources = DefaultDataSource.Factory(application!!,
                // Easynews links get the saved login inside the player; it is never in the link or the intent.
                EasynewsRoutingDataSource.Factory(fast, { if (easynewsSingle) 1 else dev.pov.tv.data.Easynews.connections(secureStore) },
                    usenet = { address -> UsenetPlayback.session(address, secureStore, application?.cacheDir) { note -> UsenetLog.i("failure: $note") } }) { dev.pov.tv.data.Easynews.credentials(secureStore)?.let { (user, password) ->
                    runCatching { dev.pov.tv.data.Easynews.auth(user, password) }.getOrNull() } })
        val mediaSources = DefaultMediaSourceFactory(playerSources)
            // A Usenet link already found unplayable fails straight away instead of being asked three more times.
            .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (refusedUsenet(info.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(info)
            })
        // Settings → Playback → Player engine mpv, or the Auto switch after this player couldn't play the file: mpv
        // behind the same player interface, reading the link through a loopback server built from the same sources.
        usingMpv = intent.getBooleanExtra("engine_mpv", false) || PlayerEngine.get(application!!) == PlayerEngine.MPV
        mpvServer?.close(); mpvServer = null
        if (usingMpv) {
            val name = mediaName.substringBefore(" · ").lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { title }
            mpvServer = try { LocalStreamServer(playerSources, Uri.parse(routed.url), name) { android.util.Log.i("PovMpv", it) } }
                catch (failure: Exception) { android.util.Log.w("PovMpv", "local server failed", failure); usingMpv = false; null }
        }
        val exo: Player = if (usingMpv) MpvPlayer(application!!) else ExoPlayer.Builder(application!!, renderers)
            .setMediaSourceFactory(mediaSources)
            // A bigger cushion after each stall (Kodi-like) for links that only just keep up with the film's bitrate.
            .setLoadControl(RebufferGuard(ReadAheadPreferences(application!!).loadControl(usenet = dev.pov.tv.data.UsenetLinks.isNzb(playing)),
                quickStartMs = RebufferGuard.USENET_QUICK_START_MS.takeIf { dev.pov.tv.data.UsenetLinks.isNzb(playing) }))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player = exo
        // Match frame rate: Off keeps the TV as it is; otherwise Android may switch where it's seamless. "Always" is
        // done by the player screen itself (FrameRateMatch), which asks the TV for a fitting display mode.
        (exo as? ExoPlayer)?.videoChangeFrameRateStrategy = if (FrameRateMatch.mode(application!!) == FrameRateMatch.SEAMLESS)
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        exo.trackSelectionParameters = SubtitlePreferences(application!!).trackParameters(exo.trackSelectionParameters)
        exo.trackSelectionParameters = AudioPreferences(application!!).trackParameters(exo.trackSelectionParameters)
        parameters?.let { exo.trackSelectionParameters = it }
        val openApp = PendingIntent.getActivity(application, 0, Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(application!!, exo).setSessionActivity(openApp).build()
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // PlayerView has received onCues before this batched callback. Apply custom
                // text positions last so embedded cue regions cannot restore the old location.
                if (isCurrent(exo) && events.contains(Player.EVENT_CUES)) {
                    targetView?.get()?.let { target -> target.subtitleView?.setCues(
                        SubtitlePreferences(target.context).positionedCues(player.currentCues.cues)) }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (!isCurrent(exo) || enforceVideoCompatibility(exo, tracks)) return
                preferFullSubtitles(exo, tracks)
                autoSyncSubtitles(exo, tracks)
                preferLatinSpanishAudio(exo, tracks)
                findMissingSubtitles(exo, tracks)
                notifyChanged()
            }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (isCurrent(exo)) notifyChanged()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrent(exo)) return
                if (isPlaying && enforceVideoCompatibility(exo)) return
                targetView?.get()?.keepScreenOn = isPlaying
                // Buffering is not a user pause. Only genuine playback starts are recorded here.
                if (isPlaying && exo.duration > 0) { saveLocal(); sync("start") }
                notifyChanged()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!isCurrent(exo)) return
                if (playWhenReady && enforceVideoCompatibility(exo)) return
                // Another app taking audio focus while POV is away is not a user pause: coming back still resumes.
                if (!backgroundPause && !playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) resumeAfterBackground = false
                if (!playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
                notifyChanged()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (isCurrent(exo) && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && exo.duration > 0) {
                    saveLocal(); sync("pause")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrent(exo)) return
                if (state != Player.STATE_ENDED && enforceVideoCompatibility(exo)) return
                if (state == Player.STATE_READY) { cachedReplay = false; quietRetries = 0; sourceRetries = 0; lookUpSkips(exo) }
                if (state == Player.STATE_READY && !lastLinkSaved) {
                    lastLinkSaved = true
                    val media = identity; val account = playbackAccount
                    // Header-bound links keep their headers; if those have expired, resuming falls back to a fresh search.
                    if (media != null && account != null) runCatching {
                        dev.pov.tv.data.LastPlayedLinks(SecureStore(application!!)).save(media, sourceUrl, sourceKey, debridService, account, mediaName, mediaSize, sourceHeaders, sourceAddon, sourceOrigin)
                    }
                }
                if (state == Player.STATE_READY && remotePercent > 0 && exo.duration > 0) {
                    val percent = remotePercent
                    remotePercent = 0.0
                    exo.seekTo((exo.duration * percent / 100).toLong())
                }
                if (state == Player.STATE_ENDED) {
                    saveLocal(); sync("stop")
                    volumeBoost.resetGain()
                    targetView?.get()?.keepScreenOn = false
                    handler.removeCallbacks(checkpoint)
                }
                notifyChanged()
            }
            override fun onPlayerError(failure: PlaybackException) {
                if (!isCurrent(exo)) return
                if (recoverOnDevice(exo, failure)) return
                // The TV (or its sound bar) advertised passthrough for this audio, such as DTS-HD, then refused to open
                // it. Decode that format in the app instead, for the rest of this app session, and carry on at the
                // same spot: the device's own decoder or the built-in FFmpeg decoder takes over.
                val audioFormat = (failure as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
                if (failure.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && error == null &&
                    audioFormat != null && audioFormat != MimeTypes.AUDIO_RAW && refusedPassthrough.add(audioFormat)) {
                    android.util.Log.w("PovPlayer", "Passthrough of $audioFormat could not be opened; decoding it in the app instead")
                    val parameters = exo.trackSelectionParameters
                    exo.trackSelectionParameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    exo.prepare()
                    handler.post { if (isCurrent(exo)) { exo.trackSelectionParameters = parameters; exo.play() } }
                    return
                }
                // "Player stuck playing with no progress": the player had started, then the next data came late (one
                // slow Usenet article) and with the sound passed through to the TV its clock just stopped, still
                // "playing", until the player gave up after 10 s. The stream itself is fine: open it again at the
                // same spot, quietly, instead of showing an error.
                if (error == null && stuckRetries < MAX_STUCK_RETRIES && failure.cause?.javaClass?.simpleName == "StuckPlayerException") {
                    stuckRetries++
                    android.util.Log.w("PovPlayer", "Player stuck with no progress; re-opening at the same spot ($stuckRetries of $MAX_STUCK_RETRIES)")
                    if (dev.pov.tv.data.UsenetLinks.isNzb(sourceUrl)) dev.pov.tv.UsenetLog.i("player was stuck with no progress; re-opening at the same spot")
                    exo.prepare(); exo.play()
                    notifyChanged()
                    return
                }
                val justBack = android.os.SystemClock.elapsedRealtime() - foregroundAt < QUIET_RETRY_WINDOW_MS
                if (isNetworkFailure(failure) && error == null && quietRetries < MAX_QUIET_RETRIES && (inBackground || justBack)) {
                    // The network was cut while the app was away (or is still coming back): retry the same link quietly.
                    quietRetries++
                    saveLocal()
                    if (inBackground) networkRetryPending = true
                    else handler.postDelayed({
                        if (isCurrent(exo) && exo.playerError != null && error == null) { exo.prepare(); exo.play() }
                    }, QUIET_RETRY_DELAY_MS)
                    notifyChanged()
                    return
                }
                // A stream that stops for a source reason (a dropped connection, a read glitch such as the MKV reader's
                // intermittent "Source error") usually plays on a second try, as a manual Retry showed: retry the same
                // link at the same spot quietly before showing an error. A refused link (403/404) won't recover.
                // AIOStreams links get more tries, also for an "unexpected" failure: after a seek its reply can come
                // back short while it is still fetching from Usenet, which a second request usually gets past.
                val failureType = (failure as? androidx.media3.exoplayer.ExoPlaybackException)?.type
                val sourceFailure = failureType == androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE ||
                    (aioStreams && failureType == androidx.media3.exoplayer.ExoPlaybackException.TYPE_UNEXPECTED)
                val maxRetries = if (aioStreams) AIO_SOURCE_RETRIES else MAX_SOURCE_RETRIES
                if (sourceFailure && !cachedReplay && error == null && sourceRetries < maxRetries && !refusedUsenet(failure) &&
                    failure.errorCode !in setOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION)) {
                    sourceRetries++
                    // A second failure on an Easynews video: fall back to one connection for this video only.
                    if (sourceRetries >= 2 && dev.pov.tv.data.Easynews.isHost(sourceUrl) && !easynewsSingle) {
                        easynewsSingle = true
                        android.util.Log.w("PovPlayer", "Easynews keeps failing over parallel connections; retrying this video over one")
                    }
                    android.util.Log.w("PovPlayer", "Source error ${failure.errorCodeName}; quiet retry $sourceRetries of $maxRetries", failure)
                    handler.postDelayed({
                        if (isCurrent(exo) && exo.playerError != null && error == null) { exo.prepare(); exo.play() }
                    }, if (aioStreams) AIO_RETRY_DELAY_MS else SOURCE_RETRY_DELAY_MS)
                    notifyChanged()
                    return
                }
                // The full reason, so a link that keeps failing can be diagnosed from the log.
                android.util.Log.e("PovPlayer", "Playback failed: ${failure.errorCodeName} (${failure.cause?.javaClass?.simpleName}: ${failure.cause?.message})" +
                    if (aioStreams) L10n.tr(R.string.t_aiostreams_link, " · AIOStreams link") else "", failure)
                if (error !is VideoCompatibilityException) error = failure
                saveLocal(); sync("pause")
                exo.pause()
                volumeBoost.resetGain()
                handler.removeCallbacks(checkpoint)
                notifyChanged()
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(mpvServer?.address ?: routed.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        exo.seekTo(position)
        exo.prepare()
        exo.playWhenReady = shouldPlay
        restartCheckpoint()
        notifyChanged()
        return true
    }

    internal fun matches(intent: Intent): Boolean = player != null &&
        sourceUrl == intent.getStringExtra("url").orEmpty() && sourceKey == intent.getStringExtra("key").orEmpty()

    fun refreshAudioLanguage() {
        val context = application ?: return
        player?.let { it.trackSelectionParameters = AudioPreferences(context).trackParameters(it.trackSelectionParameters) }
    }

    /** Adds the extra online subtitles kept back at the start (see [pendingSubtitles]); true when it did. */
    internal fun addPendingSubtitles(): Boolean {
        val add = pendingSubtitles ?: return false
        pendingSubtitles = null
        add()
        return true
    }

    fun refreshSubtitleLanguage() {
        val context = application ?: return
        player?.let { it.trackSelectionParameters = SubtitlePreferences(context).trackParameters(it.trackSelectionParameters) }
    }

    fun refreshSubtitleStyle() {
        targetView?.get()?.let { SubtitlePreferences(it.context).applyTo(it) }
        (player as? MpvPlayer)?.applySubtitleStyle()
    }

    fun attachPlayerView(view: PlayerView) {
        SubtitlePreferences(view.context).applyTo(view)
        val exo = player ?: run { view.player = null; return }
        if (targetView?.get() === view && view.player === exo) {
            view.keepScreenOn = exo.isPlaying
            return
        }
        surfaceAttachment.request(exo, view)
    }

    fun detachPlayerView(view: PlayerView) {
        surfaceAttachment.cancel(view)
        if (targetView?.get() === view) targetView = null
        if (view.player != null) view.player = null
        view.keepScreenOn = false
    }

    fun retry() {
        val exo = player ?: return
        // A decoder capability failure is deterministic for this copy; don't restart audio only.
        if (error is VideoCompatibilityException) { notifyChanged(); return }
        error = null
        exo.prepare(); exo.play()
        restartCheckpoint()
        notifyChanged()
    }

    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        val exo = player
        if (exo != null) {
            saveLocal()
            if (exo.playbackState != Player.STATE_ENDED) {
                val watched = exo.duration > 0 && exo.currentPosition.toDouble() / exo.duration >= 0.80
                sync(if (watched) "stop" else "pause")
            }
        }
        releasing = true
        surfaceAttachment.cancelAll()
        handler.removeCallbacks(checkpoint)
        resumeAfterBackground = false
        networkRetryPending = false; quietRetries = 0; sourceRetries = 0; stuckRetries = 0
        volumeBoost.close()
        targetView?.get()?.let { it.player = null; it.keepScreenOn = false }
        targetView = null
        mediaSession?.release(); mediaSession = null
        // mpv's reads end at once when its loopback server closes first (Stop no longer waits on a news server).
        mpvServer?.close(); mpvServer = null
        exo?.release(); player = null
        usingMpv = false
        UsenetSessions.close()
        sourceUrl = ""; sourceKey = ""; sourceHeaders = emptyMap(); streamSubtitles = emptyList(); title = ""; mediaName = ""; mediaSize = null; identity = null; debridService = null; sourceAddon = null; sourceOrigin = ""; proxied = false; playbackAccount = null; lastLinkSaved = false
        remotePercent = 0.0; error = null
        if (notify) notifyChanged()
    }

    internal fun saveState(out: Bundle) {
        val exo = player ?: return
        out.putLong("position", exo.currentPosition.coerceAtLeast(0))
        out.putString("debrid_service", debridService)
        out.putString("source_addon", sourceAddon)
        out.putString("source_origin", sourceOrigin)
        out.putString("playback_account", playbackAccount)
        out.putBoolean("playWhenReady", exo.playWhenReady && exo.playbackState != Player.STATE_ENDED)
        out.putDouble("remotePercent", remotePercent)
        out.putBundle("tracks", exo.trackSelectionParameters.toBundle())
    }

    internal fun currentIntent(context: Context): Intent? {
        val exo = player ?: return null
        return Intent(context, PlayerActivity::class.java).putExtra("reuse_session", true)
            .putExtra("url", sourceUrl).putExtra("title", title).putExtra("key", sourceKey)
            .putExtra("headers", JSONObject(sourceHeaders).toString())
            .putExtra("media_name", mediaName).putExtra("media_size", mediaSize ?: 0L)
            .putExtra("debrid_service", debridService).putExtra("playback_account", playbackAccount).putExtra("source_addon", sourceAddon)
            .putExtra("source_origin", sourceOrigin)
            .putExtra("stream_subtitles", dev.pov.tv.data.StreamSubtitle.toJson(streamSubtitles))
            .putExtra("resume", exo.currentPosition).putExtra("resume_percent", remotePercent)
            .putExtra("playWhenReady", exo.playWhenReady)
            .also { value -> identity?.let { value.putExtra("identity", it.toJson().toString()) } }
    }

    internal fun setVolumeBoostGain(db: Int): VolumeBoostState {
        val result = volumeBoost.setGain(db)
        (player as? MpvPlayer)?.setBoost(db)
        notifyChanged()
        return result
    }

    /**
     * Decoders (by codec name) that failed during this video. They are left out when the player picks a decoder again,
     * so the next one takes over: another hardware decoder, the device's software decoder, or, for audio with no
     * decoder left, the built-in FFmpeg decoder. Cleared for every new video: a failure can be a one-off.
     */
    private val failedDecoders: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val skipFailedDecoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
        androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            .filterNot { it.name in failedDecoders }
    }
    /** Recoveries in this video from device failures (decoders, audio output); a cap keeps a broken file from looping. */
    private var deviceRecoveries = 0

    /** The codec named in a decoder failure, when the player says which one it was. */
    private fun failedCodec(failure: Throwable?): String? {
        var cause = failure
        while (cause != null) {
            when (cause) {
                is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name?.let { return it }
                is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> cause.codecInfo?.name?.let { return it }
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Device failures that a second try usually gets past, handled at the same spot without an error:
     * - a video or audio decoder that failed to start or failed mid-video: that decoder is skipped and the next one
     *   (a software decoder, or FFmpeg for audio) takes over;
     * - audio output that failed mid-video (a sound bar switched off, the HDMI audio route changed, plain PCM that
     *   would not open): the audio is opened again.
     * Encoded passthrough the device refuses to open is handled after this, by decoding that format in the app.
     */
    private fun recoverOnDevice(exo: Player, failure: PlaybackException): Boolean {
        if (error != null || deviceRecoveries >= MAX_DEVICE_RECOVERIES) return false
        val playback = failure as? androidx.media3.exoplayer.ExoPlaybackException ?: return false
        if (playback.type != androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER) return false
        val type = runCatching { (exo as? ExoPlayer)?.getRendererType(playback.rendererIndex) ?: C.TRACK_TYPE_UNKNOWN }.getOrDefault(C.TRACK_TYPE_UNKNOWN)
        val code = failure.errorCode
        val decoderFailure = code in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
        val reason = when {
            decoderFailure && (type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO) -> {
                val codec = failedCodec(failure)
                // A reclaimed decoder (another app took it) is fine to use again; any other failure skips it.
                if (codec != null && code != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) failedDecoders += codec
                else if (codec == null && code != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) return false
                "${if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"} decoder ${codec ?: ""} ${failure.errorCodeName}".trim()
            }
            type == C.TRACK_TYPE_AUDIO && code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "audio output write failed"
            // PCM that would not open, or an encoded format already switched to in-app decoding: open the audio again.
            type == C.TRACK_TYPE_AUDIO && code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
                playback.rendererFormat?.sampleMimeType.let { it == null || it == MimeTypes.AUDIO_RAW || it in refusedPassthrough } ->
                "audio output could not open"
            else -> return false
        }
        deviceRecoveries++
        android.util.Log.w("PovPlayer", "Recovering from $reason ($deviceRecoveries of $MAX_DEVICE_RECOVERIES)", failure)
        // Turning the track type off and on makes the player choose its decoder and audio route again.
        val trackType = if (type == C.TRACK_TYPE_VIDEO) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val parameters = exo.trackSelectionParameters
        exo.trackSelectionParameters = parameters.buildUpon().setTrackTypeDisabled(trackType, true).build()
        exo.prepare()
        handler.postDelayed({ if (isCurrent(exo)) { exo.trackSelectionParameters = parameters; exo.play() } },
            if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) 800L else 0L)
        notifyChanged()
        return true
    }

    /**
     * Encoded audio formats (MIME types) whose passthrough this device advertised but then failed to open. Kept for the
     * app's lifetime, so the next video with that audio decodes at once instead of failing first. Read on the playback thread.
     */
    private val refusedPassthrough: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Read on the playback thread by the audio sink: while true, encoded passthrough is refused so audio decodes to PCM. */
    @Volatile private var decodeForSpeed = false
    /** Written by the audio sink: the current audio goes out encoded (passthrough), not as PCM. */
    @Volatile private var encodedOutput = false
    /** Passthrough was given up for a speed change and comes back at 1x. */
    private var passthroughParked = false

    /**
     * Encoded passthrough (Dolby/DTS to the TV's decoder or a receiver) cannot be time-stretched, so any speed
     * but 1x decodes the audio in the app instead; 1x hands it back to passthrough. Briefly disabling the audio
     * renderer makes it pick its route again.
     */
    internal fun setPlaybackSpeed(speed: Float) {
        val exo = player ?: return
        val decode = speed != 1f
        decodeForSpeed = decode
        val reroute = if (decode) encodedOutput.also { if (it) passthroughParked = true }
            else passthroughParked.also { passthroughParked = false }
        if (reroute) {
            val parameters = exo.trackSelectionParameters
            exo.trackSelectionParameters = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            handler.post { if (isCurrent(exo)) exo.trackSelectionParameters = parameters }
        }
        exo.setPlaybackSpeed(speed)
        notifyChanged()
    }

    private fun isCurrent(exo: Player) = !releasing && player === exo

    /** Intro, recap and credits times for the current video, once found (the player shows Skip buttons for them). */
    @Volatile var skipSegments: List<dev.pov.tv.data.SkipSegment> = emptyList()
        private set
    private var skipLookupFor = ""

    /** Once per video, when its length is known: the length picks the matching cut in the skip databases. */
    private fun lookUpSkips(exo: Player) {
        val media = identity ?: return
        val app = application ?: return
        val duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        val lookup = sourceUrl
        if (lookup.isEmpty() || skipLookupFor == lookup || !SkipButtonPreferences(app).enabled) return
        skipLookupFor = lookup
        val anime = dev.pov.tv.data.AnimeEpisodeRefs.find(app, media)
        Thread {
            val found = runCatching { dev.pov.tv.data.SkipSegmentsApi().segments(media, duration, anime) }.getOrDefault(emptyList())
            android.util.Log.i("PovSkip", "${found.size} skip segments (${if (anime != null) "anime" else "show/movie"}): " +
                found.joinToString { "${it.kind} ${it.startMs / 1000}-${it.endMs?.div(1000) ?: "end"}s" })
            handler.post { if (sourceUrl == lookup && isCurrent(exo)) { skipSegments = found; notifyChanged() } }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Without a known size (a link saved before sizes were kept), the size the server reports for the video file
     * itself fills the badge. Playlists and subtitles are other addresses, and anything under 50 MB is ignored.
     */
    private fun sizeFromServer(playing: String, opened: String = playing) = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {
            if (mediaSize != null || dataSpec.uri.toString() != opened) return
            val headers = (source as? androidx.media3.datasource.HttpDataSource)?.responseHeaders ?: return
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
            // "Content-Range: bytes 0-1023/4567890" carries the whole file's size; otherwise a full response's length.
            val total = header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { dataSpec.position == 0L }
            if (total == null || total < MIN_SERVER_SIZE) return
            handler.post { if (sourceUrl == playing && mediaSize == null) { mediaSize = total; notifyChanged() } }
        }
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
    }

    /**
     * Files often carry a "Forced" subtitle track (signs and foreign lines only) marked as the default, which
     * the player then prefers over the full track in the same language, so most dialogue shows no text.
     * When the automatic choice is forced and a full track in that language exists, the full track is used.
     * A subtitle the viewer picked is never changed.
     */
    /**
     * A video without subtitles in the viewer's language (or English, with English fallback on) gets the best matches
     * from OpenSubtitles, Wyzie/SubDL with the viewer's keys, the viewer's own subtitle add-ons and the subtitles the
     * link's add-on sent with it, added as extra subtitle tracks. The first one is chosen.
     */
    private fun findMissingSubtitles(exo: Player, tracks: Tracks) {
        if (externalSubtitlesChecked || tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }) return
        externalSubtitlesChecked = true
        val app = application ?: return
        val preferences = SubtitlePreferences(app)
        val wanted = preferences.language
        val media = identity ?: return
        // Header-bound links send their headers with every request, so no other host is asked for files.
        if (wanted.isEmpty() || wanted == "off" || sourceHeaders.isNotEmpty()) return
        val embedded = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { group.getTrackFormat(it) }
        }.filter { it.selectionFlags and C.SELECTION_FLAG_FORCED == 0 && it.label?.contains("forced", true) != true }
            .map { dev.pov.tv.data.ExternalSubtitles.iso3(it.language.orEmpty()) }.toSet()
        // The chosen language built in (or auto-fetch off): online searches are skipped, but subtitles from the viewer's own
        // add-ons and those sent with the stream are still added as extra choices, without being selected.
        // The English fallback must not stop the search: a video with English built in but no Spanish still gets Spanish
        // online first, and falls back to English (built in, or online when it isn't) only when no Spanish is found anywhere.
        val extrasOnly = !preferences.autoFetch || dev.pov.tv.data.ExternalSubtitles.iso3(wanted) in embedded
        val fallbacks = SubtitlePreferences(app).withFallback(wanted).toList()
        val candidates = if (extrasOnly) listOf(wanted) else fallbacks.takeWhile { dev.pov.tv.data.ExternalSubtitles.iso3(it) !in embedded }
        val playing = sourceUrl; val fileName = mediaName; val key = secureStore.get(WYZIE_KEY); val subdlKey = secureStore.get(SUBDL_KEY)
        val subtitleCache = java.io.File(app.cacheDir, "subtitles")
        val attached = streamSubtitles
        Thread {
            val finder = dev.pov.tv.data.ExternalSubtitles(cacheDir = subtitleCache)
            val addons = subtitleAddons()
            val found = candidates.firstNotNullOfOrNull { language ->
                runCatching { finder.find(media, language, fileName, key, subdlKey = subdlKey, addons = addons, attached = attached, builtIn = !extrasOnly) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            handler.post {
                if (found.isEmpty() || !isCurrent(exo) || sourceUrl != playing) return@post
                val add = add@{
                    if (!isCurrent(exo) || sourceUrl != playing) return@add
                    val item = exo.currentMediaItem ?: return@add
                    android.util.Log.i("PovSubtitles", "added ${found.size} online subtitles (${found.first().label})")
                    val configs = found.mapIndexed { index, subtitle ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url)).setMimeType(subtitle.mime).setId("pov-sub:$index")
                            .setLanguage(subtitle.language).setLabel(if (found.size > 1) "${subtitle.label} ${index + 1}" else subtitle.label)
                            .setSelectionFlags(if (index == 0 && !extrasOnly) C.SELECTION_FLAG_DEFAULT else 0).build()
                    }
                    addedSubtitles = found
                    exo.setMediaItem(item.buildUpon().setSubtitleConfigurations(configs).build(), false)
                    exo.prepare()
                }
                // Needed (no subtitle in the viewer's language): now. Only extra choices: when the subtitle menu opens.
                if (extrasOnly) { pendingSubtitles = add; android.util.Log.i("PovSubtitles", "${found.size} extra online subtitles kept for the subtitle menu") }
                else add()
            }
        }.apply { isDaemon = true; name = "pov-subtitles" }.start()
    }

    /** The viewer's add-ons (source providers that are on, and catalog add-ons) that may offer subtitles. */
    private fun subtitleAddons(): List<dev.pov.tv.data.SubtitleAddon> {
        val sources = runCatching { dev.pov.tv.data.SourceProviders(secureStore).load().filter { it.enabled }
            .map { dev.pov.tv.data.SubtitleAddon(it.name, dev.pov.tv.data.SourceProviders.endpoint(it.address)) } }.getOrDefault(emptyList())
        val catalogs = runCatching { dev.pov.tv.data.CatalogAddons(secureStore).load().map { dev.pov.tv.data.SubtitleAddon(it.name, it.address) } }.getOrDefault(emptyList())
        return (sources + catalogs).distinctBy { it.base }.take(20)
    }

    /**
     * Auto subtitle sync: a subtitle Nabrio added (not the file's own, not one sent with the stream) is lined up with a
     * subtitle made for this exact file, once per subtitle, unless the viewer has moved the timing by hand.
     */
    private fun autoSyncSubtitles(exo: Player, tracks: Tracks) {
        if (SubtitleSync.manual) return
        val chosen = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let { group.getTrackFormat(it) } } ?: return
        val index = Regex("pov-sub:(\\d+)").find(chosen.id.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return
        val subtitle = addedSubtitles.getOrNull(index) ?: return
        if (subtitle.url == syncedSubtitle) return
        syncedSubtitle = subtitle.url
        SubtitleSync.applyAuto(0, 1.0)                                    // another subtitle: start from its own timing
        val app = application ?: return
        val attached = streamSubtitles.map { AutoSubtitleSync.Subtitle(it.url, it.language, true) }
        AutoSubtitleSync.run(app, AutoSubtitleSync.Subtitle(subtitle.url, subtitle.language, subtitle.score == Int.MAX_VALUE), attached,
            identity, sourceUrl, sourceHeaders, mediaSize) { result ->
            handler.post {
                if (result == null || !isCurrent(exo) || syncedSubtitle != subtitle.url || SubtitleSync.manual) return@post
                SubtitleSync.applyAuto(result.offsetMs, result.factor)
                android.util.Log.i("PovSubtitles", "auto sync: ${result.offsetMs} ms × ${result.factor} (${if (result.byWords) "words" else "timing"})")
                if (kotlin.math.abs(result.offsetMs) >= 150 || result.factor != 1.0) android.widget.Toast.makeText(app,
                    app.getString(R.string.t_subtitles_synced_automatically_x, SubtitleSync.label()), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var latinAudioChecked = false

    /**
     * Spanish (Latin America) chosen: files often tag both Spanish tracks "spa", so among several Spanish audio tracks
     * the one named Latino / Latin / LAT / México / es-419 is chosen. Once per video, never over the viewer's own pick.
     */
    private fun preferLatinSpanishAudio(exo: Player, tracks: Tracks) {
        if (latinAudioChecked) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return
        latinAudioChecked = true
        if (AudioPreferences(application!!).language != dev.pov.tv.data.SourceLanguages.LATIN_SPANISH) return
        val parameters = exo.trackSelectionParameters
        if (parameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO }) return
        val spanish = audio.flatMap { group -> (0 until group.length).map { group to it } }.filter { (group, index) ->
            group.isTrackSupported(index) && group.getTrackFormat(index).language?.lowercase()?.let { it.startsWith("es") || it == "spa" } == true
        }
        if (spanish.size < 2) return
        val latin = Regex("(?i)latin|latam|(?<![a-z])lat(?![a-z])|m[eé]xico|es-419|es-mx")
        val pick = spanish.firstOrNull { (group, index) -> group.getTrackFormat(index).let { latin.containsMatchIn("${it.label} ${it.language}") } } ?: return
        if (pick.first.isTrackSelected(pick.second)) return
        android.util.Log.i("PovAudio", "Spanish (Latin America): choosing the ${pick.first.getTrackFormat(pick.second).label} track")
        exo.trackSelectionParameters = parameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second)).build()
    }

    private fun preferFullSubtitles(exo: Player, tracks: Tracks) {
        if (subtitlesChecked) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isEmpty()) return
        subtitlesChecked = true
        text.forEach { group -> (0 until group.length).forEach { index -> val format = group.getTrackFormat(index)
            android.util.Log.i("PovSubtitles", "track lang=${format.language} label=${format.label} flags=${format.selectionFlags} " +
                "mime=${format.sampleMimeType ?: format.codecs} supported=${group.isTrackSupported(index)} selected=${group.isTrackSelected(index)}") } }
        val parameters = exo.trackSelectionParameters
        if (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes || parameters.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }) return
        fun forced(format: Format) = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || format.label?.contains("forced", ignoreCase = true) == true
        val chosen = text.firstNotNullOfOrNull { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let { group.getTrackFormat(it) } } ?: return
        if (!forced(chosen)) return
        val full = text.flatMap { group -> (0 until group.length).map { group to it } }.firstOrNull { (group, index) ->
            val format = group.getTrackFormat(index)
            group.isTrackSupported(index) && !forced(format) && (chosen.language == null || format.language == chosen.language)
        } ?: return
        android.util.Log.i("PovSubtitles", "forced track was chosen; using the full ${chosen.language} track instead")
        exo.trackSelectionParameters = parameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(full.first.mediaTrackGroup, full.second)).build()
    }
    private fun observeSurfaceReadiness(view: PlayerView, ready: () -> Unit): () -> Unit {
        val holder = (view.videoSurfaceView as? SurfaceView)?.holder
        val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { ready() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ready() }
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        // Creation can precede visible-window/layout state. The first eligible draw retries the
        // same pending request; no surface is attached while its holder is still invalid.
        val observer = view.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener { ready(); true }
        holder?.addCallback(surfaceCallback)
        observer.addOnPreDrawListener(preDraw)
        return {
            holder?.removeCallback(surfaceCallback)
            if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
            else view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        }
    }
    private fun enforceVideoCompatibility(exo: Player, tracks: Tracks = exo.currentTracks): Boolean {
        val existing = error as? VideoCompatibilityException
        val issue = existing?.issue ?: VideoPlaybackCompatibility.issue(tracks, exo.playbackState, (exo as? ExoPlayer)?.videoFormat)
        if (issue == null) return false
        if (existing == null) error = VideoCompatibilityException(issue)
        resumeAfterBackground = false
        exo.pause()
        volumeBoost.resetGain()
        targetView?.get()?.keepScreenOn = false
        handler.removeCallbacks(checkpoint)
        notifyChanged()
        return true
    }
    private fun notifyChanged() { listeners.toList().forEach { it() } }
    private fun restartCheckpoint() {
        handler.removeCallbacks(checkpoint)
        if (player != null && player?.playbackState != Player.STATE_ENDED && error == null) handler.postDelayed(checkpoint, 15_000)
    }
    private fun saveLocal() {
        if (error is VideoCompatibilityException) return
        // Public-stream audits and other transient playback must not create history entries.
        if (sourceKey.isBlank() || dev.pov.tv.data.UsenetSearch.isNameSearch(identity)) return
        val exo = player ?: return
        if (exo.duration <= 0) return
        try { history.save(sourceKey, title, identity, exo.currentPosition.coerceAtLeast(0), exo.duration) }
        catch (_: Exception) { /* Preserve playback if local storage is unavailable. */ }
    }
    private fun sync(action: String) {
        if (error is VideoCompatibilityException) return
        val media = identity ?: return
        if (dev.pov.tv.data.UsenetSearch.isNameSearch(media)) return
        val exo = player ?: return
        if (exo.duration <= 0) return
        if (history.isHidden(identity = media)) return
        // A finish the trackers count as watched (80% and up): the app's own ✓ marks show it on return, without
        // waiting for the next history download.
        if (action == "stop" && exo.currentPosition.toDouble() / exo.duration >= 0.80) { if (finished.size < 50) finished.add(media) }
        // The main tracker and every other signed-in tracker that also keeps watch history, each with its own queue.
        val targets = dev.pov.tv.data.Trackers.historyTargets(secureStore).mapNotNull { id ->
            try { trackerApi(id).takeIf { it.connected() && it.scrobblingEnabled() }?.let { Triple(it, it.accountRevision(), id) } } catch (_: Exception) { null }
        }
        if (targets.isEmpty()) return
        val percent = (exo.currentPosition.toDouble() * 100 / exo.duration).coerceIn(0.0, 100.0)
        val now = android.os.SystemClock.elapsedRealtime()
        if (action == lastSyncAction && (action == "start" || now - lastSyncAt < 3_000)) return
        lastSyncAction = action; lastSyncAt = now
        val updates = targets.map { (client, account, id) ->
            val outbox = trackerOutbox(id)
            val scrobbleRevision = client.scrobbleRevision()
            val revision = try { if (action != "start") outbox.queue(media, percent, action, account, scrobbleRevision = scrobbleRevision) else null } catch (_: Exception) { null }
            listOf(client, account, outbox, revision, scrobbleRevision)
        }
        syncWorker.execute {
            updates.forEach { update ->
                val client = update[0] as dev.pov.tv.data.Tracker; val account = update[1] as String
                val outbox = update[2] as PlaybackStore; val revision = update[3] as String?; val scrobbleRevision = update[4] as String
                try {
                    if (!history.isHidden(identity = media) && client.progressForAccount(media, percent, action, account, scrobbleRevision) && revision != null) outbox.acknowledge(media, revision)
                } catch (_: Exception) { /* Each provider keeps its own final-state retry. */ }
            }
        }
    }
}

