package dev.pov.tv.usenet

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/** Reads the archive over NNTP; only decoded video is spooled locally, never archive entry paths. */
internal object SevenZipMedia {
    private const val RESERVE = 64L * 1024 * 1024
    private val videos = setOf("mkv", "mp4", "avi", "m2ts", "ts", "wmv", "mov", "mpg", "mpeg", "m4v", "webm")

    fun open(source: ByteSource, cache: File?, password: String? = null, log: (String) -> Unit = {}): UsenetMedia.Opened {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        var archive: SevenZFile? = null
        var spool: File? = null
        try {
            val channel = SourceChannel(source)
            archive = FastSha256.during { SevenZFile.builder().setSeekableByteChannel(channel)
                .setMaxMemoryLimitKiB(64 * 1024).setPassword(password).get() }
            log("7z: directory read in ${ms()} ms (${channel.readCalls} reads), ${archive.entries.count()} entries")
            val entry = archive.entries.filter { !it.isDirectory && it.name.orEmpty().substringAfterLast('.').lowercase() in videos }
                .maxByOrNull { it.size } ?: throw IOException("The 7z archive contains no video.")
            if (entry.size <= 0) throw IOException("The 7z video is empty.")
            SevenZipAes.open(source, archive, entry, password)?.also { log("7z: encrypted video, key ready at ${ms()} ms") }?.let { direct ->
                try {
                    val head = ByteArray(16)
                    var count = 0
                    while (count < head.size) {
                        val n = direct.read(count.toLong(), head, count, head.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    if (!UsenetMedia.looksLikeVideo(head.copyOf(count)))
                        throw IOException("The supplied 7z password could not unlock a recognizable video.")
                    log("7z: first bytes checked at ${ms()} ms")
                    return UsenetMedia.Opened(direct, entry.name, "7z · encrypted · direct")
                } catch (error: Exception) { direct.close(); throw error }
            }
            val readsBefore = channel.readCalls
            val input = archive.getInputStream(entry)
            val methods = entry.contentMethods?.toList().orEmpty()
            // A COPY-only stream with no data consumed while opening starts at the channel's current
            // position. Solid entries requiring earlier bytes to be skipped use the conservative cache path.
            if (methods.isNotEmpty() && methods.all { it.method == org.apache.commons.compress.archivers.sevenz.SevenZMethod.COPY }
                && channel.readCalls == readsBefore) {
                val start = channel.position()
                if (start < 0 || entry.size > source.size - start) throw IOException("The stored 7z video exceeds the archive size.")
                log("7z: stored video ready at ${ms()} ms")
                // The video is usually not the archive's last entry, so the set's own tail isn't its end: fetch that now.
                source.prefetch(start + entry.size - minOf(entry.size, VIDEO_TAIL_BYTES), minOf(entry.size, VIDEO_TAIL_BYTES))
                return UsenetMedia.Opened(Stored(source, archive, start, entry.size), entry.name, "7z · direct")
            }
            if (cache == null) throw IOException("Compressed 7z playback needs a temporary cache directory.")
            val directory = File(cache, "usenet-decoded")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Can't create the 7z playback cache.")
            // One playback session owns this directory. Remove leftovers from a killed process.
            directory.listFiles()?.filter { it.name.startsWith("video-") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (entry.size > (directory.usableSpace - RESERVE).coerceAtLeast(0))
                throw IOException("7z playback needs %.1f GB of free temporary storage. Choose a smaller release or a plain video.".format(entry.size / 1e9 + RESERVE / 1e9))
            spool = File.createTempFile("video-", ".tmp", directory)
            val decoded = Decoded(source, archive, input, spool, entry.size)
            return UsenetMedia.Opened(decoded, entry.name, "7z · temporary cache · forward seeks may buffer")
        } catch (error: Exception) {
            try { archive?.close() } finally { source.close(); spool?.delete() }
            if (error is org.apache.commons.compress.PasswordRequiredException)
                throw IOException("This 7z release needs a password, but its NZB did not provide one.", error)
            throw IOException("7z playback: ${error.message ?: "this archive could not be opened"}", error)
        }
    }

    /** Random access to unchanged archive bytes. NNTP still validates each article's yEnc checksum. */
    private class Stored(private val source: ByteSource, private val archive: SevenZFile,
                         private val start: Long, override val size: Long) : ByteSource {
        @Volatile private var closed = false
        override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("The 7z stream is closed.")
            require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return 0
            if (position >= size) return -1
            val got = source.read(start + position, target, offset, minOf(length.toLong(), size - position).toInt())
            if (got < 0) throw IOException("The stored 7z video ended early.")
            return got
        }
        override fun close() { closed = true; try { archive.close() } finally { source.close() } }
    }

    /** On-demand extraction: no whole-file RAM buffer and no download worker left running after Stop. */
    private class Decoded(
        private val source: ByteSource, private val archive: SevenZFile, private val input: InputStream,
        private val file: File, override val size: Long
    ) : ByteSource {
        private val lock = Any()
        private val output = RandomAccessFile(file, "rw")
        private val buffer = ByteArray(256 * 1024)
        private var decoded = 0L
        private var failure: IOException? = null
        @Volatile private var closed = false

        override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
            if (closed) throw IOException("The 7z stream is closed.")
            failure?.let { throw it }
            require(position >= 0 && offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return@synchronized 0
            if (position >= size) return@synchronized -1
            val count = minOf(length.toLong(), size - position).toInt()
            val end = position + count
            try {
                while (decoded < end) {
                    if (closed) throw IOException("The 7z stream is closed.")
                    if (file.parentFile!!.usableSpace < RESERVE) throw IOException("Not enough free storage for the 7z playback cache.")
                    val got = input.read(buffer, 0, minOf(buffer.size.toLong(), end - decoded).toInt())
                    if (got <= 0) throw IOException("The 7z video ended before its stated size.")
                    output.seek(decoded); output.write(buffer, 0, got); decoded += got
                }
                output.seek(position); output.readFully(target, offset, count)
                count
            } catch (error: IOException) { failure = error; throw error }
        }

        override fun close() {
            closed = true
            source.close() // Interrupt a blocked NNTP read before waiting for the decoder lock.
            synchronized(lock) {
                try { archive.close() } finally { try { output.close() } finally { file.delete() } }
            }
        }
    }

    private class SourceChannel(private val source: ByteSource) : SeekableByteChannel {
        var readCalls = 0L; private set
        private var at = 0L
        private var open = true
        private val buffer = ByteArray(64 * 1024)
        private fun checkOpen() { if (!open) throw ClosedChannelException() }
        override fun isOpen() = open
        override fun position(): Long { checkOpen(); return at }
        override fun position(newPosition: Long): SeekableByteChannel {
            checkOpen(); require(newPosition >= 0); at = newPosition; return this
        }
        override fun size(): Long { checkOpen(); return source.size }
        override fun read(dst: ByteBuffer): Int {
            checkOpen()
            if (!dst.hasRemaining()) return 0
            readCalls++
            val got = source.read(at, buffer, 0, minOf(buffer.size, dst.remaining()))
            if (got > 0) { dst.put(buffer, 0, got); at += got }
            return got
        }
        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
        override fun close() { open = false; source.close() }
    }
}
