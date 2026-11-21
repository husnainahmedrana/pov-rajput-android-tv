package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Debrid and direct video links in the built-in player, downloaded over several connections at once.
 *
 * A single connection to a far-away CDN is often capped below the line's speed (an Offcloud remux from Pakistan stayed
 * at ~25 Mbit/s, just under the film's bitrate, and stalled every few seconds; Kodi, reading through Nabrio with
 * several requests, played it smoothly). This source fetches the file as [chunkSize] pieces over [connections] parallel
 * range requests and hands them to the player in order, keeping only a few pieces ahead (about (connections + 2) ×
 * chunkSize of memory). The same design as [EasynewsParallelDataSource], for any host.
 *
 * Every read starts on [single], one connection, as before: opening a video makes several short jumps (header, the
 * index at the end, the resume point) and four connections there only made each jump slower (6686 took ~12 s to start
 * a 4.8 GB film). Only once a whole-file read has gone [switchAfter] bytes in a row (real playback) does it move to the
 * pieces at that position. Anything else, or a server that won't answer a range request with 206, stays on [single].
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ParallelHttpDataSource(
    private val single: DataSource,
    private val headers: Map<String, String>,
    private val connections: Int = 4,
    private val chunkSize: Int = 4 * 1024 * 1024,
    /** The player's size-from-server listener (it reads the Content-Range of the response). */
    listener: TransferListener? = null,
    // 2 MB: past the short header/index jumps (KB to ~1 MB), early enough that the start itself gets the speed-up
    // (8 MB on one ~11 Mbit/s connection kept a 1-hour resume of a 24 GB film loading ~8 s, 6687).
    private val switchAfter: Long = 2L * 1024 * 1024,
) : DataSource {
    private var active: DataSource? = null
    private val listeners = listOfNotNull(listener).toMutableList()
    private var spec: DataSpec? = null
    private var consumed = 0L
    private var canSwitch = false

    init { listener?.let(single::addTransferListener) }

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; single.addTransferListener(transferListener) }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val path = dataSpec.uri.path.orEmpty().lowercase()
        canSwitch = dataSpec.length == C.LENGTH_UNSET.toLong() && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            (scheme == "https" || scheme == "http") && !path.endsWith(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".ts")
        if (connections <= 1 || dataSpec.uri.host in singleHosts) canSwitch = false
        spec = dataSpec; consumed = 0
        active = single
        return single.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (canSwitch && consumed >= switchAfter) switchToPieces()
        val current = checkNotNull(active)
        val count = try { current.read(buffer, offset, length) } catch (error: IOException) {
            if (current === single || error is InterruptedIOException) throw error
            fallBackToSingle(error)
            return checkNotNull(active).read(buffer, offset, length).also { if (it > 0) consumed += it }
        }
        if (count > 0) consumed += count
        return count
    }

    /**
     * The pieces stopped delivering (some networks or devices handle several connections to one server badly): carry
     * on over one connection from the same byte. The host then stays on one connection until the app restarts.
     */
    private fun fallBackToSingle(error: IOException) {
        val from = spec ?: throw error
        android.util.Log.w(TAG, "several connections stalled (${error.javaClass.simpleName}); back to one at $consumed")
        from.uri.host?.let(singleHosts::add)
        runCatching { active?.close() }
        active = single
        single.open(from.subrange(consumed))
    }

    /** Playback is reading on: carry on from here over several connections; on any trouble stay on one. */
    private fun switchToPieces() {
        canSwitch = false
        val from = spec ?: return
        val parallel = Pieces(headers, connections, chunkSize)
        listeners.forEach(parallel::addTransferListener)
        val opened = try { parallel.openOrNull(from.subrange(consumed)) } catch (_: IOException) { null } catch (_: RuntimeException) { null }
        if (opened == null) { parallel.close(); return }
        runCatching { single.close() }
        active = parallel
    }
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { val current = active; active = null; canSwitch = false; spec = null; current?.close() }

    class Factory(private val single: DataSource.Factory, private val headers: Map<String, String>, private val connections: Int = 4,
                  private val listener: TransferListener? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelHttpDataSource(single.createDataSource(), headers, connections, listener = listener)
    }

    /** The pieces downloader itself. */
    private class Pieces(private val headers: Map<String, String>, private val connections: Int, private val chunkSize: Int) :
        BaseDataSource(true), androidx.media3.datasource.HttpDataSource {
        private var code = -1
        private class Piece(val start: Long, val size: Int) {
            val data = ByteArray(size)
            @Volatile var filled = 0
            @Volatile var failure: IOException? = null
            val lock = Object()
            fun advance(count: Int) = synchronized(lock) { filled += count; lock.notifyAll() }
            fun fail(error: IOException) = synchronized(lock) { failure = error; lock.notifyAll() }
        }

        private val live: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
        private val pieces = ArrayDeque<Piece>()
        private var pool: ExecutorService? = null
        @Volatile private var target = ""
        private var next = 0L
        private var end = 0L
        private var readOffset = 0
        private var address: Uri? = null
        private var headerFields: Map<String, List<String>> = emptyMap()
        private var opened = false
        @Volatile private var closed = false

        /** The bytes to read from [dataSpec]'s position, or null when the server doesn't do ranges (use one connection). */
        fun openOrNull(dataSpec: DataSpec): Long? {
            transferInitializing(dataSpec)
            val position = dataSpec.position
            val (first, finalUrl) = request(dataSpec.uri.toString(), position, position + chunkSize - 1)
            code = first.responseCode
            if (code != 206) { disconnect(first); return null }
            val range = first.getHeaderField("Content-Range").orEmpty()
            val start = range.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull()
            val total = range.substringAfterLast('/').trim().toLongOrNull()
            if (start != position || total == null || total <= position) { disconnect(first); return null }
            target = finalUrl
            headerFields = first.headerFields.mapNotNull { (key, values) -> key?.let { it to values } }.toMap()
            end = total
            address = Uri.parse(finalUrl)
            pool = Executors.newFixedThreadPool(connections) { task -> Thread(task, "http-piece").apply { isDaemon = true } }
            val piece = Piece(position, minOf(chunkSize.toLong(), end - position).toInt())
            pieces.addLast(piece)
            next = piece.start + piece.size
            pool!!.execute { fill(piece, first) }
            schedule()
            android.util.Log.i(TAG, "open at $position of $total over $connections connections")
            opened = true
            transferStarted(dataSpec)
            return end - position
        }

        private fun schedule() {
            val executor = pool ?: return
            while (!closed && pieces.size < connections + 2 && next < end) {
                val piece = Piece(next, minOf(chunkSize.toLong(), end - next).toInt())
                pieces.addLast(piece); next += piece.size
                executor.execute { fill(piece, null) }
            }
        }

        private fun fill(piece: Piece, first: HttpURLConnection?) {
            var connection = first
            var attempts = 0
            while (!closed && piece.filled < piece.size) {
                try {
                    val current = connection ?: request(target, piece.start + piece.filled, piece.start + piece.size - 1).first
                    connection = null
                    if (current.responseCode != 206) { disconnect(current); throw IOException("The video server returned HTTP ${current.responseCode}.") }
                    val from = current.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                    if (from != piece.start + piece.filled) { disconnect(current); throw IOException("The video server returned an unexpected byte range.") }
                    try { current.inputStream.use { input -> copy(input, piece) } } finally { disconnect(current) }
                    if (piece.filled < piece.size) throw IOException("The video server ended the download early.")
                } catch (error: IOException) {
                    connection?.let(::disconnect); connection = null
                    if (closed) return
                    android.util.Log.w(TAG, "piece at ${piece.start} stopped at ${piece.filled}/${piece.size} (attempt ${attempts + 1}): ${error.javaClass.simpleName}")
                    if (++attempts >= 4) { piece.fail(error); return }
                    try { Thread.sleep(400L * attempts) } catch (_: InterruptedException) { return }
                } catch (_: RuntimeException) {
                    connection?.let(::disconnect); connection = null
                    if (closed) return
                    if (++attempts >= 4) { piece.fail(IOException("The video connection failed.")); return }
                }
            }
        }

        private fun copy(input: InputStream, piece: Piece) {
            while (!closed && piece.filled < piece.size) {
                val count = input.read(piece.data, piece.filled, piece.size - piece.filled)
                if (count < 0) return
                piece.advance(count)
            }
        }

        /** One ranged GET, following redirects (http and https both, as the player's own source does). */
        private fun request(address: String, from: Long, to: Long): Pair<HttpURLConnection, String> {
            var next = address
            for (redirect in 0..5) {
                if (closed) throw InterruptedIOException("Closed.")
                val current = URI(next).toURL().openConnection() as HttpURLConnection
                live += current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000; current.readTimeout = 30_000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
                current.setRequestProperty("Range", "bytes=$from-$to")
                val status = current.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = current.getHeaderField("Location") ?: run { disconnect(current); throw IOException("The video server sent an invalid redirect.") }
                    next = URI(next).resolve(location).toString()
                    disconnect(current)
                    continue
                }
                return current to next
            }
            throw IOException("The video server sent too many redirects.")
        }

        private fun disconnect(connection: HttpURLConnection) { live -= connection; runCatching { connection.disconnect() } }

        override fun open(dataSpec: DataSpec): Long {
            return openOrNull(dataSpec) ?: throw IOException("Ranges are not supported here.")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val piece = pieces.firstOrNull() ?: return C.RESULT_END_OF_INPUT
            // Like a socket: a cancelled load (seek) is let finish its element for up to INTERRUPTED_WAIT_MS (see
            // EasynewsParallelDataSource: aborting mid-element crashed the MKV reader).
            var interruptedAt = 0L
            val waitingSince = android.os.SystemClock.elapsedRealtime()
            synchronized(piece.lock) {
                while (!closed && piece.filled <= readOffset && piece.failure == null) {
                    if (Thread.interrupted() && interruptedAt == 0L) interruptedAt = android.os.SystemClock.elapsedRealtime()
                    if (interruptedAt == 0L && android.os.SystemClock.elapsedRealtime() - waitingSince > STALL_MS)
                        throw IOException("No data over several connections for ${STALL_MS / 1000} s.")
                    if (interruptedAt != 0L && android.os.SystemClock.elapsedRealtime() - interruptedAt > INTERRUPTED_WAIT_MS)
                        throw InterruptedIOException("Load cancelled.")
                    try { piece.lock.wait(250) }
                    catch (_: InterruptedException) { if (interruptedAt == 0L) interruptedAt = android.os.SystemClock.elapsedRealtime() }
                }
            }
            if (closed) throw InterruptedIOException("Closed.")
            if (piece.filled <= readOffset) throw piece.failure ?: IOException("The video server stopped sending this video.")
            val count = minOf(length, piece.filled - readOffset)
            System.arraycopy(piece.data, readOffset, buffer, offset, count)
            readOffset += count
            bytesTransferred(count)
            if (readOffset == piece.size) { pieces.removeFirst(); readOffset = 0; schedule() }
            return count
        }

        override fun getUri(): Uri? = address
        override fun getResponseHeaders(): Map<String, List<String>> = headerFields
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
        override fun getResponseCode(): Int = code
        override fun close() {
            closed = true
            pool?.shutdownNow(); pool = null
            synchronized(live) { live.toList() }.forEach(::disconnect)
            pieces.forEach { piece -> synchronized(piece.lock) { piece.lock.notifyAll() } }
            pieces.clear(); readOffset = 0; address = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    private companion object {
        const val TAG = "PovHttp"
        const val INTERRUPTED_WAIT_MS = 10_000L
        /** No new byte from the pieces for this long (and no seek): treat several connections as not working here. */
        const val STALL_MS = 8_000L
        /** Hosts where several connections stalled this run: they stay on one. */
        val singleHosts: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }
}
