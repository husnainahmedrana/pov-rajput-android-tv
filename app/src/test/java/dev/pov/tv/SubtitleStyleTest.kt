package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SubtitleStyleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val base = CaptionStyleCompat(Color.YELLOW, Color.BLUE, Color.RED, CaptionStyleCompat.EDGE_TYPE_NONE, Color.GREEN, Typeface.SERIF)
    @Before fun reset() { context.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `chosen colors, outline and a see-through box replace the device style`() {
        val preferences = SubtitlePreferences(context).apply {
            textColor = Color.WHITE; edgeType = CaptionStyleCompat.EDGE_TYPE_RAISED; edgeColor = Color.BLACK
            backgroundColor = Color.BLACK; backgroundOpacity = 50
        }
        val style = preferences.style(base)
        assertEquals(Color.WHITE, style.foregroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_RAISED, style.edgeType)
        assertEquals(Color.BLACK, style.edgeColor)
        assertEquals(127, Color.alpha(style.backgroundColor))
        assertEquals(Color.TRANSPARENT, style.windowColor)
        assertFalse(preferences.transparentBackground)
    }

    @Test fun `presets set the whole look and the default preset restores the app defaults`() {
        val preferences = SubtitlePreferences(context)
        preferences.applyPreset("youtube")
        assertEquals("roboto", preferences.font); assertEquals(75, preferences.backgroundOpacity)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, preferences.style(base).edgeType)
        preferences.applyPreset("netflix")
        assertEquals("inter", preferences.font); assertEquals(0, preferences.backgroundOpacity)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, preferences.style(base).edgeType)
        preferences.applyPreset("default")
        assertEquals("lato", preferences.font); assertEquals(Color.WHITE, preferences.textColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, preferences.edgeType)
        assertTrue(preferences.transparentBackground); assertTrue(preferences.boldText); assertEquals(100, preferences.sizePercent)
        assertEquals(Color.BLACK, preferences.style(base).edgeColor)
        // "Device default" choices are kept as such, not turned back into the app's defaults.
        preferences.textColor = null; preferences.edgeType = -1
        assertNull(SubtitlePreferences(context).textColor); assertEquals(-1, SubtitlePreferences(context).edgeType)
        assertEquals(Color.YELLOW, preferences.style(base).foregroundColor)
    }

    @Test fun `every bundled and built-in font loads`() {
        SubtitlePreferences.FONTS.forEach { font ->
            assertNotNull(font.id, SubtitlePreferences.typeface(context, font.id, bold = false))
            assertNotNull(font.id, SubtitlePreferences.typeface(context, font.id, bold = true))
        }
        listOf("arimo", "inter", "open-sans", "noto-sans", "lato").forEach { id ->
            listOf("regular", "bold").forEach { weight -> context.assets.open("subtitle-fonts/$id-$weight.ttf").close() }
        }
    }
}
