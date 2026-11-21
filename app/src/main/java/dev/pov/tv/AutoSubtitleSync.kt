package dev.pov.tv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.SubtitleAligner
import org.json.JSONObject

/**
 * Auto subtitle sync on a stream: when a downloaded subtitle (OpenSubtitles, Wyzie, SubDL, a subtitle add-on) is shown,
 * it is lined up with a reference made for THIS exact file, in any language:
 * 1. subtitles the link's own add-on sent with the stream, else
 * 2. OpenSubtitles subtitles found by the file's hash (only its first and last 64 KB are read, through the player's
 *    own sources, so Usenet, Easynews and debrid all work).
 * A subtitle that is itself one of those is already right and is left alone, as is one the viewer moved by hand.
 * The answer is applied only when [SubtitleAligner] is sure of it.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object AutoSubtitleSync {
    data class Subtitle(val url: String, val language: String, val fromStream: Boolean)

    /** Runs off the main thread; [done] gets the timing to apply, or null (already right, no reference, unsure). */
    fun run(context: Context, subtitle: Subtitle, attached: List<Subtitle>, identity: PlaybackIdentity?, playing: String,
            headers: Map<String, String>, sizeHint: Long?, done: (SubtitleAligner.Result?) -> Unit) {
        val app = context.applicationContext
        Thread({
            val result = runCatching { sync(app, subtitle, attached, identity, playing, headers, sizeHint) }
                .onFailure { android.util.Log.w(TAG, "auto sync failed: ${it.message}") }.getOrNull()
            done(result)
        }, "pov-subtitle-sync").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun sync(context: Context, subtitle: Subtitle, attached: List<Subtitle>, identity: PlaybackIdentity?, playing: String,
                     headers: Map<String, String>, sizeHint: Long?): SubtitleAligner.Result? {
        if (subtitle.fromStream) { android.util.Log.i(TAG, "the stream's own subtitle: already in sync"); return null }
        var references = attached.filter { it.url != subtitle.url }.map { it.url }
        if (references.isEmpty() && identity != null && identity.imdbId.startsWith("tt")) {
            val hashed = hashMatches(context, identity, playing, headers, sizeHint)
            if (subtitle.url in hashed) { android.util.Log.i(TAG, "a hash match for this file: already in sync"); return null }
            references = hashed
        }
        if (references.isEmpty()) { android.util.Log.i(TAG, "no subtitle made for this exact file to sync against"); return null }
        val lines = SubtitleAligner.parse(text(subtitle.url))
        for (reference in references.take(2)) {
            val result = SubtitleAligner.align(lines, SubtitleAligner.parse(runCatching { text(reference) }.getOrDefault("")))
            android.util.Log.i(TAG, "against $reference: ${result ?: "no clear answer"}")
            if (result != null) return result
        }
        return null
    }

    /** OpenSubtitles subtitles timed for this exact file (by OpenSubtitles' file hash), any language. */
    private fun hashMatches(context: Context, identity: PlaybackIdentity, playing: String, headers: Map<String, String>, sizeHint: Long?): List<String> {
        val sources = ExternalPlayer.sources(context, SecureStore(context), headers)
        fun read(position: Long, length: Int): Pair<ByteArray, Long> {
            val source = sources.createDataSource()
            try {
                val opened = source.open(DataSpec.Builder().setUri(Uri.parse(playing)).setPosition(position).setLength(length.toLong()).build())
                val out = ByteArray(length); var filled = 0
                while (filled < length) { val count = source.read(out, filled, length - filled); if (count == C.RESULT_END_OF_INPUT) break; filled += count }
                return out.copyOf(filled) to opened
            } finally { runCatching { source.close() } }
        }
        val size = sizeHint?.takeIf { it > CHUNK * 2 } ?: run {
            val source = sources.createDataSource()
            try { source.open(DataSpec.Builder().setUri(Uri.parse(playing)).build()) } finally { runCatching { source.close() } }
        }.takeIf { it > CHUNK * 2 } ?: return emptyList()
        val head = read(0, CHUNK).first
        val tail = read(size - CHUNK, CHUNK).first
        if (head.size < CHUNK || tail.size < CHUNK) return emptyList()
        val hash = SubtitleAligner.movieHash(size, head, tail)
        val type = if (identity.type == "series") "series" else "movie"
        val id = if (identity.type == "series" && identity.season > 0 && identity.episode > 0) "${identity.imdbId}:${identity.season}:${identity.episode}" else identity.imdbId
        val json = JSONObject(text("$OPEN_SUBTITLES/subtitles/$type/$id/videoHash=$hash&videoSize=$size.json"))
        val list = json.optJSONArray("subtitles") ?: return emptyList()
        val matches = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("m") == "h" }.map { it.optString("url") }
            .filter { it.startsWith("https://") }
        android.util.Log.i(TAG, "file hash $hash: ${matches.size} subtitles timed for this file")
        return matches
    }

    /** A subtitle file or an API answer as text (a cached file:// path or HTTPS, at most 4 MB). */
    private fun text(address: String): String {
        if (address.startsWith("file:") || address.startsWith("/")) return java.io.File(Uri.parse(address).path ?: address).readText()
        val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "Nabrio")
        return try {
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(4 shl 20)
                String(bytes, Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) { val count = read(buffer); if (count < 0) break; out.write(buffer, 0, minOf(count, limit - out.size())) }
        return out.toByteArray()
    }

    private const val TAG = "PovSubtitles"
    private const val CHUNK = 64 * 1024
    private const val OPEN_SUBTITLES = "https://opensubtitles-v3.strem.io"
}
