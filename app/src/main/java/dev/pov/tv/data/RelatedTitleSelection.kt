package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Fresh relevant choices first, followed by TMDB's recommendation order and Similar fallback. */
internal object RelatedTitleSelection {
    fun select(recommendations: JSONArray?, recent: JSONArray?, similar: JSONArray?, type: String,
               currentId: Long, today: LocalDate, limit: Int = 20): JSONArray {
        val field = if (type == "movie") "release_date" else "first_air_date"
        fun date(entry: JSONObject) = runCatching { LocalDate.parse(entry.optString(field)) }.getOrNull()
        fun entries(list: JSONArray?) = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }.filter {
            it.optLong("id") > 0 && it.optLong("id") != currentId && !it.optBoolean("adult") &&
                it.optString("poster_path").startsWith('/') && date(it)?.isAfter(today) != true
        }
        val recommended = entries(recommendations)
        val fresh = (recommended + entries(recent)).filter {
            date(it)?.let { release -> !release.isBefore(today.minusYears(5)) } == true
        }.distinctBy { it.optLong("id") }.take(5)
        return JSONArray((fresh + recommended + entries(similar)).distinctBy { it.optLong("id") }.take(limit))
    }
}
