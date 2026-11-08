package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Key creative credits only, grouped by person; TV uses credits from across the show's run. */
internal object DetailCrew {
    private val roles = listOf("Creator", "Director", "Screenplay", "Writer", "Story", "Producer", "Executive Producer",
        "Original Music Composer", "Director of Photography", "Editor")
    private val labels = mapOf("Original Music Composer" to "Composer", "Director of Photography" to "Cinematography")
    private data class Credit(val id: Long, val name: String, val photo: String, val role: String, val count: Int)

    fun from(data: JSONObject, type: String): List<TmdbCatalog.CastMember> {
        fun objects(list: JSONArray?) = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
        val entries = mutableListOf<Credit>()
        fun add(person: JSONObject, role: String, count: Int = 0) {
            val id = person.optLong("id"); val name = person.optString("name").trim()
            if (id <= 0 || name.isBlank() || name == "null" || role !in roles) return
            val path = person.optString("profile_path")
            entries += Credit(id, name, if (path.startsWith('/')) "https://image.tmdb.org/t/p/w185$path" else "", role, count)
        }
        if (type == "series") objects(data.optJSONArray("created_by")).forEach { add(it, "Creator") }
        val list = data.optJSONObject(if (type == "series") "aggregate_credits" else "credits")?.optJSONArray("crew")
        objects(list).forEach { person ->
            val jobs = objects(person.optJSONArray("jobs"))
            if (jobs.isEmpty()) add(person, person.optString("job"))
            else jobs.forEach { job -> add(person, job.optString("job"), job.optInt("episode_count")) }
        }
        val ranked = entries.sortedWith(compareBy<Credit> { roles.indexOf(it.role) }.thenByDescending { it.count })
        val selected = roles.flatMap { role -> ranked.filter { it.role == role }.distinctBy { it.id }.take(2) }
        return selected
            .groupBy { it.id }.values.take(14).map { credits ->
                val first = credits.first()
                TmdbCatalog.CastMember(first.id, first.name,
                    credits.map { labels[it.role] ?: it.role }.distinct().take(2).joinToString(" · "),
                    credits.firstOrNull { it.photo.isNotBlank() }?.photo.orEmpty(), crew = true)
            }
    }
}
