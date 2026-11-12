package dev.pov.tv

import dev.pov.tv.usenet.ArticleHealth
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ArticleHealthTest {
    @Test fun `a timeout is unknown not a takedown`() {
        val result = ArticleHealth.check(listOf("a"), listOf({ throw IOException("timeout") }))
        assertTrue(result.missing.isEmpty()); assertEquals(setOf("a"), result.unknown)
    }
    @Test fun `only confirmed missing on every provider fails`() {
        val result = ArticleHealth.check(listOf("a", "b"), listOf({ it }, { listOf("a") }))
        assertEquals(setOf("a"), result.missing); assertTrue(result.unknown.isEmpty())
    }
    @Test fun `an unavailable backup leaves a missing primary inconclusive`() {
        val result = ArticleHealth.check(listOf("a"), listOf({ it }, { throw IOException("limit") }))
        assertTrue(result.missing.isEmpty()); assertEquals(setOf("a"), result.unknown)
    }
    @Test fun `backup proves availability even after failed check`() {
        val result = ArticleHealth.check(listOf("a"), listOf({ throw IOException() }, { emptyList() }))
        assertTrue(result.missing.isEmpty()); assertTrue(result.unknown.isEmpty())
    }
}
