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
import dev.pov.tv.usenet.Nntp
import dev.pov.tv.usenet.ByteSource
import dev.pov.tv.usenet.UnsupportedSetException
import dev.pov.tv.usenet.UsenetMedia
import java.io.IOException

/**
 * Usenet lines for logcat, also kept in the app's cache (usenet-log.txt, about the last 1 MB) so a failure can still be
 * read after the TV's own short log has moved on. No logins or passwords are ever written here.
 */
internal object UsenetLog {
    @Volatile var file: java.io.File? = null
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
    fun i(text: String) { android.util.Log.i(UsenetPlayback.TAG, text); keep("I", text) }
    fun w(text: String, error: Throwable? = null) {
        android.util.Log.w(UsenetPlayback.TAG, text, error)
        keep("W", if (error == null) text else "$text: ${error.javaClass.simpleName}: ${error.message}" + (error.cause?.let { " (cause ${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }
    @Synchronized private fun keep(level: String, text: String) {
        val target = file ?: return
        try {
            if (target.length() > 512 * 1024) target.renameTo(java.io.File(target.parentFile, "usenet-log.old.txt"))
            target.appendText("${stamp.format(java.util.Date())} $level $text" + "\n")
        } catch (_: Exception) {}
    }
}

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
        // A timeout or dropped connection while opening isn't remembered: pressing play again tries afresh.
        val made = try { create() } catch (error: Exception) { if (!dev.pov.tv.usenet.isPassingFailure(error)) refuse(key, error.message.orEmpty()); throw error }
        if (made == null) refuse(key, "") else current = made
        return made
    }

    @Synchronized fun reason(key: String): String? = refused[key]

    /** Passing read failures per video: a few are retried on the same connections before the link is given up. */
    private val stumbles = HashMap<String, Int>()
    @Synchronized fun stumble(key: String): Int = (stumbles[key] ?: 0).plus(1).also { stumbles[key] = it }

    /** A read failed: this video is not streamed this way again for the rest of this app session. */
    @Synchronized fun giveUp(key: String, why: String) {
        UsenetPlayback.invalidateHealth()
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
    private val healthCache = dev.pov.tv.usenet.HealthCache()
    fun invalidateHealth() = healthCache.clear()

    /** The stream for [address]: null when it isn't a Usenet link (or that way is off), an exception when it is one that can't play. */
    fun session(address: String, store: SecureStore, cache: java.io.File?, notify: (String) -> Unit): UsenetSessions.Session? {
        if (cache != null) UsenetLog.file = java.io.File(cache, "usenet-log.txt")
        if (!UsenetLinks.isNzb(address)) return EasynewsNntp.session(address, store, cache, notify)
        val key = sha1(address)
        // Tried before this session: say why again, every time.
        UsenetSessions.reason(key)?.let { why -> val reason = why.ifBlank { L10n.tr(R.string.t_this_usenet_link_couldn_t_be, "This Usenet link couldn't be streamed.") }; notify(reason); throw IOException(reason) }
        val made = try {
            UsenetSessions.get(key) {
                val providers = NewsProviders(store).active()
                if (providers.isEmpty()) throw IOException(L10n.tr(R.string.t_add_a_news_provider_in_settings, "Add a news provider in Settings → Sources → Usenet to play Usenet links."))
                val text = nzbText(address, key, store, cache)
                val nzb = Nzb.parse(text)
                if (nzb.files.isEmpty()) throw IOException(L10n.tr(R.string.t_the_indexer_sent_an_empty_article, "The indexer sent an empty article list for this link."))
                val healthKey = sha1(key + store.get(NewsProviders.KEY))
                // The availability check runs beside the opening (each takes a few round trips; one after the other
                // doubled the wait before the first picture). Its answer is waited for before playback starts.
                val health: java.util.concurrent.Future<Boolean>? = if (healthCache.contains(healthKey)) {
                    UsenetLog.i("health: reusing a recent successful check"); null
                } else java.util.concurrent.FutureTask { checkHealth(nzb, providers) }.also { Thread(it, "usenet-health").apply { isDaemon = true }.start() }
                fun healthy() {
                    val ok = try { health?.get() ?: return } catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
                    if (ok) healthCache.remember(healthKey)
                }
                val warm = NewsProviders.warm { store.get(it) }
                val chain = ProviderChain(providers.mapIndexed { index, provider ->
                    NntpPool(NntpServer(provider.host, provider.port, provider.tls, provider.user, provider.password), provider.connections, provider.pipelineDepth,
                        log = { UsenetLog.i("${provider.name}: $it") }, keepOpen = if (index == 0) minOf(warm, provider.connections) else 0)
                })
                try {
                    val opened = try { UsenetMedia.open(chain, nzb, cache) { UsenetLog.i(it) } }
                        // A failed opening of a taken-down release: the check's "missing parts" says it better.
                        catch (error: Exception) { healthy(); throw error }
                    try { healthy() } catch (error: Exception) { opened.source.close(); throw error }
                    notify("Streaming from ${providers[0].name} · ${providers[0].connections} connections · pipeline ${providers[0].pipelineDepth} · ${opened.kind}" +
                        if (providers.size > 1) " · ${providers.size - 1} backup" else "")
                    UsenetSessions.Session(key, chain, opened.source)
                } catch (error: Exception) { chain.close(); throw error }
            }
        } catch (error: Exception) {
            UsenetLog.w("Usenet link couldn't start", error)
            val reason = when (error) {
                is dev.pov.tv.usenet.MissingArticleException -> "Your news providers no longer have this release. Choose another link."
                is dev.pov.tv.usenet.NntpRefusedException -> "News provider: ${error.message}"
                else -> error.message ?: "This Usenet link couldn't be streamed."
            }
            notify(reason)
            throw IOException(reason, error)
        }
        return made ?: throw IOException(L10n.tr(R.string.t_this_usenet_link_couldn_t_be, "This Usenet link couldn't be streamed."))
    }

    /**
     * Asks the providers whether a spread of the release's articles still exist (about a second, all asked at once).
     * A release that has been taken down fails here, with the reason, instead of stopping partway through the video.
     */
    private fun checkHealth(nzb: Nzb, providers: List<dev.pov.tv.data.NewsProvider>): Boolean {
        val biggest = nzb.files.maxOf { it.statedBytes }
        // The files that hold the video: skip repair files, text and samples, which are far smaller.
        val segments = nzb.files.filter {
            val name = dev.pov.tv.usenet.UsenetMedia.subjectName(it.subject).orEmpty()
            it.statedBytes * 5 >= biggest && name.substringAfterLast('.').lowercase() !in setOf("par2", "nfo", "sfv")
        }.flatMap { it.segments }
        if (segments.isEmpty()) return false
        val count = minOf(SAMPLE, segments.size)
        val sample = (0 until count).map { segments[(it.toLong() * (segments.size - 1) / maxOf(1, count - 1)).toInt()].messageId }.distinct()
        val result = dev.pov.tv.usenet.ArticleHealth.check(sample, providers.map { provider ->
            { ids: List<String> -> Nntp.missing(NntpServer(provider.host, provider.port, provider.tls, provider.user, provider.password), ids) }
        })
        UsenetLog.i("health: ${result.missing.size} missing, ${result.unknown.size} unknown of ${sample.size} checked")
        if (result.missing.isNotEmpty()) throw IOException(L10n.tr(R.string.t_your_news_providers_are_missing_parts, "Your news providers are missing parts of this release (%1\$s of %2\$s checked). Choose another link.", result.missing.size, sample.size))
        // Unknown checks are inconclusive: actual BODY reads still enforce availability and checksums.
        return result.unknown.isEmpty()
    }

    /** Whether this Usenet link already failed this session (taken down, an archive it can't play…), for the link list. */
    fun failed(address: String): Boolean = UsenetLinks.isNzb(address) && UsenetSessions.reason(sha1(address)) != null

    /** The NZB, from this device's small cache when it was played before (so resuming costs no grab), else from the indexer. */
    private fun nzbText(address: String, key: String, store: SecureStore, cache: java.io.File?): String {
        val file = cache?.let { java.io.File(it, "nzb").apply { mkdirs() } }?.let { java.io.File(it, "$key.nzb.gz") }
        if (file != null && file.isFile && System.currentTimeMillis() - file.lastModified() < KEEP_MS) {
            try {
                val saved = java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
                UsenetLog.i("article list from this device's cache")
                return saved
            } catch (_: Exception) { file.delete() }
        }
        val indexers = dev.pov.tv.data.NewznabIndexers(store)
        val indexer = indexers.forLink(UsenetLinks.nzbAddress(address))
        if (indexer != null && indexers.grabsExhausted(indexer)) throw IOException(
            L10n.tr(R.string.t_x_used_today_s_grabs, "%1\$s has used today's grabs. Pick a link from another indexer.", indexer.name))
        val text = Newznab.fetch(UsenetLinks.nzbAddress(address), UsenetHeaders.grab({ store.get(it) }), 24 * 1024 * 1024)
        if ("<segment" !in text) throw IOException(
            if ("<error" in text.take(600)) L10n.tr(R.string.t_the_indexer_refused_this_download, "The indexer refused this download. Its daily grab limit may be used up.")
            else L10n.tr(R.string.t_the_indexer_didn_t_send_an_article_list, "The indexer didn't send an article list for this link."))
        if (indexer != null) indexers.noteGrab(indexer.id)
        if (file != null) try {
            java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
        } catch (_: Exception) {}
        return text
    }

    private fun sha1(value: String) = java.security.MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    private const val KEEP_FILES = 40
    private const val SAMPLE = 24
}

/** Easynews over its news servers (NNTP) instead of its web servers: "Easynews NNTP streaming" in Easynews settings. */
internal object EasynewsNntp {
    const val ON = "easynews.nntp"
    const val CONNECTIONS = "easynews.nntp.connections"
    const val DEFAULT_CONNECTIONS = 20
    /** Easynews allows 60 at once across every device and app on the account. */
    const val MAX_CONNECTIONS = 60
    const val PIPELINE = "easynews.nntp.pipeline"
    /** Requests kept in flight per connection; 2 matches the news providers' default. */
    const val DEFAULT_PIPELINE = 2
    const val MAX_PIPELINE = NntpPool.MAX_DEPTH
    const val TAG = "PovUsenet"

    fun enabled(store: SecureStore) = store.get(ON) == "true"
    fun connections(store: SecureStore) = store.get(CONNECTIONS).toIntOrNull()?.coerceIn(1, MAX_CONNECTIONS) ?: DEFAULT_CONNECTIONS
    fun pipeline(store: SecureStore) = store.get(PIPELINE).toIntOrNull()?.coerceIn(1, MAX_PIPELINE) ?: DEFAULT_PIPELINE
    fun server(user: String, password: String) = NntpServer("news.easynews.com", 563, true, user, password)

    /** The news-server stream for an Easynews link, or null (with the reason shown) when it must use the web servers. */
    fun session(address: String, store: SecureStore, cache: java.io.File?, notify: (String) -> Unit): UsenetSessions.Session? {
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
                    val count = connections(store); val depth = pipeline(store)
                    val made = NntpPool(server(user, password), count, depth, log = { UsenetLog.i(it) })
                    pool = made
                    val opened = UsenetMedia.open(made, nzb, cache) { UsenetLog.i(it) }
                    notify("Streaming over NNTP · $count connections · pipeline $depth · ${opened.kind}")
                    UsenetSessions.Session(key, made, opened.source)
                }
            } catch (error: Exception) {
                pool?.close()
                UsenetLog.w("NNTP stream couldn't start", error)
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
            val pool = NntpPool(server(user, password), count, depth, log = { UsenetLog.i(it) })
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

/**
 * Notes in the Usenet log where a player read is stuck: a read taking over 15 s gets its thread's stack written down
 * (again at 60 s and 120 s), so a hang shows exactly what it waits for.
 */
internal object ReadWatch {
    private val NL = System.lineSeparator()
    class Watch(val thread: Thread, val position: Long, val since: Long) { @Volatile var reported = 0 }
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Watch>()
    @Volatile private var running = false
    fun start(position: Long): Watch = Watch(Thread.currentThread(), position, System.nanoTime()).also { active += it; ensure() }
    fun done(watch: Watch) { active -= watch }
    private fun ensure() {
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
            Thread({
                while (true) {
                    try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                    val now = System.nanoTime()
                    for (watch in active) {
                        val seconds = (now - watch.since) / 1_000_000_000
                        val due = listOf(15L, 60L, 120L).getOrNull(watch.reported) ?: continue
                        if (seconds < due) continue
                        watch.reported++
                        UsenetLog.w("read at byte ${watch.position} stuck for $seconds s in:" + NL +
                            watch.thread.stackTrace.take(28).joinToString(NL) { "    at $it" })
                    }
                }
            }, "usenet-read-watch").apply { isDaemon = true }.start()
        }
    }
}

/** Reads a Usenet [ByteSource] for the player. Opening at a new position is cheap: the session keeps its connections. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class UsenetDataSource(private val session: UsenetSessions.Session) : BaseDataSource(true) {
    private companion object { const val PASSING_RETRIES = 4 }
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var spec: DataSpec? = null
    private var openedAt = 0L
    private var firstRead = false
    private var readSinceOpen = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri; spec = dataSpec
        transferInitializing(dataSpec)
        val size = session.source.size
        if (dataSpec.position > size) throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        // Where the player reads, and how long the first bytes there take: shows what a slow start waited for.
        openedAt = System.nanoTime(); firstRead = true
        UsenetLog.i("player reads at %.1f%% (byte %d of %d)".format(position * 100.0 / maxOf(1L, size), position, size))
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val watch = ReadWatch.start(position)
        val count = try { session.source.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) }
        catch (error: IOException) {
            ReadWatch.done(watch)
            UsenetLog.w("NNTP read failed at $position: ${error.message}")
            // A timeout or dropped connection: keep the session (its connections and read-ahead) so the player's quiet
            // retry carries on from here. Only a release fault, or the same trouble again and again, ends the link
            // (an Easynews link then moves to the web servers).
            val passing = dev.pov.tv.usenet.isPassingFailure(error)
            if (!passing || UsenetSessions.stumble(session.key) > PASSING_RETRIES)
                UsenetSessions.giveUp(session.key, if (error is dev.pov.tv.usenet.MissingArticleException) "Your news providers are missing part of this release. Choose another link." else "")
            throw error
        }
        ReadWatch.done(watch)
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (firstRead) { firstRead = false; readSinceOpen = 0; UsenetLog.i("  first bytes after ${(System.nanoTime() - openedAt) / 1_000_000} ms") }
        readSinceOpen += count
        position += count; remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false; transferEnded()
            UsenetLog.i("  player stopped reading there after %.1f MB in %d ms".format(readSinceOpen / 1e6, (System.nanoTime() - openedAt) / 1_000_000))
        }
    }
}
