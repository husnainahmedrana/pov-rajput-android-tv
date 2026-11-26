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
 * How much of a video's end is fetched as soon as it opens, when its seek index can't be located exactly (MP4's moov,
 * 7z entries). An MKV's Cues are found from its SeekHead instead ([MkvIndex]), so only those articles are fetched.
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
    /** Articles asked for by [prefetch] (the player's seek index): read-ahead elsewhere doesn't cancel them until read. */
    private val pinned = LinkedHashSet<Int>()
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
        // The first article on two connections at once: whichever arrives first is used. Nothing plays before it,
        // and the connection that takes it is often one signed in ahead of time, whose first answer after sitting
        // idle comes 1.7–2.3 s late (fresh ones answer in 0.2 s); one extra article is cheap next to that.
        val first = await(0, hedgeMs = 0)
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
            for (index in from..to) {
                if (index !in slots) slots[index] = pool.fetch(segments[index].messageId, urgent = true)
                pinned += index
            }
            while (pinned.size > MAX_PINNED) pinned.remove(pinned.first())
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
        synchronized(lock) { slots.values.forEach { it.cancel() }; slots.clear(); pinned.clear(); scheduled = -1 }
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
                    if (PeekReads.active) peekAhead(index) else schedule(index)
                    synchronized(lock) { if (pinned.isNotEmpty()) pinned.remove(index) }
                    val count = minOf(length.toLong(), end - position).toInt()
                    System.arraycopy(segment.data, (position - segment.begin).toInt(), target, offset, count)
                    return count
                }
            }
        }
        throw IOException(L10n.tr(R.string.t_the_file_s_articles_don_t, "The file's articles don't line up; position %1\$s can't be found.", position))
    }

    /** A peek at [index]: the next couple of articles too, leaving the player's read-ahead as it is. */
    private fun peekAhead(index: Int) {
        synchronized(lock) {
            if (closed) return
            for (next in index + 1..minOf(last, index + START_WINDOW)) if (next !in slots) slots[next] = pool.fetch(segments[next].messageId)
        }
    }

    /** Keeps the articles after [index] downloading and lets go of those far from it. */
    private fun schedule(index: Int) {
        synchronized(lock) {
            if (index == scheduled || closed) return
            // The first read of a stream is often only a peek (the player reads a video's first bytes, then jumps to
            // its seek index): a full window there filled every connection with articles nobody read, and what was
            // wanted waited behind them, so it starts small. A jump by the player itself (a resume, a seek) is
            // followed by playback: the whole window at once, as one connection to a far server is slow. Seek-preview
            // peeks don't come here ([peekAhead]).
            val ahead = if (scheduled < 0) minOf(window, START_WINDOW) else window
            scheduled = index
            val keep = (index - 1)..(index + window)
            val stale = slots.keys.filter { it !in keep && it !in pinned && !(pinEnds && (it <= 1 || it >= last - 2)) }
            for (key in stale) slots.remove(key)?.cancel()
            for (next in index + 1..minOf(last, index + ahead)) if (next !in slots) slots[next] = pool.fetch(segments[next].messageId)
        }
    }

    /** [hedgeMs]: how long the reader waits before a backup request goes out (0: at once, for the first article). */
    private fun await(index: Int, hedgeMs: Long = HEDGE_MS): Segment {
        val started = System.nanoTime()
        val deadline = started + AWAIT_MS * 1_000_000
        var asked = started
        // A second request for the same article on another connection, made when the reader has waited [hedgeMs]:
        // one connection on a lossy line can crawl (700 KB in 11 s while its neighbours take 2 s), and whichever
        // copy arrives first is used. The first request is kept, not cancelled: it may be nearly done.
        var backup: Fetch? = null
        var hedged = false
        while (true) {
            if (closed) throw IOException(L10n.tr(R.string.t_the_stream_is_closed, "The stream is closed."))
            backup?.let { spare ->
                spare.result?.let { segment ->
                    synchronized(lock) { slots[index]?.takeIf { it !== spare && !it.done }?.cancel(); slots[index] = spare }
                    log("article ${index + 1} came first from the backup request" + if (spare.fromBackupProvider) " (backup provider)" else "")
                    return segment
                }
                if (spare.done) backup = null                      // the backup failed: carry on with the first
            }
            // An article that hasn't come for a while is usually stuck on one slow or half-dead connection: ask for
            // it again at the front of the queue, so a free connection fetches it, instead of waiting out the timeout.
            if (System.nanoTime() - asked > STALL_MS * 1_000_000) {
                asked = System.nanoTime()
                synchronized(lock) { slots.remove(index)?.takeIf { !it.done }?.let { stale ->
                    log("article ${index + 1} of ${segments.size}${if (name.isNotEmpty()) " ($name)" else ""} stalled for ${STALL_MS / 1000} s; asking again"); stale.cancel() } }
            }
            val fetch = synchronized(lock) {
                val known = slots[index]
                if (known != null && !known.cancelled) { if (!known.done) pool.hurry(known); known }
                else pool.fetch(segments[index].messageId, urgent = true).also { slots[index] = it }
            }
            if (!hedged && !fetch.done && System.nanoTime() - started >= hedgeMs * 1_000_000) {
                hedged = true                                      // one backup per wait
                backup = pool.fetchBackup(segments[index].messageId)
            }
            val segment = try { fetch.await(400) } catch (error: IOException) {
                synchronized(lock) { if (slots[index] === fetch) slots.remove(index) }
                throw error
            }
            if (segment != null) { backup?.takeIf { !it.done }?.cancel(); return segment }
            if (System.nanoTime() > deadline) { backup?.cancel(); throw SlowArticleException() }
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; slots.values.forEach { it.cancel() }; slots.clear(); pinned.clear() }
    }

    private companion object { const val AWAIT_MS = 60_000L; const val STALL_MS = 12_000L; const val HEDGE_MS = 3_500L; const val MAX_PINNED = 16; const val START_WINDOW = 2 }
}

/**
 * Reads on this thread are only peeks (seek-preview keyframes): they fetch what they need and a couple of articles
 * after it, but never move or cancel the player's own read-ahead. Set by the data source for each read.
 */
object PeekReads {
    private val local = ThreadLocal<Boolean>()
    var active: Boolean
        get() = local.get() == true
        set(value) = local.set(value)
}

/** The availability check found articles of this release gone from every provider: choose another link. */
class ReleaseIncompleteException(message: String) : IOException(message)

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
            is MissingArticleException, is ReleaseIncompleteException, is UnsupportedSetException, is NntpRefusedException -> return false
            is SlowArticleException, is java.net.SocketTimeoutException, is java.net.SocketException,
            is java.net.UnknownHostException, is javax.net.ssl.SSLException, is java.io.EOFException -> return true
            // The indexer unreachable (code 0), busy (429) or failing (5xx) while fetching the NZB: try again later.
            is dev.pov.tv.data.ApiException -> return cause.code == 0 || cause.code == 429 || cause.code >= 500
        }
        if (cause.message?.startsWith("The news server answered") == true) return true
        cause = cause.cause
    }
    return false
}

/**
 * Whether [error] only means the work was called off: the player interrupts its loader thread on Stop, on a seek and
 * when another link is picked. That is no reason to give up on a link. A socket timeout is an InterruptedIOException
 * too, but a real failure, so it doesn't count.
 */
fun isCancellation(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is InterruptedException || cause is java.nio.channels.ClosedByInterruptException ||
            (cause is java.io.InterruptedIOException && cause !is java.net.SocketTimeoutException)) return true
        cause = cause.cause
    }
    return Thread.currentThread().isInterrupted
}

/** A link already found unplayable this session: thrown again on every reopen, but never worth a retry. */
class UsenetRefusedException(message: String) : java.io.IOException(message)
