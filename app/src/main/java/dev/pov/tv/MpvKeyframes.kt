package dev.pov.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.datasource.DataSource
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Seek-preview pictures made by an invisible mpv (its own FFmpeg, software decoding): for the videos Android's decoders
 * can't take, such as 10-bit H.264 (Hi10P), and 10-bit films while the hardware decoder is busy playing. mpv opens the
 * link through a loopback server built from the same sources, jumps to the keyframe before each spot (no decoding
 * forward from it) and writes that frame, already scaled down, as a small JPEG, which becomes the picture.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class MpvKeyframes private constructor(
    private val mpv: MPV,
    private val server: LocalStreamServer,
    private val folder: File,
    private val pq: Boolean,
) : KeyframeSource {
    override val name = "mpv (software)"
    private val restarted = Semaphore(0)
    @Volatile private var failed = false
    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: MPVNode) = Unit
        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> loaded.countDown()
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> restarted.release()
                MPV.mpvEvent.MPV_EVENT_END_FILE -> { failed = true; loaded.countDown(); restarted.release() }
            }
        }
    }
    private val loaded = CountDownLatch(1)

    override fun frameAt(positionMs: Long, width: Int): Bitmap? {
        if (failed) return null
        folder.listFiles()?.forEach { it.delete() }
        restarted.drainPermits()
        mpv.command("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute+keyframes")
        if (!restarted.tryAcquire(FRAME_WAIT_S, TimeUnit.SECONDS) || failed) return null
        // The picture is written as the frame is shown, just after the seek settles.
        val deadline = System.currentTimeMillis() + WRITE_WAIT_MS
        var file: File? = null
        while (System.currentTimeMillis() < deadline) {
            file = folder.listFiles()?.filter { it.length() > 0 }?.maxByOrNull { it.lastModified() }
            if (file != null) break
            Thread.sleep(40)
        }
        val picture = file?.let { BitmapFactory.decodeFile(it.path) } ?: return null
        val scaled = if (picture.width != width) Bitmap.createScaledBitmap(picture, width,
            (picture.height.toLong() * width / picture.width).toInt().coerceAtLeast(1), true).also { if (it !== picture) picture.recycle() } else picture
        if (!pq) return scaled
        // HDR10 (PQ) brought down to SDR, as the hardware path does, so the preview isn't washed out.
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (0xFF shl 24) or (PqToSdr.map[(p shr 16) and 0xFF] shl 16) or (PqToSdr.map[(p shr 8) and 0xFF] shl 8) or PqToSdr.map[p and 0xFF]
        }
        return Bitmap.createBitmap(pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also { scaled.recycle() }
    }

    override fun release() {
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.command("stop") }
        server.close()
        // This mpv draws nothing on screen (no graphics context), so closing it is safe.
        runCatching { mpv.close() }
        folder.deleteRecursively()
    }

    companion object {
        private const val FRAME_WAIT_S = 12L
        private const val WRITE_WAIT_MS = 3_000L
        private const val LOAD_WAIT_S = 30L

        /** An mpv set up to make pictures [width] px wide from [address], or null when it can't open it. */
        fun open(context: Context, sources: DataSource.Factory, address: String, pq: Boolean, width: Int): MpvKeyframes? {
            val app = context.applicationContext
            val folder = File(app.cacheDir, "mpv-previews-${System.nanoTime()}").apply { mkdirs() }
            val server = try { LocalStreamServer(sources, Uri.parse(address), "previews") } catch (_: Exception) { folder.delete(); return null }
            val mpv = try { MPV(app, app.filesDir.path, app.cacheDir.path) } catch (_: Throwable) { server.close(); folder.delete(); return null }
            for ((name, value) in listOf(
                "vo" to "image", "vo-image-format" to "jpg", "vo-image-jpeg-quality" to "80", "vo-image-outdir" to folder.path,
                "vf" to "scale=w=$width:h=-2", "ao" to "null", "aid" to "no", "sid" to "no", "hwdec" to "no",
                "hr-seek" to "no", "pause" to "yes", "keep-open" to "yes", "idle" to "yes", "vd-lavc-skiploopfilter" to "all",
                "vd-lavc-threads" to "2", "demuxer-max-bytes" to "${8 * 1024 * 1024}", "demuxer-max-back-bytes" to "0",
                "cache" to "no", "msg-level" to "all=warn")) mpv.setOptionString(name, value)
            val made = MpvKeyframes(mpv, server, folder, pq)
            mpv.addObserver(made.observer)
            mpv.command("loadfile", server.address)
            if (!made.loaded.await(LOAD_WAIT_S, TimeUnit.SECONDS) || made.failed) { made.release(); return null }
            return made
        }
    }
}
