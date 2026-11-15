package dev.pov.tv

import dev.pov.tv.data.EpisodeData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal object EpisodePresentation {
    fun seasons(episodes: List<EpisodeData>): List<Int> = episodes.map { it.season }.distinct()
        .sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
    fun selectedSeason(episodes: List<EpisodeData>, preferred: Int?): Int? =
        preferred?.takeIf { it in seasons(episodes) } ?: seasons(episodes).firstOrNull()

    /**
     * The episode to offer when nothing is half-watched: the first aired one after the furthest episode [watched]
     * (season to episode; finished on this device or marked watched on the tracker). Specials are left out. Null when
     * nothing was watched yet, or everything aired was.
     */
    fun nextAfterWatched(episodes: List<EpisodeData>, watched: List<Pair<Int, Int>>, now: Instant = Instant.now()): EpisodeData? {
        val furthest = watched.filter { it.first > 0 }.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: return null
        return episodes.filter { it.season > 0 && !future(it.released, now) }.sortedWith(compareBy({ it.season }, { it.episode }))
            .firstOrNull { it.season > furthest.first || (it.season == furthest.first && it.episode > furthest.second) }
    }
    private val longDate get() = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault())
    private val shortDate get() = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault())

    /** Cinemeta's date (a UTC timestamp, often a day late for evening US premieres): the fallback when IMDb has none. */
    fun releaseDate(released: String): LocalDate? = runCatching { LocalDate.parse(released.take(10)) }.getOrNull()

    /** "Aired 20 January 2008" or "Airs 25 June 2026", for the focused episode's header line. */
    fun airLine(date: LocalDate?, today: LocalDate = LocalDate.now()): String =
        date?.let { (if (it.isAfter(today)) L10n.tr(R.string.t_airs, "Airs ") else L10n.tr(R.string.t_aired, "Aired ")) + it.format(longDate) }.orEmpty()

    fun shortAirDate(date: LocalDate?): String = date?.format(shortDate).orEmpty()

    fun future(released: String, now: Instant = Instant.now()): Boolean = try {
        val moment = try { Instant.parse(released) } catch (_: Exception) {
            LocalDate.parse(released.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC)
        }
        moment.isAfter(now)
    } catch (_: Exception) { false }
}
