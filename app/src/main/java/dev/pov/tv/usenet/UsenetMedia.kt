package dev.pov.tv.usenet

import dev.pov.tv.L10n
import dev.pov.tv.R

import java.util.Locale

/**
 * Finds the video in an NZB and opens it for streaming: a plain video file as it is, or a video stored (not
 * compressed) in a set of RAR volumes read straight through the volumes. Anything else is refused with the reason.
 */
object UsenetMedia {
    class Opened(val source: ByteSource, val name: String, /** How it is stored, for the log and the on-screen note. */ val kind: String)

    private val VIDEO = setOf("mkv", "mp4", "avi", "m2ts", "ts", "wmv", "mov", "mpg", "mpeg", "m4v", "webm")
    private val QUOTED = Regex("\"([^\"]{3,240})\"")
    private val LOOSE = Regex("(?i)([\\w.\\-\\[\\]() ]{1,200}\\.(?:mkv|mp4|avi|m2ts|ts|wmv|mov|mpg|mpeg|m4v|webm|rar|r\\d{2,3}|s\\d{2}|7z|\\d{3}|par2|nfo|sfv|srt))(?![\\w.])")
    /** Plain split parts: "name.mkv.001", "name.7z.002". */
    private val SPLIT = Regex("(?i)^(.*)\\.(\\d{3})$")
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

    /** A split part's base name and number ("film.mkv.002" → "film.mkv", 2), or null. */
    internal fun splitOrder(name: String): Pair<String, Int>? = SPLIT.find(name)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    private val SEVEN_ZIP = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    internal fun isSevenZip(head: ByteArray) = head.size >= 6 && (0 until 6).all { head[it] == SEVEN_ZIP[it] }

    /** Whether a file starts like a video the player can read: Matroska/WebM, MP4/MOV, AVI, MPEG-TS (also M2TS), MPEG-PS, FLV. */
    internal fun looksLikeVideo(head: ByteArray): Boolean {
        fun at(index: Int, vararg bytes: Int) = head.size >= index + bytes.size && bytes.indices.all { head[index + it] == bytes[it].toByte() }
        return at(0, 0x1A, 0x45, 0xDF, 0xA3) || at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) || at(4, 'm'.code, 'o'.code, 'o'.code, 'v'.code) ||
            at(4, 'm'.code, 'd'.code, 'a'.code, 't'.code) || at(4, 'f'.code, 'r'.code, 'e'.code, 'e'.code) || at(4, 'w'.code, 'i'.code, 'd'.code, 'e'.code) ||
            at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) || at(0, 0x47) || at(4, 0x47) || at(0, 0x00, 0x00, 0x01, 0xBA) || at(0, 'F'.code, 'L'.code, 'V'.code)
    }

    private fun refuseArchive(head: ByteArray, name: String): Nothing = throw UnsupportedSetException(when {
        isSevenZip(head) -> "This release is a 7z archive, which can't be streamed yet. Choose another link."
        else -> "This release isn't a video file the app can read${if (name.isNotBlank()) " (${name.take(50)})" else ""}. Choose another link."
    })

    fun open(pool: ArticleSource, nzb: Nzb, cache: java.io.File? = null, log: (String) -> Unit = {}): Opened {
        val files = nzb.files
        if (files.isEmpty()) throw UnsupportedSetException(L10n.tr(R.string.t_the_article_list_is_empty, "The article list is empty."))
        fun stream(file: NzbFile, pin: Boolean) = UsenetStream(pool, file.segments, pinEnds = pin, log = log)
        val names = files.map { subjectName(it.subject) }
        // What the NZB holds, biggest first: enough to tell from the log why a link didn't play.
        log("NZB: ${files.size} files · " + files.indices.sortedByDescending { files[it].statedBytes }.take(8).joinToString(" | ") { index ->
            "${names[index] ?: files[index].subject.take(60)} (${files[index].statedBytes / 1_000_000} MB, ${files[index].segments.size} articles)" })

        // Volumes named in the subjects: the biggest RAR set, in volume order.
        val named = files.indices.mapNotNull { index -> names[index]?.let(::rarOrder)?.let { Triple(it.first, it.second, files[index]) } }
            .groupBy { it.first }.values.maxByOrNull { set -> set.sumOf { it.third.statedBytes } }?.sortedBy { it.second }?.map { it.third }
        // Plain split parts ("film.mkv.001", "film.7z.001"): the biggest such set, in part order.
        val splits = files.indices.mapNotNull { index -> names[index]?.let(::splitOrder)?.let { Triple(it.first.lowercase(Locale.ROOT), it.second, files[index]) } }
            .groupBy { it.first }.maxByOrNull { (_, set) -> set.sumOf { it.third.statedBytes } }
        val video = files.indices.filter { names[it]?.let { name -> isVideo(name) || name.endsWith(".7z", true) } == true }
            .maxByOrNull { files[it].statedBytes }?.let { files[it] }
        val sizes = listOf(named?.sumOf { it.statedBytes } ?: 0L, splits?.value?.sumOf { it.third.statedBytes } ?: 0L, video?.statedBytes ?: 0L)
        when (sizes.indices.maxByOrNull { sizes[it] }?.takeIf { sizes[it] > 0 }) {
            0 -> return openSet(named!!, ::stream, log, trusted = true)
            1 -> {
                val ordered = splits!!.value.sortedBy { it.second }
                if (ordered.withIndex().any { (index, part) -> part.second != index + 1 })
                    throw UnsupportedSetException(L10n.tr(R.string.t_the_split_set_has_missing_or, "The split set has missing or duplicate parts."))
                return openSplit(splits.key, ordered.map { it.third }, ::stream, log, cache, nzb.password)
            }
            2 -> return openPlain(video!!, ::stream, log, cache, nzb.password)
        }

        // No usable names (some services scramble subjects): the biggest file decides.
        val biggest = files.maxByOrNull { it.statedBytes }!!
        val first = stream(biggest, true)
        first.prepare()
        val start = head(first)
        if (isSevenZip(start)) return sevenZip(first, cache, nzb.password, log)
        if (!Rar.isRar(start)) {
            log("plain file: ${first.name} · starts ${hex(start)}")
            if (!looksLikeVideo(start)) { first.close(); refuseArchive(start, first.name) }
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
                ?: throw UnsupportedSetException(L10n.tr(R.string.t_the_set_s_parts_can_t, "The set's parts can't be put in order."))
            openSet(set, ::stream, log, trusted = true)
        }
    }

    private fun sevenZip(source: ByteSource, cache: java.io.File?, password: String?, log: (String) -> Unit): Opened {
        return SevenZipMedia.open(source, cache, password, log)
    }

    private class OutOfOrderException : java.io.IOException(L10n.tr(R.string.t_the_set_s_parts_are_out, "The set's parts are out of order."))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun head(source: ByteSource): ByteArray {
        val out = ByteArray(16)
        val count = source.read(0, out, 0, 16)
        return if (count == 16) out else out.copyOf(maxOf(count, 0))
    }

    private fun openPlain(file: NzbFile, stream: (NzbFile, Boolean) -> UsenetStream, log: (String) -> Unit, cache: java.io.File?, password: String?): Opened {
        val opened = stream(file, true)
        // Ask for the opening articles and the last two at once: a player reads the start, then jumps to the end
        // (an MKV's seek index, an MP4's moov), then back. Fetched together, that is one wait instead of three.
        opened.warm(); opened.beginTail(((VIDEO_TAIL_BYTES + 716_799) / 716_800).toInt())
        opened.prepare()
        val start = head(opened)
        if (isSevenZip(start)) return sevenZip(opened, cache, password, log)
        if (Rar.isRar(start)) { opened.close(); return openSet(listOf(file), stream, log, trusted = true) }
        log("plain file: ${opened.name} · starts ${hex(start)}")
        if (!looksLikeVideo(start)) { opened.close(); refuseArchive(start, opened.name) }
        return Opened(opened, opened.name, "single file")
    }

    /**
     * Plain split parts form one seekable source, used directly for video or passed to the 7z decoder.
     */
    private fun openSplit(base: String, parts: List<NzbFile>, stream: (NzbFile, Boolean) -> UsenetStream, log: (String) -> Unit, cache: java.io.File?, password: String?): Opened {
        val streams = parts.mapIndexed { index, part -> stream(part, index == 0 || index == parts.lastIndex) }
        try {
            val first = streams[0]
            // Ask for everything opening needs at once, instead of one after another: the first part's first article,
            // the first article of every part not the same size as the first (usually just the last part), and the
            // last part's final articles, where a 7z archive keeps its directory. One round trip instead of three.
            // Parts with the same article count are the same size: one of each size is asked (usually the first,
            // the second when the first is a different size, and the last); the rest take its size without a fetch.
            val leaders = HashMap<Int, Int>()
            val leader = IntArray(streams.size) { index ->
                if (index == streams.lastIndex) index else leaders.getOrPut(parts[index].segments.size) { index } }
            streams.forEachIndexed { index, part -> if (leader[index] == index) part.begin() }
            streams.last().beginTail(2)
            first.warm()
            first.prepare()
            streams.forEachIndexed { index, part -> if (leader[index] == index) part.prepare() }
            streams.forEachIndexed { index, part -> if (leader[index] != index) part.assumeLike(streams[leader[index]]) }
            streams.forEach { it.prepare() }
            val set = VolumeSet(streams.sumOf { it.size }, streams.map { VolumeSet.Piece(it, 0, it.size) })
            val start = head(set)
            log("split set: $base · ${parts.size} parts · ${set.size} bytes · starts ${hex(start)}")
            if (isSevenZip(start)) return sevenZip(set, cache, password, log)
            if (Rar.isRar(start) || !looksLikeVideo(start)) refuseArchive(start, base)
            set.prefetch(set.size - minOf(set.size, VIDEO_TAIL_BYTES), VIDEO_TAIL_BYTES)
            return Opened(set, base, "split · ${parts.size} parts")
        } catch (error: Exception) {
            streams.forEach { it.close() }
            throw error
        }
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
                ?: throw UnsupportedSetException(L10n.tr(R.string.t_the_set_holds_no_file, "The set holds no file."))
            if (entry.splitBefore) { if (!trusted) throw OutOfOrderException(); throw UnsupportedSetException(L10n.tr(R.string.t_the_set_s_first_part_is, "The set's first part is missing.")) }
            if (entry.encrypted) throw UnsupportedSetException(L10n.tr(R.string.t_this_set_is_password_protected, "This set is password-protected."))
            if (!entry.stored) throw UnsupportedSetException(L10n.tr(R.string.t_this_set_is_compressed_so_it, "This set is compressed, so it can't be streamed."))
            if (!isVideo(entry.name)) throw UnsupportedSetException(L10n.tr(R.string.t_the_set_holds_no_video_x, "The set holds no video (%1\$s).", entry.name.take(60)))
            val pieces = ArrayList<VolumeSet.Piece>()
            pieces += VolumeSet.Piece(first, entry.dataOffset, minOf(entry.packedSize, entry.unpackedSize))
            var left = entry.unpackedSize - pieces[0].length
            if (left > 0) {
                if (!entry.splitAfter || volumes.size < 2) throw UnsupportedSetException(L10n.tr(R.string.t_the_set_is_incomplete, "The set is incomplete."))
                val second = stream(volumes[1], false)
                opened += second
                second.prepare()
                val next = Rar.entries(second, limit = 1).firstOrNull()
                if (next == null || !next.splitBefore || next.name != entry.name) { if (!trusted) throw OutOfOrderException(); throw UnsupportedSetException(L10n.tr(R.string.t_the_set_s_parts_don_t, "The set's parts don't follow one another.")) }
                if (!trusted && rarOrder(second.name)?.second?.let { it != 1 } == true) throw OutOfOrderException()
                if (!next.stored || next.packedSize <= 0) throw UnsupportedSetException(L10n.tr(R.string.t_this_set_is_compressed_so_it, "This set is compressed, so it can't be streamed."))
                var index = 1
                while (left > 0) {
                    if (index >= volumes.size) throw UnsupportedSetException(L10n.tr(R.string.t_the_set_is_incomplete_x_parts, "The set is incomplete: %1\$s parts listed, more are needed.", volumes.size))
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
