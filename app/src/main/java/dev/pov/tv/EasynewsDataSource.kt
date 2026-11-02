package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import dev.pov.tv.data.Easynews
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Sends Easynews addresses through [EasynewsHttpDataSource] with the saved login, and everything else (other links,
 * subtitles) through the normal HTTP source, which never sees the login.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EasynewsRoutingDataSource(
    private val normal: DataSource,
    private val login: () -> Map<String, String>?,
    private val connections: () -> Int = { 1 }
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener; normal.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val headers = if (Easynews.isHost(dataSpec.uri.toString())) login() else null
        val count = connections()
        val source = when {
            headers == null -> normal
            count > 1 -> EasynewsParallelDataSource(headers, count)
            else -> EasynewsHttpDataSource(headers)
        }
        if (source !== normal) listeners.forEach(source::addTransferListener)
        active = source
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { try { active?.close() } finally { active = null } }

    class Factory(private val normal: DataSource.Factory, private val connections: () -> Int = { 1 },
                  private val login: () -> Map<String, String>?) : DataSource.Factory {
        override fun createDataSource(): DataSource = EasynewsRoutingDataSource(normal.createDataSource(), login, connections)
    }
}

/**
 * The upstream fork's protected source: redirects are followed by hand and each hop must be an Easynews HTTPS host
 * before the Authorization header is sent to it.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EasynewsHttpDataSource(
    private val headers: Map<String, String>,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : BaseDataSource(true), HttpDataSource {
    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()
    private var address: Uri? = null
    private val extra = linkedMapOf<String, String>()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException("Only video GET requests are supported.")
        var next = dataSpec.uri.toString()
        try {
            for (redirect in 0..5) {
                if (!Easynews.isHost(next)) throw IOException("Easynews redirected outside its own service.")
                val current = connect(URI(next))
                connection = current
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000; current.readTimeout = 45_000
                current.requestMethod = "GET"
                current.setRequestProperty("Accept-Encoding", "identity")
                extra.forEach { (key, value) -> current.setRequestProperty(key, value) }
                dataSpec.httpRequestHeaders.forEach { (key, value) -> current.setRequestProperty(key, value) }
                headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                    current.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                val code = current.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = current.getHeaderField("Location") ?: throw IOException("Easynews sent an invalid redirect.")
                    next = URI(next).resolve(location).toString()
                    current.disconnect(); connection = null
                    if (redirect == 5) throw IOException("Easynews sent too many redirects.")
                    continue
                }
                if (code !in 200..299) throw IOException(if (code == 401 || code == 403)
                    "Easynews didn't accept your login, or the link expired." else "Easynews returned HTTP $code.")
                val input = current.inputStream
                stream = input
                val skip = if (code == 200) dataSpec.position else 0L
                if (code == 206 && dataSpec.position > 0) {
                    val rangeStart = current.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (rangeStart != dataSpec.position) throw IOException("Easynews returned an unexpected byte range.")
                }
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                    else current.getHeaderFieldLong("Content-Length", -1).let { if (it >= 0) (it - skip).coerceAtLeast(0) else C.LENGTH_UNSET.toLong() }
                address = Uri.parse(next)
                opened = true; transferStarted(dataSpec)
                var toSkip = skip
                val scratch = ByteArray(8192)
                while (toSkip > 0) {
                    val read = input.read(scratch, 0, toSkip.coerceAtMost(scratch.size.toLong()).toInt())
                    if (read < 0) throw IOException("Easynews didn't provide the requested video position.")
                    toSkip -= read; bytesTransferred(read)
                }
                return remaining
            }
            throw IOException("Easynews couldn't open this video.")
        } catch (failure: IOException) { close(); throw failure }
        catch (_: RuntimeException) { close(); throw IOException("The Easynews connection couldn't be opened.") }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val request = if (remaining < 0) length else remaining.coerceAtMost(length.toLong()).toInt()
        val read = stream?.read(buffer, offset, request) ?: C.RESULT_END_OF_INPUT
        if (read > 0) { if (remaining >= 0) remaining -= read; bytesTransferred(read) }
        return read
    }
    override fun getUri(): Uri? = address
    override fun getResponseHeaders(): Map<String, List<String>> {
        val fields: Map<String?, List<String>> = connection?.headerFields ?: return emptyMap()
        return fields.mapNotNull { (key, values) -> key?.let { it to values } }.toMap()
    }
    override fun close() {
        try { stream?.close() } finally {
            stream = null; connection?.disconnect(); connection = null; address = null
            if (opened) { opened = false; transferEnded() }
        }
    }
    override fun setRequestProperty(name: String, value: String) { if (!name.equals("Authorization", true)) extra[name] = value }
    override fun clearRequestProperty(name: String) { extra.remove(name) }
    override fun clearAllRequestProperties() { extra.clear() }
    override fun getResponseCode(): Int = connection?.let { runCatching { it.responseCode }.getOrDefault(-1) } ?: -1
}
