package dev.pov.tv.data

import org.json.JSONObject

/**
 * AIOStreams' own library (the files the viewer has streamed through it, such as Usenet downloads), which it offers as
 * a catalog of type "library". Shown in the app's Library next to the debrid clouds. Call on a worker.
 */
internal class AioLibrary(private val fetch: (String) -> String = { url -> Net.raw(url) }) {
    /** One AIOStreams library: the source provider it belongs to and its catalog. */
    data class Source(val provider: String, val address: String, val catalog: String)
    data class Entry(val id: String, val name: String)
    data class Link(val url: String, val headers: Map<String, String>)

    /** The libraries among the enabled source providers (each manifest read once per session). */
    fun sources(providers: List<SourceProvider>): List<Source> = providers.filter { it.enabled }.flatMap { provider ->
        val base = runCatching { SourceProviders.endpoint(provider.address) }.getOrNull() ?: return@flatMap emptyList()
        val catalogs = synchronized(manifests) { manifests[base] } ?: runCatching {
            libraryCatalogs(JSONObject(fetch("$base/manifest.json")))
        }.getOrDefault(emptyList()).also { synchronized(manifests) { manifests[base] = it } }
        catalogs.map { Source(provider.name, base, it) }
    }

    /** A page of library files, newest first as AIOStreams lists them; [skip] pages on. */
    fun entries(source: Source, skip: Int = 0): List<Entry> {
        val path = "${source.address}/catalog/library/${Net.encode(source.catalog)}" + if (skip > 0) "/skip=$skip.json" else ".json"
        val metas = JSONObject(fetch(path)).optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }.mapNotNull { meta ->
            val id = meta.optString("id").ifBlank { return@mapNotNull null }
            Entry(id, meta.optString("name").ifBlank { return@mapNotNull null }.take(300))
        }
    }

    /**
     * The file's playable link. A library entry holds its video(s) in its meta; the stream is asked for by the video's
     * id (AIOStreams answers the entry's own id with an error).
     */
    fun link(source: Source, entry: Entry): Link? {
        val meta = JSONObject(fetch("${source.address}/meta/library/${Net.encode(entry.id)}.json")).optJSONObject("meta")
        val video = meta?.optJSONArray("videos")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            ?: meta?.optJSONObject("behaviorHints")?.optString("defaultVideoId")?.takeIf { it.isNotBlank() } ?: entry.id
        val streams = JSONObject(fetch("${source.address}/stream/library/${Net.encode(video)}.json")).optJSONArray("streams") ?: return null
        return (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.firstNotNullOfOrNull { stream ->
            val url = stream.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@firstNotNullOfOrNull null
            val request = stream.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            Link(url, request?.keys()?.asSequence()?.mapNotNull { name ->
                val value = request.optString(name)
                if (name.isBlank() || name.any { it <= ' ' || it == ':' } || value.any { it == '\r' || it == '\n' }) null else name to value
            }?.take(20)?.toMap().orEmpty())
        }
    }

    companion object {
        private val manifests = mutableMapOf<String, List<String>>()

        fun libraryCatalogs(manifest: JSONObject): List<String> {
            val catalogs = manifest.optJSONArray("catalogs") ?: return emptyList()
            return (0 until catalogs.length()).mapNotNull { catalogs.optJSONObject(it) }
                .filter { it.optString("type") == "library" && it.optString("id").isNotBlank() }.map { it.optString("id") }
        }
    }
}
