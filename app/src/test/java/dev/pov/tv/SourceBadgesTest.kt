package dev.pov.tv

import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.pov.tv.data.SourceItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SourceBadgesTest {
    @Test fun `explicit release tags become normalized badges without guessing absent capabilities`() {
        val source = SourceItem("Movie.2160p.Dolby.Vision.HDR10Plus.WEB-DL.x265.mkv", "fixture", sizeBytes = 2500000000, qualityHeight = 2160)
        assertEquals(listOf("4K", "2.50 GB", "DV · HDR10+", "WEB-DL · HEVC"), SourceBadges.from(source).map { it.label })
        val plain = SourceBadges.from(SourceItem("Movie.mkv", "fixture"))
        assertEquals(listOf("Quality unavailable", "Size unavailable"), plain.map { it.label })
    }
    @Test fun `tag boundaries avoid DVD title fragments and duplicate HDR labels`() {
        assertEquals(listOf("Quality unavailable", "Size unavailable"), SourceBadges.from(SourceItem("DVD.DVD-Rip.Webdling.AV123.HDR100.mkv", "fixture")).map { it.label })
        val labels = SourceBadges.from(SourceItem("Movie.DV.HDR10+.HDR.SDR.BDRemux.H265.mkv", "fixture")).map { it.label }
        assertEquals(listOf("Quality unavailable", "Size unavailable", "DV · HDR10+", "BLU-RAY · REMUX · HEVC"), labels)
    }
    @Test fun `quality colors match requested resolution groups`() {
        for ((height, tone) in listOf(2160 to SourceBadgeTone.UHD, 1080 to SourceBadgeTone.FULL_HD, 720 to SourceBadgeTone.HD)) {
            assertEquals(tone, SourceBadges.from(SourceItem("Movie", "fixture", qualityHeight = height)).first().tone)
        }
    }
    @Test fun `rounded badges wrap without clipping or taking remote focus across themes`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        // This test checks the Aurora gradients; Color by type is the default.
        BadgeStyle.save(activity, BadgeStyle.AURORA)
        // ...and the roomy cards (the compact list is the default).
        LinkListPreferences.setCompact(activity, false)
        try {
            for (theme in ThemeSpec.entries) {
                val source = SourceItem("Example.Movie.2160p.DV.HDR10Plus.BluRay.REMUX.HEVC.mkv", "fixture", sizeBytes = 2500000000, qualityHeight = 2160)
                val row = TvChoiceRow(activity, "fixture", source.title, "Full filename remains available", "CACHED · Public fixture", theme.palette, SourceBadges.from(source)) {}
                val root = FrameLayout(activity)
                root.addView(row, FrameLayout.LayoutParams(280, -2)); activity.setContentView(root)
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 280, 500)
                val strip = descendants(row).filterIsInstance<SourceBadgeStrip>().single()
                val chips = descendants(strip).filterIsInstance<TextView>()
                assertTrue("Narrow rows must wrap", chips.map { it.top }.distinct().size > 1)
                chips.forEach { chip ->
                    assertFalse(chip.isFocusable); assertFalse(chip.isClickable)
                    assertTrue(chip.typeface.isBold); assertTrue(chip.textSize >= 13)
                    assertTrue(chip.left >= 0 && chip.right <= strip.width)
                    assertTrue(chip.top >= 0 && chip.bottom <= strip.height)
                }
                assertTrue(strip.bottom <= row.height)
                val quality = chips.first()
                // Quality is a filled gold gradient with dark text, kept when the row is selected.
                assertEquals(Color.parseColor("#10131A"), quality.currentTextColor)
                row.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Selection must retain resolution color", Color.parseColor("#10131A"), quality.currentTextColor)
                assertEquals(listOf("#FFE34D", "#FF9F1C"), (quality.background as android.graphics.drawable.GradientDrawable).colors!!.map { String.format("#%06X", it and 0xFFFFFF) })
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, row.descendantFocusability)
            }
        } finally { activity.finish() }
    }
    @Test fun `elite badges draw plates, wordmarks and icons without focus and within the row`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        try {
            val source = SourceItem("Movie.2160p.UHD.BluRay.REMUX.DV.HDR10+.HEVC.10bit.TrueHD.7.1.Atmos.mkv", "fixture", sizeBytes = 50_000_000_000L, qualityHeight = 2160)
            val strip = SourceBadgeStrip(activity, SourceBadges.from(source, runtimeMinutes = 120), BadgeStyle.ELITE)
            val root = FrameLayout(activity); root.addView(strip, FrameLayout.LayoutParams(600, -2)); activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 800)
            val tags = (0 until strip.childCount).map { strip.getChildAt(it).tag }
            assertEquals(listOf("source-badge:4K", "source-badge:DV", "source-badge:HDR10+", "source-badge:TrueHD", "source-badge:7.1", "source-badge:Atmos",
                "source-badge:BLU-RAY", "source-badge:REMUX", "source-badge:HEVC", "source-badge:10bit"), tags.filterNotNull())
            // The size and bitrate sit beside the plate as heat-coloured text in its own rounded box.
            val sizeBox = strip.getChildAt(1) as ViewGroup
            assertEquals("50.00 GB · ≈56 Mbps", (sizeBox.getChildAt(0) as TextView).text.toString())
            assertTrue(sizeBox.background is android.graphics.drawable.GradientDrawable)
            descendants(strip).forEach { assertFalse(it.isFocusable) }
            (0 until strip.childCount).map(strip::getChildAt).forEach { assertTrue(it.right <= strip.width && it.bottom <= strip.height) }
        } finally { activity.finish() }
    }
    @Test fun `color by type is the default badge style until another is picked`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pov_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(BadgeStyle.COLOR_BY_TYPE, BadgeStyle.current(context))
        BadgeStyle.save(context, BadgeStyle.SUNSET)
        assertEquals(BadgeStyle.SUNSET, BadgeStyle.current(context))
    }
    @Test fun `compact link cards keep one line for the name and put the source in the badge row`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        try {
            LinkListPreferences.setCompact(activity, true)
            val source = SourceItem("Example.Movie.2160p.DV.HDR10Plus.BluRay.REMUX.HEVC.mkv", "fixture", sizeBytes = 2500000000, qualityHeight = 2160)
            val row = TvChoiceRow(activity, "fixture", source.title, "4KHDHub · R2", "", ThemeSpec.FLIX.palette, SourceBadges.from(source)) {}
            val labels = descendants(row).filterIsInstance<TextView>()
            assertTrue(labels.any { it.tag == "source-origin" && it.text.toString() == "4KHDHub · R2" })
            assertEquals(1, labels.first { it.text.toString() == source.title }.maxLines)
            row.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, labels.first { it.text.toString() == source.title }.maxLines)
            assertEquals(BadgeStyle.COLOR_BY_TYPE, BadgeStyle.entries.first())
        } finally { activity.finish() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
