package dev.pov.tv.usenet

import java.io.IOException

/** The set can't be streamed (compressed, encrypted, damaged): the message says why in viewer terms. */
class UnsupportedSetException(message: String) : IOException(message)

/**
 * Reads RAR volume headers, old (RAR 1.5–4) and new (RAR 5) formats, far enough to find where a stored file's bytes
 * sit inside each volume. Nothing is unpacked: a stored file is the volumes' data areas laid end to end.
 */
object Rar {
    /** One file's piece inside one volume. */
    class Entry(
        val name: String, val unpackedSize: Long,
        /** Where this piece's bytes start in the volume, and how many there are. */
        val dataOffset: Long, val packedSize: Long,
        val splitBefore: Boolean, val splitAfter: Boolean, val stored: Boolean, val encrypted: Boolean
    )

    private val OLD = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1a, 0x07, 0x00)
    private val NEW = byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1a, 0x07, 0x01, 0x00)

    fun isRar(head: ByteArray) = head.size >= 7 && (0 until 7).all { head[it] == OLD[it] } || isNew(head)
    private fun isNew(head: ByteArray) = head.size >= 8 && (0 until 8).all { head[it] == NEW[it] }

    /**
     * The file pieces in one volume, in order, up to [limit] of them. Reading stops at the first piece that continues
     * in the next volume, since nothing follows it.
     */
    fun entries(volume: ByteSource, limit: Int = 40): List<Entry> {
        val head = bytes(volume, 0, 8)
        return when {
            isNew(head) -> newEntries(volume, limit)
            isRar(head) -> oldEntries(volume, limit)
            else -> throw UnsupportedSetException("This part is not a RAR volume.")
        }
    }

    private fun bytes(source: ByteSource, position: Long, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = source.read(position + done, out, done, count - done)
            if (read <= 0) break
            done += read
        }
        return if (done == count) out else out.copyOf(done)
    }

    private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(data: ByteArray, at: Int) = u16(data, at).toLong() or (u16(data, at + 2).toLong() shl 16)

    private fun oldEntries(volume: ByteSource, limit: Int): List<Entry> {
        val found = ArrayList<Entry>()
        var at = 7L
        var blocks = 0
        while (at + 7 <= volume.size && found.size < limit && blocks++ < 400) {
            val fixed = bytes(volume, at, 7)
            if (fixed.size < 7) break
            val type = fixed[2].toInt() and 0xFF
            val flags = u16(fixed, 3)
            val headSize = u16(fixed, 5)
            if (headSize < 7) break
            when (type) {
                0x73 -> if (flags and 0x0080 != 0) throw UnsupportedSetException("This set is password-protected.")
                0x7b -> return found                                       // end of archive
            }
            if (type == 0x74) {
                val head = bytes(volume, at, minOf(headSize, 4096))
                if (head.size < 32) break
                val large = flags and 0x0100 != 0
                var packed = u32(head, 7); var unpacked = u32(head, 11)
                val method = head[25].toInt() and 0xFF
                val nameSize = u16(head, 26)
                var nameAt = 32
                if (large) {
                    if (head.size < 40) break
                    packed = packed or (u32(head, 32) shl 32); unpacked = unpacked or (u32(head, 36) shl 32)
                    nameAt = 40
                }
                val raw = head.copyOfRange(minOf(nameAt, head.size), minOf(nameAt + nameSize, head.size))
                // A Unicode name is the plain name, a zero byte, then the encoded form: the plain part is enough here.
                val plain = raw.indexOf(0).let { if (it >= 0) raw.copyOf(it) else raw }
                val entry = Entry(String(plain, Charsets.UTF_8), unpacked, at + headSize, packed,
                    splitBefore = flags and 0x01 != 0, splitAfter = flags and 0x02 != 0, stored = method == 0x30, encrypted = flags and 0x04 != 0)
                // Directories carry the dictionary bits 0xE0 and no data.
                if (flags and 0xE0 != 0xE0) found += entry
                if (entry.splitAfter) return found
                at += headSize + packed
            } else {
                // Other blocks state extra data after the header only when the long-block flag is set.
                val extra = if (flags and 0x8000 != 0) { val more = bytes(volume, at + 7, 4); if (more.size < 4) break; u32(more, 0) } else 0L
                at += headSize + extra
            }
        }
        return found
    }

    /** A variable-length integer: seven bits per byte, low first, the top bit saying another byte follows. */
    private class Cursor(val data: ByteArray, var at: Int) {
        fun vint(): Long {
            var value = 0L; var shift = 0
            while (at < data.size && shift < 70) {
                val byte = data[at++].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            throw UnsupportedSetException("This RAR volume's headers can't be read.")
        }
    }

    private fun newEntries(volume: ByteSource, limit: Int): List<Entry> {
        val found = ArrayList<Entry>()
        var at = 8L
        var blocks = 0
        while (at + 7 <= volume.size && found.size < limit && blocks++ < 400) {
            val start = bytes(volume, at, 16)
            if (start.size < 7) break
            val sizeCursor = Cursor(start, 4)
            val headSize = sizeCursor.vint()
            if (headSize <= 0 || headSize > 2 * 1024 * 1024) break
            val bodyAt = at + sizeCursor.at
            val body = bytes(volume, bodyAt, minOf(headSize, 8192).toInt())
            val cursor = Cursor(body, 0)
            val type = cursor.vint()
            val flags = cursor.vint()
            val extraSize = if (flags and 0x01 != 0L) cursor.vint() else 0L
            val dataSize = if (flags and 0x02 != 0L) cursor.vint() else 0L
            val dataAt = bodyAt + headSize
            when (type) {
                4L -> throw UnsupportedSetException("This set is password-protected.")
                5L -> return found                                         // end of archive
                2L -> {
                    val fileFlags = cursor.vint()
                    val unpacked = cursor.vint()
                    cursor.vint()                                          // attributes
                    if (fileFlags and 0x02 != 0L) cursor.at += 4           // modification time
                    if (fileFlags and 0x04 != 0L) cursor.at += 4           // checksum
                    val compression = cursor.vint()
                    cursor.vint()                                          // host system
                    val nameSize = cursor.vint().toInt()
                    if (nameSize < 0 || cursor.at + nameSize > body.size) throw UnsupportedSetException("This RAR volume's headers can't be read.")
                    val name = String(body, cursor.at, nameSize, Charsets.UTF_8)
                    // The extra area closes the header; a record of type 1 in it means the file is encrypted.
                    var encrypted = false
                    if (extraSize in 1..body.size.toLong() && headSize <= body.size) {
                        val extra = Cursor(body, (headSize - extraSize).toInt())
                        while (extra.at < body.size) {
                            val recordSize = extra.vint(); val recordStart = extra.at
                            if (recordSize <= 0) break
                            if (extra.vint() == 1L) encrypted = true
                            extra.at = recordStart + recordSize.toInt()
                        }
                    }
                    val entry = Entry(name, unpacked, dataAt, dataSize, splitBefore = flags and 0x08 != 0L, splitAfter = flags and 0x10 != 0L,
                        stored = (compression shr 7) and 0x07 == 0L, encrypted = encrypted)
                    if (fileFlags and 0x01 == 0L) found += entry           // not a directory
                    if (entry.splitAfter) return found
                }
            }
            at = dataAt + dataSize
        }
        return found
    }
}

/** A stored file read across the volumes that hold its pieces, as one continuous run of bytes. */
class VolumeSet(
    override val size: Long,
    /** Each piece: the volume it is in, where it starts there, and its length; in file order. */
    private val pieces: List<Piece>
) : ByteSource {
    class Piece(val volume: UsenetStream, val offset: Long, val length: Long)

    private val starts = LongArray(pieces.size).also { var at = 0L; for (index in pieces.indices) { it[index] = at; at += pieces[index].length } }
    @Volatile private var current = -1

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        var index = java.util.Arrays.binarySearch(starts, position)
        if (index < 0) index = -index - 2
        val piece = pieces[index]
        val within = position - starts[index]
        val count = piece.volume.read(piece.offset + within, target, offset, minOf(length.toLong(), piece.length - within).toInt())
        if (count < 0) throw IOException("A part of the set ended early.")
        if (index != current) {
            // The reader moved to another volume: let go of what the ones it left were holding (the set's two ends stay).
            val before = current
            current = index
            if (before in 1 until pieces.size - 1 && before != index + 1) pieces[before].volume.release()
        }
        // Near the end of this volume, start the next one downloading so playback crosses over without a pause.
        if (index + 1 < pieces.size && piece.length - within - count < WARM_BYTES) pieces[index + 1].volume.warm()
        return count
    }

    override fun prefetch(position: Long, length: Long) {
        var at = position.coerceAtLeast(0); val end = minOf(size, position + length)
        while (at < end) {
            var index = java.util.Arrays.binarySearch(starts, at)
            if (index < 0) index = -index - 2
            val piece = pieces[index]
            val within = at - starts[index]
            val take = minOf(end - at, piece.length - within)
            piece.volume.prefetch(piece.offset + within, take)
            at += take
        }
    }

    override fun close() { pieces.forEach { it.volume.close() } }

    private companion object { const val WARM_BYTES = 6L * 1024 * 1024 }
}
