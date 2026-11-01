package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One movie or series catalog an add-on offers, e.g. AIOMetadata's "Trending Netflix". */
internal data class AddonCatalog(val type: String, val id: String, val name: String)

/** A Stremio catalog add-on the viewer added, with the catalogs they picked for Home. */
internal data class CatalogAddon(val id: String, val name: String, val address: String, val catalogs: List<AddonCatalog>)

/**
 * User catalog add-ons (AIOMetadata and other Stremio catalog add-ons). Their addresses often hold private
 * configuration, so they are kept in SecureStore like source providers. Network calls run on a worker.
 */
internal class CatalogAddons(
    private val get: (String) -> String,
    private val put: (String, String) -> Unit,
    private val fetch: (String) -> JSONObject = { Net.json(it) },
) {
    constructor(store: SecureStore) : this({ store.get(it) }, { key, value -> store.put(key, value) })

    fun load(): List<CatalogAddon> = try {
        val saved = get(KEY)
        if (saved.isBlank()) emptyList() else JSONArray(saved).let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val catalogs = item.getJSONArray("catalogs")
                CatalogAddon(item.getString("id"), item.getString("name"), item.getString("address"),
                    (0 until catalogs.length()).map { catalogs.getJSONObject(it).let { c -> AddonCatalog(c.getString("type"), c.getString("id"), c.getString("name")) } })
            }
        }
    } catch (_: Exception) { emptyList() }

    fun save(addons: List<CatalogAddon>) {
        require(addons.size <= MAX) { "You can add up to $MAX catalog add-ons." }
        put(KEY, JSONArray().apply {
            addons.forEach { addon -> put(JSONObject().put("id", addon.id).put("name", addon.name).put("address", addon.address)
                .put("catalogs", JSONArray().apply { addon.catalogs.forEach { put(JSONObject().put("type", it.type).put("id", it.id).put("name", it.name)) } })) }
        }.toString())
    }

    /** Reads an add-on's manifest: its name and the movie/series catalogs that work without a search or required filter. */
    fun inspect(address: String): Pair<String, List<AddonCatalog>> {
        val base = SourceProviders.endpoint(address)
        val manifest = fetch("$base/manifest.json")
        val name = manifest.optString("name").trim().take(50).ifBlank { "Catalog add-on" }
        return name to parseCatalogs(manifest)
    }

    fun create(address: String, name: String, catalogs: List<AddonCatalog>) =
        CatalogAddon(UUID.randomUUID().toString(), name, SourceProviders.endpoint(address), catalogs)

    /** One page of a catalog; [skip] counts titles, as Stremio add-ons expect. Titles without an IMDb id are left out. */
    fun items(addon: CatalogAddon, catalog: AddonCatalog, skip: Int = 0): List<MediaItemData> {
        val extra = if (skip > 0) "/skip=$skip" else ""
        val url = "${addon.address}/catalog/${catalog.type}/${Net.encode(catalog.id)}$extra.json"
        return parser.parseCatalog(fetch(url), catalog.type)
    }

    companion object {
        const val KEY = "catalogs.addons.v1"
        const val MAX = 12

        /** Row ids for Home: one per picked catalog. */
        fun rowId(addon: CatalogAddon, catalog: AddonCatalog) = "addon:${addon.id}:${catalog.type}:${catalog.id}"

        internal fun parseCatalogs(manifest: JSONObject): List<AddonCatalog> {
            val catalogs = manifest.optJSONArray("catalogs") ?: return emptyList()
            return (0 until catalogs.length()).mapNotNull { index ->
                val catalog = catalogs.optJSONObject(index) ?: return@mapNotNull null
                val type = catalog.optString("type")
                val id = catalog.optString("id")
                if (type !in setOf("movie", "series") || id.isBlank()) return@mapNotNull null
                // Catalogs that only answer a search or a chosen filter cannot be a Home row.
                val required = buildSet {
                    catalog.optJSONArray("extraRequired")?.let { array -> (0 until array.length()).forEach { add(array.optString(it)) } }
                    catalog.optJSONArray("extra")?.let { array -> (0 until array.length()).forEach { i ->
                        array.optJSONObject(i)?.takeIf { it.optBoolean("isRequired") }?.let { add(it.optString("name")) } } }
                }
                if (required.isNotEmpty()) return@mapNotNull null
                val name = catalog.optString("name").trim().ifBlank { id }
                AddonCatalog(type, id, "$name · ${if (type == "movie") "Movies" else "Shows"}".take(80))
            }.distinctBy { it.type to it.id }
        }

        private val parser = CatalogApi { throw UnsupportedOperationException() }
    }
}
