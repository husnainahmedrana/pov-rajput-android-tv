package dev.pov.tv

import dev.pov.tv.usenet.ByteSource
import dev.pov.tv.usenet.MissingArticleException
import dev.pov.tv.usenet.NntpPool
import dev.pov.tv.usenet.NntpRefusedException
import dev.pov.tv.usenet.NntpServer
import dev.pov.tv.usenet.Nzb
import dev.pov.tv.usenet.NzbFile
import dev.pov.tv.usenet.NzbSegment
import dev.pov.tv.usenet.UnsupportedSetException
import dev.pov.tv.usenet.UsenetMedia
import dev.pov.tv.usenet.UsenetStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UsenetStreamTest {
    /** A news server on localhost holding files cut into yEnc articles. */
    private class FakeServer(val missing: Set<String> = emptySet(), val password: String = "secret", val maxConnections: Int = 100) {
        private class Article(val file: ByteArray, val begin: Int, val size: Int, val part: Int, val total: Int, val name: String)
        private val articles = HashMap<String, Article>()
        private var posted = 0
        val socket = ServerSocket(0)
        val busiest = AtomicInteger()
        val served = AtomicInteger()
        private val live = AtomicInteger()
        @Volatile var open = true

        /** Posts [file] as articles of [partSizes] and returns its NZB entry. */
        fun post(file: ByteArray, partSizes: List<Int>, name: String, subject: String = "\"$name\" yEnc"): NzbFile {
            val id = ++posted
            var at = 0
            val segments = partSizes.mapIndexed { index, size ->
                val key = "f$id-p${index + 1}@test"
                articles[key] = Article(file, at, size, index + 1, partSizes.size, name)
                at += size
                NzbSegment(index + 1, size + 900L, key)
            }
            check(at == file.size)
            return NzbFile(subject, segments)
        }

        init {
            Thread {
                while (open) {
                    val client = try { socket.accept() } catch (_: IOException) { break }
                    Thread { try { serve(client) } catch (_: IOException) {} finally { client.close() } }.start()
                }
            }.start()
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val out = client.getOutputStream()
            fun line(text: String) { out.write((text + "\r\n").toByteArray(Charsets.ISO_8859_1)) }
            if (live.incrementAndGet() > maxConnections) { live.decrementAndGet(); line("502 Too many connections"); out.flush(); return }
            try {
                busiest.accumulateAndGet(live.get(), ::maxOf)
                line("200 hello"); out.flush()
                while (true) {
                    val command = input.readLine() ?: return
                    when {
                        command.startsWith("AUTHINFO USER ") -> line("381 PASS required")
                        command.startsWith("AUTHINFO PASS ") -> line(if (command.removePrefix("AUTHINFO PASS ") == password) "281 Welcome" else "481 Authentication failed")
                        command.startsWith("BODY <") -> {
                            val key = command.removePrefix("BODY <").removeSuffix(">")
                            val article = articles[key]
                            if (article == null || key in missing) line("430 No such article")
                            else { served.incrementAndGet(); line("222 0 <$key>"); out.write(encode(article)) }
                        }
                        else -> line("500 What?")
                    }
                    if (!input.ready()) out.flush()
                }
            } finally { live.decrementAndGet() }
        }

        /** One article body: yEnc lines of 128 encoded bytes, dot-stuffed, ending with the lone dot. */
        private fun encode(article: Article): ByteArray {
            val out = ByteArrayOutputStream(article.size + article.size / 40 + 300)
            fun text(value: String) = out.write((value + "\r\n").toByteArray(Charsets.ISO_8859_1))
            text("=ybegin part=${article.part} total=${article.total} line=128 size=${article.file.size} name=${article.name}")
            text("=ypart begin=${article.begin + 1} end=${article.begin + article.size}")
            val line = ByteArrayOutputStream(140)
            fun endLine() {
                val bytes = line.toByteArray(); line.reset()
                if (bytes.isNotEmpty() && bytes[0] == '.'.code.toByte()) out.write('.'.code)
                out.write(bytes); out.write('\r'.code); out.write('\n'.code)
            }
            for (at in article.begin until article.begin + article.size) {
                val value = (article.file[at] + 42) and 0xFF
                if (value == 0 || value == 10 || value == 13 || value == 61) { line.write('='.code); line.write((value + 64) and 0xFF) } else line.write(value)
                if (line.size() >= 128) endLine()
            }
            if (line.size() > 0) endLine()
            val crc = CRC32().apply { update(article.file, article.begin, article.size) }
            text("=yend size=${article.size} part=${article.part} pcrc32=${java.lang.Long.toHexString(crc.value).padStart(8, '0')}")
            text(".")
            return out.toByteArray()
        }

        fun server(pass: String = password) = NntpServer("127.0.0.1", socket.localPort, false, "user", pass)
        fun close() { open = false; socket.close() }
    }

    private val servers = ArrayList<FakeServer>()
    private val pools = ArrayList<NntpPool>()
    @After fun tearDown() { pools.forEach { it.close() }; servers.forEach { it.close() } }

    private fun server(missing: Set<String> = emptySet(), maxConnections: Int = 100) = FakeServer(missing, maxConnections = maxConnections).also { servers += it }
    private fun pool(server: FakeServer, connections: Int, depth: Int = 1, pass: String = server.password) =
        NntpPool(server.server(pass), connections, depth).also { pools += it }

    private fun even(total: Int, part: Int) = ArrayList<Int>().apply { var left = total; while (left > 0) { add(minOf(part, left)); left -= part } }

    private fun readAll(source: ByteSource, from: Long, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = source.read(from + done, out, done, minOf(count - done, 9000))
            if (read < 0) break
            done += read
        }
        return out.copyOf(done)
    }

    private fun assertReadsLike(file: ByteArray, source: ByteSource, seed: Int) {
        assertEquals(file.size.toLong(), source.size)
        assertArrayEquals(file, readAll(source, 0, file.size))
        val random = Random(seed)
        repeat(40) {
            val from = random.nextInt(file.size); val count = minOf(random.nextInt(1, 150_000), file.size - from)
            assertArrayEquals("from $from", file.copyOfRange(from, from + count), readAll(source, from.toLong(), count))
        }
        assertEquals(-1, source.read(file.size.toLong(), ByteArray(10), 0, 10))
    }

    // ---- RAR volume builders ----

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }
    private fun vint(value: Long): ByteArray {
        val out = ByteArrayOutputStream(); var left = value
        while (true) { val low = (left and 0x7F).toInt(); left = left ushr 7; if (left == 0L) { out.write(low); break }; out.write(low or 0x80) }
        return out.toByteArray()
    }

    /** Old-format (RAR 4) volumes holding [inner] stored under [name], [perVolume] data bytes each. */
    private fun rar4(inner: ByteArray, name: String, perVolume: Int, method: Int = 0x30, large: Boolean = false, password: Boolean = false): List<ByteArray> {
        val count = (inner.size + perVolume - 1) / perVolume
        return (0 until count).map { index ->
            val data = inner.copyOfRange(index * perVolume, minOf(inner.size, (index + 1) * perVolume))
            val nameBytes = name.toByteArray()
            var flags = 0x8000 or (if (index > 0) 0x01 else 0) or (if (index < count - 1) 0x02 else 0) or (if (large) 0x100 else 0) or (if (password) 0x04 else 0)
            val headSize = 32 + (if (large) 8 else 0) + nameBytes.size
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1a, 0x07, 0x00))
            out.write(byteArrayOf(0, 0, 0x73) + le(if (index == 0) 0x0101 else 0x0001, 2) + le(13, 2) + ByteArray(6))
            out.write(byteArrayOf(0, 0, 0x74) + le(flags.toLong(), 2) + le(headSize.toLong(), 2) + le(data.size.toLong(), 4) + le(inner.size.toLong(), 4) +
                byteArrayOf(2) + ByteArray(4) + ByteArray(4) + byteArrayOf(0x14, method.toByte()) + le(nameBytes.size.toLong(), 2) + le(0x20, 4) +
                (if (large) ByteArray(8) else ByteArray(0)) + nameBytes)
            out.write(data)
            out.write(byteArrayOf(0, 0, 0x7b) + le(0x4000, 2) + le(7, 2))
            out.toByteArray()
        }
    }

    /** New-format (RAR 5) volumes: [extra] small files stored whole in the first volume, then [inner] across all of them. */
    private fun rar5(inner: ByteArray, name: String, perVolume: Int, extra: List<Pair<String, ByteArray>> = emptyList()): List<ByteArray> {
        fun block(body: ByteArray) = ByteArray(4) + vint(body.size.toLong()) + body
        fun file(fileName: String, data: ByteArray, total: Int, before: Boolean, after: Boolean): ByteArray {
            val flags = 0x02L or (if (before) 0x08 else 0) or (if (after) 0x10 else 0)
            val nameBytes = fileName.toByteArray()
            return block(vint(2) + vint(flags) + vint(data.size.toLong()) + vint(0) + vint(total.toLong()) + vint(0x20) + vint(0) + vint(0) +
                vint(nameBytes.size.toLong()) + nameBytes) + data
        }
        val count = (inner.size + perVolume - 1) / perVolume
        return (0 until count).map { index ->
            val data = inner.copyOfRange(index * perVolume, minOf(inner.size, (index + 1) * perVolume))
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte(), 0x1a, 0x07, 0x01, 0x00))
            out.write(block(vint(1) + vint(0) + vint(1)))
            if (index == 0) extra.forEach { (extraName, bytes) -> out.write(file(extraName, bytes, bytes.size, before = false, after = false)) }
            out.write(file(name, data, inner.size, before = index > 0, after = index < count - 1))
            out.write(block(vint(5) + vint(0) + vint(0)))
            out.toByteArray()
        }
    }

    // ---- tests ----

    @Test fun `nzb lists files and articles in order and refuses unsafe message ids`() {
        val nzb = Nzb.parse("""<?xml version="1.0"?><!DOCTYPE nzb PUBLIC "x" "http://example.invalid/nzb.dtd"><nzb>
            <head><meta type="password">pw&amp;1</meta></head>
            <file poster="a &lt;b@c&gt;" date="1" subject="My &quot;file&quot; (1/2)"><groups><group>g</group></groups><segments>
              <segment bytes="20" number="2">two@x</segment><segment bytes="10" number="1">one&amp;1@x</segment>
              <segment bytes="5" number="3">bad id
BODY evil</segment>
            </segments></file>
            <file subject="second"><segments><segment number="1" bytes="7">s@x</segment></segments></file></nzb>""")
        assertEquals("pw&1", nzb.password)
        assertEquals(2, nzb.files.size)
        assertEquals("My \"file\" (1/2)", nzb.files[0].subject)
        assertEquals(listOf("one&1@x", "two@x"), nzb.files[0].segments.map { it.messageId })
        assertEquals(30L, nzb.files[0].statedBytes)
        assertEquals(listOf("s@x"), nzb.files[1].segments.map { it.messageId })
    }

    @Test fun `a file reads back whole and from any position over several pipelined connections`() {
        val file = Random(7).nextBytes(1_000_003)
        val server = server()
        val posted = server.post(file, even(file.size, 64_000), "test video.mkv")
        val stream = UsenetStream(pool(server, connections = 4, depth = 3), posted.segments, windowBytes = 640_000)
        stream.prepare()
        assertEquals("test video.mkv", stream.name)
        assertReadsLike(file, stream, 11)
        assertTrue("used ${server.busiest.get()} connections", server.busiest.get() in 2..4)
    }

    @Test fun `parts of uneven sizes are still found by position`() {
        val file = Random(3).nextBytes(500_000)
        val sizes = listOf(64_000) + even(file.size - 64_000 - 30_000, 50_000) + listOf(30_000)
        val server = server()
        val stream = UsenetStream(pool(server, 3), server.post(file, sizes, "a.mkv").segments, windowBytes = 300_000)
        stream.prepare()
        assertReadsLike(file, stream, 5)
    }

    @Test fun `a missing article fails the read with that reason`() {
        val file = Random(1).nextBytes(300_000)
        val server = server(missing = setOf("f1-p4@test"))
        val stream = UsenetStream(pool(server, 2), server.post(file, even(file.size, 50_000), "a.mkv").segments)
        assertArrayEquals(file.copyOfRange(0, 150_000), readAll(stream, 0, 150_000))
        try { readAll(stream, 150_000, 10); fail("expected a missing article") } catch (_: MissingArticleException) {}
        // The articles after the hole are still readable.
        assertArrayEquals(file.copyOfRange(200_000, 300_000), readAll(stream, 200_000, 100_000))
    }

    @Test fun `a wrong password fails with the server's refusal`() {
        val server = server()
        val posted = server.post(Random(2).nextBytes(100_000), even(100_000, 50_000), "a.mkv")
        val made = pool(server, 2, pass = "wrong")
        try { UsenetStream(made, posted.segments).prepare(); fail("expected a refusal") } catch (error: NntpRefusedException) { assertEquals(481, error.code) }
        assertEquals(0, made.connected)
    }

    @Test fun `connections the server refuses are left out and the rest carry the file`() {
        val file = Random(9).nextBytes(800_000)
        val server = server(maxConnections = 2)
        val made = pool(server, 6)
        val stream = UsenetStream(made, server.post(file, even(file.size, 40_000), "a.mkv").segments, windowBytes = 400_000)
        assertArrayEquals(file, readAll(stream, 0, file.size))
        assertTrue(made.connected <= 2)
        assertEquals(502, made.refusal?.code)
    }

    @Test fun `a closed stream stops readers`() {
        val server = server()
        val made = pool(server, 2)
        val stream = UsenetStream(made, server.post(Random(4).nextBytes(200_000), even(200_000, 50_000), "a.mkv").segments)
        stream.prepare()
        stream.close(); made.close()
        try { stream.read(120_000, ByteArray(10), 0, 10); fail("expected closed") } catch (_: IOException) {}
        assertNull(made.refusal)
    }

    @Test fun `a video beside its repair files plays as a plain file`() {
        val file = Random(21).nextBytes(400_000)
        val server = server()
        val nzb = Nzb(listOf(
            server.post(Random(1).nextBytes(90_000), even(90_000, 30_000), "Show.S01E02.1080p.vol00+01.par2"),
            server.post(file, even(file.size, 30_000), "Show.S01E02.1080p.mkv", subject = "[1/3] - \"Show.S01E02.1080p.mkv\" yEnc (1/14)"),
            server.post("nfo".toByteArray(), listOf(3), "Show.S01E02.1080p.nfo")), null)
        val opened = UsenetMedia.open(pool(server, 3), nzb)
        assertEquals("Show.S01E02.1080p.mkv", opened.name)
        assertEquals("single file", opened.kind)
        assertReadsLike(file, opened.source, 2)
    }

    @Test fun `a stored old-format set with scrambled subjects reads straight through its volumes`() {
        val inner = Random(31).nextBytes(1_500_000)
        val server = server()
        val volumes = rar4(inner, "Movie.2023.1080p.mkv", perVolume = 200_000)
        val names = listOf("movie.rar") + (0 until volumes.size - 1).map { "movie.r%02d".format(it) }
        val nzb = Nzb(volumes.mapIndexed { index, bytes -> server.post(bytes, even(bytes.size, 30_000), names[index], subject = "x${Random(index).nextInt()}") }, null)
        val opened = UsenetMedia.open(pool(server, 4), nzb)
        assertEquals("Movie.2023.1080p.mkv", opened.name)
        assertEquals("RAR · 8 parts", opened.kind)
        // A jump into the middle of a far volume, before anything else has been read.
        assertArrayEquals(inner.copyOfRange(1_050_000, 1_050_500), readAll(opened.source, 1_050_000, 500))
        assertReadsLike(inner, opened.source, 3)
    }

    @Test fun `named volumes listed out of order are put in volume order`() {
        val inner = Random(41).nextBytes(900_000)
        val server = server()
        val volumes = rar4(inner, "Film.mkv", perVolume = 150_000, large = true)
        val names = listOf("film.rar") + (0 until volumes.size - 1).map { "film.r%02d".format(it) }
        // Alphabetical listing puts .r00 … before .rar.
        val order = (1 until volumes.size).toList() + 0
        val nzb = Nzb(order.map { server.post(volumes[it], even(volumes[it].size, 40_000), names[it]) }, null)
        assertReadsLike(inner, UsenetMedia.open(pool(server, 3), nzb).source, 4)
    }

    @Test fun `scrambled subjects listed out of order are sorted by the names inside the articles`() {
        val inner = Random(51).nextBytes(700_000)
        val server = server()
        val volumes = rar4(inner, "Film.mkv", perVolume = 150_000)
        val names = listOf("film.rar") + (0 until volumes.size - 1).map { "film.r%02d".format(it) }
        val order = (1 until volumes.size).toList() + 0
        val nzb = Nzb(order.map { server.post(volumes[it], even(volumes[it].size, 40_000), names[it], subject = "scrambled $it") }, null)
        assertReadsLike(inner, UsenetMedia.open(pool(server, 3), nzb).source, 6)
    }

    @Test fun `a new-format set with small files before the video is read too`() {
        val inner = Random(61).nextBytes(1_100_000)
        val server = server()
        val volumes = rar5(inner, "Movie.2160p.mkv", perVolume = 250_000, extra = listOf("info.nfo" to Random(1).nextBytes(700), "Sample.txt" to Random(2).nextBytes(90_000)))
        val nzb = Nzb(volumes.mapIndexed { index, bytes -> server.post(bytes, even(bytes.size, 50_000), "movie.part%02d.rar".format(index + 1)) } +
            server.post(Random(8).nextBytes(60_000), even(60_000, 30_000), "movie.vol00+02.par2"), null)
        val opened = UsenetMedia.open(pool(server, 4), nzb)
        assertEquals("Movie.2160p.mkv", opened.name)
        assertReadsLike(inner, opened.source, 7)
    }

    @Test fun `compressed or password-protected sets are refused with the reason`() {
        val server = server()
        fun open(volumes: List<ByteArray>) = UsenetMedia.open(pool(server, 2), Nzb(volumes.mapIndexed { index, bytes ->
            server.post(bytes, even(bytes.size, 40_000), if (index == 0) "a.rar" else "a.r%02d".format(index - 1)) }, null))
        val inner = Random(71).nextBytes(200_000)
        try { open(rar4(inner, "a.mkv", 90_000, method = 0x33)); fail("expected a refusal") } catch (error: UnsupportedSetException) { assertTrue(error.message, "compressed" in error.message!!) }
        try { open(rar4(inner, "a.mkv", 90_000, password = true)); fail("expected a refusal") } catch (error: UnsupportedSetException) { assertTrue(error.message, "password" in error.message!!) }
    }

    @Test fun `a backup provider supplies the articles the main one is missing`() {
        val file = Random(81).nextBytes(600_000)
        val main = server(missing = setOf("f1-p3@test", "f1-p9@test"))
        val backup = server()
        val posted = main.post(file, even(file.size, 40_000), "a.mkv")
        backup.post(file, even(file.size, 40_000), "a.mkv")
        val chain = dev.pov.tv.usenet.ProviderChain(listOf(pool(main, 3), pool(backup, 2)))
        val stream = UsenetStream(chain, posted.segments, windowBytes = 400_000)
        stream.prepare()
        assertReadsLike(file, stream, 12)
        assertTrue("rescued ${chain.rescued.get()}", chain.rescued.get() >= 2)
        // With no provider holding an article, the read fails with that reason.
        val alone = dev.pov.tv.usenet.ProviderChain(listOf(pool(main, 2), pool(server(missing = setOf("f1-p3@test")).also { it.post(file, even(file.size, 40_000), "a.mkv") }, 2)))
        try { readAll(UsenetStream(alone, posted.segments), 80_000, 50_000); fail("expected a missing article") } catch (_: MissingArticleException) {}
    }

    @Test fun `subject names and volume order are read from the usual spellings`() {
        assertEquals("Movie.2023.mkv", UsenetMedia.subjectName("[01/20] - \"Movie.2023.mkv\" yEnc (1/400)"))
        assertEquals("Movie.2023.part03.rar", UsenetMedia.subjectName("Movie.2023.part03.rar (3/400)"))
        assertNull(UsenetMedia.subjectName("IokWnOOZCgGk0x6VNSbbsRbHJ"))
        assertEquals("movie" to 0, UsenetMedia.rarOrder("Movie.rar"))
        assertEquals("movie" to 1, UsenetMedia.rarOrder("Movie.r00"))
        assertEquals("movie" to 100, UsenetMedia.rarOrder("Movie.r99"))
        assertEquals("movie" to 101, UsenetMedia.rarOrder("Movie.s00"))
        assertEquals("movie" to 2, UsenetMedia.rarOrder("Movie.part03.rar"))
        assertNull(UsenetMedia.rarOrder("Movie.mkv"))
    }
}
