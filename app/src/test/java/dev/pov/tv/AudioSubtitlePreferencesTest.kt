package dev.pov.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioSubtitlePreferencesTest {
    @Test fun `the subtitle fallback starts as the old shared switch, then is its own`() {
        val context = RuntimeEnvironment.getApplication()
        AudioPreferences(context).englishFallback = false
        // Never set on its own yet: follows the old shared switch.
        assertFalse(SubtitlePreferences(context).englishFallback)
        SubtitlePreferences(context).englishFallback = true
        AudioPreferences(context).englishFallback = false
        assertTrue(SubtitlePreferences(context).englishFallback)
        assertEquals(listOf("es", "en"), SubtitlePreferences(context).withFallback("es").toList())
        assertEquals(listOf("es"), AudioPreferences(context).withFallback("es").toList())
    }

    @Test fun `volume boost by default keeps a supported step`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(0, VolumeBoostPreferences(context).defaultDb)
        VolumeBoostPreferences(context).defaultDb = 6
        assertEquals("+6 dB", VolumeBoostPreferences(context).label)
    }
}
