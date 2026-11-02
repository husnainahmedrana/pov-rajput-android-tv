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
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var targetView: WeakReference<PlayerView>? = null
    private lateinit var history: PlaybackStore
    private lateinit var secureStore: SecureStore
    private lateinit var weTrakrApi: WeTrakrApi
    private lateinit var simklApi: dev.pov.tv.data.SimklApi
    private lateinit var weTrakrOutbox: PlaybackStore
    private lateinit var simklOutbox: PlaybackStore
    /** Scrobbles go to the tracker chosen in Settings, each with its own offline queue. */
    private val simklSelected get() = dev.pov.tv.data.Trackers.selected(secureStore) == dev.pov.tv.data.Trackers.SIMKL
    private val wetrakr: dev.pov.tv.data.Tracker get() = if (simklSelected) simklApi else weTrakrApi
    private val wetrakrOutbox: PlaybackStore get() = if (simklSelected) simklOutbox else weTrakrOutbox
    var sourceUrl = ""
        private set
    private var sourceKey = ""
    private var sourceHeaders = emptyMap<String, String>()
    private var playbackAccount: String? = null
    private var lastLinkSaved = false
    /** The automatic forced-subtitle check runs once per video. */
    private var subtitlesChecked = false
    private var externalSubtitlesChecked = false
    const val WYZIE_KEY = "subtitles.wyzie_key"
    var debridService: String? = null
        private set
    private var identity: PlaybackIdentity? = null
    var title: String = ""
    /** File name/release text and size of the link being played, for the player's badges. */
    var mediaName: String = ""
    var mediaSize: Long? = null
        private set
    var error: PlaybackException? = null
        private set
    val playerOrNull: ExoPlayer? get() = player
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
    private val surfaceAttachment = SurfaceReadyAttachment<ExoPlayer, PlayerView>(
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
    /** Set when parallel Easynews downloads keep failing for the current video; cleared when another video starts. */
    @Volatile private var easynewsSingle = false
    private fun isNetworkFailure(failure: PlaybackException) =
        failure.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            failure.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    fun initialize(context: Context) {
        if (application != null) return
        val app = context.applicationContext as Application
        application = app
        history = PlaybackStore(app)
        secureStore = SecureStore(app)
        weTrakrApi = WeTrakrApi(secureStore); simklApi = dev.pov.tv.data.SimklApi(secureStore)
        weTrakrOutbox = PlaybackStore(app, dev.pov.tv.data.Trackers.outboxKey(dev.pov.tv.data.Trackers.WETRAKR))
        simklOutbox = PlaybackStore(app, dev.pov.tv.data.Trackers.outboxKey(dev.pov.tv.data.Trackers.SIMKL))
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
    internal fun start(context: Context, intent: Intent, saved: Bundle? = null): Boolean {
        initialize(context)
        val address = intent.getStringExtra("url").orEmpty()
        val uri = try { Uri.parse(address) } catch (_: RuntimeException) { null }
        if (uri == null || uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        stopInternal(notify = false)
        sourceUrl = address
        easynewsSingle = false
        skipSegments = emptyList(); skipLookupFor = ""
        sourceKey = intent.getStringExtra("key").orEmpty()
        sourceHeaders = runCatching { JSONObject(intent.getStringExtra("headers") ?: "{}").let { json ->
            json.keys().asSequence().associateWith { json.getString(it) } } }.getOrDefault(emptyMap())
        playbackAccount = saved?.getString("playback_account") ?: intent.getStringExtra("playback_account")
        lastLinkSaved = false
        debridService = PlaybackDebrid.normalize(saved?.getString("debrid_service") ?: intent.getStringExtra("debrid_service"))
        title = intent.getStringExtra("title").orEmpty()
        if (saved == null) SubtitleSync.reset()
        subtitlesChecked = saved != null
        externalSubtitlesChecked = false
        mediaName = intent.getStringExtra("media_name").orEmpty().take(4000)
        mediaSize = intent.getLongExtra("media_size", 0L).takeIf { it > 0 }
        identity = try { intent.getStringExtra("identity")?.let { PlaybackIdentity.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
        if (saved == null) { history.show(sourceKey, identity); identity?.let(wetrakr::allowPlayback) }
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
        val renderers = object : DefaultRenderersFactory(application!!) {
            init {
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
                    // While a speed other than 1x is chosen, refuse passthrough so the renderer decodes to PCM.
                    override fun supportsFormat(format: Format) =
                        (!decodeForSpeed || format.sampleMimeType == MimeTypes.AUDIO_RAW) && super.supportsFormat(format)
                    override fun getFormatSupport(format: Format) =
                        if (decodeForSpeed && format.sampleMimeType != MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED
                        else super.getFormatSupport(format)
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
        // Debrid and Usenet-backed links can take well over the default 8 s to send their first bytes.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
            .setTransferListener(sizeFromServer(playing))
            .setDefaultRequestProperties(sourceHeaders)
        val exo = ExoPlayer.Builder(application!!, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(application!!,
                // Easynews links get the saved login inside the player; it is never in the link or the intent.
                EasynewsRoutingDataSource.Factory(http, { if (easynewsSingle) 1 else dev.pov.tv.data.Easynews.connections(secureStore) }) { dev.pov.tv.data.Easynews.credentials(secureStore)?.let { (user, password) ->
                    runCatching { dev.pov.tv.data.Easynews.auth(user, password) }.getOrNull() } })))
            .setLoadControl(ReadAheadPreferences(application!!).loadControl())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player = exo
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
                if (state == Player.STATE_READY) { quietRetries = 0; sourceRetries = 0; lookUpSkips(exo) }
                if (state == Player.STATE_READY && !lastLinkSaved) {
                    lastLinkSaved = true
                    val media = identity; val account = playbackAccount
                    // Header-bound links keep their headers; if those have expired, resuming falls back to a fresh search.
                    if (media != null && account != null) runCatching {
                        dev.pov.tv.data.LastPlayedLinks(SecureStore(application!!)).save(media, sourceUrl, sourceKey, debridService, account, mediaName, mediaSize, sourceHeaders)
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
                val sourceFailure = (failure as? androidx.media3.exoplayer.ExoPlaybackException)?.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE
                if (sourceFailure && error == null && sourceRetries < MAX_SOURCE_RETRIES &&
                    failure.errorCode !in setOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION)) {
                    sourceRetries++
                    // A second failure on an Easynews video: fall back to one connection for this video only.
                    if (sourceRetries >= 2 && dev.pov.tv.data.Easynews.isHost(sourceUrl) && !easynewsSingle) {
                        easynewsSingle = true
                        android.util.Log.w("PovPlayer", "Easynews keeps failing over parallel connections; retrying this video over one")
                    }
                    android.util.Log.w("PovPlayer", "Source error ${failure.errorCodeName}; quiet retry $sourceRetries of $MAX_SOURCE_RETRIES", failure)
                    handler.postDelayed({
                        if (isCurrent(exo) && exo.playerError != null && error == null) { exo.prepare(); exo.play() }
                    }, SOURCE_RETRY_DELAY_MS)
                    notifyChanged()
                    return
                }
                if (error !is VideoCompatibilityException) error = failure
                saveLocal(); sync("pause")
                exo.pause()
                volumeBoost.resetGain()
                handler.removeCallbacks(checkpoint)
                notifyChanged()
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(address)
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

    fun refreshSubtitleLanguage() {
        val context = application ?: return
        player?.let { it.trackSelectionParameters = SubtitlePreferences(context).trackParameters(it.trackSelectionParameters) }
    }

    fun refreshSubtitleStyle() {
        targetView?.get()?.let { SubtitlePreferences(it.context).applyTo(it) }
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
        networkRetryPending = false; quietRetries = 0; sourceRetries = 0
        volumeBoost.close()
        targetView?.get()?.let { it.player = null; it.keepScreenOn = false }
        targetView = null
        mediaSession?.release(); mediaSession = null
        exo?.release(); player = null
        sourceUrl = ""; sourceKey = ""; sourceHeaders = emptyMap(); title = ""; mediaName = ""; mediaSize = null; identity = null; debridService = null; playbackAccount = null; lastLinkSaved = false
        remotePercent = 0.0; error = null
        if (notify) notifyChanged()
    }

    internal fun saveState(out: Bundle) {
        val exo = player ?: return
        out.putLong("position", exo.currentPosition.coerceAtLeast(0))
        out.putString("debrid_service", debridService)
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
            .putExtra("debrid_service", debridService).putExtra("playback_account", playbackAccount)
            .putExtra("resume", exo.currentPosition).putExtra("resume_percent", remotePercent)
            .putExtra("playWhenReady", exo.playWhenReady)
            .also { value -> identity?.let { value.putExtra("identity", it.toJson().toString()) } }
    }

    internal fun setVolumeBoostGain(db: Int): VolumeBoostState {
        val result = volumeBoost.setGain(db)
        notifyChanged()
        return result
    }

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

    private fun isCurrent(exo: ExoPlayer) = !releasing && player === exo

    /** Intro, recap and credits times for the current video, once found (the player shows Skip buttons for them). */
    @Volatile var skipSegments: List<dev.pov.tv.data.SkipSegment> = emptyList()
        private set
    private var skipLookupFor = ""

    /** Once per video, when its length is known: the length picks the matching cut in the skip databases. */
    private fun lookUpSkips(exo: ExoPlayer) {
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
    private fun sizeFromServer(playing: String) = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {
            if (mediaSize != null || dataSpec.uri.toString() != playing) return
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
     * from OpenSubtitles, and Wyzie with the viewer's key, added as extra subtitle tracks. The first one is chosen.
     */
    private fun findMissingSubtitles(exo: ExoPlayer, tracks: Tracks) {
        if (externalSubtitlesChecked || tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }) return
        externalSubtitlesChecked = true
        val app = application ?: return
        val preferences = SubtitlePreferences(app)
        val wanted = preferences.language
        val media = identity ?: return
        // Header-bound links send their headers with every request, so no other host is asked for files.
        if (!preferences.autoFetch || wanted.isEmpty() || wanted == "off" || sourceHeaders.isNotEmpty()) return
        val embedded = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { group.getTrackFormat(it) }
        }.filter { it.selectionFlags and C.SELECTION_FLAG_FORCED == 0 && it.label?.contains("forced", true) != true }
            .map { dev.pov.tv.data.ExternalSubtitles.iso3(it.language.orEmpty()) }.toSet()
        val candidates = AudioPreferences(app).withFallback(wanted).toList()
        if (candidates.any { dev.pov.tv.data.ExternalSubtitles.iso3(it) in embedded }) return
        val playing = sourceUrl; val fileName = mediaName; val key = secureStore.get(WYZIE_KEY)
        Thread {
            val finder = dev.pov.tv.data.ExternalSubtitles()
            val found = candidates.firstNotNullOfOrNull { language ->
                runCatching { finder.find(media, language, fileName, key) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            handler.post {
                if (found.isEmpty() || !isCurrent(exo) || sourceUrl != playing) return@post
                val item = exo.currentMediaItem ?: return@post
                android.util.Log.i("PovSubtitles", "added ${found.size} online subtitles (${found.first().label})")
                val configs = found.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url)).setMimeType(subtitle.mime)
                        .setLanguage(subtitle.language).setLabel(if (found.size > 1) "${subtitle.label} ${index + 1}" else subtitle.label)
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
                }
                exo.setMediaItem(item.buildUpon().setSubtitleConfigurations(configs).build(), false)
                exo.prepare()
            }
        }.apply { isDaemon = true; name = "pov-subtitles" }.start()
    }

    private fun preferFullSubtitles(exo: ExoPlayer, tracks: Tracks) {
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
    private fun enforceVideoCompatibility(exo: ExoPlayer, tracks: Tracks = exo.currentTracks): Boolean {
        val existing = error as? VideoCompatibilityException
        val issue = existing?.issue ?: VideoPlaybackCompatibility.issue(tracks, exo.playbackState, exo.videoFormat)
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
        if (sourceKey.isBlank()) return
        val exo = player ?: return
        if (exo.duration <= 0) return
        try { history.save(sourceKey, title, identity, exo.currentPosition.coerceAtLeast(0), exo.duration) }
        catch (_: Exception) { /* Preserve playback if local storage is unavailable. */ }
    }
    private fun sync(action: String) {
        if (error is VideoCompatibilityException) return
        val media = identity ?: return
        val exo = player ?: return
        if (exo.duration <= 0) return
        if (!wetrakr.scrobblingEnabled() || history.isHidden(identity = media)) return
        val targets = listOf(wetrakr).mapNotNull { client ->
            try { if (client.connected()) client to client.accountRevision() else null } catch (_: Exception) { null }
        }
        if (targets.isEmpty()) return
        val percent = (exo.currentPosition.toDouble() * 100 / exo.duration).coerceIn(0.0, 100.0)
        val now = android.os.SystemClock.elapsedRealtime()
        if (action == lastSyncAction && now - lastSyncAt < 3_000) return
        lastSyncAction = action; lastSyncAt = now
        val scrobbleRevision = wetrakr.scrobbleRevision()
        val updates = targets.map { (client, account) ->
            val outbox = wetrakrOutbox
            val revision = try { if (action != "start") outbox.queue(media, percent, action, account, scrobbleRevision = scrobbleRevision) else null } catch (_: Exception) { null }
            Triple(client, account, outbox to revision)
        }
        syncWorker.execute {
            updates.forEach { (client, account, queued) ->
                try {
                    if (!history.isHidden(identity = media) && client.progressForAccount(media, percent, action, account, scrobbleRevision) && queued.second != null) queued.first.acknowledge(media, queued.second!!)
                } catch (_: Exception) { /* Each provider keeps its own final-state retry. */ }
            }
        }
    }
}

