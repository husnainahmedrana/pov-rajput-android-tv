package dev.pov.tv.data

import dev.pov.tv.UiText

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class SkipKind(private val english: String) {
    INTRO("Skip Intro"), RECAP("Skip Recap"), CREDITS("Skip Credits");
    val label: String get() = UiText.of(english)
}

/** One skippable part of a video. [endMs] null means it runs to the end (credits). */
data class SkipSegment(val kind: SkipKind, val startMs: Long, val endMs: Long?)

/** Which Kitsu anime entry and episode number an IMDb-numbered episode came from, for AniSkip. */
data class AnimeEpisodeRef(val kitsuId: String, val episode: Int)

/**
 * Intro, recap and credits times from public community databases:
 *  - anime: AniSkip (by MyAnimeList ID, mapped from Kitsu), then TheIntroDB;
 *  - shows and movies: TheIntroDB (api.theintrodb.org, by IMDb ID; no key, 5,000 lookups a day per IP).
 * Nothing is sent but the title's public IDs, episode numbers and the video's length.
 */
class SkipSegmentsApi internal constructor(
    private val get: (String, Map<String, String>) -> String = { url, headers -> Net.raw(url, "GET", headers) }
) {
    constructor() : this({ url, headers -> Net.raw(url, "GET", headers) })

    fun segments(identity: PlaybackIdentity, durationMs: Long, anime: AnimeEpisodeRef?): List<SkipSegment> {
        if (!identity.imdbId.matches(Regex("tt\\d{7,8}")) || durationMs <= 0) return emptyList()
        val key = "${identity.imdbId}:${identity.season}:${identity.episode}:${durationMs / 60_000}:${anime?.kitsuId}"
        cache[key]?.let { return it }
        val found = anime?.let { aniSkip(it, durationMs) }.orEmpty().ifEmpty { introDb(identity, durationMs) }
        return clean(found, durationMs).also { cache[key] = it }
    }

    private fun introDb(identity: PlaybackIdentity, durationMs: Long): List<SkipSegment> {
        val episode = if (identity.type == "series") "&season=${identity.season}&episode=${identity.episode}" else ""
        val text = try { get("https://api.theintrodb.org/v4/media?imdb_id=${identity.imdbId}$episode&duration_ms=$durationMs", emptyMap()) }
            catch (_: Exception) { return emptyList() }
        return try { parseIntroDb(JSONObject(text)) } catch (_: Exception) { emptyList() }
    }

    private fun aniSkip(anime: AnimeEpisodeRef, durationMs: Long): List<SkipSegment> {
        val mal = malId(anime.kitsuId) ?: return emptyList()
        val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed").joinToString("&") { "types%5B%5D=$it" }
        val text = try { get("https://api.aniskip.com/v2/skip-times/$mal/${anime.episode}?$types&episodeLength=${durationMs / 1000}", emptyMap()) }
            catch (_: Exception) { return emptyList() } // 404: AniSkip has nothing for this episode
        return try { parseAniSkip(JSONObject(text)) } catch (_: Exception) { emptyList() }
    }

    private fun malId(kitsuId: String): String? {
        if (!kitsuId.matches(Regex("\\d{1,9}"))) return null
        malIds[kitsuId]?.let { return it.ifEmpty { null } }
        val id = try {
            val json = JSONObject(get("https://kitsu.app/api/edge/anime/$kitsuId/mappings?filter%5BexternalSite%5D=myanimelist%2Fanime",
                mapOf("Accept" to "application/vnd.api+json")))
            val data = json.optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optJSONObject("attributes") }
                .firstOrNull { it.optString("externalSite") == "myanimelist/anime" }?.optString("externalId")?.takeIf { it.matches(Regex("\\d{1,9}")) }
        } catch (_: Exception) { return null } // network trouble: try again next time
        malIds[kitsuId] = id.orEmpty()
        return id
    }

    internal fun parseIntroDb(json: JSONObject): List<SkipSegment> = buildList {
        fun read(name: String, kind: SkipKind, startsAtBeginning: Boolean) {
            val list = json.optJSONArray(name) ?: return
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val start = if (item.isNull("start_ms")) (if (startsAtBeginning) 0L else continue) else item.optLong("start_ms")
                val end = if (item.isNull("end_ms")) (if (startsAtBeginning) continue else null) else item.optLong("end_ms")
                if (end == 0L) continue // "no segment"
                add(SkipSegment(kind, start, end))
            }
        }
        read("intro", SkipKind.INTRO, true)
        read("recap", SkipKind.RECAP, true)
        read("credits", SkipKind.CREDITS, false)
    }

    internal fun parseAniSkip(json: JSONObject): List<SkipSegment> {
        if (!json.optBoolean("found")) return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val kind = when (item.optString("skipType")) {
                "op", "mixed-op" -> SkipKind.INTRO
                "ed", "mixed-ed" -> SkipKind.CREDITS
                "recap" -> SkipKind.RECAP
                else -> return@mapNotNull null
            }
            val interval = item.optJSONObject("interval") ?: return@mapNotNull null
            SkipSegment(kind, (interval.optDouble("startTime") * 1000).toLong(), (interval.optDouble("endTime") * 1000).toLong())
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<String, List<SkipSegment>>()
        private val malIds = ConcurrentHashMap<String, String>()

        /**
         * Drops impossible or useless segments (under 3 s, outside the video, credits in the first third) and joins
         * pieces of the same kind that touch, such as an intro submitted in two parts.
         */
        internal fun clean(segments: List<SkipSegment>, durationMs: Long): List<SkipSegment> {
            val valid = segments.mapNotNull { segment ->
                val start = segment.startMs.coerceAtLeast(0)
                val end = segment.endMs?.let { if (it >= durationMs - 2_000) null else it }
                if (start >= durationMs - 2_000) return@mapNotNull null
                if ((end ?: durationMs) - start < 3_000) return@mapNotNull null
                if (segment.kind == SkipKind.CREDITS && start < durationMs / 3) return@mapNotNull null
                if (segment.kind != SkipKind.CREDITS && end == null) return@mapNotNull null
                SkipSegment(segment.kind, start, end)
            }.sortedBy { it.startMs }
            return valid.fold(mutableListOf()) { merged, next ->
                val last = merged.lastOrNull()
                if (last != null && last.kind == next.kind && last.endMs != null && next.startMs - last.endMs <= 3_000) {
                    merged[merged.lastIndex] = last.copy(endMs = next.endMs?.let { maxOf(it, last.endMs) })
                } else merged += next
                merged
            }
        }
    }
}

/** Remembers which Kitsu anime and episode each IMDb-numbered anime episode is, so the player can ask AniSkip. */
object AnimeEpisodeRefs {
    private const val PREFS = "pov_anime_episode_refs"
    private const val LIMIT = 5_000

    private fun key(identity: PlaybackIdentity) = "${identity.imdbId}:${identity.season}:${identity.episode}"

    fun remember(context: Context, catalog: AnimeEpisodeCatalog) {
        if (!catalog.anime.kitsuId.matches(Regex("\\d{1,9}"))) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (prefs.all.size > LIMIT) editor.clear()
        catalog.groups.flatMap { it.episodes }.forEach { choice -> editor.putString(key(choice.identity), "${catalog.anime.kitsuId}:${choice.catalogEpisode}") }
        editor.apply()
    }

    fun find(context: Context, identity: PlaybackIdentity): AnimeEpisodeRef? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(identity), null) ?: return null
        val (kitsu, episode) = value.split(':').takeIf { it.size == 2 } ?: return null
        return episode.toIntOrNull()?.takeIf { it > 0 }?.let { AnimeEpisodeRef(kitsu, it) }
    }
}
