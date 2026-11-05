package dev.pov.tv.data

import org.json.JSONObject

/** Public trailer identifiers only; no account headers. */
internal class HeroTrailerCatalog(private val get: (String) -> JSONObject = Net::publicJson,
    private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val at: Long, val ids: List<String>)
    private val cache = linkedMapOf<String, Entry>()
    fun videos(item: MediaItemData): List<String> {
        if (item.type !in listOf("movie", "series") || !item.imdbId.matches(Regex("tt[0-9]{5,12}"))) return emptyList()
        val key = "${item.type}:${item.imdbId}"
        synchronized(cache) { cache[key]?.takeIf { now() - it.at in 0..if (it.ids.isEmpty()) 600_000L else 21_600_000L }?.let { return it.ids } }
        val ids = parse(get("https://v3-cinemeta.strem.io/meta/${item.type}/${item.imdbId}.json"))
        synchronized(cache) { cache[key] = Entry(now(), ids); while (cache.size > 64) cache.remove(cache.keys.first()) }
        return ids
    }
    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        fun parse(payload: JSONObject): List<String> {
            val meta = payload.optJSONObject("meta") ?: return emptyList()
            val result = mutableListOf<String>()
            val trailers = meta.optJSONArray("trailers")
            for (i in 0 until minOf(trailers?.length() ?: 0, 30)) {
                val row = trailers?.optJSONObject(i) ?: continue
                if (row.optString("type").equals("Trailer", true)) result += row.optString("source")
            }
            val streams = meta.optJSONArray("trailerStreams")
            for (i in 0 until minOf(streams?.length() ?: 0, 30)) streams?.optJSONObject(i)?.optString("ytId")?.let(result::add)
            return result.filter(VIDEO_ID::matches).distinct().take(3)
        }
    }
}
