package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What an add-on's manifest says it offers (streams, subtitles, catalogs…), read once and kept for a while per address.
 * Lets subtitle add-ons be used for subtitles only, and keeps a subtitles-only add-on from showing as "unavailable"
 * when links are searched. Call [info] on a worker.
 */
internal object AddonManifests {
    /** [types]/[idPrefixes] per resource name; an empty set means any. */
    data class Info(val resources: Map<String, Pair<Set<String>, Set<String>>>) {
        fun offers(resource: String, type: String = "", id: String = ""): Boolean {
            val (types, prefixes) = resources[resource] ?: return false
            return (type.isEmpty() || types.isEmpty() || type in types) && (id.isEmpty() || prefixes.isEmpty() || prefixes.any(id::startsWith))
        }
    }

    private const val KEEP_MS = 12 * 60 * 60 * 1000L
    private const val KEEP_FAILED_MS = 10 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Pair<Long, Info?>>()

    /** Cached only: null when this add-on's manifest hasn't been read (or couldn't be). */
    fun peek(base: String): Info? = cache[base]?.takeIf { System.currentTimeMillis() < it.first }?.second

    fun info(base: String, fetch: (String) -> JSONObject = { Net.json(it) }): Info? {
        cache[base]?.takeIf { System.currentTimeMillis() < it.first }?.let { return it.second }
        val info = runCatching { parse(fetch("$base/manifest.json")) }.getOrNull()
        cache[base] = System.currentTimeMillis() + (if (info == null) KEEP_FAILED_MS else KEEP_MS) to info
        return info
    }

    /** Remembers a manifest already fetched elsewhere (adding the add-on), so playback needn't ask again. */
    fun remember(base: String, manifest: JSONObject) { cache[base] = System.currentTimeMillis() + KEEP_MS to parse(manifest) }

    fun parse(manifest: JSONObject): Info {
        val types = strings(manifest.optJSONArray("types"))
        val prefixes = strings(manifest.optJSONArray("idPrefixes"))
        val list = manifest.optJSONArray("resources") ?: JSONArray()
        val resources = (0 until list.length()).mapNotNull { index ->
            when (val item = list.opt(index)) {
                is String -> item to (types to prefixes)
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let { name ->
                    name to ((item.optJSONArray("types")?.let(::strings) ?: types) to (item.optJSONArray("idPrefixes")?.let(::strings) ?: prefixes))
                }
                else -> null
            }
        }.toMap()
        return Info(resources)
    }

    private fun strings(array: JSONArray?): Set<String> =
        if (array == null) emptySet() else (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()

    internal fun clearForTest() = cache.clear()
}
