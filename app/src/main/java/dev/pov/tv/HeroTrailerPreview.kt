package dev.pov.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.NativeTrailerSource
import java.util.concurrent.Executor

/** Muted native video in the backdrop layer; all navigation and copy remain above it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HeroTrailerPreview(private val activity: Activity, private val host: FrameLayout,
    private val worker: Executor, private val fetch: (MediaItemData) -> List<String>,
    private val bounds: () -> Rect?, private val permitted: () -> Boolean, private val backdropColor: Int) {
    private val handler = Handler(Looper.getMainLooper())
    private val resolver = NativeTrailerSource()
    private var serial = 0
    private var key = ""
    private var player: ExoPlayer? = null
    private var layer: FrameLayout? = null
    private var ids = emptyList<String>()
    private var index = 0
    var status = "idle"; private set
    var playingId = ""; private set
    val frame: Rect? get() = layer?.let { Rect(it.left, it.top, it.right, it.bottom) }
    fun select(item: MediaItemData) {
        val selected = "${item.type}:${item.imdbId}"
        if (selected == key && status != "idle") return
        stop(); key = selected; status = "waiting"
        val ticket = serial
        handler.postDelayed({
            if (ticket != serial) return@postDelayed
            if (!permitted() || bounds() == null) { stop(); return@postDelayed }
            status = "metadata"
            worker.execute {
                val found = runCatching { fetch(item) }.getOrDefault(emptyList())
                handler.post {
                    if (ticket != serial || !permitted()) return@post
                    ids = found; index = 0
                    if (ids.isEmpty()) status = "unavailable" else load(ticket)
                }
            }
        }, 1_800)
    }
    private fun load(ticket: Int) {
        if (ticket != serial || !permitted()) return
        disposePlayer(); status = "loading"; playingId = ids[index]
        val id = playingId
        worker.execute {
            val source = runCatching { resolver.resolve(id) }.getOrNull()
            handler.post {
                if (ticket != serial || id != playingId || !permitted()) return@post
                if (source == null) next(ticket) else play(source, ticket)
            }
        }
    }
    private fun play(source: NativeTrailerSource.Source, ticket: Int) {
        if (bounds() == null) { stop(); return }
        val video = TextureView(activity).apply { isFocusable = false; isClickable = false; isOpaque = false }
        val container = FrameLayout(activity).apply {
            tag = "hero-trailer-player"; alpha = 0f
            isFocusable = false; isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(backdropColor, (backdropColor and 0x00FFFFFF) or (180 shl 24), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1,-1))
            addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(backdropColor, Color.TRANSPARENT, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1,-1))
        }
        layer = container
        host.addView(container, FrameLayout.LayoutParams(-1, -1))
        val exo = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TrailerDataSourceFactory(source)))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 500, 1_000).build())
            .build()
        player = exo
        fun crop(size: VideoSize) {
            if (video.width == 0 || video.height == 0 || size.height == 0) return
            val aspect = size.width * size.pixelWidthHeightRatio / size.height
            val viewAspect = video.width.toFloat() / video.height
            video.setTransform(Matrix().apply {
                setScale(maxOf(1f, aspect / viewAspect), maxOf(1f, viewAspect / aspect), video.width / 2f, video.height / 2f)
            })
        }
        exo.volume = 0f
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { crop(videoSize) }
            override fun onRenderedFirstFrame() {
                if (ticket != serial || player !== exo) return
                if (!permitted()) { stop(); return }
                crop(exo.videoSize); status = "playing"
                handler.removeCallbacks(timeout)
                container.animate().alpha(1f).setDuration(450).start()
            }
            override fun onPlayerError(error: PlaybackException) { if (ticket == serial && player === exo) next(ticket) }
            override fun onPlaybackStateChanged(state: Int) {
                if (ticket == serial && player === exo && state == Player.STATE_ENDED) fail("ended")
            }
        })
        exo.setVideoTextureView(video)
        exo.setMediaItem(MediaItem.fromUri(source.url)); exo.prepare(); exo.play()
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 20_000)
        handler.removeCallbacks(visibilityCheck); handler.postDelayed(visibilityCheck, 250)
    }
    private fun next(ticket: Int) {
        handler.removeCallbacks(timeout); disposePlayer()
        if (index + 1 < ids.size && permitted()) { index++; load(ticket) } else fail("unavailable")
    }
    private val timeout = Runnable { next(serial) }
    private val visibilityCheck = object : Runnable {
        override fun run() {
            if (!permitted() || bounds() == null) { stop(); return }
            handler.postDelayed(this, 250)
        }
    }
    private fun fail(reason: String) {
        handler.removeCallbacks(timeout); handler.removeCallbacks(visibilityCheck)
        disposePlayer(); playingId = ""; status = reason
    }
    private fun disposePlayer() {
        player?.release(); player = null
        layer?.let { it.animate().cancel(); (it.parent as? ViewGroup)?.removeView(it) }; layer = null
    }
    fun stop() {
        serial++; handler.removeCallbacksAndMessages(null); disposePlayer()
        key = ""; ids = emptyList(); playingId = ""; status = "idle"
    }
}
