package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelatedTitleSelectionTest {
    private val today = LocalDate.of(2026, 11, 7)
    private fun title(id: Int, date: String, type: String = "movie") = JSONObject()
        .put("id", id).put("poster_path", "/p.jpg").put(if (type == "movie") "release_date" else "first_air_date", date)
    private fun ids(result: JSONArray) = (0 until result.length()).map { result.getJSONObject(it).getInt("id") }
    @Test fun `fresh relevant choices lead while older strong recommendations remain`() {
        val result = RelatedTitleSelection.select(JSONArray(listOf(title(1, "1999-01-01"), title(2, "2024-01-01"))),
            JSONArray(listOf(title(3, "2026-01-01"))), JSONArray(listOf(title(4, "2006-01-01"))), "movie", 99, today)
        assertEquals(listOf(2, 3, 1, 4), ids(result))
    }
    @Test fun `future self duplicates adult and posterless titles do not fill the row`() {
        val result = RelatedTitleSelection.select(JSONArray(listOf(title(99, "2024-01-01"), title(1, "2027-01-01"),
            title(2, "2024-01-01"), title(3, "2024-01-01").put("adult", true), title(4, "2024-01-01").put("poster_path", ""))),
            JSONArray(listOf(title(2, "2024-01-01"))), null, "movie", 99, today)
        assertEquals(listOf(2), ids(result))
    }
    @Test fun `shows use first air date and sparse recommendations retain the fallback`() {
        val result = RelatedTitleSelection.select(null, JSONArray(listOf(title(2, "2025-01-01", "series"))),
            JSONArray(listOf(title(1, "2008-01-01", "series"))), "series", 99, today)
        assertEquals(listOf(2, 1), ids(result))
    }
    @Test fun `only five recent picks are promoted before the original recommendation order`() {
        val result = RelatedTitleSelection.select(JSONArray(listOf(title(1, "1999-01-01"))),
            JSONArray((2..9).map { title(it, "2026-01-01") }), null, "movie", 99, today)
        assertEquals(listOf(2, 3, 4, 5, 6, 1), ids(result))
    }
}
