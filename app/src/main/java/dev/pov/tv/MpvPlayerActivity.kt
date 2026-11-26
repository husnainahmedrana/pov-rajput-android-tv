package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.pov.tv.data.MediaFlowProxy
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale

/**
 * The mpv engine: plays the formats and containers the main player can't (or every video, when Settings → Playback →
 * Player engine is mpv). The link is served to mpv through the same local stream server other players use, so Usenet,
 * Easynews and links with headers all work. Simple controls: OK plays/pauses, Left/Right seek, Up/Down or Menu picks
 * audio and subtitles, Back saves the position and closes.
 */
class MpvPlayerActivity : Activity() {
    private lateinit var video: MpvVideoView
    private lateinit var overlay: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var server: LocalStreamServer? = null
    private var history: PlaybackStore? = null
    private var key = ""
    private var title = ""
    private var identity: PlaybackIdentity? = null
    private var resumePercent = 0.0
    @Volatile private var loaded = false
    private var finishedPlaying = false
    private var lastSaved = 0L

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            if (System.currentTimeMillis() - lastSaved > SAVE_EVERY_MS) save()
            main.postDelayed(this, TICK_MS)
        }
    }
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: MPVNode) = Unit
        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> main.post { onLoaded() }
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post { status.visibility = View.GONE }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> main.post { onEnded() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url").orEmpty()
        title = intent.getStringExtra("title").orEmpty()
        key = intent.getStringExtra("key").orEmpty()
        identity = try { intent.getStringExtra("identity")?.let { PlaybackIdentity.fromJson(org.json.JSONObject(it)) } } catch (_: Exception) { null }
        resumePercent = intent.getDoubleExtra("resume_percent", 0.0)
        history = PlaybackStore(applicationContext)
        buildViews()
        if (Uri.parse(url).scheme !in setOf("http", "https")) { Toast.makeText(this, getString(R.string.t_mpv_couldnt_play), Toast.LENGTH_LONG).show(); finish(); return }

        // The same sources the built-in player reads (Usenet, Easynews, headers, the proxy route), served on loopback.
        val store = SecureStore(applicationContext)
        val headers = try {
            intent.getStringExtra("headers")?.let { raw -> org.json.JSONObject(raw).let { json -> json.keys().asSequence().associateWith { json.getString(it) } } }.orEmpty()
        } catch (_: Exception) { emptyMap() }
        val routed = MediaFlowProxy(store).route(url, headers, PlaybackDebrid.normalize(intent.getStringExtra("debrid_service")),
            MediaFlowProxy.Origin.decode(intent.getStringExtra("source_origin").orEmpty()))
        val name = intent.getStringExtra("media_name").orEmpty().substringBefore(" · ").lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { title }
        val served = try {
            LocalStreamServer(ExternalPlayer.sources(this, store, routed.headers), Uri.parse(routed.url), name) { Log.i(TAG, it) }
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: getString(R.string.t_mpv_couldnt_play), Toast.LENGTH_LONG).show(); finish(); return
        }
        server = served

        Utils.copyAssets(this)
        video.initialize(filesDir.path, cacheDir.path)
        video.mpv.addObserver(observer)
        val resumeMs = intent.getLongExtra("resume", 0L)
        // The file loads once the picture's surface exists; the start position goes with it.
        if (resumeMs > 0) video.mpv.setOptionString("start", String.format(Locale.US, "+%.3f", resumeMs / 1000.0))
        video.playFile(served.address)
        status.text = getString(R.string.t_opening_with_mpv)
        showOverlay()
        main.post(tick)
    }

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = MpvVideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 32).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(220, 0, 0, 0), Color.TRANSPARENT))
        }
        titleView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; text = intent.getStringExtra("title").orEmpty() }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        timeView = TextView(this).apply { setTextColor(Color.argb(220, 255, 255, 255)); textSize = 14f }
        val hint = TextView(this).apply { setTextColor(Color.argb(160, 255, 255, 255)); textSize = 13f; text = getString(R.string.t_mpv_controls_hint) }
        overlay.addView(titleView)
        overlay.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(timeView)
        overlay.addView(hint)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun onLoaded() {
        loaded = true
        // A position known only as a share of the film (from the tracker): seek there once the length is known.
        if (intent.getLongExtra("resume", 0L) <= 0 && resumePercent in 1.0..95.0) {
            val duration = video.mpv.getPropertyDouble("duration") ?: 0.0
            if (duration > 0) video.mpv.command("seek", String.format(Locale.US, "%.3f", duration * resumePercent / 100), "absolute")
        }
    }

    private fun onEnded() {
        if (isFinishing) return
        if (!loaded) {
            Toast.makeText(this, getString(R.string.t_mpv_couldnt_play), Toast.LENGTH_LONG).show()
            finish(); return
        }
        finishedPlaying = true
        save(); finish()
    }

    private fun position(): Pair<Long, Long>? {
        val at = video.mpv.getPropertyDouble("time-pos") ?: return null
        val duration = video.mpv.getPropertyDouble("duration") ?: return null
        if (duration <= 0) return null
        return (at * 1000).toLong() to (duration * 1000).toLong()
    }

    private fun save() {
        lastSaved = System.currentTimeMillis()
        if (key.isBlank() || !loaded) return
        val (at, duration) = position() ?: return
        try { history?.save(key, title, identity, if (finishedPlaying) duration else at, duration) } catch (_: Exception) {}
    }

    private fun updateTime() {
        if (!loaded) return
        val (at, duration) = position() ?: return
        bar.progress = (at * 1000 / duration).toInt()
        timeView.text = "${clock(at)} / ${clock(duration)}"
    }

    private fun clock(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        updateTime()
        main.removeCallbacks(hideOverlay)
        main.postDelayed(hideOverlay, OVERLAY_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!loaded && keyCode != KeyEvent.KEYCODE_BACK) return true
        val step = if (event.repeatCount > 0) LONG_STEP_S else STEP_S
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { video.mpv.setPropertyBoolean("pause", false); showOverlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { video.mpv.setPropertyBoolean("pause", true); showOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-step); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(step); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (event.repeatCount == 0) showTracks(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun togglePause() {
        val paused = video.mpv.getPropertyBoolean("pause") ?: false
        video.mpv.setPropertyBoolean("pause", !paused)
        if (!paused) save()
        showOverlay()
    }

    private fun seek(seconds: Int) {
        video.mpv.command("seek", seconds.toString(), "relative")
        showOverlay()
    }

    /** One list: the audio tracks, then the subtitles (and Off); the current ones are ticked. */
    private fun showTracks() {
        class Track(val type: String, val id: Int, val label: String, val selected: Boolean)
        val mpv = video.mpv
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val tracks = (0 until count).mapNotNull { index ->
            val type = mpv.getPropertyString("track-list/$index/type") ?: return@mapNotNull null
            if (type != "audio" && type != "sub") return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$index/id") ?: return@mapNotNull null
            val parts = listOfNotNull(
                mpv.getPropertyString("track-list/$index/lang")?.uppercase(Locale.ROOT),
                mpv.getPropertyString("track-list/$index/title"),
                mpv.getPropertyString("track-list/$index/codec")?.uppercase(Locale.ROOT),
                mpv.getPropertyInt("track-list/$index/demux-channel-count")?.takeIf { type == "audio" && it > 0 }?.let { "${it}ch" })
            Track(type, id, parts.joinToString(" · ").ifBlank { "#$id" }, mpv.getPropertyBoolean("track-list/$index/selected") == true)
        }
        val audio = tracks.filter { it.type == "audio" }
        val subs = tracks.filter { it.type == "sub" }
        val subsOff = subs.none { it.selected }
        val labels = audio.map { (if (it.selected) "✓ " else "   ") + getString(R.string.t_mpv_audio) + ": " + it.label } +
            listOf((if (subsOff) "✓ " else "   ") + getString(R.string.t_subtitles_off_mpv)) +
            subs.map { (if (it.selected) "✓ " else "   ") + getString(R.string.t_mpv_subtitles) + ": " + it.label }
        AlertDialog.Builder(this).setTitle(getString(R.string.t_mpv_tracks)).setItems(labels.toTypedArray()) { _, which ->
            when {
                which < audio.size -> mpv.setPropertyInt("aid", audio[which].id)
                which == audio.size -> mpv.setPropertyString("sid", "no")
                else -> mpv.setPropertyInt("sid", subs[which - audio.size - 1].id)
            }
        }.show()
    }

    override fun onPause() {
        super.onPause()
        if (loaded) { video.mpv.setPropertyBoolean("pause", true); save() }
    }

    override fun finish() {
        save()
        setResult(RESULT_OK)
        super.finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::video.isInitialized) {
            runCatching { video.mpv.removeObserver(observer) }
            runCatching { video.destroy() }
        }
        server?.close(); server = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PovMpv"
        private const val TICK_MS = 500L
        private const val SAVE_EVERY_MS = 10_000L
        private const val OVERLAY_MS = 4_000L
        private const val STEP_S = 10
        private const val LONG_STEP_S = 30

        /** The intent that plays the same link as [from] (a built-in player intent) in mpv. */
        fun intentFrom(context: Context, from: Intent): Intent = Intent(from).setClass(context, MpvPlayerActivity::class.java)
    }
}

/** mpv drawing into a SurfaceView, set up for a TV box: hardware decoding first, GPU output, Android audio. */
internal class MpvVideoView(context: Context) : BaseMPVView(context, null) {
    override fun initOptions() {
        mpv.command("apply-profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // Hardware decoding straight to the picture where the chip can, copied back where it can't; software after that.
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        mpv.setOptionString("cache-on-disk", "no")
        mpv.setOptionString("keep-open", "no")
        mpv.setOptionString("msg-level", "all=warn")
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() = Unit
}
