package dev.pov.tv.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The viewer's calendar: when the next episodes of the shows they follow come out. The shows are the ones in their
 * tracker (watched episodes, series watchlist, Continue Watching), so it works the same for WeTrakr, Trakt and Simkl;
 * the air dates come from each show's details (the episode list with release dates), kept for a few hours.
 */
internal class EpisodeCalendar(
    /** A show's details with its episodes (Cinemeta or the chosen metadata add-on). */
    private val details: (MediaItemData) -> MediaItemData,
    private val now: () -> Instant = Instant::now,
) {
    data class Entry(val show: MediaItemData, val episode: EpisodeData, val airs: Instant) {
        val identity get() = PlaybackIdentity(show.imdbId, "series", show.title, episode.season, episode.episode)
    }

    /** Episodes airing from [pastDays] ago to [days] ahead, soonest first; specials (season 0) are left out. */
    fun upcoming(shows: List<MediaItemData>, days: Int = 30, pastDays: Int = 1): List<Entry> {
        val wanted = shows.filter { it.imdbId.matches(IMDB) }.distinctBy { it.imdbId }.take(MAX_SHOWS)
        if (wanted.isEmpty()) return emptyList()
        val start = now().minusSeconds(pastDays * 86_400L); val end = now().plusSeconds(days * 86_400L)
        val pool = Executors.newFixedThreadPool(minOf(6, wanted.size))
        try {
            val tasks = wanted.map { show -> pool.submit<MediaItemData?> { runCatching { cached(show) }.getOrNull() } }
            val until = System.nanoTime() + BUDGET_NS
            return tasks.flatMap { task ->
                val show = try { task.get(maxOf(1L, until - System.nanoTime()), TimeUnit.NANOSECONDS) } catch (_: Exception) { null } ?: return@flatMap emptyList()
                show.episodes.filter { it.season > 0 }.mapNotNull { episode ->
                    val airs = airTime(episode.released) ?: return@mapNotNull null
                    if (airs.isBefore(start) || airs.isAfter(end)) null else Entry(show, episode, airs)
                }
            }.sortedWith(compareBy({ it.airs }, { it.show.title }, { it.episode.season }, { it.episode.episode }))
        } finally { pool.shutdownNow() }
    }

    private fun cached(show: MediaItemData): MediaItemData {
        val key = show.imdbId
        CACHE[key]?.let { (at, item) -> if (System.currentTimeMillis() - at < KEEP_MS) return item }
        val item = details(show.copy(type = "series"))
        if (CACHE.size > 400) CACHE.clear()
        CACHE[key] = System.currentTimeMillis() to item
        return item
    }

    companion object {
        private val IMDB = Regex("tt[0-9]{5,12}")
        private val CACHE = ConcurrentHashMap<String, Pair<Long, MediaItemData>>()
        private const val KEEP_MS = 6L * 60 * 60 * 1000
        private const val BUDGET_NS = 20_000_000_000L
        const val MAX_SHOWS = 80

        /** "2026-11-24T02:00:00.000Z", or a bare date (taken as midnight UTC). */
        internal fun airTime(released: String): Instant? = try {
            if (released.length <= 10) LocalDate.parse(released).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() else Instant.parse(released)
        } catch (_: Exception) { null }

        /** The viewer's local day of an air time, for grouping. */
        fun day(airs: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate = airs.atZone(zone).toLocalDate()

        fun forget() = CACHE.clear()
    }
}
