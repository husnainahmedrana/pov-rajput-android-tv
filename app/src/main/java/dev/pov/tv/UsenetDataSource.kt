package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import dev.pov.tv.data.Easynews
import dev.pov.tv.data.EasynewsApi
import dev.pov.tv.data.EasynewsNzb
import dev.pov.tv.data.NewsProviders
import dev.pov.tv.data.Newznab
import dev.pov.tv.data.UsenetHeaders
import dev.pov.tv.data.UsenetLinks
import dev.pov.tv.data.SecureStore
import dev.pov.tv.usenet.NntpPool
import dev.pov.tv.usenet.NntpServer
import dev.pov.tv.usenet.Nzb
import dev.pov.tv.usenet.ProviderChain
import dev.pov.tv.usenet.ByteSource
import dev.pov.tv.usenet.UnsupportedSetException
import dev.pov.tv.usenet.UsenetMedia
import java.io.IOException

/** The video now streaming from a news server: its connections and read-ahead outlive the player's seeks and reopens. */
internal object UsenetSessions {
    class Session(val key: String, val pool: java.io.Closeable, val source: ByteSource)

    private var current: Session? = null
    /** Links that can't stream this way (no NZB, a failure mid-play), with the reason: Easynews ones use the web servers instead. */
    private val refused = LinkedHashMap<String, String>()

    /** [create] returns null (the link can't stream this way) or throws; either way the link is not tried again this session. */
    @Synchronized fun get(key: String, create: () -> Session?): Session? {
        current?.let { if (it.key == key) return it }
        if (key in refused) return null
        closeCurrent()
        val made = try { create() } catch (error: Exception) { refuse(key, error.message.orEmpty()); throw error }
        if (made == null) refuse(key, "") else current = made
        return made
    }

    @Synchronized fun reason(key: String): String? = refused[key]

    /** A read failed: this video is not streamed this way again for the rest of this app session. */
    @Synchronized fun giveUp(key: String, why: String) {
        refuse(key, why)
        if (current?.key == key) closeCurrent()
    }

    /** Lets the connections go without making the caller (the main thread, on Stop) wait for a stream that is still opening. */
    fun close() { Thread({ synchronized(this) { closeCurrent() } }, "nntp-close").start() }

    private fun refuse(key: String, why: String) { if (refused.size > 500) refused.clear(); refused[key] = why }
    private fun closeCurrent() { current?.let { it.source.close(); it.pool.close() }; current = null }
}

/** Indexer links: the NZB is fetched when the video starts, then streamed from the viewer's news providers. */
internal object UsenetPlayback {
    const val TAG = "PovUsenet"

    /** The stream for [address]: null when it isn't a Usenet link (or that way is off), an exception when it is one that can't play. */
    fun session(address: String, store: SecureStore, cache: java.io.File?, notify: (String) -> Unit): UsenetSessions.Session? {
        if (!UsenetLinks.isNzb(address)) return EasynewsNntp.session(address, store, notify)
        val key = sha1(address)
        UsenetSessions.reason(key)?.let { throw IOException(it.ifBlank { "This Usenet link couldn't be streamed." }) }
        val made = try {
            UsenetSessions.get(key) {
                val providers = NewsProviders(store).active()
                if (providers.isEmpty()) throw IOException("Add a news provider in Settings → Sources → Usenet to play Usenet links.")
                val text = nzbText(address, key, store, cache)
                val nzb = Nzb.parse(text)
                if (nzb.files.isEmpty()) throw IOException("The indexer sent an empty article list for this link.")
                val chain = ProviderChain(providers.map { provider ->
                    NntpPool(NntpServer(provider.host, provider.port, provider.tls, provider.user, provider.password), provider.connections,
                        log = { android.util.Log.i(TAG, "${provider.name}: $it") })
                })
                try {
                    val opened = UsenetMedia.open(chain, nzb) { android.util.Log.i(TAG, it) }
                    notify("Streaming from ${providers[0].name} · ${providers[0].connections} connections · ${opened.kind}" +
                        if (providers.size > 1) " · ${providers.size - 1} backup" else "")
                    UsenetSessions.Session(key, chain, opened.source)
                } catch (error: Exception) { chain.close(); throw error }
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Usenet link couldn't start", error)
            val reason = when (error) {
                is dev.pov.tv.usenet.MissingArticleException -> "Your news providers no longer have this release. Choose another link."
                is dev.pov.tv.usenet.NntpRefusedException -> "News provider: ${error.message}"
                else -> error.message ?: "This Usenet link couldn't be streamed."
            }
            notify(reason)
            throw IOException(reason, error)
        }
        return made ?: throw IOException("This Usenet link couldn't be streamed.")
    }

    /** The NZB, from this device's small cache when it was played before (so resuming costs no grab), else from the indexer. */
    private fun nzbText(address: String, key: String, store: SecureStore, cache: java.io.File?): String {
        val file = cache?.let { java.io.File(it, "nzb").apply { mkdirs() } }?.let { java.io.File(it, "$key.nzb.gz") }
        if (file != null && file.isFile && System.currentTimeMillis() - file.lastModified() < KEEP_MS) {
            try {
                val saved = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
                android.util.Log.i(TAG, "article list from this device's cache")
                return saved
            } catch (_: Exception) { file.delete() }
        }
        val text = Newznab.fetch(UsenetLinks.nzbAddress(address), UsenetHeaders.grab({ store.get(it) }), 24 * 1024 * 1024)
        if ("<segment" !in text) throw IOException(
            if ("<error" in text.take(600)) "The indexer refused this download. Its daily grab limit may be used up."
            else "The indexer didn't send an article list for this link.")
        if (file != null) try {
            java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
        } catch (_: Exception) {}
        return text
    }

    private fun sha1(value: String) = java.security.MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    private const val KEEP_FILES = 40
}

/** Easynews over its news servers (NNTP) instead of its web servers: experimental, switched on in Easynews settings. */
internal object EasynewsNntp {
    const val ON = "easynews.nntp"
    const val CONNECTIONS = "easynews.nntp.connections"
    const val DEFAULT_CONNECTIONS = 20
    /** Easynews allows 60 at once across every device and app on the account. */
    const val MAX_CONNECTIONS = 60
    const val TAG = "PovUsenet"

    fun enabled(store: SecureStore) = store.get(ON) == "true"
    fun connections(store: SecureStore) = store.get(CONNECTIONS).toIntOrNull()?.coerceIn(1, MAX_CONNECTIONS) ?: DEFAULT_CONNECTIONS
    fun server(user: String, password: String) = NntpServer("news.easynews.com", 563, true, user, password)

    /** The news-server stream for an Easynews link, or null (with the reason shown) when it must use the web servers. */
    fun session(address: String, store: SecureStore, notify: (String) -> Unit): UsenetSessions.Session? {
        if (!enabled(store)) return null
        val token = EasynewsNzb.token(address) ?: return null
        val (user, password) = Easynews.credentials(store) ?: return null
        val key = token.hash + token.ext
        return UsenetSessions.get(key) {
            var pool: NntpPool? = null
            try {
                val nzb = EasynewsNzb.fetch(token, Easynews.auth(user, password))?.let(Nzb::parse)
                if (nzb == null || nzb.files.isEmpty()) { notify("NNTP: Easynews has no article list for this link · using web"); null }
                else {
                    val count = connections(store)
                    val made = NntpPool(server(user, password), count, log = { android.util.Log.i(TAG, it) })
                    pool = made
                    val opened = UsenetMedia.open(made, nzb) { android.util.Log.i(TAG, it) }
                    notify("Streaming over NNTP · $count connections · ${opened.kind}")
                    UsenetSessions.Session(key, made, opened.source)
                }
            } catch (error: Exception) {
                pool?.close()
                android.util.Log.w(TAG, "NNTP stream couldn't start", error)
                notify(if (error is UnsupportedSetException) "NNTP: ${error.message} · using web" else "NNTP didn't start (${error.message?.take(70)}) · using web")
                null
            }
        }
    }

    /**
     * Measures raw download-and-decode speed from the news server at several connection counts, with no player
     * involved. [report] gets one line per step; lines also go to logcat under [TAG].
     */
    fun speedTest(store: SecureStore, report: (String) -> Unit) {
        fun say(line: String) { android.util.Log.i(TAG, "speedtest: $line"); report(line) }
        if (!testing.compareAndSet(false, true)) { report("A speed test is already running. Wait for it to finish."); return }
        try { runSpeedTest(store, ::say) } finally { testing.set(false) }
    }

    private val testing = java.util.concurrent.atomic.AtomicBoolean()

    private fun runSpeedTest(store: SecureStore, say: (String) -> Unit) {
        val (user, password) = Easynews.credentials(store) ?: run { say("Connect Easynews first."); return }
        val auth = Easynews.auth(user, password)
        say("Finding a test video…")
        // Any articles will do for a download test, so a multi-part set counts too: all its parts' articles are used.
        val articles = try {
            // The smallest that is big enough: its article list is small, so it downloads in a moment.
            EasynewsApi(store).sample("1080p bluray").filter { (it.sizeBytes ?: 0) >= 3_000_000_000L }.sortedBy { it.sizeBytes }.asSequence().take(6)
                .mapNotNull { item -> EasynewsNzb.token(item.directUrl)?.let { EasynewsNzb.fetch(it, auth) }?.let(Nzb::parse)?.files?.flatMap { it.segments } }
                .firstOrNull { it.size >= 3000 }
        } catch (error: Exception) { say("Search failed: ${error.message}"); return }
        if (articles == null) { say("No test video found. Try again."); return }
        say("Test video: %d articles, about %.1f GB".format(articles.size, articles.sumOf { it.bytes } / 1e9))
        var next = 40
        var allowed = MAX_CONNECTIONS
        for ((wanted, depth) in listOf(1 to 1, 8 to 1, 16 to 1, 30 to 1, 50 to 1, 16 to 3)) {
            val count = minOf(wanted, allowed)
            if (count < wanted && depth == 1) { say("$wanted connections: skipped (server allowed $allowed)"); continue }
            val pool = NntpPool(server(user, password), count, depth, log = { android.util.Log.i(TAG, it) })
            try {
                // Open every connection first, so the measurement is of steady downloading only.
                val opened = System.nanoTime()
                val warm = (0 until count).map { pool.fetch(articles[next++ % articles.size].messageId) }
                val limit = System.nanoTime() + 25_000_000_000L
                while (warm.any { !it.done } && System.nanoTime() < limit) Thread.sleep(50)
                val connected = pool.connected
                val openSeconds = (System.nanoTime() - opened) / 1e9
                pool.refusal?.let { say("  server said: ${it.message?.take(90)}") }
                if (connected == 0) { say("$count connections: none opened"); break }
                if (connected < count) allowed = connected
                val flying = ArrayDeque<dev.pov.tv.usenet.Fetch>()
                val startBytes = pool.received.get(); val startCpu = android.os.Process.getElapsedCpuTime(); val start = System.nanoTime()
                var failed = 0
                while (System.nanoTime() - start < 10_000_000_000L) {
                    flying.removeAll { fetch -> if (!fetch.done) false else { if (fetch.result == null) failed++; true } }
                    while (flying.size < connected * depth + 2) flying += pool.fetch(articles[next++ % articles.size].messageId)
                    Thread.sleep(20)
                }
                val seconds = (System.nanoTime() - start) / 1e9
                val mbps = (pool.received.get() - startBytes) * 8 / 1e6 / seconds
                val cpu = (android.os.Process.getElapsedCpuTime() - startCpu) / 10.0 / seconds
                say("%d connections%s: %.0f Mbps · %.1f per connection · CPU %.0f%% of one core · opened %d in %.1f s%s".format(
                    wanted, if (depth > 1) " (pipelined ×$depth)" else "", mbps, mbps / connected, cpu, connected, openSeconds,
                    if (failed > 0) " · $failed failed" else ""))
            } catch (error: Exception) {
                say("$wanted connections: failed (${error.message?.take(80)})")
            } finally { pool.close() }
            Thread.sleep(2500)                                             // let the server free the slots
        }
        say("Done.")
    }
}

/** Reads a Usenet [ByteSource] for the player. Opening at a new position is cheap: the session keeps its connections. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class UsenetDataSource(private val session: UsenetSessions.Session) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var spec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri; spec = dataSpec
        transferInitializing(dataSpec)
        val size = session.source.size
        if (dataSpec.position > size) throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = try { session.source.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) }
        catch (error: IOException) {
            // The player's quiet retry reopens this link; by then it is marked for the web servers.
            android.util.Log.w(EasynewsNntp.TAG, "NNTP read failed at $position: ${error.message}")
            UsenetSessions.giveUp(session.key, if (error is dev.pov.tv.usenet.MissingArticleException) "Your news providers are missing part of this release. Choose another link." else "")
            throw error
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count; remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}
