package dev.pov.tv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat
import dev.pov.tv.data.SecureStore
import java.util.TreeMap
import kotlin.math.pow

/**
 * Settings → Playback → Built-in seek previews: on/off, and how much is prepared before the video starts.
 * Seekr previews, when Seekr has the title, are used instead.
 */
internal class BuiltInPreviewPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pov_seek_previews", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("built_in", false)
        set(value) = prefs.edit().putBoolean("built_in", value).apply()
    /** Percent of the first pass made before playback starts (0 = start at once, everything made while playing). */
    var startAt: Int
        get() = prefs.getInt("built_in_start", 50).takeIf { it in STEPS } ?: 50
        set(value) { require(value in STEPS); prefs.edit().putInt("built_in_start", value).apply() }

    companion object { val STEPS = listOf(0, 25, 50, 80, 100) }
}

/**
 * Seek-bar previews made on this device from the video itself, without Seekr: a keyframe every so often across the film,
 * decoded into a small picture.
 *
 * Keyframes are found with Media3's own extractors ([MediaExtractorCompat]), the same ones the player uses: they read
 * the file's index (Matroska cues, MP4 sample tables) and jump straight to each keyframe. Android's own frame grabber
 * was tried first and scanned whole remuxes instead (over 15 s a frame).
 *
 * Before playback ([prepare]) the TV's hardware decoder makes the pictures (8-bit or 10-bit): nothing else is decoding
 * then, so it can't collide with the player (the TCL TV kills an app that holds two hardware decoders). The positions go
 * coarse to fine (middle, quarters, eighths…), so whatever share is ready covers the whole film. Once the video plays
 * ([continueWhilePlaying]) the rest is made on one low-priority thread on the CPU only: the device's software decoder
 * for 8-bit video (10-bit without a lighter copy gets its previews before playback only).
 *
 * Bytes come through the player's own sources ([ExternalPlayer.sources]), so Usenet, Easynews and links with headers
 * work too. Only the keyframes are read, a few hundred kilobytes each.
 */
internal object BuiltInSeekPreviews {
    /** The share chosen in Settings ("start playback at 50%") is of this many pictures, spread over the whole film. */
    private const val FIRST_PASS = 96
    /** One picture every 10 s (wider for a film over 2 h 45: at most [MAX_SLOTS]). */
    private const val SLOT_MS = 10_000L
    private const val MAX_SLOTS = 1_000
    private const val WIDTH = 256
    private val lock = Any()
    /** The pictures as small JPEGs (about 10 KB each), so a whole film's grid fits in a few MB. */
    private val frames = TreeMap<Long, ByteArray>()
    private var shown: Pair<Long, Bitmap>? = null
    @Volatile private var url = ""
    @Volatile private var durationMs = 0L
    @Volatile private var generation = 0
    /** Every slot of the grid, coarse to fine (middle, quarters, eighths…), and the ones already tried. */
    private var plan: List<Long> = emptyList()
    private val tried = HashSet<Long>()
    private var next = 0
    /** Where the viewer is scrubbing (and since when): that part of the grid is made first. */
    @Volatile private var focusMs = -1L
    @Volatile private var focusAt = 0L
    private var sources: DataSource.Factory? = null
    /** What the pictures are read from: the film itself, or a lighter copy of it ([LightCopy]). */
    @Volatile private var readUrl = ""
    /** OK while preparing: play now with what is ready (the rest is made while playing). */
    @Volatile private var playEarly = false
    /** Back while preparing: stop and don't play. */
    @Volatile private var cancelled = false
    /** The reads the preparation is making: OK, Back or a time limit ends them at once. */
    @Volatile private var reading: AbortableSources? = null

    fun playNow() { playEarly = true; reading?.abort() }
    fun cancel() { cancelled = true; reading?.abort() }

    /** Previews exist for this link. */
    fun has(address: String) = address.isNotEmpty() && address == url && synchronized(lock) { frames.isNotEmpty() }

    /** The picture nearest [positionMs] made so far (any distance: a nearby scene beats an empty card), or null. */
    fun nearest(address: String, positionMs: Long): Bitmap? {
        if (address != url) return null
        synchronized(lock) {
            val below = frames.floorEntry(positionMs); val above = frames.ceilingEntry(positionMs)
            val best = listOfNotNull(below, above).minByOrNull { kotlin.math.abs(it.key - positionMs) } ?: return null
            shown?.takeIf { it.first == best.key }?.let { return it.second }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(best.value, 0, best.value.size) ?: return null
            shown = best.key to bitmap
            return bitmap
        }
    }

    /** The viewer is scrubbing at [positionMs]: the pictures around it are made next. */
    fun focus(positionMs: Long) { focusMs = positionMs; focusAt = SystemClock.elapsedRealtime() }

    /** Keeps [frame] as a small JPEG at [atMs]. */
    private fun keep(atMs: Long, frame: Bitmap, myGeneration: Int) {
        val jpeg = java.io.ByteArrayOutputStream(16 * 1024).also { frame.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
        frame.recycle()
        synchronized(lock) { if (myGeneration == generation) frames[atMs] = jpeg }
    }

    /**
     * The next slot to make: around the scrub point while the viewer scrubs (and for a few seconds after), else around
     * [playheadMs] ahead of it, else the next of the grid in coarse-to-fine order. Null when every slot was tried.
     */
    private fun nextSlot(playheadMs: Long): Long? = synchronized(lock) {
        fun near(center: Long, before: Long, after: Long): Long? {
            if (center < 0 || durationMs <= 0) return null
            val step = slotMs()
            var best: Long? = null
            var offset = 0L
            while (offset <= maxOf(before, after)) {
                for (candidate in listOf(center + offset, center - offset)) {
                    if (candidate < center - before || candidate > center + after) continue
                    val slot = (candidate / step).coerceIn(1, durationMs / step - 1) * step
                    if (slot !in tried && slot in planSet) { best = slot; break }
                }
                if (best != null) break
                offset += step
            }
            return best
        }
        val scrubbing = SystemClock.elapsedRealtime() - focusAt < 5_000
        val pick = (if (scrubbing) near(focusMs, 60_000, 60_000) else null)
            ?: near(playheadMs, 0, 180_000)
            ?: run { while (next < plan.size && plan[next] in tried) next++; plan.getOrNull(next) }
        pick?.also { tried += it }
    }

    private var planSet: Set<Long> = emptySet()
    private fun slotMs(): Long = maxOf(SLOT_MS, durationMs / MAX_SLOTS)

    /** Whether Seekr has ready-made previews for this title (then built-in ones aren't made). Blocking: off the main thread. */
    fun seekrHas(context: Context, media: dev.pov.tv.data.PlaybackIdentity?, durationMs: Long): Boolean {
        if (media == null || !SeekrPreferences(context).enabled || !media.imdbId.startsWith("tt")) return false
        val key = SecureStore(context.applicationContext).get(SeekrPreferences.KEY).ifBlank { BuildConfig.SEEKR_KEY }
        if (key.isBlank()) return false
        val content = if (media.type == "series" && media.season > 0 && media.episode > 0)
            tv.seekr.previews.core.SeekrContent.Episode(showImdbId = media.imdbId, season = media.season, episode = media.episode)
        else tv.seekr.previews.core.SeekrContent.Movie(imdbId = media.imdbId)
        return runCatching { kotlinx.coroutines.runBlocking { tv.seekr.previews.android.Seekr.create(key).loadTrack(content, durationMs) } }
            .getOrNull()?.let { !it.isEmpty } ?: false
    }

    /** Forgets the previews (another video, or the player closed for good). */
    fun clear() {
        synchronized(lock) { generation++; frames.clear(); shown = null; plan = emptyList(); planSet = emptySet(); tried.clear(); next = 0 }
        focusMs = -1
        url = ""; readUrl = ""; durationMs = 0; sources = null
    }

    /**
     * Makes previews for [address] until [startAtPercent] of the first pass is ready (blocking; run off the main
     * thread), reporting the share done. Returns quietly when the video can't be read this way; the player then shows
     * none. Back ([cancel]) throws [InterruptedException]; OK ([playNow]) returns with what is ready.
     */
    fun prepare(context: Context, address: String, headers: Map<String, String>, startAtPercent: Int,
                seekrHas: (Long) -> Boolean = { false }, copies: List<LightCopy> = emptyList(), progress: (Int) -> Unit) {
        clear()
        playEarly = false; cancelled = false
        val myGeneration = synchronized(lock) { generation }
        val factory = ExternalPlayer.sources(context, SecureStore(context.applicationContext), headers)
        val reads = AbortableSources(factory)
        reading = reads
        // A read that takes too long (a slow server, a file without an index) is given up: the video plays without them.
        val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        fun limit(ms: Long) = watchdog.schedule({ android.util.Log.w(TAG, "a read took over $ms ms"); reads.abort() },
            ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        var grabber: KeyframeSource? = null
        try {
            val opening = limit(OPEN_LIMIT_MS)
            val opened = KeyframeVideo.open(reads, address)
            val duration = opened.durationMs
            opening.cancel(false)
            if (cancelled) throw InterruptedException("cancelled")
            if (duration == null || duration < 60_000 || reads.aborted) { opened.release(); return }
            // Seekr has ready-made previews for this title: the player shows those, so nothing is made or waited for.
            if (runCatching { seekrHas(duration) }.getOrDefault(false)) { opened.release(); return }
            // A lighter copy of the same cut (a 1080p H.264 file instead of a 4K remux) makes the same pictures from a
            // fraction of the reading and decoding, in the right colours. The film itself when none passes.
            var video = opened
            var readFrom = address
            var readSources: DataSource.Factory = factory
            for (copy in copies.take(3)) {
                if (cancelled || playEarly) break
                val copyFactory = ExternalPlayer.sources(context, SecureStore(context.applicationContext), copy.headers)
                val copyReads = AbortableSources(copyFactory)
                reading = copyReads
                val copyLimit = watchdog.schedule({ copyReads.abort() }, COPY_LIMIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                val candidate = runCatching { KeyframeVideo.open(copyReads, copy.url) }.getOrNull()
                copyLimit.cancel(false)
                val length = candidate?.durationMs
                if (candidate != null && length != null && LightCopy.sameCut(duration, length)) {
                    android.util.Log.i(TAG, "previews from a lighter copy: ${copy.name} ($length ms vs $duration ms)")
                    opened.release(); video = candidate; readFrom = copy.url; readSources = copyFactory; break
                }
                android.util.Log.i(TAG, "lighter copy ${copy.name} not used (length $length ms vs $duration ms)")
                candidate?.release()
            }
            reading = reads
            if (cancelled) { video.release(); throw InterruptedException("cancelled") }
            // Before playback the hardware decoder is free (it also takes 10-bit HEVC, which the CPU decoders can't).
            grabber = HardwareKeyframes.open(video) ?: run { video.release(); throw java.io.IOException("no decoder for this video") }
            synchronized(lock) {
                url = address; durationMs = duration; sources = readSources; readUrl = readFrom
                val step = slotMs()
                // The whole 10-second grid, coarse to fine: its first pictures already span the film.
                plan = coarseToFine(duration, (duration / step - 1).toInt().coerceIn(FIRST_PASS, MAX_SLOTS))
                    .map { (it / step).coerceAtLeast(1) * step }.distinct()
                planSet = plan.toHashSet(); tried.clear(); next = 0
            }
            val wanted = FIRST_PASS * startAtPercent / 100
            var made = 0
            val started = SystemClock.elapsedRealtime()
            while (made < wanted && !playEarly) {
                if (cancelled || Thread.currentThread().isInterrupted || myGeneration != generation) throw InterruptedException("cancelled")
                val at = nextSlot(-1) ?: break
                val frameLimit = limit(FRAME_LIMIT_MS)
                val frame = runCatching { grabber.frameAt(at, WIDTH) }.getOrNull()
                frameLimit.cancel(false)
                if (cancelled) throw InterruptedException("cancelled")
                Thread.interrupted()                                        // a time limit's interrupt: handled here
                if (reads.aborted) break                                    // OK, or too slow: play with what is ready
                if (frame != null) keep(at, frame, myGeneration)
                made++
                // A file whose index can't be read lands every seek on the same frame: no point carrying on.
                if (made == 4 && video.unseekable()) { android.util.Log.w(TAG, "this file can't be seeked by keyframe; no previews"); clear(); return }
                progress(made * 100 / wanted.coerceAtLeast(1))
            }
            android.util.Log.i(TAG, "$made previews in ${SystemClock.elapsedRealtime() - started} ms with ${grabber.name}")
        } catch (error: InterruptedException) {
            if (cancelled) { clear(); throw error }
            // Not Back: a time limit (or OK) cut a stuck read short, such as a news server that keeps stalling on a bad
            // NZB. The video plays (the player reports a broken release itself) with whatever previews were made.
            Thread.interrupted()
            android.util.Log.w(TAG, "a stuck read was stopped; playing without waiting for more previews")
        }
        catch (error: Exception) {
            Thread.interrupted()
            if (cancelled) { clear(); throw InterruptedException("cancelled") }
            android.util.Log.w(TAG, "built-in previews stopped: ${error.message}")
        }
        finally { reading = null; watchdog.shutdownNow(); grabber?.release() }
    }

    /**
     * Makes the rest of the previews while the video plays, on the CPU only (one at a time with a pause between, so
     * playback keeps the network and the processor). Stops when [playing] turns false.
     */
    fun continueWhilePlaying(playing: () -> Boolean, playheadMs: () -> Long = { -1L }) {
        val factory = sources ?: return
        val address = readUrl
        val myGeneration = generation
        Thread({
            val decoder: KeyframeSource = try {
                val opened = KeyframeVideo.open(AbortableSources(factory), address)
                SoftwareKeyframes.open(opened) ?: run { opened.release(); throw java.io.IOException("no CPU decoder for this video (10-bit?)") }
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "no CPU decoder for the rest of the previews: ${error.message}"); return@Thread
            }
            android.util.Log.i(TAG, "making the rest of the previews while playing, with ${decoder.name}")
            var made = 0; var failed = 0
            val started = SystemClock.elapsedRealtime()
            try {
                while (playing() && myGeneration == generation) {
                    val at = nextSlot(runCatching { playheadMs() }.getOrDefault(-1L)) ?: break
                    val frame = try { decoder.frameAt(at, WIDTH) } catch (error: Exception) {
                        if (failed++ < 3) android.util.Log.w(TAG, "preview at $at ms failed: ${error.message}"); null
                    }
                    if (frame != null) { keep(at, frame, myGeneration); made++ }
                    if ((made + failed) % 25 == 0) android.util.Log.i(TAG, "$made more previews ($failed failed) in ${SystemClock.elapsedRealtime() - started} ms")
                    // Scrubbing: no pause, the viewer is waiting for these. Otherwise playback keeps the network first.
                    if (SystemClock.elapsedRealtime() - focusAt > 5_000) Thread.sleep(600)
                }
            } catch (_: InterruptedException) {
            } finally { decoder.release(); android.util.Log.i(TAG, "stopped after $made more previews ($failed failed)") }
        }, "seek-previews").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** [count] positions across the film, ordered so any prefix is spread evenly: middle, quarters, eighths… */
    internal fun coarseToFine(durationMs: Long, count: Int): List<Long> {
        val step = durationMs / (count + 1)
        val out = ArrayList<Int>(count); val seen = HashSet<Int>()
        var parts = 2
        while (out.size < count && parts <= count * 2) {
            for (k in 1 until parts) {
                val slot = ((k.toLong() * (count + 1)) / parts).toInt().coerceIn(1, count)
                if (seen.add(slot)) out += slot
            }
            parts *= 2
        }
        (1..count).filter { seen.add(it) }.forEach { out += it }
        return out.map { it * step }
    }

    internal const val TAG = "PovPreviews"
    private const val OPEN_LIMIT_MS = 30_000L
    private const val FRAME_LIMIT_MS = 15_000L
    private const val COPY_LIMIT_MS = 20_000L
}

/**
 * The player's sources with an off switch: [abort] closes every read in progress (OK, Back, a time limit), and every
 * read after it fails at once.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class AbortableSources(private val upstream: DataSource.Factory) : DataSource.Factory {
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<DataSource, Boolean>())
    /** Threads inside a read right now: closing a source doesn't wake one waiting on a news server, an interrupt does. */
    private val inside = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Thread, Boolean>())
    @Volatile var aborted = false
        private set

    fun abort() {
        aborted = true
        inside.forEach { it.interrupt() }
        open.forEach { runCatching { it.close() } }
    }

    private inline fun <T> guarded(block: () -> T): T {
        if (aborted) throw java.io.InterruptedIOException("previews stopped")
        val thread = Thread.currentThread()
        inside += thread
        try { return block() } finally { inside -= thread }
    }

    override fun createDataSource(): DataSource {
        val inner = upstream.createDataSource()
        return object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
            override fun open(dataSpec: DataSpec): Long = guarded { open += inner; inner.open(dataSpec) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = guarded { inner.read(buffer, offset, length) }
            override fun getUri(): Uri? = inner.uri
            override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
            override fun close() { open -= inner; inner.close() }
        }
    }
}

/** The video track of one link, read with Media3's extractors, and each keyframe as it is asked for. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class KeyframeVideo private constructor(private val extractor: MediaExtractorCompat, val format: MediaFormat) {
    val mime: String = format.getString(MediaFormat.KEY_MIME)!!.let { if (it == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) MediaFormat.MIMETYPE_VIDEO_HEVC else it }
    val durationMs: Long? = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null
    /** HDR10 (PQ): the picture is brought down to SDR so the preview isn't washed out. */
    val pq: Boolean = format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_ST2084
    private val landed = ArrayList<Long>()
    private var sample = ByteArray(8 shl 20)

    /** The keyframe at or before [positionMs]: its bytes and time, or null when there is none. */
    fun keyframe(positionMs: Long): Pair<ByteArray, Long>? {
        extractor.seekTo(positionMs * 1000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        val size = extractor.sampleSize.toInt()
        if (size <= 0) return null
        if (sample.size < size) sample = ByteArray(size + (size shr 2))
        val read = extractor.readSampleData(java.nio.ByteBuffer.wrap(sample), 0)
        if (read <= 0) return null
        val time = extractor.sampleTime
        synchronized(landed) { landed += time }
        return sample.copyOf(read) to time
    }

    /** Seeks that all landed on the same frame: the file's index wasn't readable. */
    fun unseekable(): Boolean = synchronized(landed) { landed.size >= 3 && landed.distinct().size == 1 }

    fun release() { runCatching { extractor.release() } }

    companion object {
        fun open(sources: DataSource.Factory, address: String): KeyframeVideo {
            val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), sources)
            try {
                extractor.setDataSource(Uri.parse(address), 0)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: error("no video track")
                extractor.selectTrack(track)
                return KeyframeVideo(extractor, extractor.getTrackFormat(track))
            } catch (error: Exception) { extractor.release(); throw error }
        }
    }
}

/** One keyframe at a time, as a small picture, for built-in seek previews. */
internal interface KeyframeSource {
    val name: String
    fun frameAt(positionMs: Long, width: Int): Bitmap?
    fun release()
}

/**
 * Keyframes through an Android decoder ([MediaCodec]): the TV's hardware decoder before playback, a software one
 * (c2.android.* / OMX.google.*, 8-bit only) while the film plays on the hardware.
 */
internal open class MediaCodecKeyframes protected constructor(private val video: KeyframeVideo, private val codec: MediaCodec) : KeyframeSource {
    override val name: String = codec.name
    private var started = false

    override fun frameAt(positionMs: Long, width: Int): Bitmap? {
        val (bytes, time) = video.keyframe(positionMs) ?: return null
        if (!started) { codec.start(); started = true } else codec.flush()
        val info = MediaCodec.BufferInfo()
        var fed = false; var endSent = false
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!endSent) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!
                    if (!fed && bytes.size <= buffer.capacity()) {
                        buffer.clear(); buffer.put(bytes); codec.queueInputBuffer(input, 0, bytes.size, time, 0); fed = true
                    } else { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); endSent = true }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                val frame = runCatching { codec.getOutputImage(output)?.use { toBitmap(it, width) } }.getOrNull()
                codec.releaseOutputBuffer(output, false)
                if (frame != null) return frame
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
        }
        return null
    }

    /** Samples the YUV picture down to [width] px and converts it to RGB; 10-bit planes (P010) read their high byte. */
    private fun toBitmap(image: android.media.Image, width: Int): Bitmap? {
        val crop = image.cropRect
        val srcW = crop.width(); val srcH = crop.height()
        if (srcW <= 0 || srcH <= 0) return null
        val dstH = (srcH.toLong() * width / srcW).toInt().coerceAtLeast(1)
        val (yP, uP, vP) = image.planes
        val wide = image.format == 0x36 || (yP.pixelStride == 2 && uP.pixelStride >= 4)       // YCBCR_P010
        val high = if (wide) 1 else 0
        val pixels = IntArray(width * dstH)
        for (dy in 0 until dstH) {
            val sy = crop.top + dy * srcH / dstH
            for (dx in 0 until width) {
                val sx = crop.left + dx * srcW / width
                val y = (yP.buffer.get(sy * yP.rowStride + sx * yP.pixelStride + high).toInt() and 0xFF) - 16
                val u = (uP.buffer.get((sy / 2) * uP.rowStride + (sx / 2) * uP.pixelStride + high).toInt() and 0xFF) - 128
                val v = (vP.buffer.get((sy / 2) * vP.rowStride + (sx / 2) * vP.pixelStride + high).toInt() and 0xFF) - 128
                val c = 1192 * y.coerceAtLeast(0)
                var r = ((c + 1836 * v) shr 10).coerceIn(0, 255)
                var g = ((c - 546 * v - 218 * u) shr 10).coerceIn(0, 255)
                var b = ((c + 2163 * u) shr 10).coerceIn(0, 255)
                if (video.pq) { r = PqToSdr.map[r]; g = PqToSdr.map[g]; b = PqToSdr.map[b] }
                pixels[dy * width + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, dstH, Bitmap.Config.ARGB_8888)
    }

    override fun release() { runCatching { if (started) codec.stop() }; runCatching { codec.release() }; video.release() }

    protected companion object {
        fun create(video: KeyframeVideo, software: Boolean): MediaCodec? {
            val format = video.format.apply {
                setString(MediaFormat.KEY_MIME, video.mime)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                if (!containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 shl 20)
            }
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(video.mime, true) } &&
                    runCatching { info.getCapabilitiesForType(video.mime).isFormatSupported(MediaFormat.createVideoFormat(video.mime,
                        format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))) }.getOrDefault(true)
            }.filter { info ->
                val soft = (Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly) || info.name.lowercase().let { it.startsWith("c2.android.") || it.startsWith("omx.google.") }
                soft == software
            }
            for (info in candidates) {
                val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
                if (runCatching { codec.configure(format, null, null, 0) }.isSuccess) return codec
                codec.release()
            }
            return null
        }
    }
}

/** Before playback: the TV's own (hardware) decoder, 8-bit and 10-bit. Null when it won't take this video. */
internal class HardwareKeyframes private constructor(video: KeyframeVideo, codec: MediaCodec) : MediaCodecKeyframes(video, codec) {
    companion object {
        fun open(video: KeyframeVideo): KeyframeSource? = create(video, software = false)?.let { HardwareKeyframes(video, it) }
    }
}

/** While the film plays: a software decoder only (8-bit). Null when the device has none for this video (10-bit). */
internal class SoftwareKeyframes private constructor(video: KeyframeVideo, codec: MediaCodec) : MediaCodecKeyframes(video, codec) {
    companion object {
        fun open(video: KeyframeVideo): KeyframeSource? {
            // A 10-bit picture would come out wrong from an 8-bit software decoder.
            val profile = if (video.format.containsKey(MediaFormat.KEY_PROFILE)) video.format.getInteger(MediaFormat.KEY_PROFILE) else -1
            if (video.mime == MediaFormat.MIMETYPE_VIDEO_HEVC && profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) return null
            if (video.pq) return null
            return create(video, software = true)?.let { SoftwareKeyframes(video, it) }
        }
    }
}

/** PQ code value (0–255) → SDR (0–255): ST 2084 to nits, a soft roll-off above about 200 nits, then gamma 2.2. */
internal object PqToSdr {
    val map = IntArray(256) { code ->
        val e = (code / 255.0).pow(1 / 78.84375)
        val nits = 10_000 * (maxOf(e - 0.8359375, 0.0) / (18.8515625 - 18.6875 * e)).pow(1 / 0.1593017578125)
        val relative = nits / 200.0
        val mapped = relative / (1 + relative / 4)
        (mapped.coerceIn(0.0, 1.0).pow(1 / 2.2) * 255).toInt()
    }
}

/**
 * A lighter copy of the playing film to make seek previews from (Stremio Origin's idea): another link of the same title
 * from the viewer's own results, light and SDR (1080p or 720p, H.264 first, cached on debrid or direct), instead of a
 * heavy remux. Used only when its length matches the film's ([sameCut]); never Usenet (reading one is as slow as the
 * film), never a torrent still to be added.
 */
internal data class LightCopy(val url: String, val headers: Map<String, String>, val name: String) {
    companion object {
        private val HEAVY = Regex("(?i)(?<![a-z0-9])(?:2160p|4k|uhd|remux|hdr10?\\+?|dv|dovi|dolby[ ._-]?vision|10[ ._-]?bit|hdr)(?![a-z0-9])")
        private val AVC = Regex("(?i)(?<![a-z0-9])(?:x264|h[ .]?264|avc)(?![a-z0-9])")

        /** The best candidates from [links] for a film of [filmBytes] (0 unknown) playing from [filmUrl], best first. */
        fun from(links: List<dev.pov.tv.data.SourceItem>, filmUrl: String, filmBytes: Long): List<LightCopy> = links.asSequence()
            .filter { it.directUrl.startsWith("http") && it.directUrl != filmUrl && !dev.pov.tv.data.UsenetLinks.isNzb(it.directUrl) }
            .filter { it.addonCached != false }
            .filter { (it.qualityHeight ?: 0) in 480..1080 }
            .filter { val size = it.sizeBytes ?: 0L; size in 200_000_000L..6_000_000_000L && (filmBytes <= 0 || size * 5 <= filmBytes * 2) }
            .filter { !HEAVY.containsMatchIn(it.title) }
            .sortedWith(compareBy({ if (AVC.containsMatchIn(it.title)) 0 else 1 }, { it.sizeBytes ?: Long.MAX_VALUE }))
            .map { LightCopy(it.directUrl, it.headers, it.title.lineSequence().first().take(120)) }
            .toList()

        /** The same cut when the lengths match within 1% (at least 20 s: studio logos and credits differ a little). */
        fun sameCut(filmMs: Long, copyMs: Long): Boolean = kotlin.math.abs(filmMs - copyMs) <= maxOf(20_000L, filmMs / 100)
    }
}
