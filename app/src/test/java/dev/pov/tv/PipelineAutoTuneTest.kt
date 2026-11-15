package dev.pov.tv

import dev.pov.tv.PipelineAutoTune.Setting
import dev.pov.tv.PipelineAutoTune.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PipelineAutoTuneTest {
    private fun step(depth: Int, connections: Int, mbps: Double, port: Int = 563, tls: Boolean = true, firstMs: Long = 500, round: Int = 1) =
        Step(Setting(port, tls, depth, connections, round), connections, mbps, 0, firstMs)

    @Test fun `the smallest depth within 5 percent of the fastest wins`() {
        val steps = listOf(step(1, 20, 40.0), step(2, 20, 70.0), step(4, 20, 95.0), step(8, 20, 99.0), step(12, 20, 100.0), step(16, 20, 97.0))
        assertEquals(4, PipelineAutoTune.pick(steps))
    }

    @Test fun `a clear winner is picked even when deeper`() {
        assertEquals(16, PipelineAutoTune.pick(listOf(step(1, 20, 10.0), step(2, 20, 20.0), step(8, 20, 60.0), step(16, 20, 120.0))))
    }

    @Test fun `a depth measured twice counts by its average, so one lucky reading can't win`() {
        // 12 read 15 then 9 (average 12); 11 read 13 and 13: 11 wins.
        val steps = listOf(step(11, 20, 13.0), step(12, 20, 15.0), step(11, 20, 13.0), step(12, 20, 9.0))
        assertEquals(11, PipelineAutoTune.pick(steps))
    }

    @Test fun `the fewest connections within 5 percent of the fastest win`() {
        val steps = listOf(step(12, 4, 6.0), step(12, 8, 14.5), step(12, 12, 15.0), step(12, 20, 15.1))
        assertEquals(8, PipelineAutoTune.pickConnections(steps))
    }

    @Test fun `when ports tie on speed the quickest first article wins`() {
        val slow = step(1, 12, 15.0, port = 563, firstMs = 1400, round = 0)
        val quick = step(1, 12, 14.6, port = 443, firstMs = 600, round = 0)
        assertSame(quick, PipelineAutoTune.pickPort(listOf(slow, quick)))
    }

    @Test fun `a plain port must be clearly faster than SSL to win`() {
        val ssl = step(1, 12, 15.0, port = 563, firstMs = 900, round = 0)
        val plainTie = step(1, 12, 15.2, port = 119, tls = false, firstMs = 300, round = 0)
        assertSame(ssl, PipelineAutoTune.pickPort(listOf(ssl, plainTie)))
        val plainFast = step(1, 12, 30.0, port = 119, tls = false, round = 0)
        assertSame(plainFast, PipelineAutoTune.pickPort(listOf(ssl, plainFast)))
    }

    @Test fun `a provider on SSL never gets a plain port saved, only mentioned`() {
        val ssl = step(1, 12, 15.0, port = 563, round = 0)
        val plainFast = step(1, 12, 30.0, port = 119, tls = false, round = 0)
        assertSame(ssl, PipelineAutoTune.pickPort(listOf(ssl, plainFast), allowPlain = false))
        assertSame(plainFast, PipelineAutoTune.plainFaster(listOf(ssl, plainFast)))
        assertNull(PipelineAutoTune.plainFaster(listOf(ssl, step(1, 12, 15.5, port = 80, tls = false, round = 0))))
    }

    @Test fun `the provider's own port is tried first, kept even when only its SSL setting differs`() {
        val provider = dev.pov.tv.data.NewsProvider("id", "Test", "news.example.com", 8443, true, "u", "p", 20)
        assertEquals(listOf(8443, 563, 443, 119, 80, 23), PipelineAutoTune.ports(provider).map { it.first })
        val plain563 = provider.copy(port = 563, tls = false)
        assertEquals(listOf(563 to false, 563 to true, 443 to true, 119 to false, 80 to false, 23 to false), PipelineAutoTune.ports(plain563))
    }

    @Test fun `an enforced connection limit keeps a share for other apps`() {
        assertEquals(20, PipelineAutoTune.withinLimit(20, null))
        assertEquals(24, PipelineAutoTune.withinLimit(32, 30))
        assertEquals(12, PipelineAutoTune.withinLimit(12, 30))
        assertEquals(1, PipelineAutoTune.withinLimit(5, 1))
    }

    @Test fun `a reading far below both neighbours is taken again, a real slope is not`() {
        val dip = listOf(step(1, 12, 34.0), step(2, 12, 33.0), step(4, 12, 37.0), step(8, 12, 42.0), step(12, 12, 41.0), step(16, 12, 21.0), step(20, 12, 44.0))
        assertEquals(listOf(16), PipelineAutoTune.outliers(dip) { it.depth })
        val slope = listOf(step(1, 12, 10.0), step(2, 12, 20.0), step(4, 12, 30.0))
        assertEquals(emptyList<Int>(), PipelineAutoTune.outliers(slope) { it.depth })
    }

    @Test fun `the same setting measured very differently marks the run unsteady`() {
        assertEquals(true, PipelineAutoTune.unsteady(listOf(step(20, 12, 44.0), step(20, 12, 2.0))))
        assertEquals(false, PipelineAutoTune.unsteady(listOf(step(20, 12, 44.0), step(20, 12, 40.0), step(8, 12, 10.0))))
    }

    @Test fun `a deeper pipeline must beat pipeline 1 by 10 percent, else pipelining stays off`() {
        // 4 is only 6% better than 1: stays at 1.
        assertEquals(1, PipelineAutoTune.pickPipeline(listOf(step(1, 4, 20.0), step(2, 4, 20.5), step(4, 4, 21.2), step(8, 4, 19.0))))
        // 4 and 8 beat 1 by 25-30%: the smallest within 5% of the best of them, 4.
        assertEquals(4, PipelineAutoTune.pickPipeline(listOf(step(1, 4, 20.0), step(2, 4, 21.0), step(4, 4, 25.0), step(8, 4, 26.0))))
        // Averages count: 8 read 30 then 18 (24, only 20% over 1) and 4 read 24 and 24 (20%): 4 is the smallest near the best.
        assertEquals(4, PipelineAutoTune.pickPipeline(listOf(step(1, 4, 20.0), step(4, 4, 24.0), step(4, 4, 24.0), step(8, 4, 30.0), step(8, 4, 18.0))))
    }

    @Test fun `nothing downloaded means no change`() {
        assertNull(PipelineAutoTune.pick(emptyList()))
        assertNull(PipelineAutoTune.pick(listOf(step(1, 20, 0.0), step(2, 20, 0.0))))
        assertNull(PipelineAutoTune.pickConnections(emptyList()))
        assertNull(PipelineAutoTune.pickPort(emptyList()))
    }

    @Test fun `no saved article list means no test`() {
        assertNull(PipelineAutoTune.articles(null))
        assertNull(PipelineAutoTune.articles(java.nio.file.Files.createTempDirectory("autotune").toFile()))
    }
}
