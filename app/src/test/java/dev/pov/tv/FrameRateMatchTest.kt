package dev.pov.tv

import dev.pov.tv.data.MetadataLanguage
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class FrameRateMatchTest {
    private val uhd = listOf(
        FrameRateMatch.Mode(1, 3840, 2160, 59.94f), FrameRateMatch.Mode(2, 3840, 2160, 60f), FrameRateMatch.Mode(3, 3840, 2160, 23.976f),
        FrameRateMatch.Mode(4, 3840, 2160, 24f), FrameRateMatch.Mode(5, 3840, 2160, 50f), FrameRateMatch.Mode(6, 3840, 2160, 25f),
        FrameRateMatch.Mode(7, 1920, 1080, 23.976f), FrameRateMatch.Mode(8, 3840, 2160, 29.97f),
    )

    @Test fun `film rate picks the matching mode at the same resolution`() {
        assertEquals(3, FrameRateMatch.best(uhd, uhd[0], 23.976f)?.id)
        assertEquals(4, FrameRateMatch.best(uhd, uhd[0], 24f)?.id)
        assertEquals(6, FrameRateMatch.best(uhd, uhd[0], 25f)?.id)
    }

    @Test fun `a current mode that already fits stays`() {
        assertNull(FrameRateMatch.best(uhd, uhd[0], 29.97f))
        assertNull(FrameRateMatch.best(uhd, uhd[0], 59.94f))
        assertNull(FrameRateMatch.best(uhd, uhd[4], 25f))
    }

    @Test fun `whole multiples are used when the exact rate is missing, unknown rates change nothing`() {
        val noFilm = listOf(FrameRateMatch.Mode(1, 3840, 2160, 60f), FrameRateMatch.Mode(2, 3840, 2160, 47.952f), FrameRateMatch.Mode(3, 3840, 2160, 119.88f))
        assertEquals(2, FrameRateMatch.best(noFilm, noFilm[0], 23.976f)?.id)
        assertNull(FrameRateMatch.best(listOf(uhd[0], uhd[1]), uhd[0], 23.976f))
        assertNull(FrameRateMatch.best(uhd, uhd[0], -1f))
    }

    @Test fun `metadata language follows the saved choice, else the app or device language`() {
        assertEquals("ar-SA", MetadataLanguage.resolve("ar-SA", "es"))
        assertEquals("es-ES", MetadataLanguage.resolve("", "es", Locale.US))
        assertEquals("es-MX", MetadataLanguage.resolve("", "", Locale.forLanguageTag("es-MX")))
        assertEquals("ar-SA", MetadataLanguage.resolve("", "", Locale.forLanguageTag("ar-EG")))
        assertEquals("en-US", MetadataLanguage.resolve("", "", Locale.forLanguageTag("sw-KE")))
        assertEquals("en-US", MetadataLanguage.resolve("xx-YY", "", Locale.US))
        assertNull(MetadataLanguage.name(""))
        assertEquals("العربية", MetadataLanguage.name("ar-SA"))
    }
}
