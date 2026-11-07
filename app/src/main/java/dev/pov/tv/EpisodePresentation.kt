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
    private val longDate = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)
    private val shortDate = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)

    /** Cinemeta's date (a UTC timestamp, often a day late for evening US premieres): the fallback when IMDb has none. */
    fun releaseDate(released: String): LocalDate? = runCatching { LocalDate.parse(released.take(10)) }.getOrNull()

    /** "Aired 20 January 2008" or "Airs 25 June 2026", for the focused episode's header line. */
    fun airLine(date: LocalDate?, today: LocalDate = LocalDate.now()): String =
        date?.let { (if (it.isAfter(today)) "Airs " else "Aired ") + it.format(longDate) }.orEmpty()

    fun shortAirDate(date: LocalDate?): String = date?.format(shortDate).orEmpty()

    fun future(released: String, now: Instant = Instant.now()): Boolean = try {
        val moment = try { Instant.parse(released) } catch (_: Exception) {
            LocalDate.parse(released.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC)
        }
        moment.isAfter(now)
    } catch (_: Exception) { false }
}
