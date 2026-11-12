package dev.pov.tv.usenet

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/** Direct ranges from an AES+COPY folder; compressed payloads stay with the normal decoder.
 * The small metadata adapter is pinned to Commons Compress 1.28.0. A different layout disables
 * this optimization rather than guessing archive offsets. No passwords or keys are persisted.
 */
internal object SevenZipAes {
    /** Decrypted at a time and kept for the reads that follow (a multiple of 16, AES's block size). */
    private const val WINDOW = 256 * 1024
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(owner) ?: throw ReflectiveOperationException("Missing archive field")
    }
    private data class Layout(val start: Long, val packed: Long, val skip: Long, val properties: ByteArray)
    private fun layout(reader: SevenZFile, entry: SevenZArchiveEntry): Layout? {
        try {
            val archive = field(reader, "archive")
            val files = field(archive, "files") as Array<*>
            val index = files.indexOfFirst { it === entry }
            if (index < 0) return null
            val map = field(archive, "streamMap")
            val fileFolders = field(map, "fileFolderIndex") as IntArray
            val folderIndex = fileFolders[index]
            if (folderIndex < 0) return null
            val folder = (field(archive, "folders") as Array<*>)[folderIndex]!!
            if ((field(folder, "packedStreams") as LongArray).size != 1) return null
            val coders = field(folder, "coders") as Array<*>
            var properties: ByteArray? = null
            for (item in coders) {
                val coder = item ?: return null
                if (field(coder, "numInStreams") != 1L || field(coder, "numOutStreams") != 1L) return null
                val method = field(coder, "decompressionMethodId") as ByteArray
                when {
                    method.contentEquals(byteArrayOf(0)) -> Unit
                    method.contentEquals(byteArrayOf(6, -15, 7, 1)) && properties == null ->
                        properties = (field(coder, "properties") as ByteArray).clone()
                    else -> return null
                }
            }
            if (properties == null) return null
            val firstPack = (field(map, "folderFirstPackStreamIndex") as IntArray)[folderIndex]
            val start = Math.addExact(32L, Math.addExact(field(archive, "packPos") as Long,
                (field(map, "packStreamOffsets") as LongArray)[firstPack]))
            var skip = 0L
            for (i in 0 until index) if (fileFolders[i] == folderIndex)
                skip = Math.addExact(skip, (files[i] as SevenZArchiveEntry).size)
            return Layout(start, (field(archive, "packSizes") as LongArray)[firstPack], skip, properties)
        } catch (_: ReflectiveOperationException) { return null }
          catch (_: ClassCastException) { return null }
          catch (_: IndexOutOfBoundsException) { return null }
    }

    fun open(source: ByteSource, reader: SevenZFile, entry: SevenZArchiveEntry, password: String?): ByteSource? {
        val layout = layout(reader, entry) ?: return null
        if (password == null) throw IOException("This 7z release needs a password, but its NZB did not provide one.")
        if (layout.start < 0 || layout.packed < 0 || layout.packed % 16 != 0L ||
            layout.start > source.size || layout.packed > source.size - layout.start ||
            layout.skip < 0 || entry.size > layout.packed - layout.skip)
            throw IOException("Invalid encrypted 7z range.")
        val props = layout.properties
        if (props.size < 2) throw IOException("Invalid 7z encryption properties.")
        val flags = props[0].toInt() and 255
        val sizes = props[1].toInt() and 255
        val power = flags and 63
        val saltSize = ((flags shr 7) and 1) + (sizes shr 4)
        val ivSize = ((flags shr 6) and 1) + (sizes and 15)
        if (2 + saltSize + ivSize > props.size) throw IOException("Invalid 7z salt or IV.")
        if (power != 63 && power > 20) throw IOException("This 7z password requires too much processing for TV playback.")
        val salt = props.copyOfRange(2, 2 + saltSize)
        val iv = ByteArray(16)
        props.copyInto(iv, 0, 2 + saltSize, 2 + saltSize + ivSize)
        val bytes = password.toByteArray(Charsets.UTF_16LE)
        val key = try {
            if (power == 63) ByteArray(32).also { out ->
                salt.copyInto(out); bytes.copyInto(out, salt.size, 0, minOf(bytes.size, 32 - salt.size))
            } else {
                // 7-Zip hashes salt + password + an 8-byte counter, 2^power times (2^19 = 524,288 by default). Three
                // small digest calls per round cost seconds on a TV (each crosses into native code), so the rounds are
                // laid end to end in a buffer and hashed a few thousand at a time: the same bytes, the same key.
                val hash = MessageDigest.getInstance("SHA-256")
                val round = salt.size + bytes.size + 8
                val perChunk = maxOf(1, (256 * 1024) / round)
                val chunk = ByteArray(perChunk * round)
                for (i in 0 until perChunk) { salt.copyInto(chunk, i * round); bytes.copyInto(chunk, i * round + salt.size) }
                var counter = 0L
                var left = 1L shl power
                try {
                    while (left > 0) {
                        val rounds = minOf(left, perChunk.toLong()).toInt()
                        for (i in 0 until rounds) {
                            val at = i * round + salt.size + bytes.size
                            var value = counter++
                            for (b in 0 until 8) { chunk[at + b] = value.toByte(); value = value ushr 8 }
                        }
                        hash.update(chunk, 0, rounds * round)
                        left -= rounds
                    }
                } finally { chunk.fill(0) }
                hash.digest()
            }
        } finally { bytes.fill(0) }
        // Fetch the video's end now (with the 16-byte block before it, the decryption's starting point): see VIDEO_TAIL_BYTES.
        val tail = minOf(entry.size, VIDEO_TAIL_BYTES)
        source.prefetch(layout.start + layout.skip + entry.size - tail - 16, tail + 32)
        return object : ByteSource {
            override val size = entry.size
            private var closed = false
            private fun exact(position: Long, out: ByteArray) {
                var done = 0
                while (done < out.size) {
                    val got = source.read(position + done, out, done, out.size - done)
                    if (got <= 0) throw IOException("The encrypted 7z video ended early.")
                    done += got
                }
            }
            // The player's MKV parser reads a few bytes at a time (its seek index is thousands of tiny reads). Each
            // 256 KB stretch is decrypted once and later reads are served from it; one Cipher is reused throughout
            // (Cipher.getInstance per read took 44–133 s on the onn 4K for one index).
            private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            private val secret = SecretKeySpec(key, "AES")
            private var windowStart = -1L                 // offset in the decrypted folder, a multiple of 16
            private var window = ByteArray(0)
            private var windowLength = 0
            @Synchronized override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
                if (closed) throw IOException("The 7z stream is closed.")
                require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
                if (length == 0) return 0
                if (position >= size) return -1
                val absolute = layout.skip + position
                if (windowStart < 0 || absolute < windowStart || absolute >= windowStart + windowLength) fill(absolute / 16 * 16)
                val at = (absolute - windowStart).toInt()
                val count = minOf(length.toLong(), size - position, (windowLength - at).toLong()).toInt()
                System.arraycopy(window, at, target, offset, count)
                return count
            }
            private fun fill(block: Long) {
                // Never past the video's own end (rounded to a whole AES block): bytes beyond it belong to the next
                // entry, weren't fetched ahead, and would make the end-of-file reads wait on downloads they don't need.
                val videoEnd = (layout.skip + entry.size + 15) / 16 * 16
                val length = minOf(WINDOW.toLong(), layout.packed - block, videoEnd - block).toInt()
                if (length <= 0 || length % 16 != 0) throw IOException("Invalid encrypted 7z range.")
                val localIv = if (block == 0L) iv else ByteArray(16).also { exact(layout.start + block - 16, it) }
                val encrypted = ByteArray(length)
                exact(layout.start + block, encrypted)
                cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(localIv))
                window.fill(0)
                window = cipher.doFinal(encrypted)
                windowStart = block; windowLength = window.size
            }
            override fun close() {
                source.close() // Interrupt blocked NNTP reads before taking the read lock.
                synchronized(this) { if (!closed) { closed = true; key.fill(0); window.fill(0); reader.close() } }
            }
        }
    }
}
