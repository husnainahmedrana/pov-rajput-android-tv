package dev.pov.tv.data

import org.json.JSONArray

/** A Home row the viewer can move and switch off: everything after Continue Watching and Top 10. */
internal data class HomeRow(val id: String, val title: String)

/**
 * The viewer's Home row order and hidden rows, by row id. Rows that come and go (e.g. TMDB-only rows) keep
 * their place when they return; rows never placed by the viewer follow in their default order.
 */
internal class HomeRows(private val get: (String) -> String?, private val put: (String, String?) -> Unit) {

    val order: List<String> get() = list(ORDER)
    val hidden: Set<String> get() = list(HIDDEN).toSet()
    val customized: Boolean get() = order.isNotEmpty() || hidden.isNotEmpty()
    /** Changes whenever the order or visibility does, so saved Home rows refresh. */
    val revision: String get() = (get(ORDER).orEmpty()) + "|" + (get(HIDDEN).orEmpty())

    /** All [available] rows in the viewer's order: placed rows first in their saved order, then the rest in default order. */
    fun <T> arrange(available: List<T>, id: (T) -> String): List<T> {
        val saved = order
        if (saved.isEmpty()) return available
        val rank = saved.withIndex().associate { it.value to it.index }
        val placed = available.filter { id(it) in rank }.sortedBy { rank.getValue(id(it)) }
        val rest = available.filter { id(it) !in rank }
        return placed + rest
    }

    fun <T> visible(available: List<T>, id: (T) -> String): List<T> = hidden.let { off -> arrange(available, id).filter { id(it) !in off } }

    /** Moves [rowId] one place up or down among [current] (the arranged list as shown) and saves the whole order. */
    fun move(current: List<String>, rowId: String, up: Boolean) {
        val list = current.toMutableList()
        val index = list.indexOf(rowId)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in list.indices) return
        list[index] = list[target]; list[target] = rowId
        // Rows not offered right now (e.g. a TMDB row without a key) keep their remembered slot at the end.
        put(ORDER, JSONArray(list + order.filter { it !in list }).toString())
    }

    fun setVisible(rowId: String, on: Boolean) {
        val next = if (on) hidden - rowId else hidden + rowId
        put(HIDDEN, JSONArray(next.toList()).toString())
    }

    fun forget(prefix: String) {
        put(ORDER, JSONArray(order.filterNot { it.startsWith(prefix) }).toString())
        put(HIDDEN, JSONArray(hidden.filterNot { it.startsWith(prefix) }).toString())
    }

    fun reset() { put(ORDER, null); put(HIDDEN, null) }

    private fun list(key: String): List<String> {
        val saved = get(key) ?: return emptyList()
        return try { JSONArray(saved).let { array -> (0 until array.length()).map(array::getString) } } catch (_: Exception) { emptyList() }
    }

    companion object {
        private const val ORDER = "home_rows.order"
        private const val HIDDEN = "home_rows.hidden"
    }
}
