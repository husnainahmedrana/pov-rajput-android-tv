package dev.pov.tv

/**
 * A video's length from the first bytes of the file, without a player: Matroska/WebM (Segment → Info → Duration and
 * TimestampScale) or MP4/MOV (moov → mvhd). Used to turn the byte another app is reading into a playback position.
 * Null when the header isn't one of those, the length isn't in the bytes given, or the values make no sense.
 */
internal object VideoDuration {
    fun read(data: ByteArray, length: Int = data.size): Long? {
        val end = minOf(length, data.size)
        if (end < 8) return null
        return runCatching {
            if (u32(data, 0) == 0x1A45DFA3L) matroska(data, end) else mp4(data, 0, end)
        }.getOrNull()?.takeIf { it in 1_000L..(48L * 3600 * 1000) }
    }

    // ---- Matroska ----
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L

    private class Element(val id: Long, val size: Long, val body: Int)

    private fun element(data: ByteArray, at: Int, end: Int): Element? {
        val (id, idLength) = vint(data, at, end, keepMarker = true) ?: return null
        val (size, sizeLength) = vint(data, at + idLength, end, keepMarker = false) ?: return null
        return Element(id, size, at + idLength + sizeLength)
    }

    /** An EBML variable-length integer: (value, byte count). An all-ones size (unknown length) comes back as -1. */
    private fun vint(data: ByteArray, at: Int, end: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (at >= end) return null
        val first = data[at].toInt() and 0xFF
        if (first == 0) return null
        val count = Integer.numberOfLeadingZeros(first) - 23
        if (count > 8 || at + count > end) return null
        var value = (if (keepMarker) first else first and (0xFF ushr count)).toLong()
        var allOnes = value == (0xFFL ushr count)
        for (index in 1 until count) {
            val byte = data[at + index].toInt() and 0xFF
            if (byte != 0xFF) allOnes = false
            value = (value shl 8) or byte.toLong()
        }
        return (if (!keepMarker && allOnes) -1L else value) to count
    }

    private fun matroska(data: ByteArray, end: Int): Long? {
        val header = element(data, 0, end) ?: return null
        var at = header.body + header.size.toInt()
        val segment = element(data, at, end)?.takeIf { it.id == SEGMENT } ?: return null
        at = segment.body
        while (at < end) {
            val child = element(data, at, end) ?: return null
            if (child.id == CLUSTER || child.size < 0) return null           // past the header without an Info
            if (child.id == INFO) return info(data, child.body, minOf(end.toLong(), child.body + child.size).toInt())
            at = (child.body + child.size).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        }
        return null
    }

    private fun info(data: ByteArray, start: Int, end: Int): Long? {
        var scale = 1_000_000L
        var duration: Double? = null
        var at = start
        while (at < end) {
            val child = element(data, at, end) ?: break
            val size = child.size.toInt()
            if (child.size < 0 || child.body + size > end) break
            when (child.id) {
                TIMESTAMP_SCALE -> scale = (0 until size).fold(0L) { value, index -> (value shl 8) or (data[child.body + index].toLong() and 0xFF) }
                DURATION -> duration = when (size) {
                    4 -> java.lang.Float.intBitsToFloat(u32(data, child.body).toInt()).toDouble()
                    8 -> java.lang.Double.longBitsToDouble((u32(data, child.body) shl 32) or u32(data, child.body + 4))
                    else -> null
                }
            }
            at = child.body + size
        }
        val value = duration ?: return null
        if (!value.isFinite() || value <= 0 || scale <= 0) return null
        return (value * scale / 1_000_000.0).toLong()
    }

    // ---- MP4 ----
    private fun mp4(data: ByteArray, start: Int, end: Int): Long? {
        var at = start
        while (at + 8 <= end) {
            var size = u32(data, at)
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var body = at + 8
            if (size == 1L) { if (at + 16 > end) return null; size = (u32(data, at + 8) shl 32) or u32(data, at + 12); body = at + 16 }
            if (size == 0L) size = (end - at).toLong()
            if (size < body - at) return null
            val boxEnd = minOf(end.toLong(), at + size).toInt()
            when (type) {
                "moov" -> return mp4(data, body, boxEnd)
                "mvhd" -> {
                    val version = data[body].toInt()
                    val (timescale, duration) = if (version == 1) {
                        if (body + 32 > end) return null
                        u32(data, body + 20) to ((u32(data, body + 24) shl 32) or u32(data, body + 28))
                    } else {
                        if (body + 20 > end) return null
                        u32(data, body + 12) to u32(data, body + 16)
                    }
                    if (timescale <= 0) return null
                    return duration * 1000 / timescale
                }
            }
            if (at + size > end) return null                                      // moov is further on (at the end)
            at += size.toInt()
        }
        return null
    }

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
}
