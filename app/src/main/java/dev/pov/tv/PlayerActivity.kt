package dev.pov.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import dev.pov.tv.data.PlaybackIdentity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : Activity() {
    private lateinit var view: PlayerView
    private lateinit var controls: PlayerControlVisibility.Binding
    private lateinit var speedButton: Button
    private var activeDialog: Dialog? = null
    private var activeTrackType: Int? = null
    private var activeTrackChoices: List<PlayerTrackChoice> = emptyList()
    private var activeTracksLoading = false
    internal val currentOptionSheet: Dialog? get() = activeDialog
    private val player get() = InAppPlaybackSession.playerOrNull
    private val boost get() = InAppPlaybackSession.volumeBoost
    private val accent by lazy { ThemePreferences(this).selected().palette.accent }
    private var stopped = false
    private var gestures: PhonePlayerGestures? = null
    private val seekPreview = DpadSeekPreview()
    private var seekKey = KeyEvent.KEYCODE_UNKNOWN
    /** Set when a horizontal key goes down fresh (not a held repeat), so steps restart at one second. */
    private var pressStarted = false
    private val commitSeek = Runnable { finishSeek() }
    private var resumeAfterSeek = false
    private lateinit var seekLabel: TextView
    private lateinit var seekThumb: ImageView
    /** The Seekr thumbnail with its time under it, floating above the seek bar at the scrubbed position. */
    private lateinit var seekCard: android.widget.LinearLayout
    private lateinit var seekCardTime: TextView
    /** Seekr's ready-made previews for this title, when the viewer has a key and Seekr has the title. */
    private var seekrTrack: tv.seekr.previews.android.SeekrTrack? = null
    private var seekrLoad: kotlinx.coroutines.Job? = null
    private var seekrFrame: kotlinx.coroutines.Job? = null
    private var seekrFor = ""
    private val seekrPrefs by lazy { SeekrPreferences(this) }
    private val activeSeekr get() = seekrTrack?.takeIf { seekrPrefs.enabled }
    private val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val timeline get() = view.findViewById<PreviewTimeBar>(androidx.media3.ui.R.id.exo_progress)
    private var revealKey = KeyEvent.KEYCODE_UNKNOWN
    private var cachedLinkAttempt = false
    private var observedError: PlaybackException? = null
    private lateinit var splash: FrameLayout
    private lateinit var splashLogo: ImageView
    private lateinit var splashTitle: TextView
    /** The title's transparent logo in the top-left corner while playback is paused. */
    private lateinit var pauseLogo: FrameLayout
    private lateinit var pauseLogoArt: ImageView
    private var splashPulse: ValueAnimator? = null
    private var everReady = false
    /** Until this time, buffering is a decoder catching up after a seek into loaded video, not a network stall. */
    private var quietSeekUntil = 0L
    private val splashHandler = Handler(Looper.getMainLooper())
    private val showSplashLater = Runnable { setSplash(true) }
    private val miniPlayer by lazy { MiniPlayerPreferences(this).enabled }
    /** pov_browse is the Streams button: the title's other links, over the playing video. */
    private val rowIds = listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio, R.id.pov_speed, R.id.pov_browse, R.id.pov_stop)
    private var streamsDialog: Dialog? = null
    private val streamsChanged: () -> Unit = { if (!stopped && streamsDialog != null && streamsDialog === activeDialog && activeDialog?.isShowing == true) showStreams() }
    private val sessionListener: () -> Unit = {
        if (!stopped) {
            updateControls()
            activeTrackType?.let { type ->
                player?.let { current ->
                    val loading = current.playbackState == Player.STATE_IDLE || current.playbackState == Player.STATE_BUFFERING
                    if (activeDialog?.isShowing == true && (loading != activeTracksLoading ||
                        trackChoices(current) != activeTrackChoices)) {
                        showTracks(type)
                    }
                }
            }
            val failure = InAppPlaybackSession.error
            if (failure != null && observedError !== failure) showPlaybackFailure(failure)
            if (!InAppPlaybackSession.hasActive()) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cachedLinkAttempt = intent.getBooleanExtra("cached_playback", false)
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        InAppPlaybackSession.initialize(this)
        view = LayoutInflater.from(this).inflate(R.layout.pov_player, null, false) as PlayerView
        setContentView(view)
        controls = PlayerControlVisibility.bind(view)
        controls.visibilityChanged = { updatePauseLogo() }
        configureButtons()
        seekLabel = TextView(this).apply {
            tag = "pov_seek_preview"; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); setLineSpacing(0f, 0.9f)
            setPadding(dp(28), dp(14), dp(28), dp(14)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
        }
        requireNotNull(view.overlayFrameLayout).addView(seekLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // Seekr's tiles are about 320 px wide: shown near that size they stay sharp (the TV gets 360 px, not a blurry 512).
        // WuPlay's size: a fixed height (213 px on a 1080p screen) with the width following each picture, so a wide
        // film gets a wide card (about 522 px for 2.39:1) instead of a short one.
        val metrics = resources.displayMetrics
        val thumbHeight = (minOf(metrics.widthPixels, metrics.heightPixels) * 0.197f).toInt()
        val thumbWidth = thumbHeight * 16 / 9
        seekThumb = ImageView(this).apply {
            tag = "pov_seek_thumb"; scaleType = ImageView.ScaleType.FIT_XY
            // The picture fills the card edge to edge: rounded corners, a thin bright border and a soft shadow.
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(8).toFloat() }
            foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat(); setStroke(maxOf(2, dp(1)), 0xE6FFFFFF.toInt()) }
            clipToOutline = true; elevation = dp(10).toFloat()
        }
        // The time pill sits on the seek bar itself, over the scrubber (as in WuPlay), inside the controls so it draws
        // above the bar and never collides with the title row.
        seekCardTime = TextView(this).apply {
            tag = "pov_seek_time"; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5)); elevation = dp(6).toFloat()
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        }
        view.findViewById<FrameLayout>(androidx.media3.ui.R.id.exo_controller)
            ?.addView(seekCardTime, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        seekCard = android.widget.LinearLayout(this).apply {
            tag = "pov_seek_card"; orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE; clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(seekThumb, android.widget.LinearLayout.LayoutParams(thumbWidth, thumbHeight))
        }
        requireNotNull(view.overlayFrameLayout).addView(seekCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        timeline.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) = showThumb(position)
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) = showThumb(position)
            // The preview stays up a moment after the finger lifts, so the scene it landed on can be seen.
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                view.removeCallbacks(hideCard); view.postDelayed(hideCard, SEEK_LINGER_MS)
            }
        })
        if (DeviceLayout.phone) {
            // A view focusable in touch mode spends a tap on taking focus, so taps never reached the
            // controls toggle; on the phone every tap shows or hides them.
            view.isFocusableInTouchMode = false
            PhonePlayerGestures.fullBleed(this)
            // The seek bar and buttons sit in the lower part of the controls; touches there stay theirs.
            gestures = PhonePlayerGestures(this, view, seekLabel, { player },
                overControls = { y -> view.isControllerFullyVisible && y > view.height * 0.6f }, showControls = { controls.show() },
                onSeekPreview = { showThumb(it) })
        }
        buildSplash()
        openCurtain()
        val reuse = InAppPlaybackSession.hasActive() &&
            (intent.getBooleanExtra("reuse_session", false) || (savedInstanceState != null && InAppPlaybackSession.matches(intent)))
        if (!reuse && !InAppPlaybackSession.start(this, intent, savedInstanceState)) {
            showSheet("Cannot open video", "This playback link is invalid. Choose the source again.",
                listOf(PlayerSheetChoice("Back to browsing") { finish() }))
        }
    }

    private fun configureButtons() {
        // The focused control is a white pill with dark icon and text; the pill glides between buttons.
        val pill = SlidingFocusPill(view.findViewById(R.id.pov_player_actions), rowIds.map { view.findViewById<View>(it) })
        rowIds.forEachIndexed { index, id ->
            val button = view.findViewById<View>(id)
            button.isFocusable = true
            button.isFocusableInTouchMode = DeviceLayout.tv
            button.nextFocusUpId = androidx.media3.ui.R.id.exo_progress
            button.nextFocusLeftId = rowIds[(index - 1).coerceAtLeast(0)]
            button.nextFocusRightId = rowIds[(index + 1).coerceAtMost(rowIds.lastIndex)]
            button.setOnFocusChangeListener { _, focused -> pill.focusChanged(button, focused) }
        }
        view.findViewById<View>(R.id.pov_audio).setOnClickListener { showTracks(C.TRACK_TYPE_AUDIO) }
        speedButton = view.findViewById(R.id.pov_speed)
        speedButton.setOnClickListener { showSpeed() }
        view.findViewById<Button>(R.id.pov_browse).apply { text = "Streams"; setOnClickListener { showStreams() } }
        // Stop ends the session (no mini player) and returns to the page the video was started from.
        view.findViewById<View>(R.id.pov_stop).setOnClickListener { InAppPlaybackSession.stop(); finish() }
        view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
            setPlayedColor(accent)
            setKeyTimeIncrement(1000)
            nextFocusDownId = androidx.media3.ui.R.id.exo_play_pause
            nextFocusUpId = id
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        InAppPlaybackSession.addListener(sessionListener)
        StreamChoices.addListener(streamsChanged)
        InAppPlaybackSession.attachPlayerView(view)
        controls.start()
        view.removeCallbacks(endTimeTicker); view.post(endTimeTicker)
        view.removeCallbacks(seekrLookup); view.postDelayed(seekrLookup, 1_000)
        updateControls()
        InAppPlaybackSession.error?.let { if (observedError !== it) showPlaybackFailure(it) }
        controls.show()
    }

    /** Stremio-style loading: the title's logo pulses over its blurred artwork while video loads or rebuffers. */
    private fun buildSplash() {
        splash = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isFocusable = false; tag = "pov_loading_splash" }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(dp(14).toFloat(), dp(14).toFloat(), Shader.TileMode.CLAMP))
        }
        splash.addView(art, FrameLayout.LayoutParams(-1, -1))
        splash.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = resources.displayMetrics.widthPixels * 0.7f
                colors = intArrayOf(0x33000000, 0xE6000000.toInt())
            }
        }, FrameLayout.LayoutParams(-1, -1))
        splashLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        splash.addView(splashLogo, FrameLayout.LayoutParams(dp(420), dp(160), Gravity.CENTER))
        splashTitle = TextView(this).apply {
            textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(dp(40), 0, dp(40), 0)
        }
        splash.addView(splashTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        requireNotNull(view.overlayFrameLayout).addView(splash, 0, FrameLayout.LayoutParams(-1, -1))
        pauseLogoArt = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; maxWidth = dp(190); maxHeight = dp(70) }
        pauseLogo = FrameLayout(this).apply {
            visibility = View.GONE; alpha = 0f; isFocusable = false
            addView(pauseLogoArt, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        requireNotNull(view.overlayFrameLayout).addView(pauseLogo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(30); leftMargin = dp(30)
        })
        val imdb = try { intent.getStringExtra("identity")?.let { org.json.JSONObject(it).optString("imdbId") } } catch (_: Exception) { null }
        if (imdb != null && imdb.matches(Regex("tt\\d+"))) {
            loadSplashImage("https://images.metahub.space/background/medium/$imdb/img") { art.setImageBitmap(it) }
            loadSplashImage("https://images.metahub.space/logo/medium/$imdb/img") {
                splashLogo.setImageBitmap(it); splashLogo.visibility = View.VISIBLE; splashTitle.visibility = View.GONE
                pauseLogoArt.setImageBitmap(withShadow(it)); updatePauseLogo()
            }
        }
    }

    private fun loadSplashImage(address: String, done: (android.graphics.Bitmap) -> Unit) {
        Thread {
            try {
                ArtworkClient.init(applicationContext)
                val bytes = ArtworkClient.bytes(address)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) runOnUiThread { if (!isDestroyed) done(bitmap) }
            } catch (_: Exception) { /* The title text remains on the splash. */ }
        }.start()
    }

    private fun setSplash(show: Boolean) {
        if (!::splash.isInitialized) return
        if (show) {
            splashTitle.text = InAppPlaybackSession.title.ifBlank { "Loading" }
            if (splash.visibility != View.VISIBLE) { splash.alpha = 0f; splash.visibility = View.VISIBLE }
            splash.animate().cancel(); splash.animate().alpha(1f).setDuration(220).start()
            if (splashPulse == null) splashPulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { val value = it.animatedValue as Float; splashLogo.alpha = value; splashTitle.alpha = value }
                start()
            }
        } else if (splash.visibility == View.VISIBLE) {
            splash.animate().cancel()
            splash.animate().alpha(0f).setDuration(260).withEndAction {
                splash.visibility = View.GONE; splashPulse?.cancel(); splashPulse = null
            }.start()
        }
    }

    private fun updateSplash() {
        val state = player?.playbackState
        if (state == Player.STATE_READY) everReady = true
        val waiting = InAppPlaybackSession.error == null && player != null &&
            (state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && !everReady))
        if (!waiting) { splashHandler.removeCallbacks(showSplashLater); setSplash(false); return }
        // First load shows at once; a rebuffer waits briefly so quick seeks never flash the splash.
        if (!everReady) setSplash(true)
        else if (splash.visibility != View.VISIBLE) {
            splashHandler.removeCallbacks(showSplashLater)
            val quiet = (quietSeekUntil - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
            splashHandler.postDelayed(showSplashLater, maxOf(500, quiet))
        }
    }

    private val endTimeTicker = object : Runnable {
        override fun run() { updateEndTime(); if (!stopped) view.postDelayed(this, 15_000) }
    }
    /** "Ends at 9:42 PM": remaining time at the current speed, added to the TV's local clock. */
    private fun updateEndTime() {
        val label = view.findViewById<TextView>(R.id.pov_end_time) ?: return
        val exo = player
        val remaining = exo?.let { it.duration - it.currentPosition } ?: -1
        if (exo == null || exo.duration <= 0 || remaining <= 0) { label.visibility = View.GONE; return }
        val speed = exo.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        val end = java.util.Date(System.currentTimeMillis() + (remaining / speed).toLong())
        label.text = "Ends at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(end)
        label.visibility = View.VISIBLE
    }

    /** No panel behind the logo: a soft shadow traced from its own shape keeps it readable over a bright frame. */
    private fun withShadow(logo: android.graphics.Bitmap): android.graphics.Bitmap {
        val blur = dp(10)
        val out = android.graphics.Bitmap.createBitmap(logo.width + blur * 2, logo.height + blur * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val shadow = android.graphics.Paint().apply {
            color = Color.BLACK; alpha = 170; maskFilter = android.graphics.BlurMaskFilter(blur.toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(logo.extractAlpha(), blur.toFloat(), blur.toFloat() + dp(2), shadow)
        canvas.drawBitmap(logo, blur.toFloat(), blur.toFloat(), null)
        return out
    }

    private fun updatePauseLogo() {
        if (!::pauseLogo.isInitialized) return
        val exo = player
        val show = exo != null && pauseLogoArt.drawable != null && exo.playbackState == Player.STATE_READY &&
            // The title logo shows while paused, and also beside the controls while they are open.
            (!exo.playWhenReady || view.isControllerFullyVisible) &&
            !seekPreview.active && splash.visibility != View.VISIBLE && InAppPlaybackSession.error == null
        if (show == (pauseLogo.visibility == View.VISIBLE && pauseLogo.alpha > 0f && pauseLogo.tag != "hiding")) return
        pauseLogo.animate().cancel()
        if (show) {
            pauseLogo.tag = null; pauseLogo.visibility = View.VISIBLE
            pauseLogo.animate().alpha(1f).setDuration(260).start()
        } else {
            pauseLogo.tag = "hiding"
            pauseLogo.animate().alpha(0f).setDuration(160).withEndAction { pauseLogo.visibility = View.GONE; pauseLogo.tag = null }.start()
        }
    }

    private fun updateControls() {
        updateSplash()
        updatePauseLogo()
        updateEndTime()
        if (player?.playbackState == Player.STATE_READY) cachedLinkAttempt = false
        view.findViewById<TextView>(R.id.pov_playback_title).text = InAppPlaybackSession.title.ifBlank { "Now playing" }
        PlaybackDebrid.bind(view, InAppPlaybackSession.debridService)
        view.findViewById<PlayerBadgeRow>(R.id.pov_media_badges)?.show(PlayerMediaBadges.from(PlayerMediaFacts.from(
            InAppPlaybackSession.mediaName, InAppPlaybackSession.mediaSize, player?.videoFormat, player?.audioFormat)))
        speedButton.text = "Speed · ${speedLabel(player?.playbackParameters?.speed ?: 1f)}"
        controls.refreshPlaybackState()
    }

    private fun showPlaybackFailure(error: PlaybackException) {
        if (stopped || isFinishing || isDestroyed) return
        observedError = error
        if (cachedLinkAttempt) {
            cachedLinkAttempt = false; setResult(RESULT_FIRST_USER, Intent().putExtra("cached_identity", intent.getStringExtra("identity"))); InAppPlaybackSession.stop(); finish(); return
        }
        if (error is VideoCompatibilityException) {
            showSheet("Choose a compatible video", error.issue.message,
                listOf(PlayerSheetChoice("Choose another source") { InAppPlaybackSession.stop(); finish() }))
            return
        }
        showSheet("Playback stopped",
            "The link may have expired, the connection may be unavailable, or this TV may not support the format. Code: ${error.errorCodeName}",
            listOf(
                PlayerSheetChoice("Retry") { if (!stopped) { observedError = null; InAppPlaybackSession.retry() } },
                PlayerSheetChoice("Browse sources") { finish() }
            ))
    }

    private fun trackChoices(exo: Player) = TRACK_TYPES.flatMap { PlayerTrackOptions.choices(exo.currentTracks, exo.trackSelectionParameters, it) }

    /** One sheet for both: Audio and Subtitles side by side, opened with [type]'s column focused. */
    private fun showTracks(type: Int) {
        val exo = player ?: return
        val title = "Audio & Subtitles"
        val loading = exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_BUFFERING
        val columns = TRACK_TYPES.map { trackType ->
            val choices = PlayerTrackOptions.choices(exo.currentTracks, exo.trackSelectionParameters, trackType)
            val rows = choices.map { choice -> PlayerSheetChoice(choice.label, choice.checked, selectable = choice.selectable) {
                if (player === exo && !stopped) {
                    val stillAvailable = choice.kind != TrackChoiceKind.TRACK || exo.currentTracks.groups.any {
                        it.mediaTrackGroup == choice.group && choice.index in 0 until it.length && it.isTrackSupported(choice.index)
                    }
                    if (stillAvailable) exo.trackSelectionParameters = PlayerTrackOptions.apply(exo.trackSelectionParameters, choice)
                    else showSheet(title, "Tracks changed. Open this menu again to choose a current track.",
                        listOf(PlayerSheetChoice("Close") {}))
                }
            } }
            val menuRows = if (trackType == C.TRACK_TYPE_TEXT) rows + PlayerSheetChoice("Subtitle sync · ${SubtitleSync.label()}") { showSubtitleSync() } +
                PlayerSheetChoice("Subtitle style") { showSubtitleStyle() } else rows + PlayerSheetChoice("Volume boost · ${boostLabel()}") { showBoost() }
            PlayerSheetColumn(if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles",
                PlayerTrackOptions.description(choices, trackType, loading), menuRows)
        }
        showSheet(title, "", columns, TRACK_TYPES.indexOf(type).coerceAtLeast(0))
        activeTrackType = type
        activeTrackChoices = trackChoices(exo)
        activeTracksLoading = loading
    }

    private fun showSubtitleSync() {
        finishSeek(cancelled = true)
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        // Hide the controls so the subtitles stay in view while they are being moved.
        controls.dismiss()
        activeDialog = SubtitleSyncPicker.show(this, dismissed = { activeDialog = null; if (!stopped) controls.show() })
    }

    private fun showSubtitleStyle() {
        val preferences = SubtitlePreferences(this)
        showSheet("Subtitle style", "Applies to text subtitles. Image subtitles keep their original appearance.", listOf(
            PlayerSheetChoice("Transparent background", preferences.transparentBackground) {
                preferences.transparentBackground = !preferences.transparentBackground
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            },
            PlayerSheetChoice("Bold text", preferences.boldText) {
                preferences.boldText = !preferences.boldText
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            },
            PlayerSheetChoice("Subtitle size · ${preferences.sizeLabel}") { showSubtitleSize() },
            PlayerSheetChoice("Subtitle position · ${preferences.positionLabel}") { showSubtitlePosition() }
        ))
    }

    private fun showSubtitlePosition() {
        finishSeek(cancelled = true)
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        activeDialog = SubtitlePositionPicker.show(this, liveVideo = true,
            changed = { SubtitlePreferences(this).applyTo(view); InAppPlaybackSession.refreshSubtitleStyle() },
            dismissed = { activeDialog = null; if (!stopped) controls.show() })
    }

    private fun showSubtitleSize() {
        val preferences = SubtitlePreferences(this)
        showSheet("Subtitle size", "Medium keeps the original size.", SubtitlePreferences.SIZES.mapIndexed { index, size ->
            PlayerSheetChoice(SubtitlePreferences.SIZE_LABELS[index], preferences.sizePercent == size) {
                preferences.sizePercent = size
                preferences.applyTo(view); InAppPlaybackSession.refreshSubtitleStyle(); showSubtitleStyle()
            }
        })
    }

    private fun boostLabel() = when {
        boost.gainDb == 0 -> "Off"
        boost.state == VolumeBoostState.UNAVAILABLE -> "Unavailable"
        else -> "+${boost.gainDb} dB"
    }

    private fun showBoost() {
        showSheet("Volume boost",
            "Boost decoded PCM audio in software for this playback session. Encoded passthrough bypasses it. Off restores normal audio.",
            SessionVolumeBoost.STEPS.map { db -> PlayerSheetChoice(
                if (db == 0) "Off · Normal audio" else "+$db dB", boost.gainDb == db
            ) {
                if (player != null && !stopped) {
                    val state = InAppPlaybackSession.setVolumeBoostGain(db)
                    updateControls()
                    val result = when (state) {
                        VolumeBoostState.OFF -> "Volume boost off"
                        VolumeBoostState.WAITING_FOR_AUDIO -> "Software boost will start when decoded audio flows"
                        VolumeBoostState.UNAVAILABLE -> "Software boost is unavailable on this audio route"
                        VolumeBoostState.REQUESTED -> "+${boost.gainDb} dB software boost active"
                    }
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                }
            } })
    }

    /** Kodi-style tempo: audio is time-stretched with its pitch kept, for this playback only. */
    private fun showSpeed() {
        val current = player?.playbackParameters?.speed ?: 1f
        showSheet("Playback speed",
            "Voices keep their normal pitch. Applies to this video only. Dolby or DTS sent to a receiver is decoded by the app while sped up.",
            SPEEDS.map { speed -> PlayerSheetChoice(if (speed == 1f) "Normal · 1×" else speedLabel(speed), current == speed) {
                val exo = player ?: return@PlayerSheetChoice
                if (stopped) return@PlayerSheetChoice
                InAppPlaybackSession.setPlaybackSpeed(speed)
                updateControls()
                // Passthrough is switched to in-app decoding first; if the audio clock still keeps 1x, say so.
                view.postDelayed({
                    if (player === exo && !stopped && speed != 1f && exo.playbackParameters.speed != speed) {
                        updateControls()
                        Toast.makeText(this, "This audio can't be played at a different speed", Toast.LENGTH_LONG).show()
                    }
                }, 2_000)
            } })
    }

    private fun speedLabel(speed: Float) =
        (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

    private fun showOptionsMenu() {
        showSheet("Playback options", "Choose an action. Playback continues while you browse.",
            listOf(
                PlayerSheetChoice("Audio & Subtitles") { showTracks(C.TRACK_TYPE_AUDIO) },
                PlayerSheetChoice("Subtitle style") { showSubtitleStyle() },
                PlayerSheetChoice("Volume boost") { showBoost() },
                PlayerSheetChoice("Playback speed") { showSpeed() },
                PlayerSheetChoice("Browse while playing") { finish() },
                PlayerSheetChoice("Stop playback") { InAppPlaybackSession.stop(); finish() }
            ))
    }

    /**
     * The title's other links over the video. Picking one closes the player for a moment (the video carries on in the
     * mini player) while the link is prepared, then it continues from the same spot.
     */
    private fun showStreams() {
        val media = InAppPlaybackSession.playbackIdentity
        if (media == null || !media.imdbId.startsWith("tt")) {
            showSheet("Streams", "Other links are available for titles from POV's catalogs.", listOf(PlayerSheetChoice("Close") { activeDialog?.dismiss() }))
            streamsDialog = activeDialog; return
        }
        if (!StreamChoices.has(media)) StreamChoices.searcher?.invoke(media)
        val ours = StreamChoices.key == StreamChoices.keyOf(media)
        val links = if (ours) StreamChoices.items else null
        val playing = InAppPlaybackSession.mediaName
        when {
            links == null && ours && StreamChoices.failed ->
                showSheet("Streams", "The link search failed. Check your connection and try again.", listOf(PlayerSheetChoice("Try again") { StreamChoices.searcher?.invoke(media); showStreams() }))
            links == null -> showSheet("Streams", "Searching for links… they appear here in a moment.", listOf(PlayerSheetChoice("Searching…", selectable = false) {}))
            links.isEmpty() -> showSheet("Streams", "No other links were found for this title.", listOf(PlayerSheetChoice("Close") { activeDialog?.dismiss() }))
            else -> {
                // Quality, size, ≈bitrate (from this video's length), HDR, codec and language badges, as in the source list.
                val languages = SourceSortPreferences(this).languages
                val minutes = player?.duration?.takeIf { it > 0 }?.let { (it / 60_000).toInt() }
                val choices = links.take(STREAMS_SHOWN).map { source ->
                    val status = when {
                        source.directUrl.isNotEmpty() -> "Direct"
                        source.cachedServices.isNotEmpty() -> "Cached · " + source.cachedServices.joinToString(" + ") { dev.pov.tv.data.DebridServices.name(it) }
                        else -> "Not cached"
                    }
                    val name = source.title.lineSequence().firstOrNull().orEmpty()
                    val current = playing.isNotBlank() && name.isNotBlank() && playing.contains(name.take(40))
                    PlayerSheetChoice(name.ifBlank { source.title }, checked = current,
                        detail = listOf(status, source.provider, source.detail.replace('\n', ' ')).filter(String::isNotBlank).joinToString("  ·  "),
                        badges = SourceBadges.from(source, languages, minutes)) { pickStream(media, source) }
                }
                showSheet("Streams", "${links.size} links · pick one to continue from the same spot.", choices)
            }
        }
        streamsDialog = activeDialog
    }

    private val switcher by lazy { StreamSwitcher(this) }
    private var switching = false

    /** Prepares the picked link here in the player and switches to it at the same spot; the player never closes. */
    private fun pickStream(media: PlaybackIdentity, source: dev.pov.tv.data.SourceItem) {
        val exo = player ?: return
        if (switching) { Toast.makeText(this, "Preparing the link you picked…", Toast.LENGTH_SHORT).show(); return }
        val at = exo.currentPosition
        val ordered = StreamChoices.items.orEmpty()
        activeDialog?.dismiss()
        runSwitch(media, source, at, "Preparing ${source.title.lineSequence().firstOrNull().orEmpty().take(60)}…") { switcher.prepare(source, media, ordered) }
    }

    private fun runSwitch(media: PlaybackIdentity, source: dev.pov.tv.data.SourceItem, at: Long, message: String, work: () -> StreamSwitcher.Result) {
        switching = true
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching(work).getOrElse { StreamSwitcher.Result.Failed(it.message ?: "This link could not be prepared. Pick another link.") }
            runOnUiThread {
                switching = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is StreamSwitcher.Result.Ready -> startSwitchedStream(media, result, at)
                    is StreamSwitcher.Result.Failed -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    is StreamSwitcher.Result.ChooseFile -> showSheet("Choose a file", "This link has several videos. Pick the one to play.",
                        result.files.map { (id, name, size) ->
                            PlayerSheetChoice(name, detail = if (size > 0) "%.2f GB".format(java.util.Locale.ROOT, size / 1_000_000_000.0) else "") {
                                runSwitch(media, source, at, "Preparing ${name.take(60)}…") { switcher.file(result.prepared, source, id) }
                            }
                        })
                }
            }
        }.start()
    }

    /** Starts the new link in this player at [at]; the session's old link is saved and released first. */
    private fun startSwitchedStream(media: PlaybackIdentity, ready: StreamSwitcher.Result.Ready, at: Long) {
        val service = PlaybackDebrid.normalize(ready.service)
        val intent = Intent().putExtra("url", ready.url).putExtra("title", InAppPlaybackSession.title.ifBlank { media.title })
            .putExtra("identity", media.toJson().toString()).putExtra("key", "media:${media.imdbId}:${media.season}:${media.episode}")
            .putExtra("resume", at).putExtra("debrid_service", service)
            .putExtra("playback_account", dev.pov.tv.data.LastPlayedLinks(dev.pov.tv.data.SecureStore(this)).scope(service))
            .putExtra("media_name", ready.name).putExtra("media_size", ready.size)
            .putExtra("headers", org.json.JSONObject(ready.headers).toString())
        observedError = null
        if (!InAppPlaybackSession.start(this, intent)) {
            Toast.makeText(this, "This link did not return a playable web link. Pick another link.", Toast.LENGTH_LONG).show(); return
        }
        InAppPlaybackSession.attachPlayerView(view)
        updateControls()
    }

    private fun showSheet(title: String, detail: String, choices: List<PlayerSheetChoice>) =
        showSheet(title, detail, listOf(PlayerSheetColumn("", "", choices)), 0)

    private fun showSheet(title: String, detail: String, columns: List<PlayerSheetColumn>, focusColumn: Int) {
        finishSeek(cancelled = true)
        activeDialog?.dismiss()
        activeTrackType = null
        activeTrackChoices = emptyList()
        activeTracksLoading = false
        if (isFinishing || isDestroyed) return
        controls.show()
        val dialog = PlayerOptionSheet.create(this, title, detail, columns, accent, focusColumn)
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeTrackType = null
                activeTrackChoices = emptyList()
                activeTracksLoading = false
                if (!stopped) controls.show()
            }
        }
        dialog.show()
    }

    private fun beginOrMoveSeek(keyCode: Int): Boolean {
        val exo = player ?: return false
        if (!exo.isCurrentMediaItemSeekable || exo.duration <= 0) return false
        if (!seekPreview.active) {
            if (!seekPreview.begin(exo.currentPosition, exo.duration)) return false
            resumeAfterSeek = exo.playWhenReady
            exo.pause()
        }
        view.removeCallbacks(commitSeek)
        if (seekKey == KeyEvent.KEYCODE_UNKNOWN || pressStarted) seekPreview.newPress()
        pressStarted = false
        seekKey = keyCode
        seekPreview.step(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
        controls.show(); timeline.requestFocus()
        timeline.previewPosition = seekPreview.position
        seekLabel.text = "${DpadSeekPreview.time(seekPreview.position)}\n${DpadSeekPreview.offset(seekPreview.offsetSeconds * 1000)}  ·  ${DpadSeekPreview.time(exo.duration)}"
        seekLabel.visibility = View.VISIBLE
        showThumb(seekPreview.position, DpadSeekPreview.offset(seekPreview.offsetSeconds * 1000))
        return true
    }

    /** Looks the playing title up on Seekr once its length is known; retried every few seconds until then. */
    private val seekrLookup: Runnable = object : Runnable {
        override fun run() {
            if (stopped) return
            player?.takeIf { it.duration > 0 }?.let { loadSeekr(it.duration) }
            view.postDelayed(this, SEEKR_LOOKUP_MS)
        }
    }

    /** Looks the title up on Seekr once and downloads its preview sheets in the background, so scrubbing is instant. */
    private fun loadSeekr(durationMs: Long) {
        if (!seekrPrefs.enabled) return
        val key = InAppPlaybackSession.secureValue(SeekrPreferences.KEY).ifBlank { BuildConfig.SEEKR_KEY }
        val media = InAppPlaybackSession.playbackIdentity ?: return
        if (key.isBlank() || !media.imdbId.startsWith("tt")) return
        val id = "${media.imdbId}:${media.season}:${media.episode}"
        if (id == seekrFor) return
        seekrFor = id; seekrTrack = null; seekrLoad?.cancel()
        val content = if (media.type == "series" && media.season > 0 && media.episode > 0)
            tv.seekr.previews.core.SeekrContent.Episode(showImdbId = media.imdbId, season = media.season, episode = media.episode)
        else tv.seekr.previews.core.SeekrContent.Movie(imdbId = media.imdbId)
        seekrLoad = uiScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            val track = runCatching { tv.seekr.previews.android.Seekr.create(key).loadTrack(content, durationMs) }
                .onFailure { android.util.Log.w(SeekrPreferences.TAG, "Seekr lookup failed", it) }.getOrNull()?.takeIf { !it.isEmpty }
            android.util.Log.i(SeekrPreferences.TAG, "Seekr ${if (track != null) "has" else "has no"} previews for $id (${android.os.SystemClock.elapsedRealtime() - started} ms)")
            if (track == null) return@launch
            seekrTrack = track
            val sheets = android.os.SystemClock.elapsedRealtime()
            runCatching { track.prefetchSheets() }
            android.util.Log.i(SeekrPreferences.TAG, "Seekr sprite sheets ready in ${android.os.SystemClock.elapsedRealtime() - sheets} ms")
        }
    }

    /** The Seekr thumbnail nearest [position] above the seek label, or hide it when null. */
    private val hideCard = Runnable { showThumb(null) }
    private fun showThumb(position: Long?, offset: String? = null) {
        view.removeCallbacks(hideCard)
        if (position == null) {
            seekrFrame?.cancel()
            // Both fade out rather than vanishing.
            for (part in listOf<View>(seekCard, seekCardTime)) if (part.visibility == View.VISIBLE)
                part.animate().alpha(0f).setDuration(CARD_FADE_MS).withEndAction {
                    part.visibility = View.GONE; part.alpha = 1f; if (part === seekCard) seekThumb.setImageDrawable(null)
                }.start()
            return
        }
        for (part in listOf<View>(seekCard, seekCardTime)) { part.animate().cancel(); part.alpha = 1f }
        val track = activeSeekr
        val onBar = seekBarShown()
        // The pill carries the time on the seek bar, so the big centre label steps aside. When the bar is hidden (a
        // phone swipe) the label stays and only the picture shows.
        if (onBar) seekLabel.visibility = View.GONE
        seekCardTime.text = DpadSeekPreview.time(position) + (offset?.let { "  ·  $it" } ?: "")
        seekCardTime.visibility = if (onBar) View.VISIBLE else View.GONE
        seekCard.visibility = if (track != null) View.VISIBLE else View.GONE
        placeSeekCard(position)
        seekrFrame?.cancel()
        if (track == null) return
        seekrFrame = uiScope.launch {
            val raw = runCatching { nearestSeekrFrame(track, position) }.getOrNull()
            if (raw != null && seekCard.visibility == View.VISIBLE) {
                val frame = withoutBlackBars(track, raw)
                // The card takes the picture's own shape at a fixed height, so a wide film gets a wide card.
                val params = seekThumb.layoutParams
                val width = (params.height.toLong() * frame.width / frame.height.coerceAtLeast(1)).toInt()
                if (params.width != width) { params.width = width; seekThumb.layoutParams = params }
                seekThumb.setImageBitmap(frame)
                placeSeekCard(position)
            }
        }
    }

    private fun seekBarShown(): Boolean = timeline?.let { it.isShown && it.width > 0 } == true && (player?.duration ?: 0L) > 0

    /**
     * The black bars Seekr keeps in its 16:9 pictures of wider (or narrower) films, learned per title. The thinnest bars
     * seen win, because a dark scene looks like all bar.
     */
    private var barsFor: Any? = null
    private var bars = android.graphics.Rect()

    /** Trims the film's letterbox or pillarbox bars off a Seekr picture, so the card shows only the picture. */
    private fun withoutBlackBars(track: Any, frame: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = frame.width; val h = frame.height
        if (w < 16 || h < 16) return frame
        val pixels = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        fun dark(p: Int) = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < 28
        fun rowDark(y: Int) = (0 until w step 2).all { dark(pixels[y * w + it]) }
        fun colDark(x: Int) = (0 until h step 2).all { dark(pixels[it * w + x]) }
        var top = 0; while (top < h / 2 && rowDark(top)) top++
        var bottom = h - 1; while (bottom > h / 2 && rowDark(bottom)) bottom--
        var left = 0; while (left < w / 2 && colDark(left)) left++
        var right = w - 1; while (right > w / 2 && colDark(right)) right--
        val found = android.graphics.Rect(left, top, right + 1, bottom + 1)
        // An all-dark scene says nothing about the bars.
        val usable = found.height() >= h * 0.35f && found.width() >= w * 0.35f
        if (barsFor !== track) { barsFor = track; bars = if (usable) found else android.graphics.Rect(0, 0, w, h) }
        else if (usable) bars = android.graphics.Rect(minOf(bars.left, found.left), minOf(bars.top, found.top),
            maxOf(bars.right, found.right), maxOf(bars.bottom, found.bottom))
        val crop = android.graphics.Rect(bars)
        if (!crop.intersect(0, 0, w, h) || (crop.width() >= w - 2 && crop.height() >= h - 2)) return frame
        return android.graphics.Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height())
    }

    /**
     * Seekr has a picture every ~10 s and looks up the one at or before a position; past the middle of that interval the
     * next picture is closer to what the viewer lands on, so it is shown instead (never more than ~5 s off).
     */
    private suspend fun nearestSeekrFrame(track: tv.seekr.previews.android.SeekrTrack, position: Long): android.graphics.Bitmap? {
        val cue = track.thumbnailFor(position) ?: return null
        val laterIsCloser = cue.cueEndMs > cue.cueStartMs && position - cue.cueStartMs > cue.cueEndMs - position
        return if (laterIsCloser) track.thumbnailFor(cue.cueEndMs)?.bitmap ?: cue.bitmap else cue.bitmap
    }

    /** The pill on the seek bar at [position] and the picture just above it, kept on screen; centred when the bar is hidden. */
    private fun placeSeekCard(position: Long) {
        val overlay = view.overlayFrameLayout ?: return
        val duration = player?.duration ?: 0L
        seekCard.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = seekCard.measuredWidth; val h = seekCard.measuredHeight
        val bar: View? = timeline
        if (bar != null && seekBarShown()) {
            val o = IntArray(2); overlay.getLocationInWindow(o)
            val b = IntArray(2); bar.getLocationInWindow(b)
            val inset = dp(8) // the bar is drawn inside the time bar's scrubber padding
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            val centreInWindow = b[0] + inset + fraction * (bar.width - 2 * inset)
            val margin = dp(16).toFloat()
            seekCard.x = (centreInWindow - o[0] - w / 2f).coerceIn(margin, (overlay.width - w - margin).coerceAtLeast(margin))
            // The picture's bottom sits a little above the bar, clear of the pill.
            seekCard.y = (b[1] - o[1] - h - dp(6)).toFloat()
            (seekCardTime.parent as? View)?.let { controls ->
                val c = IntArray(2); controls.getLocationInWindow(c)
                seekCardTime.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val pw = seekCardTime.measuredWidth; val ph = seekCardTime.measuredHeight
                val lo = (b[0] - c[0]).toFloat(); val hi = (b[0] - c[0] + bar.width - pw).toFloat().coerceAtLeast(lo)
                seekCardTime.x = (centreInWindow - c[0] - pw / 2f).coerceIn(lo, hi)
                seekCardTime.y = b[1] - c[1] + bar.height / 2f - ph / 2f
            }
        } else {
            seekCard.x = (overlay.width - w) / 2f
            seekCard.y = (overlay.height - h) / 2f - dp(90)
        }
    }

    private fun finishSeek(cancelled: Boolean = false, restorePlayback: Boolean = true) {
        view.removeCallbacks(commitSeek)
        if (!seekPreview.active) return
        val target = seekPreview.finish(cancelled)
        timeline.previewPosition = null
        seekLabel.visibility = View.GONE
        // A landed seek keeps its preview for a moment, then it fades; a cancelled one goes at once.
        if (target == null) showThumb(null) else { view.removeCallbacks(hideCard); view.postDelayed(hideCard, SEEK_LINGER_MS) }
        seekKey = KeyEvent.KEYCODE_UNKNOWN
        player?.let { exo ->
            if (target != null) {
                timeline.setPosition(target)
                val from = exo.currentPosition
                // Video can only start from a keyframe. Landing off one makes the TV decode forward from the keyframe before
                // it, which takes the TCL a second or two even when that video is already loaded, so seeks always land on
                // one: the closest for bigger jumps, the next or previous for short taps so they still move that way.
                val distance = kotlin.math.abs(target - from)
                exo.setSeekParameters(when {
                    distance >= SNAP_CLOSEST_FROM_MS -> androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
                    target >= from -> androidx.media3.exoplayer.SeekParameters.NEXT_SYNC
                    else -> androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC
                })
                if (target >= from - ReadAheadPreferences.BACK_BUFFER_MS && target <= exo.bufferedPosition)
                    quietSeekUntil = android.os.SystemClock.uptimeMillis() + QUIET_SEEK_MS
                exo.seekTo(target)
            }
            if (restorePlayback && resumeAfterSeek) exo.play()
        }
        controls.show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) finishSeek(cancelled = true, restorePlayback = false)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) controls.userInteraction()
        if (activeDialog?.isShowing != true && gestures?.onTouch(event) == true) return true
        return super.dispatchTouchEvent(event)
    }

    // Holding Back leaves the player for Settings; the video keeps its mini-player session.
    private val backHold = BackHoldGesture { BackHoldGesture.settingsRequested = true; if (seekPreview.active) finishSeek(cancelled = true); finish() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backHold.intercept(event)) return true
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (event.action == KeyEvent.ACTION_UP && horizontal && seekPreview.active) {
            // Releasing the key seeks at once (buffered video plays instantly); the preview stays up a moment so the scene
            // can be seen, and another press fine-tunes from there.
            if (event.keyCode == seekKey) finishSeek(cancelled = event.isCanceled)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && seekPreview.active && !horizontal) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                finishSeek(cancelled = true); revealKey = KeyEvent.KEYCODE_BACK; return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                finishSeek(); revealKey = event.keyCode; return true // OK lands on the previewed scene now
            }
            finishSeek()
        }
        if (event.action == KeyEvent.ACTION_DOWN && horizontal && event.repeatCount == 0) pressStarted = true
        if (event.action == KeyEvent.ACTION_DOWN && horizontal && activeDialog?.isShowing != true &&
            (seekPreview.active || !view.isControllerFullyVisible || currentFocus?.id == androidx.media3.ui.R.id.exo_progress || currentFocus === view)) {
            if (beginOrMoveSeek(event.keyCode)) return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == revealKey) {
            revealKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) controls.userInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS) showTracks(C.TRACK_TYPE_TEXT) else showOptionsMenu()
            }
            return true
        }
        val dpad = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        if (event.action == KeyEvent.ACTION_DOWN && dpad) {
            if (!view.isControllerFullyVisible) {
                controls.show()
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                revealKey = event.keyCode
                return true
            }
            val focused = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused != null && focused.id in rowIds) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused?.id == androidx.media3.ui.R.id.exo_progress) {
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
                return true
            }
        }
        if (view.dispatchMediaKeyEvent(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) controls.show()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            seekPreview.active -> finishSeek(cancelled = true)
            // Back first brings up the controls; a second Back (with them showing) leaves for the mini player, or stops the
            // video (saving its progress, like Stop) when the mini player is off.
            !view.isControllerFullyVisible -> {
                controls.show()
                view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()
            }
            else -> { if (!miniPlayer) InAppPlaybackSession.stop(); finish() }
        }
    }

    /**
     * A black curtain over everything, video included, fades the player in on open and out before it closes.
     * SurfaceView video and Activity window animations do not compose smoothly on older TVs, so the motion
     * happens inside the window instead.
     */
    private var curtain: View? = null
    private var closing = false

    private fun openCurtain() {
        val cover = View(this).apply { setBackgroundColor(Color.BLACK); isFocusable = false; isClickable = false }
        (window.decorView as android.view.ViewGroup).addView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
        curtain = cover
        view.scaleX = 0.96f; view.scaleY = 0.96f
        cover.post {
            view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            cover.animate().alpha(0f).setDuration(280).withEndAction { cover.visibility = View.GONE }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        if (!isFinishing && InAppPlaybackSession.error is VideoCompatibilityException) InAppPlaybackSession.stop()
        val cover = curtain
        if (closing || cover == null || isDestroyed || !window.decorView.isAttachedToWindow) { closeNow(); return }
        closing = true
        cover.animate().cancel(); cover.visibility = View.VISIBLE
        cover.animate().alpha(1f).setDuration(180).withEndAction { closeNow() }.start()
    }

    @Suppress("DEPRECATION")
    private fun closeNow() {
        closing = true
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        InAppPlaybackSession.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        view.removeCallbacks(seekrLookup)
        finishSeek(cancelled = true, restorePlayback = false)
        stopped = true
        controls.stop()
        activeDialog?.dismiss(); activeDialog = null
        InAppPlaybackSession.removeListener(sessionListener)
        StreamChoices.removeListener(streamsChanged)
        InAppPlaybackSession.detachPlayerView(view)
        super.onStop()
    }

    override fun onDestroy() {
        splashHandler.removeCallbacks(showSplashLater); splashPulse?.cancel()
        controls.release()
        InAppPlaybackSession.removeListener(sessionListener)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SEEKR_LOOKUP_MS = 3_000L
        /** How long the seek preview stays after the key or finger is released, before the video jumps there. */
        private const val SEEK_LINGER_MS = 1_000L
        private const val CARD_FADE_MS = 200L
        private const val STREAMS_SHOWN = 60
        /** A seek may land up to this far from its target to reach a keyframe. */
        const val SEEK_SNAP_MS = 2_000L
        /** From this distance a seek lands on whichever keyframe is closest, in either direction. */
        const val SNAP_CLOSEST_FROM_MS = 10_000L
        const val QUIET_SEEK_MS = 4_000L
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val TRACK_TYPES = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
        fun openCurrent(context: Context): Boolean {
            val intent = InAppPlaybackSession.currentIntent(context) ?: return false
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            @Suppress("DEPRECATION")
            if (context is Activity) context.overridePendingTransition(0, 0)
            return true
        }
    }
}

