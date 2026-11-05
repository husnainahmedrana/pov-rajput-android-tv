package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import dev.pov.tv.data.NativeTrailerSource

/** Bounded query ranges for public YouTube video files (NuvioTV-inspired, GPL-3.0).
 * Keeps Media3 seek/length semantics and propagates failed chunks instead of truncating video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TrailerDataSourceFactory(private val source: NativeTrailerSource.Source): DataSource.Factory {
    override fun createDataSource(): DataSource = object: DataSource {
        private val upstream = DefaultHttpDataSource.Factory().setUserAgent(source.userAgent)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000).createDataSource()
        private var original: DataSpec? = null
        private var position = 0L
        private var remaining = 0L
        private var chunkRemaining = 0L
        private var opened = false
        override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
        override fun getUri(): Uri? = original?.uri
        override fun getResponseHeaders(): Map<String,List<String>> = upstream.responseHeaders
        override fun open(dataSpec: DataSpec): Long {
            original = dataSpec; position = dataSpec.position
            remaining = (source.length - position).coerceAtLeast(0)
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining,dataSpec.length)
            if (remaining > 0) openChunk()
            return remaining
        }
        private fun openChunk() {
            val spec = checkNotNull(original)
            chunkRemaining = minOf(remaining, 1024L*1024)
            val uri = spec.uri.buildUpon().appendQueryParameter("range","$position-${position+chunkRemaining-1}").build()
            opened = true
            upstream.open(spec.buildUpon().setUri(uri).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (chunkRemaining == 0L) { upstream.close(); opened = false; openChunk() }
            val count = upstream.read(buffer,offset,minOf(length.toLong(),chunkRemaining).toInt())
            if (count == C.RESULT_END_OF_INPUT) throw java.io.EOFException("Incomplete trailer chunk")
            position += count; remaining -= count; chunkRemaining -= count
            return count
        }
        override fun close() { try { if(opened) upstream.close() } finally { opened=false; original=null } }
    }
}
