package dev.pov.tv

import dev.pov.tv.data.ApiException
import dev.pov.tv.usenet.MissingArticleException
import dev.pov.tv.usenet.Nzb
import dev.pov.tv.usenet.isCancellation
import dev.pov.tv.usenet.isPassingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UsenetFailuresTest {
    @Test fun `stopping or switching links is a cancellation, a timeout is not`() {
        assertTrue(isCancellation(java.io.InterruptedIOException("interrupted")))
        assertTrue(isCancellation(IOException("wrapped", InterruptedException())))
        assertFalse(isCancellation(java.net.SocketTimeoutException("read timed out")))
        assertFalse(isCancellation(IOException("The news server took too long")))
    }

    @Test fun `a DNS blip or a busy indexer is passing, a missing article or a refused grab is not`() {
        assertTrue(isPassingFailure(java.net.UnknownHostException("news.example.com")))
        assertTrue(isPassingFailure(ApiException(0, "The indexer couldn't be reached.")))
        assertTrue(isPassingFailure(ApiException(503, "busy")))
        assertTrue(isPassingFailure(ApiException(429, "slow down")))
        assertFalse(isPassingFailure(ApiException(403, "forbidden")))
        assertFalse(isPassingFailure(MissingArticleException("a@b")))
    }

    @Test fun `segments without a bytes attribute still parse, using only their own tag`() {
        val nzb = """<?xml version="1.0"?><nzb><file subject="&quot;a.mkv&quot; yEnc (1/2)"><segments>
            <segment number="1">p1@test</segment><segment bytes="500" number="2">p2@test</segment>
            </segments></file></nzb>"""
        val parsed = Nzb.parse(nzb)
        val segments = parsed.files.single().segments
        assertEquals(listOf("p1@test", "p2@test"), segments.map { it.messageId })
        assertEquals(500L, segments.single { it.number == 2 }.bytes)
    }
}
