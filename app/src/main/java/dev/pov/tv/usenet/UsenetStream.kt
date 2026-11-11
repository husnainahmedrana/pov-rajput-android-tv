package dev.pov.tv.usenet

import java.io.Closeable
import java.io.IOException

/** Bytes that can be read at any position: one posted file, or a video stored across a set of RAR volumes. */
interface ByteSource : Closeable {
    val size: Long
    /** Copies up to [length] bytes from [position]; -1 at the end. Blocks while the data downloads. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

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
            if (first.begin != 0L || first.fileSize <= 0 || first.size <= 0) throw IOException("The first article doesn't describe the file.")
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

    private fun windowFor(part: Long) = minOf((pool.connections + 2).toLong(), windowBytes / part).toInt().coerceAtLeast(4)

    /** Asks for the first article without waiting for it, so several files can be prepared side by side. */
    fun begin() {
        synchronized(lock) { if (!closed && 0 !in slots) slots[0] = pool.fetch(segments[0].messageId) }
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
            if (assumed && segment.fileSize != size) throw IOException("This part of the set is not the size the others are.")
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
        throw IOException("The file's articles don't line up; position $position can't be found.")
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
        val deadline = System.nanoTime() + AWAIT_MS * 1_000_000
        while (true) {
            if (closed) throw IOException("The stream is closed.")
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
            if (System.nanoTime() > deadline) throw IOException("The news server took too long to send the video.")
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; slots.values.forEach { it.cancel() }; slots.clear() }
    }

    private companion object { const val AWAIT_MS = 45_000L }
}
