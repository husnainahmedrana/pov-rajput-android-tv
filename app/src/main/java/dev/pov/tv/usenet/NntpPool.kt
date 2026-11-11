package dev.pov.tv.usenet

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where articles come from: one news server's connections, or several servers tried in turn. */
interface ArticleSource : Closeable {
    /** How many articles can download at once, which sizes the read-ahead. */
    val connections: Int
    /** Queues an article; [urgent] ones (what the player is waiting on) go ahead of read-ahead. */
    fun fetch(messageId: String, urgent: Boolean = false): Fetch
    /** Moves a queued article to the front: the player has reached it before a connection did. */
    fun hurry(fetch: Fetch)
}

/** One article being fetched. [await] blocks until it is decoded or has failed. */
class Fetch internal constructor(val messageId: String) {
    private val lock = Object()
    @Volatile private var segment: Segment? = null
    @Volatile private var failure: IOException? = null
    @Volatile internal var cancelled = false
    internal var attempts = 0
    private var listener: ((Fetch) -> Unit)? = null
    internal var onCancel: (() -> Unit)? = null

    val done: Boolean get() = segment != null || failure != null
    val result: Segment? get() = segment
    internal val error: IOException? get() = failure

    internal fun complete(value: Segment) { finish { segment = value } }
    internal fun fail(error: IOException) { finish { failure = error } }
    private fun finish(set: () -> Unit) {
        val told = synchronized(lock) { if (done) return; set(); lock.notifyAll(); listener.also { listener = null } }
        told?.invoke(this)
    }
    /** Calls [block] once when this fetch ends (at once when it already has), on whichever thread ended it. */
    internal fun listen(block: (Fetch) -> Unit) {
        synchronized(lock) { if (!done) { listener = block; return } }
        block(this)
    }
    fun cancel() { cancelled = true; onCancel?.invoke() }

    /** The decoded article, or its failure; null when [timeoutMs] passes first. */
    fun await(timeoutMs: Long): Segment? {
        synchronized(lock) {
            if (segment == null && failure == null) lock.wait(timeoutMs)
            failure?.let { throw it }
            return segment
        }
    }
}

/**
 * Up to [connections] connections to one news server, each a worker thread that takes articles from a shared queue.
 * A worker keeps up to [depth] requests in flight on its connection (pipelining). Connections open when there is work
 * and close after sitting idle, so a paused video doesn't hold the account's connection slots.
 */
class NntpPool(
    private val server: NntpServer,
    override val connections: Int,
    private val depth: Int = 1,
    private val open: (NntpServer) -> Socket = NntpConnection.Companion::openSocket,
    private val idleCloseMs: Long = 45_000,
    private val log: (String) -> Unit = {}
) : ArticleSource {
    private val queue = ArrayDeque<Fetch>()
    private val lock = Object()
    @Volatile private var closed = false
    private var workers = 0
    /** How many connections the server has let us have; drops when it refuses one. */
    private var limit = connections
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<NntpConnection>()
    private var idle = 0
    private val live = AtomicInteger()
    /** Decoded bytes delivered, for speed readouts. */
    val bytes = AtomicLong()
    /** Bytes as they arrive from the server (encoded, about 2% over the decoded size): a live meter. */
    val received = AtomicLong()
    private val count: (Int) -> Unit = { received.addAndGet(it.toLong()) }
    /** Why the server last refused a connection (wrong password, too many connections), if it did. */
    @Volatile var refusal: NntpRefusedException? = null; private set
    /** Connections now signed in. */
    val connected: Int get() = live.get()

    override fun fetch(messageId: String, urgent: Boolean): Fetch {
        val fetch = Fetch(messageId)
        enqueue(fetch, urgent)
        return fetch
    }

    override fun hurry(fetch: Fetch) {
        synchronized(lock) { if (queue.remove(fetch)) { queue.addFirst(fetch); lock.notify() } }
    }

    private fun enqueue(fetch: Fetch, urgent: Boolean) {
        synchronized(lock) {
            if (closed) { fetch.fail(IOException("The news connection is closed.")); return }
            if (limit == 0) { fetch.fail(refusal ?: IOException("The news server can't be reached.")); return }
            if (urgent) queue.addFirst(fetch) else queue.addLast(fetch)
            if (idle == 0 && workers < limit) {
                workers++
                Thread({ work() }, "nntp-${workers}").apply { isDaemon = true }.start()
            } else lock.notify()
        }
    }

    /** Waits for work; null when the pool closes or this worker has been idle long enough to let its connection go. */
    private fun take(block: Boolean): Fetch? {
        synchronized(lock) {
            val deadline = System.nanoTime() + idleCloseMs * 1_000_000
            while (true) {
                if (closed) return null
                while (true) {
                    val next = queue.pollFirst() ?: break
                    if (!next.cancelled) return next
                }
                if (!block) return null
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                idle++
                try { lock.wait(left) } finally { idle-- }
            }
        }
    }

    private fun work() {
        var connection: NntpConnection? = null
        val flying = ArrayDeque<Fetch>()
        var failures = 0
        try {
            while (!closed) {
                // Top up the requests in flight; block for work only when nothing is in flight.
                var wrote = false
                while (flying.size < depth) {
                    val next = take(block = flying.isEmpty()) ?: break
                    if (connection == null) {
                        try {
                            val fresh = NntpConnection(server, open, count)
                            sockets += fresh
                            try { fresh.connect() } catch (error: IOException) { fresh.close(); sockets -= fresh; throw error }
                            if (closed) { fresh.close(); sockets -= fresh; next.fail(IOException("The news connection is closed.")); return }
                            connection = fresh
                            live.incrementAndGet(); failures = 0
                        } catch (error: IOException) {
                            if (closed) { next.fail(error); return }
                            if (error is NntpRefusedException) {
                                // Wrong login or the account's connection limit: this worker steps aside for good. The
                                // others carry on; when it was the last one, what is queued fails with the reason.
                                refusal = error
                                log("connection refused: ${error.message}")
                                val last = synchronized(lock) { limit = maxOf(limit - 1, 0); workers == 1 }
                                if (last) { next.fail(error); failAll(error) } else enqueue(next, urgent = true)
                                return
                            }
                            if (++failures >= 4) {
                                // No route to the server just now: fail this article; a later one tries again.
                                log("connection failed: ${error.message}")
                                next.fail(error)
                                return
                            }
                            enqueue(next, urgent = true)
                            try { Thread.sleep(500L * failures) } catch (_: InterruptedException) { return }
                            continue
                        }
                    }
                    checkNotNull(connection).send("BODY <${next.messageId}>")
                    flying.addLast(next); wrote = true
                }
                if (flying.isEmpty()) break                               // idle for long enough, or closed
                val current = checkNotNull(connection)
                try {
                    if (wrote) current.flush()
                    val (code, text) = current.status()
                    val fetch = flying.removeFirst()
                    when (code) {
                        222 -> if (fetch.cancelled) current.skipBody() else {
                            val segment = try { current.body() } catch (error: IOException) { flying.addFirst(fetch); throw error }
                            if (!segment.crcOk && fetch.attempts++ < 1) {
                                log("article ${fetch.messageId} failed its checksum; fetching again")
                                enqueue(fetch, urgent = true)
                            } else { bytes.addAndGet(segment.size.toLong()); fetch.complete(segment) }
                        }
                        430, 423, 420 -> fetch.fail(MissingArticleException(fetch.messageId))
                        else -> { flying.addFirst(fetch); throw IOException("The news server answered $code $text") }
                    }
                } catch (error: IOException) {
                    // A dropped or confused connection: reopen it and ask again for everything that was in flight.
                    current.close(); sockets -= current; connection = null; live.decrementAndGet()
                    if (closed) break
                    log("connection dropped (${error.javaClass.simpleName}: ${error.message}); ${flying.size} to re-request")
                    while (true) {
                        val fetch = flying.pollLast() ?: break
                        if (fetch.attempts++ >= 3) fetch.fail(error) else enqueue(fetch, urgent = true)
                    }
                    if (++failures >= 6) return
                }
            }
        } finally {
            if (connection != null) { connection.close(); sockets -= connection; live.decrementAndGet() }
            while (true) { val fetch = flying.pollFirst() ?: break; if (closed) fetch.fail(IOException("The news connection is closed.")) else enqueue(fetch, urgent = true) }
            synchronized(lock) {
                workers--
                // Work that arrived while this worker was on its way out still needs a connection.
                if (!closed && queue.isNotEmpty() && idle == 0 && workers < limit) {
                    workers++
                    Thread({ work() }, "nntp-r").apply { isDaemon = true }.start()
                }
            }
        }
    }

    private fun failAll(error: IOException) {
        synchronized(lock) { while (true) { val fetch = queue.pollFirst() ?: break; fetch.fail(error) } }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            while (true) { val fetch = queue.pollFirst() ?: break; fetch.fail(IOException("The news connection is closed.")) }
            lock.notifyAll()
        }
        // Workers blocked on a read notice only when their socket goes away.
        sockets.forEach { it.close() }
    }
}

/**
 * Several news servers in the viewer's order. An article is asked of the first; when that one doesn't have it (or
 * can't be reached), the next is asked, so a backup provider fills the holes in the main one.
 */
class ProviderChain(private val pools: List<NntpPool>) : ArticleSource {
    init { require(pools.isNotEmpty()) { "Add a news provider first." } }
    override val connections: Int get() = pools[0].connections
    private val current = java.util.concurrent.ConcurrentHashMap<Fetch, Pair<NntpPool, Fetch>>()
    /** Articles a later provider supplied after an earlier one didn't have them. */
    val rescued = AtomicLong()

    override fun fetch(messageId: String, urgent: Boolean): Fetch {
        val outer = Fetch(messageId)
        outer.onCancel = { current.remove(outer)?.second?.cancel() }
        ask(outer, 0, urgent)
        return outer
    }

    private fun ask(outer: Fetch, index: Int, urgent: Boolean) {
        if (outer.cancelled) return
        val pool = pools[index]
        val inner = pool.fetch(outer.messageId, urgent)
        current[outer] = pool to inner
        inner.listen { ended ->
            val segment = ended.result
            when {
                segment != null -> { current.remove(outer); if (index > 0) rescued.incrementAndGet(); outer.complete(segment) }
                index + 1 < pools.size && !outer.cancelled -> ask(outer, index + 1, urgent = true)
                else -> { current.remove(outer); outer.fail(ended.error ?: IOException("The article couldn't be fetched.")) }
            }
        }
    }

    override fun hurry(fetch: Fetch) { current[fetch]?.let { (pool, inner) -> pool.hurry(inner) } }
    override fun close() { pools.forEach { it.close() } }
}
