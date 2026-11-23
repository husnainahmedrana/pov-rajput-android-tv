package dev.pov.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A filter the add-on insists on (usually a genre), with the values it accepts. */
internal data class AddonFilter(val name: String, val options: List<String>)

/**
 * One movie or series catalog an add-on offers, e.g. AIOMetadata's "Trending Netflix". [extra] is the filter value a
 * row always sends ("genre=Action") for catalogs that require one; [filter] is only set while the viewer picks rows.
 */
internal data class AddonCatalog(val type: String, val id: String, val name: String, val extra: String = "", val filter: AddonFilter? = null) {
    /** The same catalog with one of its filter's values chosen, as its own row ("Top · Movies · Action"). */
    fun withValue(value: String): AddonCatalog = copy(extra = "${filter?.name ?: "genre"}=$value", name = "$name · $value".take(100), filter = null)
}

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
    /** The viewer's TMDB key, when set: the quickest way to find the IMDb id of a title an add-on names by TMDB id. */
    private val tmdbKey: () -> String = { "" },
    private val tmdbGet: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, "GET", headers) },
) {
    constructor(store: SecureStore) : this({ store.get(it) }, { key, value -> store.put(key, value) }, tmdbKey = { store.get(TMDB_KEY) })

    fun load(): List<CatalogAddon> = try {
        val saved = get(KEY)
        if (saved.isBlank()) emptyList() else JSONArray(saved).let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val catalogs = item.getJSONArray("catalogs")
                CatalogAddon(item.getString("id"), item.getString("name"), item.getString("address"),
                    (0 until catalogs.length()).map { catalogs.getJSONObject(it).let { c -> AddonCatalog(c.getString("type"), c.getString("id"), c.getString("name"), c.optString("extra")) } })
            }
        }
    } catch (_: Exception) { emptyList() }

    fun save(addons: List<CatalogAddon>) {
        require(addons.size <= MAX) { "You can add up to $MAX catalog add-ons." }
        put(KEY, JSONArray().apply {
            addons.forEach { addon -> put(JSONObject().put("id", addon.id).put("name", addon.name).put("address", addon.address)
                .put("catalogs", JSONArray().apply { addon.catalogs.forEach { put(JSONObject().put("type", it.type).put("id", it.id).put("name", it.name).put("extra", it.extra)) } })) }
        }.toString())
    }

    /** Reads an add-on's manifest: its name and the movie/series catalogs that can be Home rows. */
    fun inspect(address: String): Pair<String, List<AddonCatalog>> {
        val base = SourceProviders.endpoint(address)
        val manifest = fetch("$base/manifest.json")
        val name = manifest.optString("name").trim().take(50).ifBlank { "Catalog add-on" }
        return name to parseCatalogs(manifest)
    }

    fun create(address: String, name: String, catalogs: List<AddonCatalog>) =
        CatalogAddon(UUID.randomUUID().toString(), name, SourceProviders.endpoint(address), catalogs)

    /** One page of a catalog; [skip] counts titles, as Stremio add-ons expect. Titles with no IMDb id to be found are left out. */
    fun items(addon: CatalogAddon, catalog: AddonCatalog, skip: Int = 0): List<MediaItemData> {
        val url = "${addon.address}/catalog/${catalog.type}/${Net.encode(catalog.id)}${extraPath(catalog.extra, skip)}.json"
        val answer = fetch(url)
        answer.optJSONArray("metas")?.let { metas -> withImdbIds(addon, catalog.type, metas) }
        return parser.parseCatalog(answer, catalog.type)
    }

    /**
     * Many add-ons name titles by TMDB or Kitsu id ("tmdb:550", "kitsu:1"), which the app can't play or look up. Each
     * such title gets its IMDb id written in, from TMDB (with the viewer's key), the add-on's own details for it, or
     * the Kitsu mapping; answers are remembered for the session, and titles none of them knows stay out as before.
     */
    private fun withImdbIds(addon: CatalogAddon, type: String, metas: JSONArray) {
        val missing = (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }
            .filter { !IMDB.matches(it.optString("imdb_id")) && !IMDB.matches(it.optString("id")) && it.optString("id").isNotBlank() }
            .take(MAX_LOOKUPS)
        if (missing.isEmpty()) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, missing.size))
        try {
            val tasks = missing.map { meta -> meta to pool.submit(java.util.concurrent.Callable { imdbFor(addon, type, meta.optString("id")) }) }
            val until = System.nanoTime() + LOOKUP_BUDGET_NS
            tasks.forEach { (meta, task) ->
                val found = try { task.get(maxOf(1L, until - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS) } catch (_: Exception) { null }
                if (found != null) meta.put("imdb_id", found)
            }
        } finally { pool.shutdownNow() }
    }

    private fun imdbFor(addon: CatalogAddon, type: String, id: String): String? {
        val key = "${addon.address}|$type|$id"
        IDS[key]?.let { return it.takeIf(IMDB::matches) }
        val number = id.substringAfter(':', "").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val tmdb = tmdbKey().trim()
        val steps = listOf<() -> String?>(
            { if (id.startsWith("tmdb:") && number != null && tmdb.isNotBlank()) tmdbImdb(if (type == "movie") "movie" else "tv", number, tmdb) else null },
            { fetch("${addon.address}/meta/$type/${Net.encode(id)}.json").optJSONObject("meta")?.let { meta ->
                meta.optString("imdb_id").takeIf(IMDB::matches) ?: meta.optString("id").takeIf(IMDB::matches) } },
            { if (id.startsWith("kitsu:") && number != null) fetch("${AnimeCatalogApi.MAPPING_BASE}/meta/${if (type == "movie") "movie" else "series"}/kitsu:$number.json")
                .optJSONObject("meta")?.optString("imdb_id")?.takeIf(IMDB::matches) else null },
        )
        val found = steps.firstNotNullOfOrNull { step -> try { step() } catch (_: Exception) { null } }
        if (IDS.size > 5000) IDS.clear()
        IDS[key] = found ?: "-"
        return found
    }

    private fun tmdbImdb(kind: String, number: String, key: String): String? {
        val bearer = key.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3/$kind/$number/external_ids" + if (bearer) "" else "?api_key=${Net.encode(key)}"
        return JSONObject(tmdbGet(url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap())).optString("imdb_id").takeIf(IMDB::matches)
    }

    companion object {
        const val KEY = "catalogs.addons.v1"
        const val MAX = 12
        private const val TMDB_KEY = "tmdb.key"
        private val IMDB = Regex("tt[0-9]{5,12}")
        private val IDS = java.util.concurrent.ConcurrentHashMap<String, String>()
        private const val MAX_LOOKUPS = 60
        private const val LOOKUP_BUDGET_NS = 6_000_000_000L

        /** Row ids for Home: one per picked catalog (and filter value). */
        fun rowId(addon: CatalogAddon, catalog: AddonCatalog) = "addon:${addon.id}:${catalog.type}:${catalog.id}" + if (catalog.extra.isBlank()) "" else ":${catalog.extra}"

        /** "/genre=Action&skip=20": Stremio's way of sending a catalog's extras in the address. */
        internal fun extraPath(extra: String, skip: Int): String {
            val parts = buildList {
                if (extra.isNotBlank()) {
                    val name = extra.substringBefore('='); val value = extra.substringAfter('=', "")
                    add("${Net.encode(name)}=${Net.encode(value).replace("+", "%20")}")
                }
                if (skip > 0) add("skip=$skip")
            }
            return if (parts.isEmpty()) "" else "/" + parts.joinToString("&")
        }

        internal fun parseCatalogs(manifest: JSONObject): List<AddonCatalog> {
            val catalogs = manifest.optJSONArray("catalogs") ?: return emptyList()
            return (0 until catalogs.length()).mapNotNull { index ->
                val catalog = catalogs.optJSONObject(index) ?: return@mapNotNull null
                val type = catalog.optString("type")
                val id = catalog.optString("id")
                if (type !in setOf("movie", "series") || id.isBlank()) return@mapNotNull null
                val extras = catalog.optJSONArray("extra")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
                val required = buildSet {
                    catalog.optJSONArray("extraRequired")?.let { array -> (0 until array.length()).forEach { add(array.optString(it)) } }
                    extras.filter { it.optBoolean("isRequired") }.forEach { add(it.optString("name")) }
                }.filter { it.isNotBlank() }
                val name = catalog.optString("name").trim().ifBlank { id }
                val plain = AddonCatalog(type, id, "$name · ${if (type == "movie") "Movies" else "Shows"}".take(80))
                if (required.isEmpty()) return@mapNotNull plain
                // A required filter with a list of values (genre, year…) works: the viewer picks values, each its own
                // row. One that needs typed text (search) or several filters at once can't be a Home row.
                if (required.size > 1 || required.single() == "search") return@mapNotNull null
                val wanted = required.single()
                val options = extras.firstOrNull { it.optString("name") == wanted }?.optJSONArray("options")
                    ?.let { array -> (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }.distinct() }.orEmpty()
                if (options.isEmpty()) return@mapNotNull null
                plain.copy(filter = AddonFilter(wanted, options.take(200)))
            }.distinctBy { it.type to it.id }
        }

        private val parser = CatalogApi { throw UnsupportedOperationException() }
    }
}
