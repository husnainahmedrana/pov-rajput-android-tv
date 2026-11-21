package dev.pov.tv.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Auto subtitle sync: lines a downloaded subtitle up with a reference that is right for THIS video (subtitles made for
 * this exact file, in any language: only WHEN lines are spoken matters). The approach follows Stremio Origin's:
 * the same words on both sides vote for one shift; otherwise every shift within ±30 s (50 ms steps) is tried at the
 * usual frame-rate ratios, and the best overlap wins only when it clearly beats the rest. The answer is
 * new time = old × [Result.factor] + [Result.offsetMs].
 */
object SubtitleAligner {
    data class Line(val startMs: Long, val endMs: Long, val text: String = "")
    data class Result(val offsetMs: Long, val factor: Double, val byWords: Boolean, val confidence: Double)

    /** 1.0 and the frame-rate mix-ups subtitles are commonly timed with (23.976 / 24 / 25 fps). */
    private val FACTORS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)
    private const val RANGE_MS = 30_000L
    private const val STEP_MS = 50L

    /** The shift (and speed) that lines [subtitle] up with [reference], or null when no answer is clearly right. */
    fun align(subtitle: List<Line>, reference: List<Line>): Result? {
        val x = subtitle.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val r = reference.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (x.size < 20 || r.size < 20) return null
        return byWords(x, r) ?: byTiming(x, r)
    }

    /** Words that appear once in each (names, numbers, rare words) say where the same line sits on both sides. */
    private fun byWords(x: List<Line>, r: List<Line>): Result? {
        fun unique(lines: List<Line>): Map<String, Line> {
            val seen = HashMap<String, Int>(); val at = HashMap<String, Line>()
            for (line in lines) for (word in words(line.text)) { seen[word] = (seen[word] ?: 0) + 1; at[word] = line }
            return at.filterKeys { seen[it] == 1 }
        }
        val ux = unique(x); val ur = unique(r)
        val votes = ux.mapNotNull { (word, line) -> ur[word]?.let { it.startMs - line.startMs } }.filter { abs(it) <= RANGE_MS }
        if (votes.size < 6) return null
        val bins = votes.groupBy { Math.floorDiv(it, 200L) }
        val best = bins.maxByOrNull { it.value.size } ?: return null
        val runnerUp = bins.filterKeys { abs(it - best.key) > 1 }.maxOfOrNull { it.value.size } ?: 0
        val near = bins.filterKeys { abs(it - best.key) <= 1 }.values.flatten().sorted()
        if (near.size < 6 || near.size < runnerUp * 3) return null
        return Result(near[near.size / 2], 1.0, true, near.size.toDouble() / votes.size)
    }

    /** Every shift and frame-rate ratio tried; the overlap of spoken time decides, when it clearly stands out. */
    private fun byTiming(x: List<Line>, r: List<Line>): Result? {
        val bin = STEP_MS
        val end = maxOf(r.last().endMs, (x.maxOf { it.endMs } * 1.05).toLong()) + RANGE_MS + bin
        val size = (end / bin + 2).toInt()
        val covered = BooleanArray(size)
        for (line in r) for (i in (line.startMs / bin).toInt() until minOf(size, ceil(line.endMs / bin.toDouble()).toInt())) covered[i] = true
        val prefix = IntArray(size + 1)
        for (i in 0 until size) prefix[i + 1] = prefix[i] + if (covered[i]) 1 else 0
        fun overlap(startMs: Double, endMs: Double): Int {
            val from = (startMs / bin).toInt().coerceIn(0, size); val to = ceil(endMs / bin).toInt().coerceIn(0, size)
            return if (to > from) prefix[to] - prefix[from] else 0
        }
        data class Try(val factor: Double, val offset: Long, val score: Int)
        val tries = ArrayList<Try>(FACTORS.size * (2 * RANGE_MS / STEP_MS + 1).toInt())
        for (factor in FACTORS) {
            var offset = -RANGE_MS
            while (offset <= RANGE_MS) {
                var score = 0
                for (line in x) score += overlap(line.startMs * factor + offset, line.endMs * factor + offset)
                tries += Try(factor, offset, score)
                offset += STEP_MS
            }
        }
        val best = tries.maxByOrNull { it.score } ?: return null
        // The best answer must clearly beat every other answer that isn't just a step or two from it.
        val runnerUp = tries.filter { it.factor != best.factor || abs(it.offset - best.offset) > 1_000 }.maxOfOrNull { it.score } ?: 0
        val spoken = x.sumOf { ((it.endMs - it.startMs) * best.factor / bin).toInt() }.coerceAtLeast(1)
        if (best.score < spoken * 0.35 || best.score < runnerUp * 1.2) return null
        return Result(best.offset, best.factor, false, best.score.toDouble() / spoken)
    }

    private val WORD = Regex("[\\p{L}\\p{N}']{4,}")
    private fun words(text: String) = WORD.findAll(text.lowercase(Locale.ROOT).replace(Regex("<[^>]*>|\\{[^}]*\\}"), " ")).map { it.value }.toSet()

    // ------------------------------------------------------------------ subtitle files

    private val TIMING = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")

    /** The lines of an SRT, WebVTT or ASS/SSA file: when each is shown and what it says. */
    fun parse(text: String): List<Line> {
        val body = text.removePrefix(Char(0xFEFF).toString())
        if (body.contains("[Events]", ignoreCase = true) || body.contains("\nDialogue:")) return parseAss(body)
        val out = ArrayList<Line>()
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val match = TIMING.find(lines[i])
            if (match == null) { i++; continue }
            val g = match.groupValues
            val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
            val said = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank() && TIMING.find(lines[i]) == null) { said.append(lines[i]).append(' '); i++ }
            if (end > start) out += Line(start, end, said.toString().trim())
        }
        return out
    }

    private fun ms(hours: String, minutes: String, seconds: String, fraction: String): Long {
        val frac = fraction.padEnd(3, '0').take(3).toLong()
        return ((hours.toLongOrNull() ?: 0) * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + frac
    }

    private val ASS_TIME = Regex("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{1,3})")

    private fun parseAss(text: String): List<Line> = text.lineSequence().filter { it.startsWith("Dialogue:", ignoreCase = true) }.mapNotNull { line ->
        val fields = line.substringAfter(':').split(',', limit = 10)
        if (fields.size < 10) return@mapNotNull null
        fun time(value: String) = ASS_TIME.find(value.trim())?.groupValues?.let { (it[1].toLong() * 3600 + it[2].toLong() * 60 + it[3].toLong()) * 1000 +
            it[4].padEnd(3, '0').take(3).toLong() }
        val start = time(fields[1]) ?: return@mapNotNull null
        val end = time(fields[2]) ?: return@mapNotNull null
        Line(start, end, fields[9].replace("\\N", " ").replace(Regex("\\{[^}]*\\}"), "").trim())
    }.filter { it.endMs > it.startMs && it.text.isNotEmpty() }.toList()

    // ------------------------------------------------------------------ OpenSubtitles file hash

    /**
     * OpenSubtitles' hash of a video: its size plus the 64-bit little-endian words of its first and last 64 KB. Subtitles
     * found by this hash were timed for this exact file.
     */
    fun movieHash(size: Long, head: ByteArray, tail: ByteArray): String {
        var hash = size
        for (chunk in listOf(head, tail)) {
            val buffer = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= 8) hash += buffer.long
        }
        return String.format(Locale.ROOT, "%016x", hash)
    }
}
