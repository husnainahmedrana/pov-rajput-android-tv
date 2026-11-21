package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves one video to another app on this device (Kodi, Dune HD Media Center, VLC…) at
 * `http://127.0.0.1:<port>/<token>/<name>`, with byte ranges so that player can seek.
 *
 * The bytes come from the same data sources the built-in player reads: Usenet links stream from the viewer's news
 * providers (NZB, RAR volumes, 7z), Easynews links get the saved login, and links that need request headers get them.
 * None of that (logins, headers, NZB addresses) is in the address handed to the other app.
 *
 * Only the loopback interface is bound, and each video gets a fresh random token, so other devices on the network and
 * other addresses can't read it. A new video replaces the old one; [stop] ends it.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalStreamServer(
    private val source: DataSource.Factory,
    private val upstream: Uri,
    private val name: String,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val token = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = java.util.concurrent.ConcurrentLinkedDeque<Socket>()
    @Volatile private var closed = false
    @Volatile private var size: Long = C.LENGTH_UNSET.toLong()
    private val sizeLock = Any()
    /** When a request last ended (or the server started): an idle server is stopped by its owner. */
    val lastActive = AtomicLong(System.currentTimeMillis())

    val path: String = "/$token/" + safeName(name)
    val address: String get() = "http://127.0.0.1:${server.localPort}$path"
    /** Requests being answered right now. */
    val busy: Int get() = clients.size
    /** The video's size in bytes, once a request has learnt it (negative before). */
    val knownSize: Long get() = size
    /**
     * Where the other app is reading: the byte reached by the latest request that has carried at least
     * [PLAYING_BYTES] (a short probe, such as the index at the end of a file, doesn't count). Negative until then.
     */
    @Volatile var playhead: Long = -1
        private set
    @Volatile private var duration: Long? = null
    @Volatile private var durationRead = false

    init {
        Thread({ acceptLoop() }, "local-stream-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            // External players open a few connections (a probe at the end for the index, then the picture): a handful
            // at once is normal; beyond that the oldest is let go so a player that never closes can't pile them up.
            clients.addLast(socket)
            while (clients.size > MAX_CLIENTS) clients.pollFirst()?.let { runCatching { it.close() } }
            Thread({ answer(socket) }, "local-stream").apply { isDaemon = true }.start()
        }
    }

    private fun answer(socket: Socket) {
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream().buffered(64 * 1024)
            val request = readHead(input) ?: return
            val (method, target) = request.line.split(' ').let { if (it.size >= 2) it[0] to it[1] else return }
            if (method != "GET" && method != "HEAD") { status(output, 405, "Method Not Allowed"); return }
            if (target.substringBefore('?') != path) { status(output, 404, "Not Found"); return }
            socket.soTimeout = 0
            val total = size()
            val range = parseRange(request.headers["range"], total)
            if (range == Range.UNSATISFIABLE) {
                output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                output.flush(); return
            }
            val start = range?.start ?: 0L
            val end = range?.end ?: (if (total >= 0) total - 1 else -1L)
            val length = if (end >= 0) end - start + 1 else C.LENGTH_UNSET.toLong()
            val head = StringBuilder()
            head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ").append(contentType(name)).append("\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (length >= 0) head.append("Content-Length: ").append(length).append("\r\n")
            if (range != null && total >= 0) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray())
            if (method == "HEAD") { output.flush(); return }
            copy(start, length, output)
            output.flush()
        } catch (_: SocketException) {
            // The other app closed the connection (a seek, Stop): normal.
        } catch (error: IOException) {
            if (!closed) log("request ended: ${error.message}")
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
            lastActive.set(System.currentTimeMillis())
        }
    }

    /** The video's size, learnt from the first opening (an open from the start reports it) and kept. */
    private fun size(): Long {
        if (size >= 0) return size
        synchronized(sizeLock) {
            if (size >= 0) return size
            val reader = source.createDataSource()
            try { size = reader.open(DataSpec.Builder().setUri(upstream).setPosition(0).build()) } finally { runCatching { reader.close() } }
            return size
        }
    }

    /** The start of the video, read before the other app is opened ([warm]); its first requests are answered from here. */
    @Volatile private var head = ByteArray(0)

    /**
     * Opens the stream and reads its first [bytes] before the other app starts: a Usenet stream can take many seconds
     * to send its first bytes (the NZB, the connections, the health check), longer than players wait for an answer.
     * Throws when the video can't be opened.
     */
    fun warm(bytes: Int, progress: (Int) -> Unit = {}) {
        val total = size()
        val want = if (total > 0) minOf(bytes.toLong(), total).toInt() else bytes
        val reader = source.createDataSource()
        try {
            reader.open(DataSpec.Builder().setUri(upstream).setPosition(0).setLength(want.toLong()).build())
            val out = ByteArray(want)
            var filled = 0
            var told = -1
            while (filled < want && !closed) {
                val count = reader.read(out, filled, want - filled)
                if (count == C.RESULT_END_OF_INPUT) break
                filled += count
                val percent = (filled.toLong() * 100 / want).toInt()
                if (percent != told) { told = percent; progress(percent) }
            }
            head = if (filled == want) out else out.copyOf(filled)
        } finally { runCatching { reader.close() } }
    }

    private fun copy(start: Long, length: Long, output: OutputStream) {
        var start = start
        var length = length
        // What [warm] already read is sent at once; the stream carries on from where it ends.
        val ready = head
        if (start < ready.size) {
            val count = if (length < 0) ready.size - start.toInt() else minOf(length, ready.size - start).toInt()
            output.write(ready, start.toInt(), count)
            output.flush()
            start += count
            if (length >= 0) { length -= count; if (length == 0L) return }
            if (size in 0..start) return
        }
        val reader = source.createDataSource()
        try {
            reader.open(DataSpec.Builder().setUri(upstream).setPosition(start).setLength(length).build())
            val buffer = ByteArray(256 * 1024)
            var left = length
            var carried = 0L
            while (!closed && (length < 0 || left > 0)) {
                val wanted = if (length < 0) buffer.size else minOf(buffer.size.toLong(), left).toInt()
                val count = reader.read(buffer, 0, wanted)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count <= 0) continue
                output.write(buffer, 0, count)
                left -= count
                carried += count
                if (carried >= PLAYING_BYTES) playhead = start + carried
            }
        } finally { runCatching { reader.close() } }
    }

    /** The video's length in milliseconds from its own header (Matroska or MP4), or null when it can't be read. */
    fun durationMs(): Long? {
        if (durationRead) return duration
        synchronized(sizeLock) {
            if (durationRead) return duration
            duration = try {
                val reader = source.createDataSource()
                try {
                    reader.open(DataSpec.Builder().setUri(upstream).setPosition(0).setLength(HEADER_BYTES.toLong()).build())
                    val header = ByteArray(HEADER_BYTES)
                    var filled = 0
                    while (filled < header.size) { val count = reader.read(header, filled, header.size - filled); if (count < 0) break; filled += count }
                    VideoDuration.read(header, filled)
                } finally { runCatching { reader.close() } }
            } catch (_: Exception) { null }
            durationRead = true
            return duration
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        while (true) { val socket = clients.pollFirst() ?: break; runCatching { socket.close() } }
    }

    private class Head(val line: String, val headers: Map<String, String>)

    private fun readHead(input: InputStream): Head? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (++total > MAX_HEAD) return null
            if (byte == '\n'.code) {
                val text = line.toString().trimEnd('\r'); line.setLength(0)
                if (text.isEmpty()) break
                lines += text
            } else line.append(byte.toChar())
        }
        if (lines.isEmpty()) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':')
            if (colon <= 0) null else header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
        }.toMap()
        return Head(lines[0], headers)
    }

    private fun status(output: OutputStream, code: Int, text: String) {
        output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
    }

    data class Range(val start: Long, val end: Long) {
        companion object { val UNSATISFIABLE = Range(-1, -1) }
    }

    companion object {
        private const val MAX_CLIENTS = 6
        private const val MAX_HEAD = 16 * 1024
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val PLAYING_BYTES = 8L * 1024 * 1024
        private const val HEADER_BYTES = 512 * 1024
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "ts", "m2ts", "avi", "webm", "mov")

        /**
         * One `bytes=` range against a video of [total] bytes (negative when unknown): null for no range (the whole
         * video), [Range.UNSATISFIABLE] for one that starts past the end. Only the first range of a list is answered.
         */
        fun parseRange(header: String?, total: Long): Range? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.substringBefore(',')?.trim() ?: return null
            val dash = spec.indexOf('-').takeIf { it >= 0 } ?: return null
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                // "bytes=-500": the last 500 bytes.
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                if (total < 0) return null
                return Range(maxOf(0, total - suffix), total - 1)
            }
            val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (total >= 0 && start >= total) return Range.UNSATISFIABLE
            val end = last.toLongOrNull()?.let { if (total >= 0) minOf(it, total - 1) else it } ?: (if (total >= 0) total - 1 else -1L)
            if (end >= 0 && end < start) return null
            return Range(start, end)
        }

        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "ts", "m2ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> "video/*"
        }

        /** A file name safe in a URL path: other players show it as the title and read the extension from it. */
        fun safeName(name: String): String {
            val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]+"), ".").trim('.').take(180)
            return cleaned.ifEmpty { "video" }.let { if (it.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) it else "$it.mkv" }
        }
    }
}
