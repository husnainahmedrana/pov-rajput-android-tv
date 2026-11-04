package dev.pov.tv.data

import org.json.JSONObject

/**
 * A catalog add-on (such as a self-hosted AIOMetadata) used for search and title details instead of Cinemeta: its
 * movie and series search catalogs, and whether it serves details ("meta"). Cinemeta stays the fallback.
 */
internal data class MetadataSource(val name: String, val address: String, val searches: Map<String, String>, val details: Boolean) {
    fun toJson(): String = JSONObject().put("name", name).put("address", address).put("details", details)
        .put("searches", JSONObject(searches)).toString()

    companion object {
        const val KEY = "metadata.source.v1"

        fun fromJson(value: String): MetadataSource? = runCatching {
            val json = JSONObject(value)
            val searches = json.optJSONObject("searches")
            MetadataSource(json.getString("name"), json.getString("address"),
                searches?.keys()?.asSequence()?.associateWith { searches.getString(it) }.orEmpty(), json.optBoolean("details"))
        }.getOrNull()?.takeIf { it.address.startsWith("https://") }

        /** What an add-on's manifest offers: the first search catalog for movies and for series, and details. */
        fun inspect(name: String, address: String, manifest: JSONObject): MetadataSource? {
            val catalogs = manifest.optJSONArray("catalogs")
            val searches = mutableMapOf<String, String>()
            for (index in 0 until (catalogs?.length() ?: 0)) {
                val catalog = catalogs?.optJSONObject(index) ?: continue
                val type = catalog.optString("type")
                if (type !in setOf("movie", "series") || type in searches) continue
                val extras = catalog.optJSONArray("extra")
                val search = (0 until (extras?.length() ?: 0)).any { extras?.optJSONObject(it)?.optString("name") == "search" }
                // People search lists people, not titles.
                if (search && !catalog.optString("id").contains("people", ignoreCase = true)) searches[type] = catalog.optString("id")
            }
            val resources = manifest.optJSONArray("resources")
            val details = (0 until (resources?.length() ?: 0)).any { index ->
                (resources?.opt(index) as? String) == "meta" || (resources?.optJSONObject(index)?.optString("name") == "meta")
            }
            return if (searches.isEmpty() && !details) null else MetadataSource(name, address, searches, details)
        }
    }
}
