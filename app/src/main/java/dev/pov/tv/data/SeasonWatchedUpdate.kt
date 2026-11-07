package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Explicit episode lists avoid marking another season or auto-filling future episodes. */
internal object SeasonWatchedUpdate {
    fun body(show: PlaybackIdentity, season: Int, episodes: List<Int>, weTrakr: Boolean): JSONObject {
        require(show.type == "series" && show.imdbId.matches(Regex("tt\\d{5,12}")) && season >= 0)
        require(episodes.isNotEmpty() && episodes.all { it > 0 })
        val rows = episodes.distinct().sorted().map { number -> JSONObject().put("number", number).apply {
            if (weTrakr) put("status", "watched")
        } }
        val item = JSONObject().put("ids", JSONObject().put("imdb", show.imdbId))
            .put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", JSONArray(rows))))
        return JSONObject().put("shows", JSONArray().put(item)).apply { if (weTrakr) put("allow_rewatch", false) }
    }
}
