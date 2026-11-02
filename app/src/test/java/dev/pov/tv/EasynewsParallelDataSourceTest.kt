package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EasynewsParallelDataSourceTest {
    private val file = Random(7).nextBytes(5_000_123)
    private val origin = "https://members.easynews.com/dl/auto/443/abc.mkv/Movie.mkv"
    private val server = "https://ams-dl-01.easynews.com:443/dl/token/Movie.mkv"

    /** A fake Easynews: the members address redirects to the download server, which answers byte ranges. */
    private inner class Fake(url: URL, private val dropFirst: AtomicInteger, private val auth: MutableList<String?>) : HttpURLConnection(url) {
        private val range get() = getRequestProperty("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { auth += getRequestProperty("Authorization"); return if (url.host == "members.easynews.com") 302 else 206 }
        override fun getHeaderField(name: String): String? = when {
            name == "Location" && url.host == "members.easynews.com" -> server
            name == "Content-Range" -> { val (from, to) = range; "bytes $from-${minOf(to, file.size - 1L)}/${file.size}" }
            else -> null
        }
        override fun getHeaderFields(): Map<String?, List<String>> = mapOf("Content-Range" to listOf(getHeaderField("Content-Range")!!))
        override fun getInputStream(): InputStream {
            val (from, to) = range
            val bytes = file.copyOfRange(from.toInt(), minOf(to, file.size - 1L).toInt() + 1)
            // One connection drops halfway, to check that the piece resumes where it stopped.
            if (from > 0 && dropFirst.getAndDecrement() > 0) return object : InputStream() {
                var index = 0
                override fun read() = if (index < bytes.size / 2) bytes[index++].toInt() and 255 else throw IOException("reset")
            }
            if (slow) Thread.sleep(30)
            return bytes.inputStream()
        }
    }

    /** Makes every download wait a little, so the reader really blocks waiting for pieces. */
    @Volatile private var slow = false

    private fun readAll(position: Long, connections: Int, auth: MutableList<String?> = mutableListOf(), interrupt: Boolean = false): ByteArray {
        val drops = AtomicInteger(1)
        val source = EasynewsParallelDataSource(mapOf("Authorization" to "Basic abc"), connections, chunkSize = 300_000,
            connect = { uri: URI -> Fake(uri.toURL(), drops, auth) })
        val length = source.open(DataSpec.Builder().setUri(Uri.parse(origin)).setPosition(position).build())
        assertEquals(file.size - position, length)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(65_536)
        while (true) {
            // The player interrupts the loading thread when it cancels a load; a read must ride that out like a socket.
            if (interrupt) Thread.currentThread().interrupt()
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        source.close()
        return out.toByteArray()
    }

    @Test fun `pieces arrive in order with the exact bytes from any start`() {
        assertArrayEquals(file, readAll(0, 4))
        assertArrayEquals(file.copyOfRange(1_234_567, file.size), readAll(1_234_567, 8))
    }

    @Test fun `an interrupted read keeps waiting and returns the right bytes`() {
        slow = true
        try { assertArrayEquals(file.copyOfRange(7_911, file.size), readAll(7_911, 8, interrupt = true)) }
        finally { slow = false; Thread.interrupted() }
    }

    @Test fun `the login only goes to Easynews hosts`() {
        val auth = mutableListOf<String?>()
        readAll(0, 4, auth)
        assertTrue(auth.isNotEmpty() && auth.all { it == "Basic abc" })
    }
}
