package dev.pov.tv

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LocalStreamServerTest {
    private val video = ByteArray(1_000_000) { (it * 31 + 7).toByte() }
    private fun server() = LocalStreamServer(DataSource.Factory { ByteArrayDataSource(video) }, Uri.parse("https://example.invalid/x#pov-nzb"),
        "First Blood (1982) 2160p.mkv")

    private fun get(address: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = method
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        val body = if (code < 400) connection.inputStream.use { it.readBytes() } else ByteArray(0)
        connection.disconnect()
        return Triple(code, headers, body)
    }

    @Test fun wholeVideo() = server().use { server ->
        val (code, headers, body) = get(server.address)
        assertEquals(200, code)
        assertEquals("1000000", headers["content-length"])
        assertEquals("bytes", headers["accept-ranges"])
        assertEquals("video/x-matroska", headers["content-type"])
        assertArrayEquals(video, body)
    }

    @Test fun byteRangesForSeeking() = server().use { server ->
        val (code, headers, body) = get(server.address, "bytes=500000-500099")
        assertEquals(206, code)
        assertEquals("bytes 500000-500099/1000000", headers["content-range"])
        assertArrayEquals(video.copyOfRange(500000, 500100), body)
        val (openCode, openHeaders, tail) = get(server.address, "bytes=999990-")
        assertEquals(206, openCode)
        assertEquals("bytes 999990-999999/1000000", openHeaders["content-range"])
        assertArrayEquals(video.copyOfRange(999990, 1000000), tail)
        val (suffixCode, _, last) = get(server.address, "bytes=-16")
        assertEquals(206, suffixCode)
        assertArrayEquals(video.copyOfRange(999984, 1000000), last)
        assertEquals(416, get(server.address, "bytes=1000000-").first)
    }

    @Test fun headAndWrongAddresses() = server().use { server ->
        val (code, headers, body) = get(server.address, method = "HEAD")
        assertEquals(200, code); assertEquals("1000000", headers["content-length"]); assertEquals(0, body.size)
        // Without the token nothing is served, so the address can't be guessed.
        assertEquals(404, get(server.address.substringBeforeLast('/') + "x/" + server.address.substringAfterLast('/')).first)
        assertEquals(404, get("http://127.0.0.1:" + URL(server.address).port + "/").first)
    }

    @Test fun nothingAfterClose() {
        val server = server(); val address = server.address; server.close()
        assertEquals(true, runCatching { get(address) }.isFailure)
    }

    @Test fun rangeParsing() {
        assertNull(LocalStreamServer.parseRange(null, 100))
        assertNull(LocalStreamServer.parseRange("items=0-1", 100))
        assertEquals(LocalStreamServer.Range(10, 99), LocalStreamServer.parseRange("bytes=10-", 100))
        assertEquals(LocalStreamServer.Range(10, 99), LocalStreamServer.parseRange("bytes=10-500", 100))
        assertEquals(LocalStreamServer.Range(90, 99), LocalStreamServer.parseRange("bytes=-10", 100))
        assertEquals(LocalStreamServer.Range.UNSATISFIABLE, LocalStreamServer.parseRange("bytes=100-", 100))
        assertEquals(LocalStreamServer.Range(0, 9), LocalStreamServer.parseRange("bytes=0-9, 20-29", 100))
    }

    @Test fun safeNames() {
        assertEquals("First.Blood.1982.2160p.mkv", LocalStreamServer.safeName("First Blood (1982) 2160p.mkv"))
        assertEquals("Movie.Title.mkv", LocalStreamServer.safeName("Movie Title"))
        assertEquals("a.mp4", LocalStreamServer.safeName("dir/a.mp4"))
        assertEquals("video.mkv", LocalStreamServer.safeName("???"))
    }

    @Test fun subtitlesForOtherPlayers() {
        val subs = listOf(dev.pov.tv.data.StreamSubtitle("https://s/a.srt", "fre", "A"), dev.pov.tv.data.StreamSubtitle("https://s/b.srt", "eng", "B"),
            dev.pov.tv.data.StreamSubtitle("file:///x.srt", "eng"), dev.pov.tv.data.StreamSubtitle("https://s/b.srt", "eng"))
        assertEquals(listOf("https://s/b.srt", "https://s/a.srt"), ExternalPlayer.orderSubtitles(subs, "en").map { it.url })
        assertEquals(listOf("https://s/a.srt", "https://s/b.srt"), ExternalPlayer.orderSubtitles(subs, "").map { it.url })
        assertEquals(emptyList<String>(), ExternalPlayer.orderSubtitles(subs, "off").map { it.url })
    }

    @Test fun playheadFollowsPlayingReadsNotProbes() {
        val big = ByteArray(20 * 1024 * 1024)
        LocalStreamServer(DataSource.Factory { ByteArrayDataSource(big) }, Uri.parse("https://example.invalid/big"), "big.mkv").use { server ->
            assertEquals(-1L, server.playhead)
            get(server.address, "bytes=${big.size - 1024 * 1024}-")              // an index probe at the end: ignored
            assertEquals(-1L, server.playhead)
            get(server.address, "bytes=1000-")                                   // playing from near the start
            assertEquals(big.size.toLong(), server.playhead)
            assertEquals(big.size.toLong(), server.knownSize)
        }
    }

    @Test fun matroskaDuration() {
        fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val duration = java.nio.ByteBuffer.allocate(4).putFloat(7_200_000f).array()
        // EBML header (empty), Segment of unknown size, Info { TimestampScale 1 ms, Duration 7 200 000 }.
        val mkv = hex("1A45DFA3 80 18538067 01FFFFFFFFFFFFFF 1549A966 8E 2AD7B1 83 0F4240 4489 84") + duration
        assertEquals(7_200_000L, VideoDuration.read(mkv))
    }

    @Test fun mp4Duration() {
        val out = java.io.ByteArrayOutputStream(); val data = java.io.DataOutputStream(out)
        data.writeInt(16); data.writeBytes("ftyp"); data.writeBytes("isom"); data.writeInt(0)
        data.writeInt(36); data.writeBytes("moov")
        data.writeInt(28); data.writeBytes("mvhd"); data.writeInt(0); data.writeInt(0); data.writeInt(0); data.writeInt(1000); data.writeInt(5_400_000)
        assertEquals(5_400_000L, VideoDuration.read(out.toByteArray()))
        assertNull(VideoDuration.read(ByteArray(64)))
    }

    @Test fun warmedStartIsServedThenTheRestStreams() {
        var opens = 0
        LocalStreamServer(DataSource.Factory { opens++; ByteArrayDataSource(video) }, Uri.parse("https://example.invalid/x"), "x.mkv").use { server ->
            server.warm(300_000)
            val before = opens
            val (code, _, body) = get(server.address, "bytes=1000-")
            assertEquals(206, code)
            assertArrayEquals(video.copyOfRange(1000, video.size), body)       // head from memory, then the stream
            assertEquals(before + 1, opens)
            val (_, _, start) = get(server.address, "bytes=0-99")
            assertArrayEquals(video.copyOfRange(0, 100), start)
            assertEquals(before + 1, opens)                                     // answered from the warmed start alone
        }
    }
}
