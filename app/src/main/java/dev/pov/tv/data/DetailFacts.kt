package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The details page's About section: short label/value facts from one TMDB movie or TV details response
 * (with `credits` appended for a movie's director). Missing or junk values are left out, not shown blank.
 */
internal object DetailFacts {
    private val dateFormat get() = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

    fun from(data: JSONObject, type: String): List<Pair<String, String>> {
        val facts = mutableListOf<Pair<String, String>>()
        fun add(label: String, value: String?) { if (!value.isNullOrBlank()) facts += label to value }
        if (type == "movie") {
            val crew = data.optJSONObject("credits")?.optJSONArray("crew")
            add(L10n.tr(R.string.t_directed_by, "Directed by"), names(crew) { it.optString("job") == "Director" })
            add(L10n.tr(R.string.t_released, "Released"), date(data.optString("release_date")))
            add(L10n.tr(R.string.t_runtime, "Runtime"), duration(data.optInt("runtime")))
            add(L10n.tr(R.string.t_language, "Language"), language(data))
            add(L10n.tr(R.string.t_studio, "Studio"), names(data.optJSONArray("production_companies")))
            add(L10n.tr(R.string.t_budget, "Budget"), money(data.optLong("budget")))
            add(L10n.tr(R.string.t_box_office, "Box office"), money(data.optLong("revenue")))
        } else {
            add(L10n.tr(R.string.t_created_by, "Created by"), names(data.optJSONArray("created_by")))
            add(L10n.tr(R.string.t_network, "Network"), names(data.optJSONArray("networks")))
            add(L10n.tr(R.string.t_first_aired, "First aired"), date(data.optString("first_air_date")))
            val status = data.optString("status")
            if (status == "Ended" || status == "Canceled") add(L10n.tr(R.string.t_last_aired, "Last aired"), date(data.optString("last_air_date")))
            add(L10n.tr(R.string.t_status, "Status"), when (status) {
                "Returning Series" -> L10n.tr(R.string.t_still_running, "Still running"); "Ended" -> L10n.tr(R.string.t_ended, "Ended"); "Canceled" -> L10n.tr(R.string.t_cancelled, "Cancelled")
                "In Production" -> L10n.tr(R.string.t_in_production, "In production"); "Planned" -> L10n.tr(R.string.t_planned, "Planned"); else -> null
            })
            val seasons = data.optInt("number_of_seasons"); val episodes = data.optInt("number_of_episodes")
            add(L10n.tr(R.string.t_seasons, "Seasons"), listOfNotNull(
                seasons.takeIf { it > 0 }?.let { "$it ${if (it == 1) "season" else "seasons"}" },
                episodes.takeIf { it > 0 }?.let { "$it ${if (it == 1) "episode" else "episodes"}" },
            ).joinToString(" · "))
            add(L10n.tr(R.string.t_episode_length, "Episode length"), duration(data.optJSONArray("episode_run_time")?.optInt(0) ?: 0))
            add(L10n.tr(R.string.t_language, "Language"), language(data))
        }
        return facts
    }

    private fun names(list: JSONArray?, keep: (JSONObject) -> Boolean = { true }): String? =
        (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.filter(keep)
            .map { it.optString("name").trim() }.filter { it.isNotBlank() && it != "null" }.distinct().take(2)
            .joinToString(", ").ifBlank { null }

    private fun date(raw: String): String? = runCatching { LocalDate.parse(raw.take(10)).format(dateFormat) }.getOrNull()

    private fun duration(minutes: Int): String? = when {
        minutes <= 0 -> null
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    /** TMDB stores placeholder budgets like 1 or 100; under $10,000 is treated as unknown. */
    private fun money(dollars: Long): String? = when {
        dollars < 10_000 -> null
        dollars >= 1_000_000_000 -> "$" + trim(dollars / 1_000_000_000.0) + "B"
        dollars >= 1_000_000 -> "$" + trim(dollars / 1_000_000.0) + "M"
        else -> "$" + trim(dollars / 1_000.0) + "K"
    }

    private fun trim(value: Double): String =
        if (value >= 100) value.toLong().toString() else String.format(Locale.ENGLISH, "%.1f", value).removeSuffix(".0")

    private fun language(data: JSONObject): String? {
        val code = data.optString("original_language").trim().takeIf { it.isNotBlank() && it != "xx" } ?: return null
        val spoken = data.optJSONArray("spoken_languages")
        val named = (0 until (spoken?.length() ?: 0)).mapNotNull { spoken?.optJSONObject(it) }
            .firstOrNull { it.optString("iso_639_1") == code }?.optString("english_name")?.takeIf { it.isNotBlank() }
        return named ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code }
    }
}
