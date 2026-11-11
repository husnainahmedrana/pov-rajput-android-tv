package dev.pov.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerJumpPointsTest {
    private fun labels(minutes: Double) = PlayerActivity.jumpPoints((minutes * 60_000).toLong()).map(PlayerActivity::jumpLabel)

    @Test fun `jump points spread over the video's own length`() {
        assertEquals(listOf("10m", "20m", "35m", "45m"), labels(56.1))
        assertEquals(listOf("20m", "40m", "1h", "1h 20m"), labels(100.0))
        assertEquals(listOf("30m", "1h", "1h 30m", "2h"), labels(150.0))
        assertEquals(listOf("4m", "8m", "13m", "17m"), labels(21.0))
        assertTrue(PlayerActivity.jumpPoints(3 * 60_000L).isEmpty())
        assertTrue(PlayerActivity.jumpPoints(0).isEmpty())
    }
}
