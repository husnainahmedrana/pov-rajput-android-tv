package dev.pov.tv

import dev.pov.tv.data.NewsProvider
import dev.pov.tv.data.NewsProviders
import dev.pov.tv.usenet.Fetch
import dev.pov.tv.usenet.NntpPool
import dev.pov.tv.usenet.NntpServer
import dev.pov.tv.usenet.Nzb
import dev.pov.tv.usenet.NzbSegment

/**
 * Finds the port, pipeline depth (requests kept in flight on each connection) and connection count that download
 * fastest from one news provider on this network, with real video articles, each setting for a few seconds:
 *  0. the common ports ([PORTS], SSL and plain) at the provider's saved settings: an internet provider may slow some
 *     ports. Speed decides; when speeds tie (the line is the limit) the quickest first article does. SSL is kept
 *     unless a plain port is clearly better;
 *  1. pipeline depths [DEPTHS] on that port, then the depths next to the winner, then the top two again (one noisy
 *     reading can't pick the winner);
 *  2. connection counts [COUNTS] at that depth, up to what the account allows (a refused connection is the ceiling);
 *  3. the depths beside the winner once more at the new connection count, and the runner-up port at the tuned
 *     settings (the port was chosen at the old ones).
 * The smallest setting within [GOOD_ENOUGH] of the fastest wins: more depth or connections that add no speed only add
 * waiting and use the account's connection slots.
 */
internal object PipelineAutoTune {
    /** Ports news providers commonly answer on: SSL first, then plain. */
    val PORTS = listOf(563 to true, 443 to true, 119 to false, 80 to false, 23 to false)
    val DEPTHS = listOf(1, 2, 4, 8, 12, 16, 20)
    val COUNTS = listOf(4, 8, 12, 16, 20, 24, 32, 40, 50)
    const val GOOD_ENOUGH = 0.95
    /** Of a connection limit the server enforced, the share kept: the account's slots are shared with other apps. */
    const val LIMIT_SHARE = 0.8
    private const val STEP_NS = 6_000_000_000L
    private const val OPEN_NS = 20_000_000_000L
    /** A test file needs enough articles that the deepest step never asks for the same one twice at once. */
    private const val MIN_ARTICLES = 400

    /** One setting: [round] 0 is the port round, which the dialog words differently. */
    data class Setting(val port: Int, val tls: Boolean, val depth: Int, val connections: Int, val round: Int)
    class Step(val setting: Setting, val connected: Int, val mbps: Double, val failed: Int, val firstMs: Long) {
        val port get() = setting.port
        val tls get() = setting.tls
        val depth get() = setting.depth
        val connections get() = connected
    }
    /** [plainFaster]: a port without SSL that was clearly faster; never saved on its own (it sends the login unencrypted). */
    class Result(val port: Int, val tls: Boolean, val depth: Int, val connections: Int, val mbps: Double, val limit: Int?, val plainFaster: Step? = null)

    /** The smallest depth within [GOOD_ENOUGH] of the fastest (repeats count by their average); null when nothing downloaded. */
    fun pick(steps: List<Step>): Int? = smallestNearBest(steps.groupBy { it.depth }.mapValues { (_, all) -> all.map { it.mbps }.average() })

    /** The fewest connections within [GOOD_ENOUGH] of the fastest; null when nothing downloaded. */
    fun pickConnections(steps: List<Step>): Int? = smallestNearBest(steps.groupBy { it.connections }.mapValues { (_, all) -> all.map { it.mbps }.average() })

    private fun smallestNearBest(speeds: Map<Int, Double>): Int? {
        val best = speeds.values.maxOrNull() ?: return null
        if (best <= 0.0) return null
        return speeds.filterValues { it >= best * GOOD_ENOUGH }.keys.min()
    }

    /**
     * The port to use: of those within [GOOD_ENOUGH] of the fastest, SSL ones if any (a plain port must be clearly
     * faster to win), then the quickest first article. Without [allowPlain] (the provider uses SSL) only SSL ports
     * count: switching to plain would send the login unencrypted, so that is left to the viewer. Null when nothing
     * downloaded.
     */
    fun pickPort(steps: List<Step>, allowPlain: Boolean = true): Step? {
        val candidates = if (allowPlain) steps else steps.filter { it.tls }
        val best = candidates.maxOfOrNull { it.mbps } ?: return null
        if (best <= 0.0) return null
        val near = candidates.filter { it.mbps >= best * GOOD_ENOUGH }
        return near.filter { it.tls }.ifEmpty { near }.minByOrNull { it.firstMs }
    }

    /** A plain port clearly faster than the best SSL one, to mention (not save) when the provider uses SSL. */
    fun plainFaster(steps: List<Step>): Step? {
        val ssl = steps.filter { it.tls }.maxOfOrNull { it.mbps } ?: return null
        return steps.filter { !it.tls && it.mbps * GOOD_ENOUGH > ssl }.maxByOrNull { it.mbps }
    }

    /** Connections to keep when the server allowed only [limit]: a share of it, so other apps still get slots. */
    fun withinLimit(wanted: Int, limit: Int?): Int = if (limit == null) wanted else minOf(wanted, maxOf(1, (limit * LIMIT_SHARE).toInt()))

    /** The ports to try: the provider's own first, then [PORTS]. */
    fun ports(provider: NewsProvider): List<Pair<Int, Boolean>> = (listOf(provider.port to provider.tls) + PORTS).distinct()

    /** Articles to test with: the biggest file of the most recent NZB this device played (they are kept a while). */
    fun articles(cache: java.io.File?): List<NzbSegment>? {
        val saved = cache?.let { java.io.File(it, "nzb") }?.listFiles { file -> file.name.endsWith(".nzb.gz") }.orEmpty()
        for (file in saved.sortedByDescending { it.lastModified() }) {
            val nzb = try {
                Nzb.parse(java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (_: Exception) { continue }
            val biggest = nzb.files.maxByOrNull { it.segments.size } ?: continue
            if (biggest.segments.size >= MIN_ARTICLES) return biggest.segments
        }
        return null
    }

    /**
     * Runs the rounds and returns the setting to save, or null when nothing downloaded (or [cancelled]).
     * [onTesting] says what is being measured now (with the step number), [onStep] what it managed.
     */
    /** What a run tunes; unticked settings stay as saved. */
    enum class Round { PORT, PIPELINE, CONNECTIONS }

    fun run(provider: NewsProvider, articles: List<NzbSegment>, rounds: Set<Round>, cancelled: () -> Boolean,
            onTesting: (Setting, step: Int) -> Unit, onStep: (Step) -> Unit,
            onLimit: (Int) -> Unit, onError: (Setting, message: String) -> Unit): Result? {
        var next = articles.size / 3                                       // away from the file's two ends
        var number = 0
        var limit: Int? = null

        /** One setting for [STEP_NS]; null when cancelled or nothing could be opened. */
        fun measure(asked: Setting): Step? {
            if (cancelled()) return null
            // Once the account's limit is known, never ask for more: refused sign-ins can get an account blocked for a while.
            val setting = limit?.let { asked.copy(connections = minOf(asked.connections, it)) } ?: asked
            onTesting(setting, ++number)
            val server = NntpServer(provider.host, setting.port, setting.tls, provider.user, provider.password)
            val pool = NntpPool(server, setting.connections, setting.depth, log = { UsenetLog.i("auto-tune: $it") })
            try {
                // Sign every connection in first, so only steady downloading is measured; the first article to arrive
                // (sign-in included) is what starting a video waits for.
                val opened = System.nanoTime()
                var opening: List<Fetch>? = (0 until setting.connections).map { pool.fetch(articles[next++ % articles.size].messageId) }
                val until = opened + OPEN_NS
                var firstMs = -1L
                while (opening!!.any { !it.done } && System.nanoTime() < until && !cancelled()) {
                    if (firstMs < 0 && opening.any { it.result != null }) firstMs = (System.nanoTime() - opened) / 1_000_000
                    Thread.sleep(20)
                }
                if (firstMs < 0 && opening.any { it.result != null }) firstMs = (System.nanoTime() - opened) / 1_000_000
                opening = null                                             // the decoded articles: up to 35 MB on 50 connections
                val connected = pool.connected
                if (connected == 0) { onError(setting, pool.refusal?.message ?: "no connection opened"); return null }
                if (firstMs < 0) { onError(setting, "the test video's articles are missing on this provider; play a newer Usenet link first"); return null }
                // The highest count that signed in: a lower one later can be the previous step's slots not yet freed.
                if (connected < setting.connections && pool.refusal != null) { limit = maxOf(limit ?: 0, connected); onLimit(limit!!) }
                val flying = ArrayDeque<Fetch>()
                var failed = 0
                val startBytes = pool.received.get()
                val start = System.nanoTime()
                while (System.nanoTime() - start < STEP_NS && !cancelled()) {
                    flying.removeAll { fetch -> if (!fetch.done) false else { if (fetch.result == null) failed++; true } }
                    while (flying.size < connected * setting.depth + 2) flying += pool.fetch(articles[next++ % articles.size].messageId)
                    Thread.sleep(20)
                }
                if (cancelled()) return null
                val seconds = (System.nanoTime() - start) / 1e9
                val step = Step(setting, connected, (pool.received.get() - startBytes) * 8 / 1e6 / seconds, failed, firstMs)
                UsenetLog.i("auto-tune: ${provider.name} port ${setting.port}${if (setting.tls) " SSL" else ""} · pipeline ${setting.depth} · " +
                    "$connected connections: %.1f Mbps, first article $firstMs ms, $failed failed".format(step.mbps))
                onStep(step)
                return step
            } catch (error: Exception) {
                onError(setting, error.message ?: error.javaClass.simpleName)
                return null
            } finally {
                pool.close()
                try { Thread.sleep(2500) } catch (_: InterruptedException) {}  // let the server free the slots
            }
        }

        /** Keeps what [step] measured; false when the run was cancelled. */
        fun kept(step: Step?, into: MutableList<Step>): Boolean { if (step != null) into += step; return !cancelled() }

        // A provider on SSL stays on SSL unless the viewer allowed plain ports; a faster plain port is then only mentioned.
        val allowPlain = !provider.tls || provider.allowPlain
        val all = mutableListOf<Step>()                                   // every reading, for the final speed

        // 0. Ports, at the provider's saved settings.
        val portSteps = mutableListOf<Step>()
        var port = provider.port
        var tls = provider.tls
        if (Round.PORT in rounds) {
            for ((each, secure) in ports(provider)) if (!kept(measure(Setting(each, secure, provider.pipelineDepth, provider.connections, 0)), portSteps)) return null
            val chosen = pickPort(portSteps, allowPlain) ?: return null
            port = chosen.port; tls = chosen.tls
            all += portSteps
        }
        fun at(depth: Int, connections: Int, round: Int) = Setting(port, tls, depth, connections, round)

        // 1. Pipeline depth on that port: a spread, the neighbours of the winner, the top two again.
        val base = provider.connections
        var depth = provider.pipelineDepth
        val depthSteps = mutableListOf<Step>()
        if (Round.PIPELINE in rounds) {
            for (each in DEPTHS) if (!kept(measure(at(each, base, 1)), depthSteps)) return null
            depth = pick(depthSteps) ?: return null
            for (near in (depth - 2..depth + 2).filter { it in 1..NewsProviders.MAX_PIPELINE_DEPTH && depthSteps.none { s -> s.depth == it } })
                if (!kept(measure(at(near, base, 1)), depthSteps)) return null
            val averaged = depthSteps.groupBy { it.depth }.mapValues { (_, steps) -> steps.map { it.mbps }.average() }
            for (top in averaged.entries.sortedByDescending { it.value }.take(2).map { it.key })
                if (!kept(measure(at(top, base, 1)), depthSteps)) return null
            depth = pick(depthSteps) ?: return null
            all += depthSteps
        }

        // 2. Connection counts at that depth, stopping at the account's limit or when more stop helping.
        var connections = base
        val countSteps = all.filter { it.port == port && it.depth == depth && it.connections == base }.toMutableList()
        if (Round.CONNECTIONS in rounds) {
            if (countSteps.isEmpty() && !kept(measure(at(depth, base, 2)), countSteps)) return null
            var falling = 0
            for (count in COUNTS.filter { it <= NewsProviders.MAX_CONNECTIONS && it != base }) {
                if (limit?.let { count > it } == true) break
                val step = measure(at(depth, count, 2)) ?: if (cancelled()) return null else break
                val bestSoFar = countSteps.maxOfOrNull { it.mbps } ?: 0.0
                countSteps += step
                falling = if (count > base && step.mbps < bestSoFar * 0.9) falling + 1 else 0
                if (falling >= 2 || (limit != null && step.connections < count)) break
            }
            connections = withinLimit(pickConnections(countSteps) ?: base, limit)
            all += countSteps
        }

        // 3. With both tuned, the depths beside the winner once more at the new connection count (it can shift) ...
        val finalSteps = all.filter { it.port == port && it.connections == connections }.toMutableList()
        if (Round.PIPELINE in rounds && Round.CONNECTIONS in rounds && connections != base) {
            for (near in listOf(depth - 2, depth + 2).filter { it in 1..NewsProviders.MAX_PIPELINE_DEPTH })
                if (!kept(measure(at(near, connections, 3)), finalSteps)) return null
            if (finalSteps.none { it.depth == depth } && !kept(measure(at(depth, connections, 3)), finalSteps)) return null
            depth = pick(finalSteps.filter { it.connections == connections }) ?: depth
            all += finalSteps
        }
        // Lists share readings: each counts once.
        val tuned = all.distinct().filter { it.port == port && it.depth == depth && it.connections == connections }
        var speed = tuned.map { it.mbps }.ifEmpty { listOf(all.maxOfOrNull { it.mbps } ?: 0.0) }.average()

        // ... and when the port was tuned along with the rest, the runner-up port at the new settings (the port was
        // chosen at the old ones).
        var finalPort = port
        var finalTls = tls
        val changed = depth != provider.pipelineDepth || connections != provider.connections
        val runnerUp = portSteps.filter { it.port != port && it.mbps > 0 && (allowPlain || it.tls) }.maxByOrNull { it.mbps }
        val winnerNow = tuned.lastOrNull()
        if (Round.PORT in rounds && changed && runnerUp != null && winnerNow != null) {
            val again = measure(Setting(runnerUp.port, runnerUp.tls, depth, connections, 0))
            if (cancelled()) return null
            if (again != null && pickPort(listOf(winnerNow, again), allowPlain) === again) { finalPort = again.port; finalTls = again.tls; speed = again.mbps }
        }
        return Result(finalPort, finalTls, depth, connections, speed, limit, if (allowPlain) null else plainFaster(portSteps))
    }
}
