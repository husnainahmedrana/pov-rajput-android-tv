package dev.pov.tv.data

import org.json.JSONArray
import java.util.Locale

/**
 * Recent searches, newest first, kept on this device only (not in backups: what someone searched is private). The
 * Search screen lists them to run again, offers them as suggestions while typing, and can forget one or all.
 */
internal class SearchHistory(private val get: () -> String?, private val put: (String?) -> Unit) {
    constructor(context: android.content.Context) : this(
        { context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString(KEY, null) },
        { value -> context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply() })

    fun all(): List<String> = try {
        get()?.let { saved -> JSONArray(saved).let { array -> (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } } }.orEmpty()
    } catch (_: Exception) { emptyList() }

    /** Moves [query] to the top (the same words in other letter case count as one search). */
    fun add(query: String) {
        val clean = query.trim().replace(Regex("\\s+"), " ").take(200)
        if (clean.isEmpty()) return
        save(listOf(clean) + all().filterNot { same(it, clean) })
    }

    fun remove(query: String) = save(all().filterNot { same(it, query) })
    fun clear() = put(null)

    /** Past searches for what is being typed: those starting with it first, then those containing it. */
    fun matching(typed: String, limit: Int = 5): List<String> {
        val words = norm(typed)
        if (words.isEmpty()) return all().take(limit)
        val past = all().filterNot { norm(it) == words }
        return (past.filter { norm(it).startsWith(words) } + past.filter { words in norm(it) && !norm(it).startsWith(words) }).take(limit)
    }

    private fun save(items: List<String>) = put(JSONArray(items.take(MAX)).toString())
    private fun same(a: String, b: String) = norm(a) == norm(b)
    private fun norm(text: String) = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    companion object {
        const val FILE = "pov_search_history"
        private const val KEY = "recent"
        const val MAX = 20
    }
}
