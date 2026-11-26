package dev.pov.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale

/**
 * mpv behind the standard player interface, so the app's own player screen (controls, seek bar, audio and subtitle
 * menus, speed, Streams, progress and tracker sync) drives it exactly as it drives ExoPlayer. mpv draws into the player
 * screen's surface and decodes in hardware where the chip can, in software (its own FFmpeg) where it can't; it renders
 * subtitles itself (libass). Its tracks are offered as ordinary tracks; picking one sets mpv's aid/sid.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MpvPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mpv: MPV

    private var item: MediaItem? = null
    private var startMs = 0L
    private var prepared = false
    private var loading = false
    private var loaded = false
    private var playWhenReady = false
    private var error: PlaybackException? = null
    private var trackParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var speed = 1f
    private var volume = 1f

    // Last values mpv reported (its thread), read when the state is built (main thread).
    @Volatile private var positionS = 0.0
    @Volatile private var durationS = 0.0
    @Volatile private var cacheS = 0.0
    @Volatile private var paused = true
    @Volatile private var waiting = true
    @Volatile private var ended = false
    /** The video's frame rate from its container (0 until known): for Match frame rate. */
    @Volatile var frameRate = 0f; private set
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var firstFrame = false
    private var reportedFirstFrame = false
    private var tracks = Tracks.EMPTY
    /** Each offered track group's mpv track: its type ("video", "audio", "sub") and id. */
    private val mpvTracks = HashMap<TrackGroup, Pair<String, Int>>()

    private var surface: Surface? = null
    private var holder: SurfaceHolder? = null
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attach(holder.surface)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            mpv.setPropertyString("android-surface-size", "${width}x$height")
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
    }

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) { if (property == "track-list") main.post { refreshTracks() } }
        override fun eventProperty(property: String, value: Long) {
            when (property) { "dwidth" -> width = value.toInt(); "dheight" -> height = value.toInt() }
            changed()
        }
        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache", "seeking" -> waiting = value || mpv.getPropertyBoolean("seeking") == true || mpv.getPropertyBoolean("paused-for-cache") == true
                "eof-reached" -> ended = value
            }
            changed()
        }
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> { positionS = value; syncSubtitles() }
                "duration" -> durationS = value
                "demuxer-cache-time" -> cacheS = value
                "container-fps" -> { frameRate = value.toFloat(); main.post { applyFrameRate() } }
            }
            if (property != "time-pos" && property != "demuxer-cache-time") changed()
        }
        override fun eventProperty(property: String, value: MPVNode) = Unit
        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> main.post { loaded = true; loading = false; addSubtitles(); refreshTracks(); invalidateState() }
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post {
                    waiting = false
                    if (!firstFrame) firstFrame = true
                    invalidateState()
                }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> main.post {
                    if (!loaded && prepared && error == null) {
                        error = PlaybackException(L10n.tr(R.string.t_mpv_couldnt_play, "mpv couldn't play this link either. Pick another source."),
                            null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                        loading = false
                        invalidateState()
                    }
                }
            }
        }
    }

    init {
        Utils.copyAssets(app)
        mpv = MPV(app, app.filesDir.path, app.cacheDir.path)
        mpv.command("apply-profile", "fast")
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // Hardware decoding straight to the picture where the chip can, copied back where it can't; software after that.
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${app.filesDir.path}/cacert.pem")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        mpv.setOptionString("cache-on-disk", "no")
        // Stays on the last frame at the end, so the end is reported (and saved) like the main player's.
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("pause", "yes")
        mpv.setOptionString("msg-level", "all=warn")
        // The player screen sizes the surface to the picture (and to Zoom / Stretch); mpv fills it exactly.
        mpv.setOptionString("keepaspect", "no")
        configureAudio()
        applySubtitleStyle()
        mpv.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("seeking", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("dwidth", MPV.mpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("dheight", MPV.mpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("container-fps", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        mpv.addObserver(observer)
    }

    private fun changed() { main.post { invalidateState() } }

    /**
     * Sound like the main player: formats the TV takes go to it encoded (passthrough), as Settings, Audio, Audio
     * passthrough allows; the rest is decoded without Dolby's dynamic range compression (which made dialogue quiet) and
     * with the default volume boost.
     */
    private fun configureAudio() {
        val mode = AudioPassthrough.mode(app)
        val spdif = if (AudioPassthrough.surroundNever(app)) emptyList() else runCatching {
            val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(app, androidx.media3.common.AudioAttributes.DEFAULT, null)
            listOf(Triple(C.ENCODING_AC3, "ac3", MimeTypes.AUDIO_AC3), Triple(C.ENCODING_E_AC3, "eac3", MimeTypes.AUDIO_E_AC3),
                Triple(C.ENCODING_DTS, "dts", MimeTypes.AUDIO_DTS), Triple(C.ENCODING_DTS_HD, "dts-hd", MimeTypes.AUDIO_DTS_HD),
                Triple(C.ENCODING_DOLBY_TRUEHD, "truehd", MimeTypes.AUDIO_TRUEHD))
                .filter { (encoding, _, mime) -> caps.supportsEncoding(encoding) && !AudioPassthrough.decodes(mode, mime) }.map { it.second }
        }.getOrDefault(emptyList())
        if (spdif.isNotEmpty()) mpv.setOptionString("audio-spdif", spdif.joinToString(","))
        android.util.Log.i("PovMpv", "passthrough: ${spdif.ifEmpty { listOf("none") }.joinToString(",")}")
        mpv.setOptionString("ad-lavc-ac3drc", "0")
        mpv.setOptionString("audio-normalize-downmix", "no")
        mpv.setOptionString("volume-gain-max", "30")
        VolumeBoostPreferences(app).defaultDb.takeIf { it > 0 }?.let { setBoost(it) }
    }

    /** The player's Volume boost (dB), as mpv's gain. */
    fun setBoost(db: Int) { mpv.setPropertyDouble("volume-gain", db.toDouble()) }

    /** Subtitle sync (the player's Sync menu or auto sync): mpv's sub-delay and sub-speed follow it. */
    @Volatile private var appliedDelay = Long.MIN_VALUE
    @Volatile private var appliedSpeed = 0.0
    private fun syncSubtitles() {
        val offset = SubtitleSync.offsetMs; val factor = SubtitleSync.factor
        if (offset == appliedDelay && factor == appliedSpeed) return
        appliedDelay = offset; appliedSpeed = factor
        main.post {
            mpv.setPropertyDouble("sub-delay", offset / 1000.0)
            mpv.setPropertyDouble("sub-speed", factor)
        }
    }

    /** Match frame rate "Seamless": asks for the video's rate on the surface where the TV can switch without a blink. */
    private fun applyFrameRate() {
        val target = surface ?: return
        if (frameRate <= 0f || android.os.Build.VERSION.SDK_INT < 30 || FrameRateMatch.mode(app) != FrameRateMatch.SEAMLESS) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            else target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        invalidateState()
    }

    /**
     * The viewer's subtitle look (Settings, Subtitles and the player's Style menu): font, size, position, colors, outline,
     * shadow and box, as close to the main player's as mpv draws them. Sizes follow the main player's (5.33% of the
     * picture height at Medium, in mpv's 720-line units).
     */
    fun applySubtitleStyle() {
        val style = SubtitlePreferences(app)
        fun set(name: String, value: String) { mpv.setPropertyString(name, value) }
        fun color(argb: Int) = String.format(Locale.ROOT, "#%08X", argb)
        val captions = app.getSystemService(Context.CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
        set("sub-font-size", String.format(Locale.ROOT, "%.1f", 38.4 * style.sizePercent / 100.0 * (captions?.fontScale ?: 1f)))
        set("sub-bold", if (style.boldText) "yes" else "no")
        subtitleFont(style.font)?.let { set("sub-font", it) }
        set("sub-color", color(style.textColor ?: android.graphics.Color.WHITE))
        val edge = style.edgeColor ?: android.graphics.Color.BLACK
        val (outline, shadow) = when (style.edgeType) {
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE -> 0.0 to 0.0
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE -> 2.5 to 0.0
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> 0.0 to 2.0
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_RAISED, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> 0.5 to 1.5
            else -> 2.5 to 0.0
        }
        // Both spellings: newer mpv calls the border an outline.
        for (name in listOf("sub-outline-size", "sub-border-size")) set(name, outline.toString())
        for (name in listOf("sub-outline-color", "sub-border-color")) set(name, color(edge))
        set("sub-shadow-offset", shadow.toString())
        set("sub-shadow-color", color(edge))
        val opacity = style.backgroundOpacity
        if (opacity > 0) {
            set("sub-border-style", "background-box")
            set("sub-back-color", color((style.backgroundColor and 0x00FFFFFF) or ((opacity * 255 / 100) shl 24)))
        } else set("sub-border-style", "outline-and-shadow")
        val position = style.positionPercent
        if (position >= 0) { set("sub-pos", (100 - position).toString()); set("sub-margin-y", "0") }
        else { set("sub-pos", "100"); set("sub-margin-y", "22") }
        // Styled (ASS) subtitles take the viewer's look too, keeping their own placement, as the main player does.
        set("sub-ass-override", "yes")
    }

    /** The chosen font's family for mpv; its files are copied once from the app's assets to a folder mpv reads. */
    private fun subtitleFont(id: String): String? {
        val family = mapOf("lato" to "Lato", "inter" to "Inter", "arimo" to "Arimo", "open-sans" to "Open Sans", "noto-sans" to "Noto Sans")[id]
            ?: return when (id) { "roboto", "" -> "Roboto"; "mono" -> "monospace"; "serif" -> "serif"; else -> null }
        val folder = java.io.File(app.filesDir, "mpv-fonts").apply { mkdirs() }
        for (weight in listOf("regular", "bold")) {
            val target = java.io.File(folder, "$id-$weight.ttf")
            if (!target.isFile) runCatching { app.assets.open("subtitle-fonts/$id-$weight.ttf").use { input -> target.outputStream().use { input.copyTo(it) } } }
        }
        mpv.setPropertyString("sub-fonts-dir", folder.path)
        return family
    }

    private val commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TRACKS,
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_VOLUME,
        Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES).build()

    override fun getState(): State {
        val state = when {
            error != null || !prepared || item == null -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !loaded || waiting || !firstFrame -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            // Only while it plays or waits: the player library refuses "loading" when idle or ended (that crashed on exit).
            .setIsLoading((state == Player.STATE_BUFFERING || state == Player.STATE_READY) && (loading || loaded))
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParameters)
            .setVolume(volume)
            .setVideoSize(if (width > 0 && height > 0) VideoSize(width, height) else VideoSize.UNKNOWN)
        // The player screen hides its black shutter on the first picture.
        if (firstFrame && !reportedFirstFrame) { reportedFirstFrame = true; builder.setNewlyRenderedFirstFrame(true) }
        item?.let { media ->
            val data = MediaItemData.Builder("mpv").setMediaItem(media).setIsSeekable(true).setTracks(tracks)
                .setDurationUs(if (durationS > 0) (durationS * 1_000_000).toLong() else C.TIME_UNSET)
            builder.setPlaylist(listOf(data.build()))
            builder.setContentPositionMs(PositionSupplier { if (loaded) (positionS * 1000).toLong() else startMs })
            builder.setContentBufferedPositionMs(PositionSupplier { if (loaded) (maxOf(cacheS, positionS) * 1000).toLong() else startMs })
        }
        return builder.build()
    }

    /** Subtitle files already handed to mpv (add-on and OpenSubtitles ones arrive by re-setting the same item). */
    private val addedSubtitles = HashSet<String>()

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val next = mediaItems.getOrNull(maxOf(startIndex, 0))
        val sameFile = next != null && item?.localConfiguration?.uri == next.localConfiguration?.uri
        if (sameFile && next != null) {
            // The same video with subtitles added: give mpv the new files, keep playing.
            item = next
            if (loaded) addSubtitles()
            return Futures.immediateVoidFuture()
        }
        addedSubtitles.clear()
        item = next
        startMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0)
        loaded = false; ended = false; firstFrame = false; reportedFirstFrame = false
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (item == null) return Futures.immediateVoidFuture()
        error = null; prepared = true; loading = true
        if (surface != null && !loaded) load()
        return Futures.immediateVoidFuture()
    }

    /** Loads the file once a surface is there to draw into (mpv's video output needs it from the start). */
    private fun load() {
        val uri = item?.localConfiguration?.uri?.toString() ?: return
        mpv.setOptionString("start", if (startMs > 0) String.format(Locale.US, "+%.3f", startMs / 1000.0) else "none")
        mpv.setPropertyBoolean("pause", !playWhenReady)
        mpv.command("loadfile", uri, "replace")
    }

    private fun addSubtitles() {
        val configs = item?.localConfiguration?.subtitleConfigurations ?: return
        for (config in configs) {
            val uri = config.uri.toString()
            if (!addedSubtitles.add(uri)) continue
            val title = config.label?.toString() ?: config.language ?: "Subtitles"
            mpv.command("sub-add", uri, "auto", title, config.language ?: "")
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        mpv.setPropertyBoolean("pause", !playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0)
        if (loaded) {
            ended = false; waiting = true
            positionS = target / 1000.0
            mpv.command("seek", String.format(Locale.US, "%.3f", target / 1000.0), "absolute")
        } else startMs = target
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        mpv.setPropertyDouble("speed", speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        mpv.setPropertyDouble("volume", (volume * 100).toDouble())
        return Futures.immediateVoidFuture()
    }

    /** The app's audio/subtitle menus pick a track (an override) or turn a type off; mpv gets the matching id. */
    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParameters = trackSelectionParameters
        // The viewer's preferred languages (Settings → Audio & Subtitles) for mpv's own choice when a file loads.
        trackSelectionParameters.preferredAudioLanguages.takeIf { it.isNotEmpty() }?.let { mpv.setOptionString("alang", it.joinToString(",")) }
        trackSelectionParameters.preferredTextLanguages.takeIf { it.isNotEmpty() }?.let { mpv.setOptionString("slang", it.joinToString(",")) }
        for ((property, type, mpvType) in listOf(Triple("aid", C.TRACK_TYPE_AUDIO, "audio"), Triple("sid", C.TRACK_TYPE_TEXT, "sub"))) {
            if (type in trackSelectionParameters.disabledTrackTypes) { mpv.setPropertyString(property, "no"); continue }
            val chosen = trackSelectionParameters.overrides.values.firstOrNull { it.type == type && mpvTracks[it.mediaTrackGroup]?.first == mpvType }
            chosen?.let { override -> mpvTracks[override.mediaTrackGroup]?.second?.let { mpv.setPropertyInt(property, it) } }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> useHolder(videoOutput.holder)
            is SurfaceHolder -> useHolder(videoOutput)
            is Surface -> attach(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        holder?.removeCallback(surfaceCallback); holder = null
        detach()
        return Futures.immediateVoidFuture()
    }

    private fun useHolder(next: SurfaceHolder) {
        holder?.removeCallback(surfaceCallback)
        holder = next
        next.addCallback(surfaceCallback)
        next.surface?.takeIf { it.isValid }?.let(::attach)
    }

    private fun attach(next: Surface) {
        if (surface === next) return
        surface = next
        mpv.attachSurface(next)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        applyFrameRate()
        if (prepared && !loaded) load()
    }

    private fun detach() {
        if (surface == null) return
        surface = null
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false; loaded = false; loading = false
        mpv.command("stop")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        prepared = false
        holder?.removeCallback(surfaceCallback); holder = null
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.command("stop") }
        detach()
        runCatching { mpv.close() }
        return Futures.immediateVoidFuture()
    }

    /** mpv's track list as ordinary track groups (one track each), with the current ones marked selected. */
    private fun refreshTracks() {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val groups = ArrayList<Tracks.Group>()
        mpvTracks.clear()
        for (index in 0 until count) {
            val type = mpv.getPropertyString("track-list/$index/type") ?: continue
            val id = mpv.getPropertyInt("track-list/$index/id") ?: continue
            val codec = mpv.getPropertyString("track-list/$index/codec").orEmpty().lowercase(Locale.ROOT)
            val format = Format.Builder().setId("mpv:$type:$id")
                .setSampleMimeType(mime(type, codec) ?: continue)
                .setCodecs(codec.ifBlank { null })
                .setLanguage(mpv.getPropertyString("track-list/$index/lang"))
                .setLabel(mpv.getPropertyString("track-list/$index/title"))
                .apply {
                    if (type == "audio") mpv.getPropertyInt("track-list/$index/demux-channel-count")?.takeIf { it > 0 }?.let(::setChannelCount)
                    if (type == "video") {
                        mpv.getPropertyInt("track-list/$index/demux-w")?.let(::setWidth)
                        mpv.getPropertyInt("track-list/$index/demux-h")?.let(::setHeight)
                    }
                    if (mpv.getPropertyBoolean("track-list/$index/default") == true) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    if (mpv.getPropertyBoolean("track-list/$index/forced") == true) setSelectionFlags(C.SELECTION_FLAG_FORCED)
                }.build()
            val group = TrackGroup("mpv-$type-$id", format)
            mpvTracks[group] = type to id
            val selected = mpv.getPropertyBoolean("track-list/$index/selected") == true
            groups += Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        }
        tracks = Tracks(groups)
        invalidateState()
    }

    private fun mime(type: String, codec: String): String? = when (type) {
        "video" -> when (codec) {
            "h264" -> MimeTypes.VIDEO_H264; "hevc" -> MimeTypes.VIDEO_H265; "av1" -> MimeTypes.VIDEO_AV1; "vp9" -> MimeTypes.VIDEO_VP9
            "vp8" -> MimeTypes.VIDEO_VP8; "mpeg2video" -> MimeTypes.VIDEO_MPEG2; "mpeg4" -> MimeTypes.VIDEO_MP4V; "vc1" -> MimeTypes.VIDEO_VC1
            else -> "video/x-mpv-$codec"
        }
        "audio" -> when (codec) {
            "aac" -> MimeTypes.AUDIO_AAC; "ac3" -> MimeTypes.AUDIO_AC3; "eac3" -> MimeTypes.AUDIO_E_AC3; "dts" -> MimeTypes.AUDIO_DTS
            "truehd" -> MimeTypes.AUDIO_TRUEHD; "opus" -> MimeTypes.AUDIO_OPUS; "flac" -> MimeTypes.AUDIO_FLAC; "mp3" -> MimeTypes.AUDIO_MPEG
            "vorbis" -> MimeTypes.AUDIO_VORBIS; "pcm_s16le", "pcm_s24le" -> MimeTypes.AUDIO_RAW
            else -> "audio/x-mpv-$codec"
        }
        "sub" -> when (codec) {
            "subrip" -> MimeTypes.APPLICATION_SUBRIP; "ass", "ssa" -> MimeTypes.TEXT_SSA; "webvtt" -> MimeTypes.TEXT_VTT
            "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS; "dvd_subtitle" -> MimeTypes.APPLICATION_VOBSUB; "mov_text" -> MimeTypes.APPLICATION_TX3G
            else -> "text/x-mpv-$codec"
        }
        else -> null
    }
}
