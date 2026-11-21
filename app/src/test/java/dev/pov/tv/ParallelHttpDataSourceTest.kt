package dev.pov.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ParallelHttpDataSourceTest {
    private val video = ByteArray(10 * 1024 * 1024 + 12_345) { (it * 31 + 7).toByte() }

    private fun readAll(source: DataSource, position: Long): ByteArray {
        val length = source.open(DataSpec.Builder().setUri(Uri.parse(address)).setPosition(position).build())
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, count) }
        source.close()
        assertEquals(video.size - position, length)
        return out.toByteArray()
    }

    private lateinit var address: String

    @Test fun piecesOverSeveralConnectionsComeBackInOrder() {
        // Nabrio's own local server answers ranged requests like a debrid CDN.
        LocalStreamServer(DataSource.Factory { ByteArrayDataSource(video) }, Uri.parse("https://example.invalid/v"), "v.mkv").use { server ->
            address = server.address
            val source = ParallelHttpDataSource(DefaultHttpDataSource.Factory().createDataSource(), emptyMap(), connections = 4, chunkSize = 1024 * 1024, switchAfter = 1024 * 1024)
            assertArrayEquals(video, readAll(source, 0))
            val seek = 3_333_333L
            val again = ParallelHttpDataSource(DefaultHttpDataSource.Factory().createDataSource(), emptyMap(), connections = 4, chunkSize = 1024 * 1024, switchAfter = 1024 * 1024)
            assertArrayEquals(video.copyOfRange(seek.toInt(), video.size), readAll(again, seek))
        }
    }
}
