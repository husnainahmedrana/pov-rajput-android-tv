package dev.pov.tv.usenet

import dev.pov.tv.L10n
import dev.pov.tv.R

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
    val requestCapacity: Int get() = connections
    /** Queues an article; [urgent] ones (what the player is waiting on) go ahead of read-ahead. */
    fun fetch(messageId: String, urgent: Boolean = false): Fetch
    /** Moves a queued article to the front: the player has reached it before a connection did. */
    fun hurry(fetch: Fetch)
    /**
     * A second copy of an article the player is waiting on: from another provider when there is one (a slow article
     * is often slow on every connection to the same servers, as they fetch it from slower storage), else as [fetch].
     */
    fun fetchBackup(messageId: String): Fetch = fetch(messageId, urgent = true)
}

/** One article being fetched. [await] blocks until it is decoded or has failed. */
class Fetch internal constructor(val messageId: String) {
    private val lock = Object()
    @Volatile private var segment: Segment? = null
    @Volatile private var failure: IOException? = null
    @Volatile internal var cancelled = false
    /** What the player is waiting on (or a backup copy of it): only an idle connection takes it, see [NntpPool.take]. */
    @Volatile internal var urgent = false
    /** The copy came from a backup provider (ProviderChain), not the main one: for the log. */
    @Volatile var fromBackupProvider = false; internal set
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
    private val log: (String) -> Unit = {},
    /** Connections kept open while nothing is downloading (a paused video), instead of closing them all. */
    private val keepOpen: Int = 0
) : ArticleSource {
    /** Workers that have decided to let their connection go but haven't left yet. */
    private var closing = 0
    init { require(connections > 0 && depth in 1..MAX_DEPTH) }
    override val requestCapacity: Int get() = connections * depth
    private val queue = ArrayDeque<Fetch>()
    private val lock = Object()
    @Volatile private var closed = false
    private var workers = 0
    /** How many connections the server has let us have; drops when it refuses one. */
    private var limit = connections
    /**
     * When a connection the server turned away for being one too many (not a wrong login) may be tried again: the
     * slot is often busy only for a moment (the availability check's own connection, a closing one), so the session
     * shouldn't run short of it for good. 0: nothing to win back.
     */
    private var regrowAt = 0L
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
    /** When the first connection of this pool was signed in (System.nanoTime), 0 until then: for the start-time readout. */
    @Volatile var firstConnectedAt = 0L; private set

    /**
     * Signs in up to [count] connections now, before any article is asked for: the NZB is still downloading, and the
     * sign-ins (TLS + login, 0.5–3 s each) are then done by the time the first articles are wanted. Each worker signs
     * in and waits for work; unused ones close after the usual idle time.
     */
    fun prestart(count: Int) {
        synchronized(lock) {
            if (closed) return
            val target = minOf(count, limit)
            while (workers < target) {
                workers++
                Thread({ work() }, "nntp-${workers}").apply { isDaemon = true }.start()
            }
        }
    }

    override fun fetch(messageId: String, urgent: Boolean): Fetch {
        val fetch = Fetch(messageId)
        enqueue(fetch, urgent)
        return fetch
    }

    override fun hurry(fetch: Fetch) {
        synchronized(lock) { if (queue.remove(fetch)) { fetch.urgent = true; queue.addFirst(fetch); lock.notify() } }
    }

    private fun enqueue(fetch: Fetch, urgent: Boolean) {
        synchronized(lock) {
            if (closed) { fetch.fail(IOException(L10n.tr(R.string.t_the_news_connection_is_closed, "The news connection is closed."))); return }
            if (limit == 0) { fetch.fail(refusal ?: IOException(L10n.tr(R.string.t_the_news_server_can_t_be, "The news server can't be reached."))); return }
            val now = System.nanoTime() / 1_000_000
            if (regrowAt in 1..now && limit < connections) { limit++; regrowAt = if (limit < connections) now + REGROW_MS else 0 }
            if (urgent) { fetch.urgent = true; queue.addFirst(fetch) } else queue.addLast(fetch)
            if (idle == 0 && workers < limit) {
                workers++
                Thread({ work() }, "nntp-${workers}").apply { isDaemon = true }.start()
            } else lock.notify()
        }
    }

    /** Waits for work; null when the pool closes or this worker has been idle long enough to let its connection go. */
    private fun take(block: Boolean): Fetch? {
        synchronized(lock) {
            var deadline = System.nanoTime() + idleCloseMs * 1_000_000
            while (true) {
                if (closed) return null
                while (true) {
                    // Priority lane: what the player waits on goes only to a connection with nothing in flight, so it
                    // never queues behind other articles in a pipeline (5-10 s each on a slow line). Busy connections
                    // stop topping up meanwhile, so the next one free takes it.
                    val front = queue.peekFirst() ?: break
                    if (front.cancelled) { queue.pollFirst(); continue }
                    if (!block && front.urgent) return null
                    return queue.pollFirst()
                }
                if (!block) return null
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) {
                    // The last [keepOpen] connections stay through a pause, so playing again needs no new sign-in.
                    if (workers - closing <= keepOpen) { deadline = System.nanoTime() + idleCloseMs * 1_000_000; continue }
                    closing++
                    return null
                }
                idle++
                try { lock.wait(left) } finally { idle-- }
            }
        }
    }

    private fun work() {
        var connection: NntpConnection? = null
        // Concurrent: a cancel on another thread (the player seeking) looks at it, see [abandon].
        val flying = java.util.concurrent.ConcurrentLinkedDeque<Fetch>()
        // The signed-in connection, readable from other threads, and whether it is being let go on purpose.
        val signedIn = java.util.concurrent.atomic.AtomicReference<NntpConnection?>()
        val abandoning = java.util.concurrent.atomic.AtomicBoolean(false)
        // Everything this connection is fetching has been called off (a seek, a closed stream, a backup copy that lost):
        // close it now instead of downloading articles nobody wants (5–10 s each on a slow line); it signs in again in
        // about a second and takes the articles that are wanted.
        // Except one past halfway through its only article: finishing that costs less than signing in again, and on a
        // seek every connection was let go at once, so the download crawled for seconds after each jump (2026-11-28).
        val abandon = {
            val busy = signedIn.get()
            val nearlyDone = busy != null && flying.size == 1 && busy.lastBody > 0 && busy.bodyRead * 2 >= busy.lastBody
            if (busy != null && !nearlyDone && !flying.isEmpty() && flying.all { it.cancelled } && abandoning.compareAndSet(false, true)) busy.close()
        }
        var failures = 0
        // Diagnostics: which connection an article was on and how long it took, to tell a slow connection from a slow line.
        val number = serial.incrementAndGet()
        var kind = ""
        var signedInAt = 0L
        try {
            while (!closed) {
                // Sign in BEFORE taking an article. Taking first let a worker stuck in a slow sign-in (a bad route
                // can take 10–15 s for one TLS handshake) hold the very first articles a video needs, while
                // connections that were already signed in sat idle; now whichever connection is ready first gets them.
                if (connection == null) {
                    try {
                        // A connection signed in ahead of time (see WarmConnections) skips the 1–3 s sign-in.
                        val warm = WarmConnections.take(server)
                        val fresh = warm ?: NntpConnection(server, open, count)
                        fresh.received = count
                        sockets += fresh
                        val signInStarted = System.nanoTime()
                        if (warm == null) try { fresh.connect() } catch (error: IOException) { fresh.close(); sockets -= fresh; throw error }
                        if (closed) { fresh.close(); sockets -= fresh; return }
                        connection = fresh
                        signedIn.set(fresh); abandoning.set(false)
                        signedInAt = System.nanoTime()
                        kind = if (warm != null) "warm (signed in ${WarmConnections.ageSeconds(warm)} s before)" else "fresh"
                        log("connection $number ${if (warm != null) "reused $kind" else "signed in in ${(signedInAt - signInStarted) / 1_000_000} ms"}")
                        if (live.incrementAndGet() == 1 && firstConnectedAt == 0L) firstConnectedAt = System.nanoTime()
                        failures = 0
                    } catch (error: IOException) {
                        if (closed) return
                        if (error is NntpRefusedException) {
                            // Wrong login or the account's connection limit: this worker steps aside for good. The
                            // others carry on; when it was the last one, what is queued fails with the reason.
                            refusal = error
                            log("connection refused: ${error.message}")
                            // Two workers refused at once both see workers > 1: a limit of 0 also means none is left.
                            val last = synchronized(lock) {
                                limit = maxOf(limit - 1, 0)
                                // A wrong login stays refused; "too many connections" is tried again later.
                                if (error.code != 481 && error.code != 482 && limit > 0) regrowAt = System.nanoTime() / 1_000_000 + REGROW_MS
                                workers == 1 || limit == 0
                            }
                            if (last) failAll(error)
                            return
                        }
                        if (++failures >= 4) {
                            // No route to the server just now. With no connection working at all, the article at the
                            // front fails (the player shows why); a later request tries again.
                            log("connection failed: ${error.message}")
                            if (live.get() == 0) synchronized(lock) { queue.pollFirst() }?.fail(error)
                            return
                        }
                        try { Thread.sleep(500L * failures) } catch (_: InterruptedException) { return }
                        continue
                    }
                }
                // Top up the requests in flight; block for work only when nothing is in flight.
                var wrote = false
                while (flying.size < depth) {
                    val next = take(block = flying.isEmpty()) ?: break
                    // A copy of an article this connection is already fetching (a backup request) belongs on another
                    // connection: here it would only wait behind the first one.
                    if (flying.any { it.messageId == next.messageId }) {
                        synchronized(lock) { queue.addFirst(next); lock.notify() }
                        break
                    }
                    checkNotNull(connection).send("BODY <${next.messageId}>")
                    flying.addLast(next); wrote = true
                    next.onCancel = abandon
                }
                if (flying.isEmpty()) break                               // idle for long enough, or closed
                val current = checkNotNull(connection)
                try {
                    if (wrote) current.flush()
                    val (code, text) = current.status()
                    val fetch = flying.removeFirst()
                    when (code) {
                        222 -> if (fetch.cancelled) {
                            current.skipBody()
                        } else {
                            val segment = try { current.body() }
                                catch (error: BadArticleException) { fetch.fail(error); continue }   // read whole: the connection is fine
                                catch (error: IOException) { flying.addFirst(fetch); throw error }
                            if (!segment.crcOk) {
                                if (fetch.attempts++ < 1) {
                                    log("article failed its checksum; fetching again")
                                    enqueue(fetch, urgent = true)
                                } else fetch.fail(IOException(L10n.tr(R.string.t_the_article_failed_its_checksum_after, "The article failed its checksum after retrying.")))
                            } else {
                                bytes.addAndGet(segment.size.toLong()); fetch.complete(segment)
                            }
                        }
                        430, 423, 420 -> fetch.fail(MissingArticleException(fetch.messageId))
                        else -> { flying.addFirst(fetch); throw IOException("The news server answered $code $text") }
                    }
                } catch (error: IOException) {
                    // A dropped or confused connection: reopen it and ask again for everything that was in flight.
                    current.close(); sockets -= current; connection = null; signedIn.set(null); live.decrementAndGet()
                    if (closed) break
                    val deliberate = abandoning.getAndSet(false)
                    if (deliberate) log("connection $number let go: its articles were no longer wanted; signing in again")
                    else log("connection dropped (${error.javaClass.simpleName}: ${error.message}); ${flying.size} to re-request")
                    while (true) {
                        val fetch = flying.pollLast() ?: break
                        if (fetch.cancelled) continue
                        // A connection let go on purpose (a seek) isn't the article's fault: it doesn't use up a retry.
                        if (!deliberate && fetch.attempts++ >= 3) fetch.fail(error) else enqueue(fetch, urgent = true)
                    }
                    if (!deliberate && ++failures >= 6) return
                    // Let go on purpose: give the server a moment to count the old connection as closed before signing in
                    // again, or an account used up to its connection limit is refused one for a while.
                    if (deliberate) try { Thread.sleep(LET_GO_RECONNECT_DELAY_MS) } catch (_: InterruptedException) { return }
                }
            }
        } finally {
            if (connection != null) { connection.close(); sockets -= connection; live.decrementAndGet() }
            while (true) { val fetch = flying.pollFirst() ?: break; if (closed) fetch.fail(IOException(L10n.tr(R.string.t_the_news_connection_is_closed, "The news connection is closed."))) else enqueue(fetch, urgent = true) }
            synchronized(lock) {
                workers--
                if (closing > 0) closing--
                // Work that arrived while this worker was on its way out still needs a connection.
                if (!closed && queue.isNotEmpty() && idle == 0 && workers < limit) {
                    workers++
                    Thread({ work() }, "nntp-r").apply { isDaemon = true }.start()
                }
            }
        }
    }

    companion object {
        /** Most requests one connection keeps in flight (Settings → Usenet → provider → Pipeline depth). */
        const val MAX_DEPTH = 20
        private const val REGROW_MS = 60_000L
        /** Pause between letting a connection go on purpose and signing it in again. */
        private const val LET_GO_RECONNECT_DELAY_MS = 300L
        private val serial = AtomicInteger()
    }

    private fun failAll(error: IOException) {
        synchronized(lock) { while (true) { val fetch = queue.pollFirst() ?: break; fetch.fail(error) } }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            while (true) { val fetch = queue.pollFirst() ?: break; fetch.fail(IOException(L10n.tr(R.string.t_the_news_connection_is_closed, "The news connection is closed."))) }
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
    override val requestCapacity: Int get() = pools.maxOf { it.requestCapacity }
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
                segment != null -> { current.remove(outer); if (index > 0) { rescued.incrementAndGet(); outer.fromBackupProvider = true }; outer.complete(segment) }
                index + 1 < pools.size && !outer.cancelled -> ask(outer, index + 1, urgent = true)
                else -> { current.remove(outer); outer.fail(ended.error ?: IOException(L10n.tr(R.string.t_the_article_couldn_t_be_fetched, "The article couldn't be fetched."))) }
            }
        }
    }

    override fun hurry(fetch: Fetch) { current[fetch]?.let { (pool, inner) -> pool.hurry(inner) } }
    override fun close() { pools.forEach { it.close() } }

    /** Asked of the first backup provider (then any after it); the main provider is still fetching the first copy. */
    override fun fetchBackup(messageId: String): Fetch {
        if (pools.size < 2) return fetch(messageId, urgent = true)
        val outer = Fetch(messageId)
        outer.onCancel = { current.remove(outer)?.second?.cancel() }
        ask(outer, 1, urgent = true)
        return outer
    }

    /**
     * Signs in [count] connections to the main provider now (see [NntpPool.prestart]), and a couple to the first
     * backup provider, so a backup request for a slow article doesn't wait for a sign-in.
     */
    fun prestart(count: Int) {
        pools[0].prestart(count)
        if (pools.size > 1) pools[1].prestart(minOf(BACKUP_PRESTART, pools[1].connections))
    }
    private companion object { const val BACKUP_PRESTART = 2 }
    /** When the main provider's first connection was signed in, 0 until then. */
    val firstConnectedAt: Long get() = pools[0].firstConnectedAt
}

/**
 * News connections signed in before they are needed: when a link list with Usenet links appears, a couple of
 * connections to the main provider are opened, so pressing play skips the sign-in (TLS + login, 1–3 s from far away).
 * Unused ones are closed after [KEEP_MS] so they don't hold the account's connection slots.
 */
object WarmConnections {
    private class Held(val key: String, val connection: NntpConnection, val since: Long)
    private val held = ArrayList<Held>()
    private val opening = HashMap<String, Int>()
    private const val KEEP_MS = 120_000L
    private fun key(server: NntpServer) = "${server.host}:${server.port}:${server.tls}:${server.user}"
    private fun now() = System.nanoTime() / 1_000_000

    fun warm(server: NntpServer, count: Int, log: (String) -> Unit = {}) {
        if (count <= 0) return
        val key = key(server)
        val need = synchronized(this) {
            expire()
            (count - held.count { it.key == key } - (opening[key] ?: 0)).coerceAtLeast(0).also { if (it > 0) opening[key] = (opening[key] ?: 0) + it }
        }
        if (need == 0) return
        log("warming $need connection${if (need == 1) "" else "s"} to ${server.host}")
        repeat(need) {
            Thread({
                try {
                    val connection = NntpConnection(server)
                    try { connection.connect() } catch (error: IOException) { connection.close(); throw error }
                    synchronized(this) { held += Held(key, connection, now()); signedIn[connection] = now() }
                } catch (_: Exception) {
                    // No warm connection then: the pool signs in as usual.
                } finally { synchronized(this) { opening[key] = ((opening[key] ?: 1) - 1).coerceAtLeast(0) } }
            }, "nntp-warm").apply { isDaemon = true }.start()
        }
        // Let unused ones go after a while even if nothing else touches this.
        Thread({ try { Thread.sleep(KEEP_MS + 1_000) } catch (_: InterruptedException) {}; synchronized(this) { expire() } }, "nntp-warm-expiry")
            .apply { isDaemon = true }.start()
    }

    /** How long ago [connection] was signed in, for the log; 0 when it isn't (or no longer) known here. */
    private val signedIn = java.util.WeakHashMap<NntpConnection, Long>()
    internal fun ageSeconds(connection: NntpConnection): Long = synchronized(this) { signedIn[connection]?.let { (now() - it) / 1000 } ?: 0 }

    internal fun take(server: NntpServer): NntpConnection? = synchronized(this) {
        expire()
        val key = key(server)
        val index = held.indexOfFirst { it.key == key }
        if (index < 0) null else held.removeAt(index).connection
    }

    private fun expire() {
        val now = now()
        held.removeAll { (now - it.since > KEEP_MS).also { old -> if (old) it.connection.close() } }
    }
}
