package dev.pov.tv.data

import java.net.URI
import java.net.URLEncoder

/**
 * Poster services, as in AIOStreams: every poster in the app (Home, catalogs, search, library, Top 10, calendar, the
 * links page) is asked from the chosen service by the title's IMDb id, with the usual poster as the fallback when
 * the service has none or can't be reached. The addresses match AIOStreams' own (packages/core/src/poster).
 */
object PosterServices {
    class Service(val id: String, val name: String, val needsKey: Boolean, val keyHint: String = "")

    const val NONE = "none"
    const val RPDB = "rpdb"
    const val TOP_POSTERS = "top-poster"
    const val AIORATINGS = "aioratings"
    const val OPENPOSTERDB = "openposterdb"
    const val BETTERPOSTERS = "betterposters"
    const val CUSTOM = "custom"

    val ALL = listOf(
        Service(NONE, "None", false),
        Service(RPDB, "RPDB", false, "ratingposterdb.com/api-key"),
        Service(TOP_POSTERS, "TOP Posters", true, "top-posters.com"),
        Service(AIORATINGS, "AIOratings", true, "aioratings.com"),
        Service(OPENPOSTERDB, "OpenPosterDB", true, "openposterdb.com"),
        Service(BETTERPOSTERS, "BetterPosters", false, "btttr.cc"),
        Service(CUSTOM, "Custom URL", false),
    )

    // Saved in SecureStore (keys are private; the backup keeps them with the other secrets).
    const val SERVICE = "posters.service"
    const val KEY_PREFIX = "posters.key."
    const val CUSTOM_URL = "posters.custom"
    const val AIORATINGS_PROFILE = "posters.aioratings.profile"
    const val OPENPOSTERDB_URL = "posters.openposterdb.url"
    const val DEFAULT_OPENPOSTERDB = "https://openposterdb.com"
    /** RPDB's free key, open to everyone (https://api.ratingposterdb.com/t0-free-rpdb/imdb/poster-default/{imdb}.jpg). */
    const val RPDB_FREE_KEY = "t0-free-rpdb"

    class Config(val service: String, val key: String = "", val custom: String = "", val profile: String = "default", val base: String = DEFAULT_OPENPOSTERDB)

    /** Read once and kept (encrypted settings decrypt on every read); [reload] after a change in Settings. */
    @Volatile var active: Config = Config(NONE)
        private set

    fun reload(get: (String) -> String) {
        val service = get(SERVICE).takeIf { saved -> ALL.any { it.id == saved } } ?: NONE
        active = Config(service, get(KEY_PREFIX + service).trim(), get(CUSTOM_URL).trim(),
            get(AIORATINGS_PROFILE).trim().ifBlank { "default" }, get(OPENPOSTERDB_URL).trim().trimEnd('/').ifBlank { DEFAULT_OPENPOSTERDB })
    }
    fun reload(store: SecureStore) = reload { store.get(it) }

    /** The chosen service's poster for a title, or null to use the usual poster. */
    fun url(imdbId: String, type: String, config: Config = active): String? {
        if (!IMDB.matches(imdbId)) return null
        val key = enc(config.key)
        val needsKey = ALL.firstOrNull { it.id == config.service }?.needsKey == true
        if (needsKey && config.key.isBlank()) return null
        val address = when (config.service) {
            // Without the viewer's own key, RPDB's free public key (posters with ratings, limited styles).
            RPDB -> "https://api.ratingposterdb.com/${key.ifBlank { RPDB_FREE_KEY }}/imdb/poster-default/$imdbId.jpg?fallback=true"
            TOP_POSTERS -> "https://api.top-posters.com/$key/imdb/poster/$imdbId.jpg?fallback=true"
            AIORATINGS -> "https://apiv2.aioratings.com/api/$key/imdb/${enc(config.profile)}/$imdbId.jpg"
            OPENPOSTERDB -> "${config.base}/$key/imdb/poster-default/$imdbId"
            BETTERPOSTERS -> "https://btttr.cc/poster/imdb/poster-default/$imdbId.jpg"
            CUSTOM -> custom(config.custom, imdbId, type)
            else -> null
        } ?: return null
        return address.takeIf(::safe)
    }

    /**
     * A custom pattern such as https://example.com/poster/{imdb_id}.jpg: {imdb_id} or {id} take the IMDb id, {type}
     * "movie" or "series", {shape} "poster". Other placeholders are left out (the app only knows the IMDb id here).
     */
    internal fun custom(template: String, imdbId: String, type: String): String? {
        if (template.isBlank() || !(template.contains("{imdb_id") || template.contains("{id"))) return null
        var missing = false
        val filled = Regex("\\{([a-z_]+(?:\\|[a-z_]+)*)(\\?)?\\}").replace(template) { match ->
            val value = match.groupValues[1].split('|').firstNotNullOfOrNull { name ->
                when (name) { "imdb_id", "id" -> imdbId; "type" -> type; "shape" -> "poster"; else -> null }
            }
            if (value != null) enc(value) else { if (match.groupValues[2].isEmpty()) missing = true; "" }
        }
        return if (missing) null else filled
    }

    /** Checks a custom pattern for Settings: an error line, or null when it is usable. */
    fun customProblem(template: String): String? = when {
        template.isBlank() -> "Enter the poster address."
        !(template.contains("{imdb_id") || template.contains("{id")) -> "The address needs {imdb_id} (or {id}) where the IMDb id goes."
        custom(template, "tt0111161", "movie")?.let(::safe) != true -> "Use an https:// address, such as https://example.com/poster/{imdb_id}.jpg."
        else -> null
    }

    fun validBase(address: String): Boolean = safe("${address.trim().trimEnd('/')}/x")

    /** Only https addresses with a host and no login inside, as for every other image the app loads. */
    private fun safe(address: String): Boolean = try {
        val uri = URI(address)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    } catch (_: Exception) { false }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private val IMDB = Regex("tt[0-9]{5,12}")
}
