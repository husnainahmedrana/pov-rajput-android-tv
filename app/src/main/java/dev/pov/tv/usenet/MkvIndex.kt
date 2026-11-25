package dev.pov.tv.usenet

/**
 * Finds where an MKV keeps its seek index (Cues) from the file's first bytes: the SeekHead near the start lists the
 * Cues' position. Fetching just those articles replaces guessing at the file's last megabytes, which on a slow line
 * took bandwidth from the video's start (and was wasted when the Cues are near the front).
 *
 * Only reads what is in [head]; anything unusual (no SeekHead before the first Cluster, sizes that don't fit) gives null.
 */
object MkvIndex {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CUES = 0x1C53BB6BL
    private const val CLUSTER = 0x1F43B675L

    fun isMkv(head: ByteArray, count: Int) = count >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
        head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    /** The Cues' byte position in the file, or null when the first [count] bytes of [head] don't say. */
    fun cuesPosition(head: ByteArray, count: Int, fileSize: Long): Long? {
        val n = minOf(count, head.size)
        if (!isMkv(head, n)) return null
        var at = 0
        val ebml = element(head, at, n) ?: return null
        if (ebml.id != EBML || ebml.size < 0) return null
        at = ebml.dataStart + (ebml.size.toIntOrNullIn(n - ebml.dataStart) ?: return null)
        val segment = element(head, at, n) ?: return null
        if (segment.id != SEGMENT) return null
        val segmentStart = segment.dataStart
        at = segmentStart
        // The Segment's first children: SeekHead usually comes first, perhaps after a Void. Stop at the first Cluster.
        repeat(16) {
            val child = element(head, at, n) ?: return null
            if (child.id == CLUSTER || child.size < 0) return null
            if (child.id == SEEK_HEAD) {
                val end = child.dataStart.toLong() + child.size
                if (end > n) return null
                return seekToCues(head, child.dataStart, end.toInt())?.let { segmentStart + it }?.takeIf { it in 0 until fileSize }
            }
            at = child.dataStart + (child.size.toIntOrNullIn(n - child.dataStart) ?: return null)
        }
        return null
    }

    private fun seekToCues(head: ByteArray, from: Int, end: Int): Long? {
        var at = from
        while (at < end) {
            val seek = element(head, at, end) ?: return null
            if (seek.size < 0) return null
            val seekEnd = seek.dataStart + (seek.size.toIntOrNullIn(end - seek.dataStart) ?: return null)
            if (seek.id == SEEK) {
                var id = -1L; var position = -1L
                var inner = seek.dataStart
                while (inner < seekEnd) {
                    val field = element(head, inner, seekEnd) ?: break
                    val length = field.size.toIntOrNullIn(seekEnd - field.dataStart) ?: break
                    if (length in 1..8) {
                        var value = 0L
                        for (i in 0 until length) value = (value shl 8) or (head[field.dataStart + i].toLong() and 0xFF)
                        when (field.id) { SEEK_ID -> id = value; SEEK_POSITION -> position = value }
                    }
                    inner = field.dataStart + length
                }
                if (id == CUES && position >= 0) return position
            }
            at = seekEnd
        }
        return null
    }

    private class Element(val id: Long, /** -1: unknown size. */ val size: Long, val dataStart: Int)

    /** The element header at [at]: its id (marker kept, as written in the spec) and size (marker removed). */
    private fun element(b: ByteArray, at: Int, end: Int): Element? {
        if (at >= end) return null
        val idLength = vintLength(b[at]) ?: return null
        if (idLength > 4 || at + idLength > end) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (b[at + i].toLong() and 0xFF)
        val sizeAt = at + idLength
        if (sizeAt >= end) return null
        val sizeLength = vintLength(b[sizeAt]) ?: return null
        if (sizeAt + sizeLength > end) return null
        var size = (b[sizeAt].toLong() and 0xFF) and (0xFFL shr sizeLength)
        var allOnes = size == (0xFFL shr sizeLength)
        for (i in 1 until sizeLength) {
            val byte = b[sizeAt + i].toLong() and 0xFF
            if (byte != 0xFFL) allOnes = false
            size = (size shl 8) or byte
        }
        return Element(id, if (allOnes) -1 else size, sizeAt + sizeLength)
    }

    private fun vintLength(first: Byte): Int? {
        val value = first.toInt() and 0xFF
        if (value == 0) return null
        return Integer.numberOfLeadingZeros(value) - 23
    }

    /** This size as an Int when it fits in [room] bytes, else null. */
    private fun Long.toIntOrNullIn(room: Int): Int? = if (this in 0..room.toLong()) toInt() else null
}
