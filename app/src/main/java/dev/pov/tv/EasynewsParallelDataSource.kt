package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.pov.tv.data.Easynews
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Easynews caps each download connection (about 2–3 Mbps from Pakistan in a 2026-11-02 test, 11 Mbps with eight),
 * so this source fetches the video as [chunkSize] pieces over [connections] parallel HTTPS range requests and hands
 * them to the player in order. Only a few pieces are fetched ahead of playback, so memory stays at about
 * (connections + 2) × chunkSize. A seek closes the source and the player reopens it at the new position.
 *
 * The login is sent only to Easynews hosts: every redirect hop is checked with [Easynews.isHost], as in
 * [EasynewsHttpDataSource].
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EasynewsParallelDataSource(
    private val headers: Map<String, String>,
    connections: Int,
    private val chunkSize: Int = 2 * 1024 * 1024,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : BaseDataSource(true), HttpDataSource {
    private val connections = connections.coerceIn(1, Easynews.MAX_CONNECTIONS)

    private class Piece(val start: Long, val size: Int) {
        val data = ByteArray(size)
        @Volatile var filled = 0
        @Volatile var failure: IOException? = null
        val lock = Object()
        fun advance(count: Int) = synchronized(lock) { filled += count; lock.notifyAll() }
        fun fail(error: IOException) = synchronized(lock) { failure = error; lock.notifyAll() }
    }

    private val extra = linkedMapOf<String, String>()
    private val live: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
    private val pieces = ArrayDeque<Piece>()
    private var pool: ExecutorService? = null
    private var origin = ""
    @Volatile private var target = ""
    private var next = 0L
    private var end = 0L
    private var readOffset = 0
    private var address: Uri? = null
    private var headerFields: Map<String, List<String>> = emptyMap()
    private var code = -1
    private var opened = false
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException(L10n.tr(R.string.t_only_video_get_requests_are_supported, "Only video GET requests are supported."))
        closed = false
        origin = dataSpec.uri.toString()
        val position = dataSpec.position
        val bounded = dataSpec.length != C.LENGTH_UNSET.toLong()
        try {
            // The first request follows the redirects to the download server, learns the file size and becomes piece one.
            val firstEnd = position + (if (bounded) minOf(dataSpec.length, chunkSize.toLong()) else chunkSize.toLong()) - 1
            val (first, finalUrl) = request(origin, position, firstEnd)
            target = finalUrl
            code = first.responseCode
            headerFields = first.headerFields.mapNotNull { (key, values) -> key?.let { it to values } }.toMap()
            if (code != 206) { disconnect(first); throw IOException(L10n.tr(R.string.t_easynews_didn_t_accept_a_ranged, "Easynews didn't accept a ranged download (HTTP %1\$s). Set Easynews connections to 1.", code)) }
            val range = first.getHeaderField("Content-Range").orEmpty()
            val start = range.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull()
            val total = range.substringAfterLast('/').trim().toLongOrNull()
            if (start != position || total == null) { disconnect(first); throw IOException(L10n.tr(R.string.t_easynews_returned_an_unexpected_byte_range, "Easynews returned an unexpected byte range.")) }
            end = if (bounded) minOf(total, position + dataSpec.length) else total
            address = Uri.parse(target)
            pool = Executors.newFixedThreadPool(connections) { task -> Thread(task, "easynews-piece").apply { isDaemon = true } }
            val piece = Piece(position, minOf(chunkSize.toLong(), end - position).toInt())
            pieces.addLast(piece)
            next = piece.start + piece.size
            pool!!.execute { fill(piece, first) }
            schedule()
            // Positions and sizes only: never the address (it carries a download token) or the login.
            android.util.Log.i(TAG, "open at $position of $total (${end - position} bytes) over $connections connections")
            opened = true
            transferStarted(dataSpec)
            return end - position
        } catch (failure: IOException) { close(); throw failure }
        catch (_: RuntimeException) { close(); throw IOException(L10n.tr(R.string.t_the_easynews_connection_couldn_t_be, "The Easynews connection couldn't be opened.")) }
    }

    /** Keeps [connections] + 2 pieces requested ahead of the one being read. */
    private fun schedule() {
        val executor = pool ?: return
        while (!closed && pieces.size < connections + 2 && next < end) {
            val piece = Piece(next, minOf(chunkSize.toLong(), end - next).toInt())
            pieces.addLast(piece); next += piece.size
            executor.execute { fill(piece, null) }
        }
    }

    /** Downloads one piece, resuming after a dropped connection; [first] is the already-open opening request. */
    private fun fill(piece: Piece, first: HttpURLConnection?) {
        var connection = first
        var attempts = 0
        while (!closed && piece.filled < piece.size) {
            try {
                val current = connection ?: request(target, piece.start + piece.filled, piece.start + piece.size - 1).let { (opened, finalUrl) ->
                    if (opened.responseCode in setOf(401, 403, 404, 410)) {
                        // The download server's link expired: ask Easynews for a fresh one.
                        android.util.Log.i(TAG, "download link refused (HTTP ${opened.responseCode}); asking Easynews for a fresh one")
                        disconnect(opened)
                        request(origin, piece.start + piece.filled, piece.start + piece.size - 1).also { target = it.second }.first
                    } else { if (finalUrl != target) target = finalUrl; opened }
                }
                connection = null
                if (current.responseCode != 206) { disconnect(current); throw IOException(L10n.tr(R.string.t_easynews_returned_http_x, "Easynews returned HTTP %1\$s.", current.responseCode)) }
                val from = current.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (from != piece.start + piece.filled) { disconnect(current); throw IOException(L10n.tr(R.string.t_easynews_returned_an_unexpected_byte_range, "Easynews returned an unexpected byte range.")) }
                try {
                    current.inputStream.use { input -> copy(input, piece) }
                } finally { disconnect(current) }
                if (piece.filled < piece.size) throw IOException(L10n.tr(R.string.t_easynews_ended_the_download_early, "Easynews ended the download early."))
            } catch (error: IOException) {
                connection?.let(::disconnect); connection = null
                if (closed) return
                android.util.Log.w(TAG, "piece at ${piece.start} stopped at ${piece.filled}/${piece.size} (attempt ${attempts + 1}): ${error.javaClass.simpleName}")
                if (++attempts >= 4) { piece.fail(error); return }
                try { Thread.sleep(400L * attempts) } catch (_: InterruptedException) { return }
            } catch (_: RuntimeException) {
                connection?.let(::disconnect); connection = null
                if (closed) return
                if (++attempts >= 4) { piece.fail(IOException(L10n.tr(R.string.t_the_easynews_connection_failed, "The Easynews connection failed."))); return }
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

    /** One ranged GET, following redirects by hand so the login only reaches Easynews hosts. */
    private fun request(address: String, from: Long, to: Long): Pair<HttpURLConnection, String> {
        var next = address
        for (redirect in 0..5) {
            if (closed) throw InterruptedIOException("Closed.")
            if (!Easynews.isHost(next)) throw IOException(L10n.tr(R.string.t_easynews_redirected_outside_its_own_service, "Easynews redirected outside its own service."))
            val current = connect(URI(next))
            live += current
            current.instanceFollowRedirects = false
            current.connectTimeout = 15_000; current.readTimeout = 45_000
            current.requestMethod = "GET"
            current.setRequestProperty("Accept-Encoding", "identity")
            extra.forEach { (key, value) -> current.setRequestProperty(key, value) }
            headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
            current.setRequestProperty("Range", "bytes=$from-$to")
            val status = current.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = current.getHeaderField("Location") ?: run { disconnect(current); throw IOException(L10n.tr(R.string.t_easynews_sent_an_invalid_redirect, "Easynews sent an invalid redirect.")) }
                next = URI(next).resolve(location).toString()
                disconnect(current)
                continue
            }
            return current to next
        }
        throw IOException(L10n.tr(R.string.t_easynews_sent_too_many_redirects, "Easynews sent too many redirects."))
    }

    private fun disconnect(connection: HttpURLConnection) { live -= connection; runCatching { connection.disconnect() } }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val piece = pieces.firstOrNull() ?: return C.RESULT_END_OF_INPUT
        // The player interrupts this thread when it cancels a load (a seek or resume). A normal network read ignores
        // that and finishes, letting the MKV reader complete its current element; aborting here instead left the
        // reader mid-element and crashed it on the next load (Media3 SampleQueue NullPointerException, 2026-11-02).
        // So keep waiting like a socket would, for at most INTERRUPTED_WAIT_MS once interrupted.
        var interruptedAt = 0L
        synchronized(piece.lock) {
            while (!closed && piece.filled <= readOffset && piece.failure == null) {
                if (Thread.interrupted() && interruptedAt == 0L) interruptedAt = android.os.SystemClock.elapsedRealtime()
                if (interruptedAt != 0L && android.os.SystemClock.elapsedRealtime() - interruptedAt > INTERRUPTED_WAIT_MS)
                    throw InterruptedIOException(L10n.tr(R.string.t_easynews_load_cancelled, "Easynews load cancelled."))
                try { piece.lock.wait(250) }
                catch (_: InterruptedException) { if (interruptedAt == 0L) interruptedAt = android.os.SystemClock.elapsedRealtime() }
            }
        }
        if (closed) throw InterruptedIOException("Closed.")
        if (piece.filled <= readOffset) throw piece.failure ?: IOException(L10n.tr(R.string.t_easynews_stopped_sending_this_video, "Easynews stopped sending this video."))
        val count = minOf(length, piece.filled - readOffset)
        System.arraycopy(piece.data, readOffset, buffer, offset, count)
        readOffset += count
        bytesTransferred(count)
        if (readOffset == piece.size) { pieces.removeFirst(); readOffset = 0; schedule() }
        return count
    }

    override fun getUri(): Uri? = address
    override fun getResponseHeaders(): Map<String, List<String>> = headerFields
    override fun close() {
        closed = true
        pool?.shutdownNow(); pool = null
        synchronized(live) { live.toList() }.forEach(::disconnect)
        pieces.forEach { piece -> synchronized(piece.lock) { piece.lock.notifyAll() } }
        pieces.clear(); readOffset = 0; address = null
        if (opened) { opened = false; transferEnded() }
    }
    override fun setRequestProperty(name: String, value: String) { if (!name.equals("Authorization", true)) extra[name] = value }
    override fun clearRequestProperty(name: String) { extra.remove(name) }
    override fun clearAllRequestProperties() { extra.clear() }
    override fun getResponseCode(): Int = code

    private companion object {
        const val TAG = "PovEasynews"
        const val INTERRUPTED_WAIT_MS = 10_000L
    }
}
