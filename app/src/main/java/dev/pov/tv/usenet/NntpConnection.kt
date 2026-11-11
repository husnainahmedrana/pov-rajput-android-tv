package dev.pov.tv.usenet

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.CRC32
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A news server and the login for it. */
class NntpServer(val host: String, val port: Int, val tls: Boolean, val user: String, val password: String) {
    init {
        require(host.isNotBlank() && host.none { it <= ' ' }) { "Enter the news server's address." }
        require(port in 1..65535) { "Enter the news server's port." }
        // Both go on a command line: a line break would let them start another command.
        require(user.isNotEmpty() && user.none { it == '\r' || it == '\n' }) { "Enter the news server username." }
        require(password.isNotEmpty() && password.none { it == '\r' || it == '\n' }) { "Enter the news server password." }
    }
}

/** The server does not have this article (430): a hole in the file, not a connection problem. */
class MissingArticleException(messageId: String) : IOException("The news server no longer has article $messageId.")

/** The server refused the login or the connection (too many connections, wrong password): retrying will not help. */
class NntpRefusedException(val code: Int, message: String) : IOException(message)

/** One decoded article: [size] bytes of the file starting at [begin] (zero-based), out of [fileSize]. */
class Segment(val data: ByteArray, val size: Int, val begin: Long, val fileSize: Long, val name: String, val crcOk: Boolean)

/**
 * One blocking connection to a news server. Commands may be sent ahead of reading their answers (pipelining); answers
 * come back in the order the commands were sent.
 */
internal class NntpConnection(
    private val server: NntpServer,
    private val open: (NntpServer) -> Socket = ::openSocket,
    /** Told of every batch of bytes as it arrives, for a live speed readout. */
    private val received: (Int) -> Unit = {}
) : Closeable {
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val buffer = ByteArray(128 * 1024)
    private var position = 0
    private var limit = 0
    private val pending = java.io.ByteArrayOutputStream(256)
    private val crc = CRC32()

    fun connect() {
        val opened = open(server)
        socket = opened
        input = opened.getInputStream(); output = opened.getOutputStream()
        val greeting = status()
        if (greeting.first != 200 && greeting.first != 201) throw NntpRefusedException(greeting.first, "The news server refused the connection (${greeting.first}).")
        send("AUTHINFO USER ${server.user}"); flush()
        var answer = status()
        if (answer.first == 381) { send("AUTHINFO PASS ${server.password}"); flush(); answer = status() }
        if (answer.first != 281) throw NntpRefusedException(answer.first,
            if (answer.first == 481 || answer.first == 482) "The news server didn't accept this username and password."
            else "The news server refused the login (${answer.first}): ${answer.second.take(80)}")
    }

    /** Queues a command; nothing is sent until [flush]. */
    fun send(command: String) { pending.write(command.toByteArray(Charsets.ISO_8859_1)); pending.write(CRLF) }

    fun flush() {
        if (pending.size() == 0) return
        pending.writeTo(output); output.flush(); pending.reset()
    }

    /** The next answer's status line: its code and the rest of the line. */
    fun status(): Pair<Int, String> {
        val end = lineEnd()
        var stop = end
        if (stop > position && buffer[stop - 1] == CR) stop--
        val line = String(buffer, position, stop - position, Charsets.ISO_8859_1)
        position = end + 1
        val code = line.take(3).toIntOrNull() ?: throw IOException("The news server sent an unreadable answer.")
        return code to line.drop(3).trim()
    }

    /** Reads the article that follows a 222 answer and decodes its yEnc data. */
    fun body(): Segment {
        var data = EMPTY
        var filled = 0
        var begin = 0L; var fileSize = -1L; var partSize = -1L
        var name = ""
        var expected: Long? = null
        var ended = false
        while (true) {
            val end = lineEnd()
            var start = position
            var stop = end
            if (stop > start && buffer[stop - 1] == CR) stop--
            position = end + 1
            if (stop - start == 1 && buffer[start] == DOT) break
            if (stop > start && buffer[start] == DOT) start++          // dot-stuffed line
            if (stop - start >= 2 && buffer[start] == EQUALS && buffer[start + 1] == LOWER_Y) {
                val line = String(buffer, start, stop - start, Charsets.ISO_8859_1)
                when {
                    line.startsWith("=ybegin ") -> {
                        fileSize = field(line, "size")?.toLongOrNull() ?: -1
                        name = line.substringAfter(" name=", "").trim()
                        partSize = fileSize                              // a one-part file has no =ypart line
                    }
                    line.startsWith("=ypart ") -> {
                        val first = field(line, "begin")?.toLongOrNull(); val last = field(line, "end")?.toLongOrNull()
                        if (first != null && last != null && last >= first) { begin = first - 1; partSize = last - first + 1 }
                    }
                    line.startsWith("=yend") -> {
                        ended = true
                        expected = (field(line, "pcrc32") ?: field(line, "crc32"))?.toLongOrNull(16)
                    }
                }
                continue
            }
            if (ended || fileSize < 0) continue                           // text before =ybegin or after =yend
            if (data === EMPTY) {
                if (partSize <= 0 || partSize > MAX_PART) throw IOException("The article is not a usable yEnc part.")
                data = ByteArray(partSize.toInt())
            }
            // The decode loop: every byte is shifted by 42; '=' marks an escaped byte shifted by a further 64.
            val room = data.size
            var index = start
            while (index < stop) {
                var value = buffer[index++].toInt()
                if (value == EQUALS.toInt()) {
                    if (index >= stop) break
                    value = buffer[index++].toInt() - 64
                }
                if (filled >= room) throw IOException("The article holds more data than it states.")
                data[filled++] = (value - 42).toByte()
            }
        }
        if (data === EMPTY) throw IOException("The article holds no yEnc data.")
        crc.reset(); crc.update(data, 0, filled)
        return Segment(data, filled, begin, fileSize, name, crcOk = ended && filled.toLong() == partSize && (expected == null || expected == crc.value))
    }

    /** Which of [ids] the server doesn't have, asked all at once (pipelined STAT). */
    fun missing(ids: List<String>): List<String> {
        ids.forEach { send("STAT <$it>") }
        flush()
        return ids.filter { id ->
            val (code, _) = status()
            when (code) { 223 -> false; 430, 423 -> true; else -> throw IOException("The news server answered $code to a check.") }
        }
    }

    /** Skips the article that follows a 222 answer without decoding it. */
    fun skipBody() {
        while (true) {
            val end = lineEnd()
            var stop = end
            if (stop > position && buffer[stop - 1] == CR) stop--
            val done = stop - position == 1 && buffer[position] == DOT
            position = end + 1
            if (done) return
        }
    }

    /** The index of the line feed that ends the line at [position], reading more from the server as needed. */
    private fun lineEnd(): Int {
        var scan = position
        while (true) {
            while (scan < limit) { if (buffer[scan] == LF) return scan; scan++ }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, limit - position)
                scan -= position; limit -= position; position = 0
            }
            if (limit == buffer.size) throw IOException("The news server sent an overlong line.")
            val count = input.read(buffer, limit, buffer.size - limit)
            if (count < 0) throw IOException("The news server closed the connection.")
            limit += count
            received(count)
        }
    }

    override fun close() {
        val current = socket ?: return
        socket = null
        try { current.close() } catch (_: IOException) {}
    }

    companion object {
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val DOT = '.'.code.toByte()
        private const val EQUALS = '='.code.toByte()
        private const val LOWER_Y = 'y'.code.toByte()
        private val CRLF = byteArrayOf(CR, LF)
        private val EMPTY = ByteArray(0)
        /** Articles are well under a megabyte; anything far larger is not a normal post. */
        private const val MAX_PART = 16L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 25_000

        private fun field(line: String, key: String): String? {
            val at = line.indexOf(" $key=")
            if (at < 0) return null
            val from = at + key.length + 2
            val to = line.indexOf(' ', from).let { if (it < 0) line.length else it }
            return line.substring(from, to)
        }

        /** A TLS socket that checks the server's certificate against its name, or a plain one when TLS is off. */
        fun openSocket(server: NntpServer): Socket {
            val plain = Socket()
            try {
                plain.tcpNoDelay = true
                plain.receiveBufferSize = 512 * 1024
                plain.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS)
                plain.soTimeout = READ_TIMEOUT_MS
                if (!server.tls) return plain
                val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, server.host, server.port, true) as SSLSocket
                secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                secure.startHandshake()
                return secure
            } catch (error: Exception) {
                try { plain.close() } catch (_: IOException) {}
                throw error as? IOException ?: IOException("The news server connection couldn't be opened.", error)
            }
        }
    }
}

/** A one-off sign-in, for the Test button in Settings. */
object Nntp {
    /** Connects and signs in; returns how long it took in milliseconds, or throws with the reason. */
    /** Which of [ids] [server] doesn't have. */
    fun missing(server: NntpServer, ids: List<String>): List<String> {
        if (ids.isEmpty()) return ids
        val connection = NntpConnection(server)
        try { connection.connect(); return connection.missing(ids) } finally { connection.close() }
    }

    fun check(server: NntpServer): Long {
        val started = System.nanoTime()
        val connection = NntpConnection(server)
        try { connection.connect() } finally { connection.close() }
        return (System.nanoTime() - started) / 1_000_000
    }
}
