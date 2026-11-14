package dev.pov.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.NalUnitUtil
import java.nio.ByteBuffer

internal enum class DolbyVisionMode(private val english: String) {
    AUTO("Automatic compatibility"), HDR_BASE("Prefer HDR10 / HLG"), STANDARD("Standard player");
    val label: String get() = UiText.of(english)
}

internal class VideoCompatibilityPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pov_video", Context.MODE_PRIVATE)
    var mode: DolbyVisionMode
        get() = DolbyVisionMode.entries.firstOrNull { it.name == prefs.getString("dolby_mode", "") } ?: DolbyVisionMode.AUTO
        set(value) { prefs.edit().putString("dolby_mode", value.name).apply() }
}

/** Only clear P7/P8 with a verified Main10 base SPS and matching HDR signalling are eligible.
 * Profile 5 is intentionally excluded: its picture cannot become HDR10 by dropping metadata.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object DolbyVisionCompatibility {
    fun profile(format: Format): Int? = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION)
        format.codecs?.let { Regex("^(?:dvhe|dvh1)\\.(\\d{2})\\.\\d{2}$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } else null

    fun baseFormat(format: Format, mode: DolbyVisionMode, nativeSupported: Boolean, displayTransfers: Set<Int>): Format? {
        if (mode == DolbyVisionMode.STANDARD || (mode == DolbyVisionMode.AUTO && nativeSupported)) return null
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION || format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) return null
        val profile = profile(format)
        if (profile != 7 && profile != 8) return null
        val color = format.colorInfo ?: return null
        if (color.colorSpace != C.COLOR_SPACE_BT2020 || color.colorRange != C.COLOR_RANGE_LIMITED) return null
        if (color.colorTransfer !in setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) || color.colorTransfer !in displayTransfers) return null
        if (profile == 7 && color.colorTransfer != C.COLOR_TRANSFER_ST2084) return null
        return try {
            val codec = NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) ?: return null
            // HEVC Main10, derived from the actual layer-zero SPS rather than an invented level.
            if (!codec.startsWith("hvc1.2.")) return null
            val csd = format.initializationData.map { data ->
                val buffer = ByteBuffer.wrap(data.copyOf())
                val length = retainBaseLayer(buffer)
                buffer.array().copyOf(length)
            }.filter { it.isNotEmpty() }
            if (csd.isEmpty()) return null
            format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codec).setInitializationData(csd).build()
        } catch (_: RuntimeException) { null }
    }

    /** Compact an Annex-B read buffer in place, preserving only layer-zero HEVC (no RPU/EL).
     * Absolute accesses also work with MediaCodec's direct ByteBuffers. No frame re-encoding.
     * Unexpected framing fails closed instead of sending unverified data to a different decoder.
     */
    fun retainBaseLayer(data: ByteBuffer): Int {
        val limit = data.limit()
        if (limit == 0) return 0
        require(data.position() == 0 && !data.isReadOnly)
        // One bulk copy, then a plain array scan: per-byte ByteBuffer.get on MediaCodec's direct buffers was millions
        // of calls a second for a 4K frame and left the player thread too busy to keep up (choppy picture).
        val inPlace = data.hasArray() && data.arrayOffset() == 0
        val bytes = if (inPlace) data.array() else ByteArray(limit).also { data.duplicate().apply { position(0); limit(limit) }.get(it) }
        fun prefix(at: Int): Int = when {
            at + 4 <= limit && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte() -> 4
            at + 3 <= limit && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte() -> 3
            else -> 0
        }
        var start = 0
        var write = 0
        var dropped = false
        while (start < limit) {
            val header = start + prefix(start).also { require(it != 0) { "Unsupported HEVC sample framing" } }
            require(header + 2 <= limit) { "Truncated HEVC NAL unit" }
            val first = bytes[header].toInt() and 255
            val second = bytes[header + 1].toInt() and 255
            require(first and 128 == 0 && second and 7 != 0) { "Invalid HEVC NAL header" }
            val type = first shr 1 and 63
            val layer = ((first and 1) shl 5) or (second shr 3)
            // The next start code: only a zero byte can begin one, so skip quickly to each zero.
            var end = header + 2
            while (end < limit) {
                if (bytes[end] == 0.toByte() && prefix(end) != 0) break
                end++
            }
            if (layer == 0 && type != 62 && type != 63) {
                if (write != start) System.arraycopy(bytes, start, bytes, write, end - start)
                write += end - start
            } else dropped = true
            start = end
        }
        // Nothing removed: the buffer is already right. Otherwise put the compacted bytes back (absolute, position 0).
        if (dropped && !inPlace) data.duplicate().apply { position(0); limit(limit) }.put(bytes, 0, write)
        return write
    }
}
