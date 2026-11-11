package dev.pov.tv

import org.junit.Assert.*
import org.junit.Test

class DpadSeekPreviewTest {
    @Test fun `each press moves ten seconds and release commits once`() {
        val seek = DpadSeekPreview()
        assertTrue(seek.begin(600_000, 7_200_000))
        assertEquals(610_000, seek.step(1))
        repeat(2) { seek.step(1) }
        assertTrue(seek.active)
        assertEquals(30, seek.offsetSeconds)
        assertEquals(630_000L, seek.finish())
        assertFalse(seek.active)
        assertNull(seek.finish())
    }
    @Test fun `reverse direction and cancellation preserve the original position`() {
        val seek = DpadSeekPreview()
        seek.begin(90_000, 600_000)
        repeat(2) { seek.step(1) }; repeat(5) { seek.step(-1) }
        assertEquals(60_000, seek.position)
        assertEquals(-30, seek.offsetSeconds)
        assertNull(seek.finish(cancelled = true))
        seek.begin(90_000, 600_000)
        assertEquals(80_000, seek.step(-1))
    }
    @Test fun `unknown duration and both boundaries are safe without overflowing`() {
        val seek = DpadSeekPreview()
        assertFalse(seek.begin(0, -1))
        assertFalse(seek.begin(0, 0))
        seek.begin(4_000, 22_000)
        assertEquals(0, seek.step(-1))
        repeat(5) { seek.step(1) }
        assertEquals(22_000, seek.position)
        seek.finish(); seek.begin(Long.MAX_VALUE - 500, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, seek.step(1))
    }
    @Test fun `a held key climbs to twenty then thirty seconds a step`() {
        val seek = DpadSeekPreview()
        seek.begin(0, 7_200_000)
        repeat(30) { seek.step(1) }
        assertEquals(300_000, seek.position)
        assertEquals(320_000, seek.step(1))
        repeat(29) { seek.step(1) }
        assertEquals(900_000, seek.position)
        assertEquals(930_000, seek.step(1))
        assertEquals(960_000, seek.step(1))
        repeat(1000) { seek.step(1) }
        assertEquals(7_200_000, seek.position)
        assertEquals(7_200_000L, seek.finish())
    }
    @Test fun `reversing or a fresh press goes back to ten second steps`() {
        val seek = DpadSeekPreview()
        seek.begin(2_000_000, 7_200_000)
        repeat(35) { seek.step(-1) } // 30 x 10 s, then 5 x 20 s
        assertEquals(1_600_000, seek.position)
        assertEquals(1_610_000, seek.step(1))
        repeat(35) { seek.step(1) }
        seek.newPress()
        assertEquals(seek.position + 10_000, seek.step(1))
    }

    @Test fun `timer always includes seconds and a long movie includes hours`() {
        assertEquals("00:00", DpadSeekPreview.time(0))
        assertEquals("01:01", DpadSeekPreview.time(61_999))
        assertEquals("1:02:03", DpadSeekPreview.time(3_723_000))
    }
}
