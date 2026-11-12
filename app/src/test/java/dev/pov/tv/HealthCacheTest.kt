package dev.pov.tv

import dev.pov.tv.usenet.HealthCache
import org.junit.Assert.*
import org.junit.Test

class HealthCacheTest {
    @Test fun `checks expire and changed provider keys are not reused`() {
        var clock = 0L
        val cache = HealthCache({ clock }, 100)
        assertFalse(cache.contains("release/provider-a"))
        cache.remember("release/provider-a")
        clock = 99
        assertTrue(cache.contains("release/provider-a"))
        assertFalse(cache.contains("release/provider-b"))
        clock = 100
        assertFalse(cache.contains("release/provider-a"))
    }
    @Test fun `read failures can invalidate checks and the cache is bounded`() {
        val cache = HealthCache({ 0 })
        for (i in 0..64) cache.remember("$i")
        assertFalse(cache.contains("0")); assertTrue(cache.contains("64"))
        cache.clear()
        assertFalse(cache.contains("64"))
    }
}
