package dev.pov.tv.usenet

import java.util.Locale

/**
 * Finds the video in an NZB and opens it for streaming: a plain video file as it is, or a video stored (not
 * compressed) in a set of RAR volumes read straight through the volumes. Anything else is refused with the reason.
 */
object UsenetMedia {
    class Opened(val source: ByteSource, val name: String, /** How it is stored, for the log and the on-screen note. */ val kind: String)

    private val VIDEO = setOf("mkv", "mp4", "avi", "m2ts", "ts", "wmv", "mov", "mpg", "mpeg", "m4v", "webm")
    private val QUOTED = Regex("\"([^\"]{3,240})\"")
    private val LOOSE = Regex("(?i)([\\w.\\-\\[\\]() ]{1,200}\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|mpg|mpeg|m4v|webm|rar|r\\d{2,3}|s\\d{2}|par2|nfo|sfv|srt))(?![\\w.])")
    private val PART = Regex("(?i)^(.*)\\.part(\\d{1,4})\\.rar$")
    private val OLD_PART = Regex("(?i)^(.*)\\.([rs])(\\d{2,3})$")

    /** The file name a subject line carries ("name.ext" yEnc (1/70)), if it has one. */
    internal fun subjectName(subject: String): String? =
        (QUOTED.find(subject)?.groupValues?.get(1) ?: LOOSE.find(subject)?.groupValues?.get(1))?.trim()?.takeIf { '.' in it }

    /** A RAR volume's set name and its place in the set (first volume 0), or null when the name isn't a RAR volume's. */
    internal fun rarOrder(name: String): Pair<String, Int>? {
        PART.find(name)?.let { return it.groupValues[1].lowercase(Locale.ROOT) to (it.groupValues[2].toInt() - 1).coerceAtLeast(0) }
        OLD_PART.find(name)?.let {
            val number = it.groupValues[3].toInt()
            return it.groupValues[1].lowercase(Locale.ROOT) to (if (it.groupValues[2].lowercase(Locale.ROOT) == "r") 1 else 101) + number
        }
        return if (name.endsWith(".rar", ignoreCase = true)) name.dropLast(4).lowercase(Locale.ROOT) to 0 else null
    }

    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO

    fun open(pool: ArticleSource, nzb: Nzb, log: (String) -> Unit = {}): Opened {
        val files = nzb.files
        if (files.isEmpty()) throw UnsupportedSetException("The article list is empty.")
        fun stream(file: NzbFile, pin: Boolean) = UsenetStream(pool, file.segments, pinEnds = pin, log = log)
        val names = files.map { subjectName(it.subject) }

        // Volumes named in the subjects: the biggest RAR set, in volume order.
        val named = files.indices.mapNotNull { index -> names[index]?.let(::rarOrder)?.let { Triple(it.first, it.second, files[index]) } }
            .groupBy { it.first }.values.maxByOrNull { set -> set.sumOf { it.third.statedBytes } }?.sortedBy { it.second }?.map { it.third }
        val video = files.indices.filter { names[it]?.let(::isVideo) == true }.maxByOrNull { files[it].statedBytes }?.let { files[it] }
        if (named != null && (video == null || named.sumOf { it.statedBytes } > video.statedBytes)) return openSet(named, ::stream, log, trusted = true)
        if (video != null) return openPlain(video, ::stream, log)

        // No usable names (some services scramble subjects): the biggest file decides.
        val biggest = files.maxByOrNull { it.statedBytes }!!
        val first = stream(biggest, true)
        first.prepare()
        if (!Rar.isRar(head(first))) {
            log("plain file: ${first.name}")
            return Opened(first, first.name, "single file")
        }
        // A RAR volume: take the listed order from it on, and check it really is where the set starts.
        val listed = files.subList(files.indexOf(biggest), files.size)
        return try { openSet(listed, ::stream, log, trusted = false, ready = first) } catch (_: OutOfOrderException) {
            log("volumes are not listed in order; reading every part's name")
            val streams = files.map { stream(it, false) }
            streams.forEach { it.begin() }
            val ordered = files.indices.mapNotNull { index ->
                val name = try { streams[index].prepare(); streams[index].name } catch (_: java.io.IOException) { "" }
                rarOrder(name)?.let { Triple(it.first, it.second, files[index]) }
            }
            streams.forEach { it.close() }
            val set = ordered.groupBy { it.first }.values.maxByOrNull { group -> group.sumOf { it.third.statedBytes } }?.sortedBy { it.second }?.map { it.third }
                ?: throw UnsupportedSetException("The set's parts can't be put in order.")
            openSet(set, ::stream, log, trusted = true)
        }
    }

    private class OutOfOrderException : java.io.IOException("The set's parts are out of order.")

    private fun head(source: ByteSource): ByteArray {
        val out = ByteArray(8)
        val count = source.read(0, out, 0, 8)
        return if (count == 8) out else out.copyOf(maxOf(count, 0))
    }

    private fun openPlain(file: NzbFile, stream: (NzbFile, Boolean) -> UsenetStream, log: (String) -> Unit): Opened {
        val opened = stream(file, true)
        opened.prepare()
        if (Rar.isRar(head(opened))) { opened.close(); return openSet(listOf(file), stream, log, trusted = true) }
        log("plain file: ${opened.name}")
        return Opened(opened, opened.name, "single file")
    }

    /** [volumes] in order. When the order is only the NZB's listing ([trusted] false), the first two volumes must prove it. */
    private fun openSet(volumes: List<NzbFile>, stream: (NzbFile, Boolean) -> UsenetStream, log: (String) -> Unit, trusted: Boolean,
                        ready: UsenetStream? = null): Opened {
        val first = ready ?: stream(volumes[0], true)
        val opened = ArrayList<UsenetStream>().apply { add(first) }
        try {
            first.prepare()
            val entries = Rar.entries(first)
            val entry = entries.filter { isVideo(it.name) }.maxByOrNull { it.unpackedSize } ?: entries.maxByOrNull { it.unpackedSize }
                ?: throw UnsupportedSetException("The set holds no file.")
            if (entry.splitBefore) { if (!trusted) throw OutOfOrderException(); throw UnsupportedSetException("The set's first part is missing.") }
            if (entry.encrypted) throw UnsupportedSetException("This set is password-protected.")
            if (!entry.stored) throw UnsupportedSetException("This set is compressed, so it can't be streamed.")
            if (!isVideo(entry.name)) throw UnsupportedSetException("The set holds no video (${entry.name.take(60)}).")
            val pieces = ArrayList<VolumeSet.Piece>()
            pieces += VolumeSet.Piece(first, entry.dataOffset, minOf(entry.packedSize, entry.unpackedSize))
            var left = entry.unpackedSize - pieces[0].length
            if (left > 0) {
                if (!entry.splitAfter || volumes.size < 2) throw UnsupportedSetException("The set is incomplete.")
                val second = stream(volumes[1], false)
                opened += second
                second.prepare()
                val next = Rar.entries(second, limit = 1).firstOrNull()
                if (next == null || !next.splitBefore || next.name != entry.name) { if (!trusted) throw OutOfOrderException(); throw UnsupportedSetException("The set's parts don't follow one another.") }
                if (!trusted && rarOrder(second.name)?.second?.let { it != 1 } == true) throw OutOfOrderException()
                if (!next.stored || next.packedSize <= 0) throw UnsupportedSetException("This set is compressed, so it can't be streamed.")
                var index = 1
                while (left > 0) {
                    if (index >= volumes.size) throw UnsupportedSetException("The set is incomplete: ${volumes.size} parts listed, more are needed.")
                    val length = minOf(next.packedSize, left)
                    val lastPiece = left <= next.packedSize
                    val volume = if (index == 1) second else stream(volumes[index], lastPiece).also { opened += it }
                    // Middle volumes are all alike: knowing one, the rest can be read from anywhere without fetching
                    // their first article. The last (shorter) one, or any that looks different, learns its own size.
                    if (index > 1 && !lastPiece && volumes[index].segments.size == volumes[1].segments.size) volume.assumeLike(second)
                    pieces += VolumeSet.Piece(volume, next.dataOffset, length)
                    left -= length; index++
                }
            }
            log("RAR set: ${entry.name} · ${entry.unpackedSize} bytes in ${pieces.size} of ${volumes.size} parts")
            return Opened(VolumeSet(entry.unpackedSize, pieces), entry.name, if (pieces.size == 1) "RAR" else "RAR · ${pieces.size} parts")
        } catch (error: Exception) {
            opened.forEach { it.close() }
            throw error
        }
    }
}
