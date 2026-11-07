package dev.pov.tv.data

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * AltMount's library: the files it has imported, in its WebDAV folders (Movies, TV, complete, stremio…). Its Stremio
 * add-on offers no catalog, so the app reads the folders with the viewer's WebDAV login in one deep listing and plays
 * a file straight from WebDAV. The login is kept encrypted on the device, like the Easynews one. Call on a worker.
 */
internal class AltMountLibrary(private val propfind: (String, String, String) -> Pair<Int, String> = ::request) {
    data class File(val path: String, val name: String, val folder: String, val sizeBytes: Long, val modified: Long)

    /** Checks the login against the WebDAV root; throws with a readable reason when it is refused. */
    fun verify(origin: String, user: String, password: String) {
        val (code, _) = propfind("$origin$ROOT", Credentials.basic(user, password, Charsets.UTF_8), "0")
        when {
            code == 401 || code == 403 -> throw ApiException(code, "AltMount did not accept that WebDAV username and password.")
            code == 404 -> throw ApiException(code, "AltMount's WebDAV isn't at ${origin.removePrefix("https://")}$ROOT.")
            code !in 200..299 -> throw ApiException(code, "AltMount's WebDAV answered with error $code.")
        }
    }

    /** Every video file in the library, newest first. Its temporary folder is left out. */
    fun files(origin: String, user: String, password: String): List<File> {
        val (code, body) = propfind("$origin$ROOT", Credentials.basic(user, password, Charsets.UTF_8), "infinity")
        if (code == 401 || code == 403) throw ApiException(code, "AltMount no longer accepts the saved WebDAV login.")
        if (code !in 200..299) throw ApiException(code, "AltMount's WebDAV answered with error $code.")
        return parse(body)
    }

    companion object {
        const val ROOT = "/webdav/"
        /** SecureStore keys: the AltMount server and its WebDAV login (needed to play, so the password is kept). */
        const val ORIGIN = "altmount.library.origin"
        const val USER = "altmount.library.user"
        const val PASSWORD = "altmount.library.password"

        private val VIDEO = Regex("(?i)\\.(mkv|mp4|m4v|avi|ts|m2ts|webm|mov)$")
        private val RESPONSE = Regex("(?is)<(?:[a-z0-9]+:)?response\\b[^>]*>(.*?)</(?:[a-z0-9]+:)?response>")
        private val HREF = Regex("(?is)<(?:[a-z0-9]+:)?href[^>]*>([^<]*)<")
        private val COLLECTION = Regex("(?i)<(?:[a-z0-9]+:)?collection\\b")
        private val LENGTH = Regex("(?is)<(?:[a-z0-9]+:)?getcontentlength[^>]*>\\s*(\\d+)\\s*<")
        private val MODIFIED = Regex("(?is)<(?:[a-z0-9]+:)?getlastmodified[^>]*>([^<]*)<")
        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
        }

        private fun request(url: String, authorization: String, depth: String): Pair<Int, String> {
            val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
            val call = Request.Builder().url(url).header("Authorization", authorization).header("Depth", depth)
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
            return client.newCall(call).execute().use { response ->
                // A deep listing of a few thousand files is a few MB; anything far larger is not a library listing.
                response.code to (response.body?.source()?.let { source -> source.request(20L * 1024 * 1024); source.buffer.readUtf8() }.orEmpty())
            }
        }

        /** The AltMount server the add-on link points at ("https://host"), where its WebDAV lives. */
        fun origin(endpoint: String): String? = runCatching {
            val uri = URI(endpoint)
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) null else "https://${uri.rawAuthority}"
        }.getOrNull()

        /** The streaming address of [file]: its WebDAV path is already URL-encoded. */
        fun url(origin: String, file: File) = origin + file.path

        fun authorization(user: String, password: String) = Credentials.basic(user, password, Charsets.UTF_8)

        internal fun parse(xml: String): List<File> = RESPONSE.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            if (COLLECTION.containsMatchIn(block)) return@mapNotNull null
            val href = HREF.find(block)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&") ?: return@mapNotNull null
            // Only paths on this server, inside the library root, and never its temporary folder.
            val path = runCatching { URI(href).rawPath }.getOrNull()?.takeIf { it.startsWith(ROOT) } ?: return@mapNotNull null
            val decoded = runCatching { URI(path).path }.getOrNull() ?: return@mapNotNull null
            if (!VIDEO.containsMatchIn(decoded) || decoded.startsWith("${ROOT}tmp/")) return@mapNotNull null
            val name = decoded.substringAfterLast('/').replace(VIDEO, "")
            val folder = decoded.removePrefix(ROOT).substringBefore('/', "")
            val modified = MODIFIED.find(block)?.groupValues?.get(1)?.trim()?.let { value ->
                runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            } ?: 0L
            File(path, name.take(300), folder, LENGTH.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L, modified)
        }.distinctBy { it.path }.sortedByDescending { it.modified }.toList()
    }
}
