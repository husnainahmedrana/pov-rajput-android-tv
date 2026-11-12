package dev.pov.tv.usenet

import java.io.IOException

/** A failed STAT request means unknown, never evidence that an article is missing. */
internal object ArticleHealth {
    data class Result(val missing: Set<String>, val unknown: Set<String>)

    fun check(ids: List<String>, probes: List<(List<String>) -> List<String>>): Result {
        var remaining = ids.toSet()
        val uncertain = mutableSetOf<String>()
        for (probe in probes) {
            if (remaining.isEmpty()) break
            try { remaining = probe(remaining.toList()).toSet().intersect(remaining) }
            catch (_: IOException) { uncertain += remaining }
        }
        return Result(if (probes.isEmpty()) emptySet() else remaining - uncertain,
            if (probes.isEmpty()) remaining else remaining.intersect(uncertain))
    }
}
