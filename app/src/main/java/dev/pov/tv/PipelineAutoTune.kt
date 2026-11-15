package dev.pov.tv

import dev.pov.tv.data.NewsProvider
import dev.pov.tv.data.NewsProviders
import dev.pov.tv.usenet.Fetch
import dev.pov.tv.usenet.MissingArticleException
import dev.pov.tv.usenet.NntpPool
import dev.pov.tv.usenet.NntpServer
import dev.pov.tv.usenet.Nzb
import dev.pov.tv.usenet.NzbSegment

/**
 * Finds the port, pipeline depth (requests kept in flight on each connection) and connection count that download
 * fastest from one news provider on this network, with real video articles:
 *  0. the common ports ([PORTS], SSL and plain) at the provider's saved settings: an internet provider may slow some
 *     ports. Speed decides; when speeds tie (the line is the limit) the quickest first article does. SSL is kept
 *     unless a plain port is clearly better and allowed;
 *  1. pipeline depths [PIPELINE_DEPTHS] on that port at a few connections ([PIPELINE_TEST_CONNECTIONS], where the
 *     pipeline's own effect shows): a deeper pipeline must beat pipeline 1 by [PIPELINE_WIN] (an idea from AltMount);
 *  2. connection counts [COUNTS] at that depth on the real line, up to what the account allows (a refused connection
 *     is the ceiling);
 *  3. the runner-up port at the tuned settings (the port was chosen at the old ones).
 * Each step downloads for at least [MIN_STEP_NS] and [MIN_STEP_BYTES] (up to [MAX_STEP_NS]), so a slow line still gets
 * a fair sample. A reading far below both its neighbours is taken again, and every winner needs two readings: one
 * lucky or unlucky moment on an unsteady line can't decide. The smallest setting within [GOOD_ENOUGH] of the fastest
 * (by average) wins: more depth or connections that add no speed only add waiting and use the account's slots.
 */
internal object PipelineAutoTune {
    /** Ports news providers commonly answer on: SSL first, then plain. */
    val PORTS = listOf(563 to true, 443 to true, 119 to false, 80 to false, 23 to false)
    val COUNTS = listOf(4, 8, 12, 16, 20, 24, 32, 40, 50)
    /** Depths the pipeline round tries, at [PIPELINE_TEST_CONNECTIONS] connections. */
    val PIPELINE_DEPTHS = listOf(1, 2, 4, 8, 16)
    /** Few enough connections that the line isn't already full, so the pipeline's own effect shows (as AltMount tests). */
    const val PIPELINE_TEST_CONNECTIONS = 4
    /** A deeper pipeline must beat pipeline 1 by this much to be worth its waiting; otherwise pipelining stays off. */
    const val PIPELINE_WIN = 1.10
    /** Re-test steps allowed to give a final choice its second reading (each can move the choice). */
    const val MAX_CONFIRM = 3
    const val GOOD_ENOUGH = 0.95
    /** Of a connection limit the server enforced, the share kept: the account's slots are shared with other apps. */
    const val LIMIT_SHARE = 0.8
    /** A reading this far below both neighbours is suspect and taken again. */
    const val OUTLIER = 0.75
    /** Two readings of one setting further apart than this: the connection is unsteady, results may not hold. */
    const val UNSTEADY = 0.6
    private const val MIN_STEP_NS = 10_000_000_000L
    private const val MAX_STEP_NS = 25_000_000_000L
    private const val MIN_STEP_BYTES = 50L * 1024 * 1024
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
    /** Why a step gave no reading. */
    enum class Failure { NONE, NO_CONNECTION, TOO_SLOW, MISSING, ERROR }
    /** Why the connections round ended before the last count. */
    enum class Stop { LIMIT, NOT_HELPING, TOO_SLOW, FAILED }
    /**
     * [plainFaster]: a port without SSL that was clearly faster; never saved on its own (it sends the login
     * unencrypted). [unsteady]: the same setting measured very differently, so the result may not hold.
     */
    class Result(val port: Int, val tls: Boolean, val depth: Int, val connections: Int, val mbps: Double, val limit: Int?,
                 val plainFaster: Step? = null, val unsteady: Boolean = false)

    /** The smallest depth within [GOOD_ENOUGH] of the fastest (repeats count by their average); null when nothing downloaded. */
    fun pick(steps: List<Step>): Int? = smallestNearBest(averages(steps) { it.depth })

    /**
     * The pipeline depth to use: pipeline 1 unless a deeper one beats it by [PIPELINE_WIN] (by averages); of those that
     * do, the smallest within [GOOD_ENOUGH] of the fastest. Without a pipeline-1 reading, the plain smallest-near-best.
     */
    fun pickPipeline(steps: List<Step>): Int {
        val speeds = averages(steps) { it.depth }
        val baseline = speeds[1] ?: return pick(steps) ?: 1
        val better = speeds.filter { (depth, mbps) -> depth > 1 && mbps >= baseline * PIPELINE_WIN }
        if (better.isEmpty()) return 1
        val best = better.values.max()
        return better.filterValues { it >= best * GOOD_ENOUGH }.keys.min()
    }

    /** The fewest connections within [GOOD_ENOUGH] of the fastest; null when nothing downloaded. */
    fun pickConnections(steps: List<Step>): Int? = smallestNearBest(averages(steps) { it.connections })

    /** Each setting's average speed, by [key]. */
    fun averages(steps: List<Step>, key: (Step) -> Int): Map<Int, Double> = steps.groupBy(key).mapValues { (_, all) -> all.map { it.mbps }.average() }

    private fun smallestNearBest(speeds: Map<Int, Double>): Int? {
        val best = speeds.values.maxOrNull() ?: return null
        if (best <= 0.0) return null
        return speeds.filterValues { it >= best * GOOD_ENOUGH }.keys.min()
    }

    /**
     * Settings whose reading is far below both neighbours (in [key] order), worth taking again: on an unsteady line a
     * single dip otherwise counts as a real result. Only settings read once.
     */
    fun outliers(steps: List<Step>, key: (Step) -> Int): List<Int> {
        val speeds = averages(steps, key).toSortedMap()
        val once = steps.groupBy(key).filterValues { it.size == 1 }.keys
        val keys = speeds.keys.toList()
        return keys.indices.filter { index ->
            val value = speeds.getValue(keys[index])
            val neighbours = listOfNotNull(keys.getOrNull(index - 1), keys.getOrNull(index + 1)).map(speeds::getValue)
            // Both sides: at an end, a low reading is just the slope (fewer connections or a shallow pipeline are slower).
            keys[index] in once && neighbours.size == 2 && neighbours.all { value < it * OUTLIER }
        }.map { keys[it] }
    }

    /** True when any setting read twice or more differs by more than [UNSTEADY] between readings. */
    fun unsteady(steps: List<Step>): Boolean = steps.groupBy { it.setting.copy(round = 0, connections = it.connected) }.values.any { same ->
        val low = same.minOf { it.mbps }; val high = same.maxOf { it.mbps }
        same.size > 1 && high > 0 && low < high * UNSTEADY
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

    /** What a run tunes; unticked settings stay as saved. */
    enum class Round { PORT, PIPELINE, CONNECTIONS }

    /**
     * Runs the ticked rounds and returns the setting to save, or null when nothing downloaded (or [cancelled]).
     * [onTesting] says what is being measured now (with the step number), [onStep] what it managed, [onFailed] why a
     * step gave nothing, [onStop] why the connections round ended early.
     */
    fun run(provider: NewsProvider, articles: List<NzbSegment>, rounds: Set<Round>, cancelled: () -> Boolean,
            onTesting: (Setting, step: Int) -> Unit, onStep: (Step) -> Unit, onLimit: (Int) -> Unit,
            onFailed: (Setting, Failure, message: String) -> Unit, onStop: (Stop) -> Unit = {}): Result? {
        var next = articles.size / 3                                       // away from the file's two ends
        var number = 0
        var limit: Int? = null
        var lastFailure = Failure.NONE

        /** One setting; null when cancelled or it gave no reading ([lastFailure] says why). */
        fun measure(asked: Setting): Step? {
            lastFailure = Failure.NONE
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
                val missing = opening.any { it.error is MissingArticleException }
                opening = null                                             // the decoded articles: up to 35 MB on 50 connections
                val connected = pool.connected
                if (connected == 0) {
                    lastFailure = Failure.NO_CONNECTION
                    onFailed(setting, lastFailure, pool.refusal?.message ?: "no connection opened"); return null
                }
                if (firstMs < 0) {
                    // Nothing arrived: either the provider lacks the test video, or the line is crawling right now.
                    lastFailure = if (missing) Failure.MISSING else Failure.TOO_SLOW
                    onFailed(setting, lastFailure, ""); return null
                }
                // The highest count that signed in: a lower one later can be the previous step's slots not yet freed.
                if (connected < setting.connections && pool.refusal != null) { limit = maxOf(limit ?: 0, connected); onLimit(limit!!) }
                val flying = ArrayDeque<Fetch>()
                var failed = 0
                val startBytes = pool.received.get()
                val start = System.nanoTime()
                // At least MIN_STEP_NS and MIN_STEP_BYTES (a slow line needs longer for a fair sample), at most MAX_STEP_NS.
                while (!cancelled()) {
                    val elapsed = System.nanoTime() - start
                    if (elapsed >= MAX_STEP_NS || (elapsed >= MIN_STEP_NS && pool.received.get() - startBytes >= MIN_STEP_BYTES)) break
                    flying.removeAll { fetch -> if (!fetch.done) false else { if (fetch.result == null) failed++; true } }
                    while (flying.size < connected * setting.depth + 2) flying += pool.fetch(articles[next++ % articles.size].messageId)
                    Thread.sleep(20)
                }
                if (cancelled()) return null
                val seconds = (System.nanoTime() - start) / 1e9
                val step = Step(setting, connected, (pool.received.get() - startBytes) * 8 / 1e6 / seconds, failed, firstMs)
                UsenetLog.i("auto-tune: ${provider.name} port ${setting.port}${if (setting.tls) " SSL" else ""} · pipeline ${setting.depth} · " +
                    "$connected connections: %.1f Mbps over %.1f s, first article $firstMs ms, $failed failed".format(step.mbps, seconds))
                onStep(step)
                return step
            } catch (error: Exception) {
                lastFailure = Failure.ERROR
                onFailed(setting, lastFailure, error.message ?: error.javaClass.simpleName)
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

        // 1. Pipeline depth on that port, at a few connections ([PIPELINE_TEST_CONNECTIONS]): with all of them the line is
        //    usually full already, every depth reads the same and noise picks the winner. A deeper pipeline has to beat
        //    pipeline 1 by [PIPELINE_WIN] on two-reading averages; dips are taken again.
        val base = provider.connections
        var depth = provider.pipelineDepth
        val testConnections = minOf(base, PIPELINE_TEST_CONNECTIONS)
        val depthSteps = mutableListOf<Step>()
        var pipelineShaky = false
        if (Round.PIPELINE in rounds) {
            fun test(each: Int) = kept(measure(at(each, testConnections, 1)), depthSteps)
            fun readings(each: Int) = depthSteps.count { it.depth == each }
            for (each in PIPELINE_DEPTHS) if (!test(each)) return null
            for (dip in outliers(depthSteps) { it.depth }) if (!test(dip)) return null
            // Pipeline 1 (the baseline every deeper one must beat) and the top two get their second reading.
            val top = averages(depthSteps) { it.depth }.entries.sortedByDescending { it.value }.take(2).map { it.key }
            for (each in (listOf(1) + top).distinct()) if (readings(each) < 2 && !test(each)) return null
            // The leader's neighbours between the tested depths.
            val leader = pickPipeline(depthSteps)
            for (near in listOf(leader - 1, leader + 1).filter { it in 1..NewsProviders.MAX_PIPELINE_DEPTH && leader > 1 && readings(it) == 0 })
                if (!test(near)) return null
            depth = pickPipeline(depthSteps)
            // The final pick needs two readings too (it can move to a depth read once); each re-test can move it again.
            var extra = 0
            while (readings(depth) < 2 && extra < MAX_CONFIRM) { extra++; if (!test(depth)) return null; depth = pickPipeline(depthSteps) }
            if (readings(depth) < 2) pipelineShaky = true
            all += depthSteps
        }

        // 2. Connection counts at that depth, on the real line, stopping at the account's limit, when more stop helping
        //    (against the averages, so one lucky reading can't end it), or after two steps in a row too slow to measure.
        var connections = base
        val countSteps = all.filter { it.port == port && it.depth == depth && it.connections == base }.toMutableList()
        if (Round.CONNECTIONS in rounds) {
            if (countSteps.isEmpty() && !kept(measure(at(depth, base, 2)), countSteps)) return null
            var falling = 0
            var slow = 0
            for (count in COUNTS.filter { it <= NewsProviders.MAX_CONNECTIONS && it != base }) {
                if (limit?.let { count > it } == true) { onStop(Stop.LIMIT); break }
                val bestSoFar = averages(countSteps) { it.connections }.values.maxOrNull() ?: 0.0
                val step = measure(at(depth, count, 2))
                if (cancelled()) return null
                if (step == null) {
                    if (lastFailure == Failure.TOO_SLOW && ++slow < 2) continue
                    onStop(when (lastFailure) { Failure.TOO_SLOW -> Stop.TOO_SLOW; Failure.NO_CONNECTION -> Stop.LIMIT; else -> Stop.FAILED }); break
                }
                slow = 0
                countSteps += step
                falling = if (count > base && step.mbps < bestSoFar * 0.9) falling + 1 else 0
                if (falling >= 2) { onStop(Stop.NOT_HELPING); break }
                if (limit != null && step.connections < count) { onStop(Stop.LIMIT); break }
            }
            for (dip in outliers(countSteps) { it.connections }) if (!kept(measure(at(depth, dip, 2)), countSteps)) return null
            // The top two counts get their second reading before the pick, so it doesn't chase one lucky reading.
            for (each in averages(countSteps) { it.connections }.entries.sortedByDescending { it.value }.take(2).map { it.key })
                if (countSteps.count { it.connections == each } < 2 && !kept(measure(at(depth, each, 2)), countSteps)) return null
            connections = withinLimit(pickConnections(countSteps) ?: base, limit)
            all += countSteps
        }

        // The final setting needs two good readings at its own connection count. Up to two more steps; a connection
        // round's choice can move with them (the pipeline, chosen where it shows, stays). A re-test that gives nothing
        // counts against trusting the result.
        var shaky = pipelineShaky
        if (Round.PIPELINE in rounds || Round.CONNECTIONS in rounds) {
            var extra = 0
            while (extra < MAX_CONFIRM && all.distinct().count { it.port == port && it.depth == depth && it.connections == connections } < 2) {
                extra++
                val again = measure(at(depth, connections, if (Round.CONNECTIONS in rounds) 2 else 1))
                if (cancelled()) return null
                if (again == null) { shaky = true; continue }
                all += again
                if (Round.CONNECTIONS in rounds)
                    connections = withinLimit(pickConnections(all.distinct().filter { it.port == port && it.depth == depth }) ?: connections, limit)
            }
            if (all.distinct().count { it.port == port && it.depth == depth && it.connections == connections } < 2) shaky = true
        }
        // Lists share readings: each counts once.
        val distinct = all.distinct()
        val tuned = distinct.filter { it.port == port && it.depth == depth && it.connections == connections }
        var speed = tuned.map { it.mbps }.ifEmpty { listOf(distinct.maxOfOrNull { it.mbps } ?: 0.0) }.average()

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
        // Unsteady only where it mattered: the readings that decided the result (pipeline 1, the chosen pipeline and its
        // closest rival, the chosen connection count and its rival, the final setting). A dip elsewhere, already taken
        // again, doesn't make the result doubtful.
        fun contenders(steps: List<Step>, key: (Step) -> Int, keep: Set<Int>): List<Step> {
            val top = averages(steps, key).entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet() + keep
            return steps.filter { key(it) in top }
        }
        val decisive = (contenders(depthSteps, { it.depth }, setOf(1, depth)) +
            contenders(countSteps, { it.connections }, setOf(connections)) + tuned).distinct()
        return Result(finalPort, finalTls, depth, connections, speed, limit, if (allowPlain) null else plainFaster(portSteps), shaky || unsteady(decisive))
    }
}
