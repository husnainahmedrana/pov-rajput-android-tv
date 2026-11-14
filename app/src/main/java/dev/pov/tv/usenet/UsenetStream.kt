package dev.pov.tv.usenet

import dev.pov.tv.L10n
import dev.pov.tv.R

import java.io.Closeable
import java.io.IOException

/** Bytes that can be read at any position: one posted file, or a video stored across a set of RAR volumes. */
interface ByteSource : Closeable {
    val size: Long
    /** Copies up to [length] bytes from [position]; -1 at the end. Blocks while the data downloads. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
    /** Starts downloading [length] bytes at [position] without waiting: the player is known to want them soon. */
    fun prefetch(position: Long, length: Long) {}
}

/**
 * How much of a video's end is fetched as soon as it opens. An MKV keeps its seek index (Cues) at the end and the
 * player reads it before the first picture; on a big remux it is 2–5 MB. Fetched alongside the start, that jump costs
 * nothing, instead of waiting behind the start's read-ahead (35 s on a 10 GB set on the onn).
 */
const val VIDEO_TAIL_BYTES = 5L * 1024 * 1024

/**
 * One posted file read as if it were a local file: [read] at any position fetches the article that holds it and keeps
 * a window of the following articles downloading ahead over the pool's connections.
 *
 * An NZB lists article sizes as posted (encoded), so positions come from the articles themselves: the first one states
 * the file's size and the part size, later ones state where they begin. Parts are nearly always the same size, so the
 * article for a position is guessed by division and corrected from what the fetched article says.
 */
class UsenetStream(
    private val pool: ArticleSource,
    private val segments: List<NzbSegment>,
    /** Memory the read-ahead window may hold. */
    private val windowBytes: Long = 32L * 1024 * 1024,
    /** Keep the file's first and last articles in memory: players return to a video's two ends. */
    private val pinEnds: Boolean = true,
    private val log: (String) -> Unit = {}
) : ByteSource {
    override var size = -1L; private set
    var name = ""; private set
    var partSize = 0L; private set
    private var window = 4
    private val slots = HashMap<Int, Fetch>()
    private val lock = Any()
    private var scheduled = -1
    /** The size was taken from a sibling file, not read from this one: every article must agree with it. */
    private var assumed = false
    @Volatile private var closed = false
    private val last = segments.size - 1
    val prepared: Boolean get() = size >= 0

    /** Fetches the first article to learn the file's size and part size. */
    fun prepare() {
        if (prepared) return
        require(segments.isNotEmpty()) { "The NZB lists no articles." }
        val first = await(0)
        synchronized(lock) {
            if (prepared) return
            if (first.begin != 0L || first.fileSize <= 0 || first.size <= 0) throw IOException(L10n.tr(R.string.t_the_first_article_doesn_t_describe, "The first article doesn't describe the file."))
            partSize = first.size.toLong(); name = first.name
            window = windowFor(partSize)
            size = first.fileSize
        }
        log("stream ready: $size bytes in ${segments.size} articles of $partSize, read-ahead $window articles")
    }

    /**
     * Takes the size and part size from [sibling], a file of the same set already known to be the same size (RAR
     * volumes), so this one needs no article fetched before it can be read from the middle.
     */
    fun assumeLike(sibling: UsenetStream) {
        synchronized(lock) {
            if (prepared) return
            partSize = sibling.partSize; window = windowFor(partSize); assumed = true
            size = sibling.size
        }
    }

    private fun windowFor(part: Long) = minOf((pool.requestCapacity + 2).toLong(), windowBytes / part).toInt().coerceAtLeast(4)

    /** Asks for the first article without waiting for it, so several files can be prepared side by side. */
    fun begin() {
        synchronized(lock) { if (!closed && 0 !in slots) slots[0] = pool.fetch(segments[0].messageId) }
    }

    /** Asks for the file's last [count] articles without waiting (an archive keeps its directory at the end). */
    fun beginTail(count: Int) {
        synchronized(lock) { if (!closed) for (index in maxOf(0, last - count + 1)..last) if (index !in slots) slots[index] = pool.fetch(segments[index].messageId) }
    }

    override fun prefetch(position: Long, length: Long) {
        synchronized(lock) {
            if (closed || !prepared || partSize <= 0 || length <= 0) return
            val from = (position / partSize).toInt().coerceIn(0, last)
            val to = ((position + length - 1) / partSize + 1).toInt().coerceIn(0, last)
            // Ahead of read-ahead in the queue: these few articles are what the player is about to block on.
            for (index in from..to) if (index !in slots) slots[index] = pool.fetch(segments[index].messageId, urgent = true)
        }
    }

    /** Starts the first articles downloading without waiting for them: the reader is about to arrive here. */
    fun warm() {
        synchronized(lock) {
            if (closed || scheduled >= 0) return
            for (next in 0..minOf(last, if (prepared) window else 3)) if (next !in slots) slots[next] = pool.fetch(segments[next].messageId)
        }
    }

    /** Lets go of everything downloaded or queued; the stream can still be read again later. */
    fun release() {
        synchronized(lock) { slots.values.forEach { it.cancel() }; slots.clear(); scheduled = -1 }
    }

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        prepare()
        if (position >= size) return -1
        if (length == 0) return 0
        var index = (position / partSize).toInt().coerceIn(0, last)
        repeat(24) {
            val segment = await(index)
            if (assumed && segment.fileSize != size) throw IOException(L10n.tr(R.string.t_this_part_of_the_set_is, "This part of the set is not the size the others are."))
            val end = segment.begin + segment.size
            when {
                position < segment.begin -> index = (index - maxOf(1L, (segment.begin - position + partSize - 1) / partSize).toInt()).coerceAtLeast(0)
                position >= end -> {
                    if (index == last) return -1
                    index = (index + maxOf(1L, (position - end) / partSize + 1).toInt()).coerceAtMost(last)
                }
                else -> {
                    schedule(index)
                    val count = minOf(length.toLong(), end - position).toInt()
                    System.arraycopy(segment.data, (position - segment.begin).toInt(), target, offset, count)
                    return count
                }
            }
        }
        throw IOException(L10n.tr(R.string.t_the_file_s_articles_don_t, "The file's articles don't line up; position %1\$s can't be found.", position))
    }

    /** Keeps the articles after [index] downloading and lets go of those far from it. */
    private fun schedule(index: Int) {
        synchronized(lock) {
            if (index == scheduled || closed) return
            scheduled = index
            val keep = (index - 1)..(index + window)
            val stale = slots.keys.filter { it !in keep && !(pinEnds && (it <= 1 || it >= last - 2)) }
            for (key in stale) slots.remove(key)?.cancel()
            for (next in index + 1..minOf(last, index + window)) if (next !in slots) slots[next] = pool.fetch(segments[next].messageId)
        }
    }

    private fun await(index: Int): Segment {
        val started = System.nanoTime()
        val deadline = started + AWAIT_MS * 1_000_000
        var asked = started
        while (true) {
            if (closed) throw IOException(L10n.tr(R.string.t_the_stream_is_closed, "The stream is closed."))
            // An article that hasn't come for a while is usually stuck on one slow or half-dead connection: ask for
            // it again at the front of the queue, so a free connection fetches it, instead of waiting out the timeout.
            if (System.nanoTime() - asked > STALL_MS * 1_000_000) {
                asked = System.nanoTime()
                synchronized(lock) { slots.remove(index)?.takeIf { !it.done }?.let { stale ->
                    log("article ${index + 1} stalled for ${STALL_MS / 1000} s; asking again"); stale.cancel() } }
            }
            val fetch = synchronized(lock) {
                val known = slots[index]
                if (known != null && !known.cancelled) { if (!known.done) pool.hurry(known); known }
                else pool.fetch(segments[index].messageId, urgent = true).also { slots[index] = it }
            }
            val segment = try { fetch.await(400) } catch (error: IOException) {
                synchronized(lock) { if (slots[index] === fetch) slots.remove(index) }
                throw error
            }
            if (segment != null) return segment
            if (System.nanoTime() > deadline) throw SlowArticleException()
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; slots.values.forEach { it.cancel() }; slots.clear() }
    }

    private companion object { const val AWAIT_MS = 60_000L; const val STALL_MS = 12_000L }
}

/** No article arrived in time: the connection or server was slow just then. Worth trying again; not a broken release. */
class SlowArticleException : IOException(L10n.tr(R.string.t_the_news_server_took_too_long, "The news server took too long to send the video."))

/**
 * Whether [error] was the network's moment (a timeout, a dropped connection) rather than the release itself (missing
 * articles, an archive the app can't play, a refused login). Passing failures are retried; lasting ones are remembered.
 */
fun isPassingFailure(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        when (cause) {
            is MissingArticleException, is UnsupportedSetException, is NntpRefusedException -> return false
            is SlowArticleException, is java.net.SocketTimeoutException, is java.net.SocketException,
            is javax.net.ssl.SSLException, is java.io.EOFException -> return true
        }
        if (cause.message?.startsWith("The news server answered") == true) return true
        cause = cause.cause
    }
    return false
}
