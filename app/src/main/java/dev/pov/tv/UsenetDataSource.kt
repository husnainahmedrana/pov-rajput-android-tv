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
    /**
     * Lines go to one writer thread, so the news connections' workers never wait on flash storage (a burst of
     * connection trouble used to make them take turns opening and closing the file). Bounded: a flood drops lines.
     */
    private val pending = java.util.concurrent.LinkedBlockingQueue<String>(2000)
    private val writer by lazy {
        Thread({
            var out: java.io.Writer? = null
            var target: java.io.File? = null
            while (true) {
                val line = try { pending.take() } catch (_: InterruptedException) { return@Thread }
                try {
                    val now = file ?: continue
                    if (target != now || now.length() > 512 * 1024) {
                        out?.close()
                        if (now.length() > 512 * 1024) now.renameTo(java.io.File(now.parentFile, "usenet-log.old.txt"))
                        target = now; out = java.io.BufferedWriter(java.io.FileWriter(now, true))
                    }
                    out!!.write(line)
                    if (pending.isEmpty()) out.flush()                     // written out once a burst is over
                } catch (_: Exception) { runCatching { out?.close() }; out = null; target = null }
            }
        }, "usenet-log").apply { isDaemon = true; start() }
    }
    private fun keep(level: String, text: String) {
        if (file == null) return
        writer
        pending.offer("${synchronized(stamp) { stamp.format(java.util.Date()) }} $level $text\n")
    }
}

/**
 * The opened video, which the availability check (running beside the start, see [UsenetPlayback]) can still fail: once
 * it finds the release taken down, every read throws that reason instead of the player hitting the hole later.
 */
internal class GuardedSource : ByteSource {
    @Volatile private var inner: ByteSource? = null
    @Volatile var failure: dev.pov.tv.usenet.ReleaseIncompleteException? = null; private set
    val attached: Boolean get() = inner != null
    fun attach(source: ByteSource) { inner = source }
    fun fail(error: dev.pov.tv.usenet.ReleaseIncompleteException) { failure = error }
    private fun source(): ByteSource = inner ?: throw IOException("The stream isn't open yet.")
    override val size: Long get() = source().size
    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        failure?.let { throw dev.pov.tv.usenet.ReleaseIncompleteException(it.message.orEmpty()) }
        return source().read(position, target, offset, length)
    }
    override fun prefetch(position: Long, length: Long) { inner?.prefetch(position, length) }
    override fun close() { inner?.close() }
}

/**
 * How long each step of the last Usenet start took, from pressing play to the first picture. Preview builds show it on
 * screen (BuildConfig.USENET_TIMES); every build writes it to the Usenet log.
 */
internal object UsenetStartTimes {
    @Volatile private var started = 0L
    private val marks = java.util.LinkedHashMap<String, Long>()

    fun begin() { synchronized(marks) { started = System.nanoTime(); marks.clear() } }
    fun cancel() { synchronized(marks) { started = 0L; marks.clear() } }

    /** Notes that [name] finished now, or at [at] (System.nanoTime) when given. A start already reported is left alone. */
    fun mark(name: String, at: Long = 0L) {
        synchronized(marks) { if (started != 0L && name !in marks) marks[name] = if (at > 0) at else System.nanoTime() }
    }

    /** The first picture: the summary of this start (once; null afterwards or when no Usenet start was timed). */
    fun firstPicture(): String? {
        val text = synchronized(marks) {
            val from = started
            if (from == 0L) return null
            started = 0L
            fun seconds(at: Long) = "%.1f".format(java.util.Locale.ROOT, (at - from) / 1e9)
            val steps = marks.entries.sortedBy { it.value }.joinToString(" · ") { "${it.key} ${seconds(it.value)}" }
            "Usenet start ${seconds(System.nanoTime())} s" + if (steps.isEmpty()) " (stream already open)" else "  ($steps)"
        }
        UsenetLog.i(text)
        return text
    }
}

/** The video now streaming from a news server: its connections and read-ahead outlive the player's seeks and reopens. */
internal object UsenetSessions {
    class Session(val key: String, val pool: java.io.Closeable, val source: ByteSource)

    /** One stream opens at a time; only [get] holds this, for the whole (possibly long) opening. */
    private val opening = Any()
    @Volatile private var current: Session? = null
    /**
     * Links that can't stream this way (no NZB, a failure mid-play), with the reason: Easynews ones use the web servers
     * instead. Read without any lock, so the link list and the Streams panel never wait for a stream that is opening.
     */
    private val refused = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** When each refused link's reason was last shown: a reopen right after (the player's own retries) stays quiet. */
    private val told = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [create] returns null (the link can't stream this way) or throws; either way the link is not tried again this session. */
    fun get(key: String, create: () -> Session?): Session? = synchronized(opening) {
        current?.let { if (it.key == key) return it }
        if (refused.containsKey(key)) return null
        closeSession(current)
        stumbles.remove(key)                                         // a fresh session starts with a clean count
        // A timeout, a dropped connection or a cancelled opening (Stop, another link) isn't remembered: pressing play
        // again tries afresh.
        val made = try { create() } catch (error: Exception) {
            if (!dev.pov.tv.usenet.isCancellation(error) && !dev.pov.tv.usenet.isPassingFailure(error)) refuse(key, error.message.orEmpty())
            throw error
        }
        if (made == null) refuse(key, "") else current = made
        made
    }

    fun reason(key: String): String? = refused[key]

    /** True when [key]'s refusal should be shown now: once, then again only after a quiet spell (a deliberate replay). */
    fun shouldTell(key: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = told[key]
        if (last != null && now - last < TELL_AGAIN_MS) return false
        told[key] = now
        return true
    }

    /** Passing read failures per video: a few are retried on the same connections before the link is given up. */
    private val stumbles = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun stumble(key: String): Int = stumbles.merge(key, 1, Int::plus) ?: 1
    /** A long stretch read fine: earlier trouble no longer counts towards giving the link up. */
    fun steady(key: String) { stumbles.remove(key) }

    /** A read failed: this video is not streamed this way again for the rest of this app session. */
    fun giveUp(key: String, why: String) {
        UsenetPlayback.invalidateHealth()
        refuse(key, why)
        current?.takeIf { it.key == key }?.let(::closeSession)
    }

    /**
     * Lets the connections go without making the caller (the main thread, on Stop) wait. Only the stream current right
     * now is closed: a close that runs late must never close the next video's stream. A stream still opening is
     * cancelled by the player itself (its loader is interrupted) and closes its own connections.
     */
    fun close() {
        val target = current ?: return
        Thread({ closeSession(target) }, "nntp-close").start()
    }

    private fun refuse(key: String, why: String) { if (refused.size > 500) refused.clear(); refused[key] = why }
    private fun closeSession(session: Session?) {
        if (session == null) return
        synchronized(this) { if (current === session) current = null }
        session.source.close(); session.pool.close()
    }
    private const val TELL_AGAIN_MS = 30_000L
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
        // Tried before this session: fail at once. The reason is shown once, not on each of the player's own retries
        // (those used to queue a dozen toasts); a deliberate replay later shows it again.
        UsenetSessions.reason(key)?.let { why ->
            val reason = why.ifBlank { L10n.tr(R.string.t_this_usenet_link_couldn_t_be, "This Usenet link couldn't be streamed.") }
            if (UsenetSessions.shouldTell(key)) notify(reason)
            throw dev.pov.tv.usenet.UsenetRefusedException(reason)
        }
        val made = try {
            UsenetSessions.get(key) {
                val providers = NewsProviders(store).active()
                if (providers.isEmpty()) throw IOException(L10n.tr(R.string.t_add_a_news_provider_in_settings, "Add a news provider in Settings → Sources → Usenet to play Usenet links."))
                val warm = NewsProviders.warm { store.get(it) }
                // The connections first: they sign in while the NZB downloads (sign-in is 0.5–3 s, as long as the NZB
                // itself), instead of only once it has been read.
                val chain = ProviderChain(providers.mapIndexed { index, provider ->
                    NntpPool(NntpServer(provider.host, provider.port, provider.tls, provider.user, provider.password), provider.connections, provider.pipelineDepth,
                        log = { UsenetLog.i("${provider.name}: $it") }, keepOpen = if (index == 0) minOf(warm, provider.connections) else 0)
                })
                try {
                    // All of them: one connection to a far server can carry only 1–1.5 Mbit/s, so a video starts on time only
                    // when every connection is downloading from the first second (with 6, the rest signed in after
                    // playback had already caught up with the download and stalled at 0:03).
                    chain.prestart(providers[0].connections)
                    PlaybackStatus.stage(L10n.tr(R.string.t_status_getting_nzb, "Getting the NZB from the indexer"))
                    val text = nzbText(address, key, store, cache)
                    UsenetStartTimes.mark("NZB")
                    val nzb = Nzb.parse(text)
                    if (nzb.files.isEmpty()) throw IOException(L10n.tr(R.string.t_the_indexer_sent_an_empty_article, "The indexer sent an empty article list for this link."))
                    val healthKey = sha1(key + store.get(NewsProviders.KEY))
                    // The availability check runs beside the opening and playback no longer waits for it: it signs in
                    // on its own connection, which made it the slowest step of a start. When it finds the release taken
                    // down, the stream fails with that reason (before or soon after the first picture).
                    val guard = GuardedSource()
                    val health: java.util.concurrent.FutureTask<Unit>? = if (healthCache.contains(healthKey)) {
                        UsenetLog.i("health: reusing a recent successful check"); null
                    } else java.util.concurrent.FutureTask {
                        try {
                            if (checkHealth(nzb, providers)) healthCache.remember(healthKey)
                            UsenetStartTimes.mark("check")
                        } catch (error: dev.pov.tv.usenet.ReleaseIncompleteException) {
                            UsenetLog.w("health check found the release incomplete", error)
                            guard.fail(error)
                            if (guard.attached) UsenetSessions.giveUp(key, error.message.orEmpty())
                        }
                    }.also { Thread(it, "usenet-health").apply { isDaemon = true }.start() }
                    PlaybackStatus.stage(L10n.tr(R.string.t_status_opening_release_x, "Opening the release on %1\$s", providers[0].name))
                    val opened = try { UsenetMedia.open(chain, nzb, cache) { UsenetLog.i(it) } }
                        catch (error: Exception) {
                            // A failed opening of a taken-down release: the check's "missing parts" says it better.
                            if (!dev.pov.tv.usenet.isCancellation(error)) try { health?.get(HEALTH_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                            guard.failure?.let { throw it }
                            throw error
                        }
                    chain.firstConnectedAt.takeIf { it > 0 }?.let { UsenetStartTimes.mark("signed in", it) }
                    UsenetStartTimes.mark("opened")
                    guard.attach(opened.source)
                    guard.failure?.let { opened.source.close(); throw it }
                    PlaybackStatus.stage(L10n.tr(R.string.t_status_first_data, "Downloading the start of the video"))
                    UsenetLog.i("Streaming from ${providers[0].name} · ${providers[0].connections} connections · pipeline ${providers[0].pipelineDepth} · ${opened.kind}" +
                        if (providers.size > 1) " · ${providers.size - 1} backup" else "")
                    UsenetSessions.Session(key, chain, guard)
                } catch (error: Exception) { chain.close(); throw error }
            }
        } catch (error: Exception) {
            // Called off by the player (Stop, a seek, another link): nothing to tell, nothing remembered.
            if (dev.pov.tv.usenet.isCancellation(error)) {
                UsenetLog.i("Usenet link opening cancelled")
                throw (error as? java.io.InterruptedIOException) ?: java.io.InterruptedIOException("cancelled").apply { initCause(error) }
            }
            UsenetLog.w("Usenet link couldn't start", error)
            val reason = when (error) {
                is dev.pov.tv.usenet.MissingArticleException -> "Your news providers no longer have this release. Choose another link."
                is dev.pov.tv.usenet.NntpRefusedException -> "News provider: ${error.message}"
                else -> error.message ?: "This Usenet link couldn't be streamed."
            }
            if (UsenetSessions.shouldTell(key)) notify(reason)
            // Remembered as unplayable: the player must not retry it. A passing failure can be retried as usual.
            if (UsenetSessions.reason(key) != null) throw dev.pov.tv.usenet.UsenetRefusedException(reason).apply { initCause(error) }
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
        if (result.missing.isNotEmpty()) throw dev.pov.tv.usenet.ReleaseIncompleteException(L10n.tr(R.string.t_your_news_providers_are_missing_parts, "Your news providers are missing parts of this release (%1\$s of %2\$s checked). Choose another link.", result.missing.size, sample.size))
        // Unknown checks are inconclusive: actual BODY reads still enforce availability and checksums.
        return result.unknown.isEmpty()
    }

    /** Whether this Usenet link already failed this session (taken down, an archive it can't play…), for the link list. */
    fun failed(address: String): Boolean = UsenetLinks.isNzb(address) && UsenetSessions.reason(sha1(address)) != null

    /** The NZB for an indexer link (device cache first), for TorBox Usenet. Costs one grab when it isn't cached here. */
    internal fun nzbFor(address: String, store: SecureStore, cache: java.io.File?): String = nzbText(address, sha1(address), store, cache)

    /** The NZB only when this device already has it (no grab), for checking TorBox before anything is played. */
    internal fun cachedNzb(address: String, cache: java.io.File?): String? {
        val file = cache?.let { java.io.File(java.io.File(it, "nzb"), "${sha1(address)}.nzb.gz") } ?: return null
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() >= KEEP_MS) return null
        return runCatching { java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    /** The NZB, from this device's small cache when it was played before (so resuming costs no grab), else from the indexer. */
    private const val PREFETCH_KEY = "usenet.prefetch_nzb"
    private const val PREFETCH_MAX_LIMITED_GRABS = 50
    /** [address] is a Usenet link already found unplayable this session (gone, or a set the app can't stream). */
    fun refused(address: String) = UsenetLinks.isNzb(address) && UsenetSessions.reason(sha1(address)) != null
    /** The reason [address] was found unplayable this session, as the error the main player would have had. */
    fun refusal(address: String): Throwable? = if (!UsenetLinks.isNzb(address)) null
        else UsenetSessions.reason(sha1(address))?.let { why -> dev.pov.tv.usenet.UsenetRefusedException(
            why.ifBlank { L10n.tr(R.string.t_this_usenet_link_couldn_t_be, "This Usenet link couldn't be streamed.") }) }
    fun prefetchEnabled(store: SecureStore) = store.get(PREFETCH_KEY) != "false"
    fun setPrefetch(store: SecureStore, on: Boolean) = store.put(PREFETCH_KEY, on.toString())

    /** NZBs being downloaded ahead of play, by link key: pressing play waits for one instead of grabbing it twice. */
    private val prefetching = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
    private val prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "nzb-prefetch").apply { isDaemon = true } }

    /**
     * Downloads [address]'s NZB into this device's cache now, when it is a Usenet link this app streams itself, the
     * setting is on and it isn't cached or known to be dead already. Called for the top link of a link list: pressing
     * play then reads the NZB from the cache (it counts as one grab at the indexer, as playing it would).
     */
    @Volatile private var latestPrefetch = ""
    fun prefetchNzb(address: String, store: SecureStore, cache: java.io.File?) {
        if (cache == null || !UsenetLinks.isNzb(address) || !prefetchEnabled(store)) return
        if (dev.pov.tv.data.UsenetRoute.get(store) != dev.pov.tv.data.UsenetRoute.NEWS) return
        // An indexer with a small daily grab limit isn't spent on lists the viewer only browses.
        dev.pov.tv.data.NewznabIndexers(store).forLink(UsenetLinks.nzbAddress(address))
            ?.takeIf { it.dailyGrabLimit in 1..PREFETCH_MAX_LIMITED_GRABS }?.let { return }
        val key = sha1(address)
        latestPrefetch = key
        if (UsenetSessions.reason(key) != null) return
        val file = java.io.File(java.io.File(cache, "nzb"), "$key.nzb.gz")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < KEEP_MS) return
        prefetching.computeIfAbsent(key) {
            prefetcher.submit {
                // Only the list on screen now: titles browsed past while this one waited aren't grabbed.
                if (latestPrefetch != key) { prefetching.remove(key); return@submit }
                try { nzbText(address, key, store, cache); UsenetLog.i("NZB of the top link downloaded ahead of play") }
                catch (error: Exception) { UsenetLog.i("NZB prefetch failed: ${error.message}") }
                finally { prefetching.remove(key) }
            }
        }
    }

    private fun nzbText(address: String, key: String, store: SecureStore, cache: java.io.File?): String {
        // A prefetch of this very NZB still downloading: wait for it, then read it from the cache.
        if (Thread.currentThread().name != "nzb-prefetch") prefetching[key]?.let { pending -> runCatching { pending.get(30, java.util.concurrent.TimeUnit.SECONDS) } }
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
        fun grab() = UsenetLinks.nzbAddress(address).let { link ->
            // Easynews as an indexer: its article list comes from Easynews with that entry's login, not a plain GET.
            if (dev.pov.tv.data.EasynewsIndexer.isLink(link)) dev.pov.tv.data.EasynewsIndexer.nzb(link, indexer)
            else Newznab.fetch(link, UsenetHeaders.grab({ store.get(it) }), 24 * 1024 * 1024)
        }
        val text = try { grab() } catch (error: dev.pov.tv.data.ApiException) {
            if (error.code != 0 && error.code < 500) throw error
            UsenetLog.i("indexer didn't answer (${error.code}): asking once more")
            Thread.sleep(1_500)
            grab()
        }
        if ("<segment" !in text) throw IOException(
            if ("<error" in text.take(600)) L10n.tr(R.string.t_the_indexer_refused_this_download, "The indexer refused this download. Its daily grab limit may be used up.")
            else L10n.tr(R.string.t_the_indexer_didn_t_send_an_article_list, "The indexer didn't send an article list for this link."))
        if (indexer != null) indexers.noteGrab(indexer.id)
        if (file != null) try {
            // Written as it is encoded: no second full copy of a NZB that can be 24 MB.
            java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
        } catch (_: Exception) {}
        return text
    }

    private fun sha1(value: String) = java.security.MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000
    private const val KEEP_FILES = 40
    private const val SAMPLE = 24
    /** Connections to the main provider signed in while the NZB downloads. */
    /** How long a failed opening waits for the availability check's clearer reason. */
    private const val HEALTH_WAIT_MS = 4_000L
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
                PlaybackStatus.stage(L10n.tr(R.string.t_status_connecting_x, "Connecting to %1\$s", "Easynews"))
                val nzb = EasynewsNzb.fetch(token, Easynews.auth(user, password))?.let(Nzb::parse)
                if (nzb == null || nzb.files.isEmpty()) { UsenetLog.i("NNTP: Easynews has no article list for this link · using web"); null }
                else {
                    val count = connections(store); val depth = pipeline(store)
                    val made = NntpPool(server(user, password), count, depth, log = { UsenetLog.i(it) })
                    pool = made
                    val opened = UsenetMedia.open(made, nzb, cache) { UsenetLog.i(it) }
                    UsenetLog.i("Streaming over NNTP · $count connections · pipeline $depth · ${opened.kind}")
                    UsenetSessions.Session(key, made, opened.source)
                }
            } catch (error: Exception) {
                pool?.close()
                UsenetLog.w("NNTP stream couldn't start", error)
                UsenetLog.i(if (error is UnsupportedSetException) "NNTP: ${error.message} · using web" else "NNTP didn't start (${error.message?.take(70)}) · using web")
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
    private companion object {
        const val PASSING_RETRIES = 4; const val STEADY_BYTES = 64L * 1024 * 1024
        const val SMALL_BUFFER = 64 * 1024; const val SMALL_READ = 4 * 1024
    }
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var spec: DataSpec? = null
    private var openedAt = 0L
    private var firstRead = false
    private var readSinceOpen = 0L
    /**
     * The player's extractor reads an MKV's seek index (hundreds of thousands of small elements on a remux) a few bytes
     * at a time. Each of those went through the stream's article lookup; now small reads are served from this copy,
     * filled one article piece at a time. The stream's bytes never change, so it stays valid across reopens.
     */
    private val small = ByteArray(SMALL_BUFFER)
    private var smallStart = 0L
    private var smallLength = 0

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
        val wanted = minOf(length.toLong(), remaining).toInt()
        val cached = position - smallStart
        if (smallLength > 0 && cached in 0 until smallLength) {
            val count = minOf(wanted.toLong(), smallLength - cached).toInt()
            System.arraycopy(small, cached.toInt(), buffer, offset, count)
            return delivered(count)
        }
        val fill = wanted < SMALL_READ
        val watch = ReadWatch.start(position)
        val count = try {
            if (!fill) session.source.read(position, buffer, offset, wanted)
            else session.source.read(position, small, 0, minOf(SMALL_BUFFER.toLong(), remaining).toInt()).also { got ->
                if (got > 0) { smallStart = position; smallLength = got; System.arraycopy(small, 0, buffer, offset, minOf(got, wanted)) }
            }.let { got -> if (got > 0) minOf(got, wanted) else got }
        }
        catch (error: IOException) {
            // A seek or Stop interrupts the read: called off, not a failure of the link.
            if (dev.pov.tv.usenet.isCancellation(error)) throw error
            UsenetLog.w("NNTP read failed at $position: ${error.message}")
            // A timeout or dropped connection: keep the session (its connections and read-ahead) so the player's quiet
            // retry carries on from here. Only a release fault, or the same trouble again and again, ends the link
            // (an Easynews link then moves to the web servers).
            val passing = dev.pov.tv.usenet.isPassingFailure(error)
            if (!passing || UsenetSessions.stumble(session.key) > PASSING_RETRIES)
                UsenetSessions.giveUp(session.key, when (error) {
                    is dev.pov.tv.usenet.MissingArticleException -> "Your news providers are missing part of this release. Choose another link."
                    is dev.pov.tv.usenet.ReleaseIncompleteException -> error.message.orEmpty()
                    else -> ""
                })
            throw error
        } finally { ReadWatch.done(watch) }                       // however the read ended (a cancel too)
        if (count < 0) return C.RESULT_END_OF_INPUT
        return delivered(count)
    }

    private fun delivered(count: Int): Int {
        if (firstRead) { firstRead = false; readSinceOpen = 0; UsenetStartTimes.mark("first bytes"); UsenetLog.i("  first bytes after ${(System.nanoTime() - openedAt) / 1_000_000} ms") }
        // A long stretch read without trouble: earlier hiccups no longer count towards giving the link up, so a
        // five-hour film (or a TV left on for days) isn't refused for a handful of unrelated timeouts.
        if (readSinceOpen < STEADY_BYTES && readSinceOpen + count >= STEADY_BYTES) UsenetSessions.steady(session.key)
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
