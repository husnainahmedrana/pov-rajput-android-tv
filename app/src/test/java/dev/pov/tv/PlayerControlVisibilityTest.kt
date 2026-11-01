package dev.pov.tv

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerControlVisibilityTest {
    private val chromeIds = listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_play_pause,
        androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd, R.id.pov_audio,
        R.id.pov_speed, R.id.pov_browse, R.id.pov_stop)

    @Test fun `focused audio action does not pin timeline loading indicator or other controls after timeout`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 960, 540)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.hide()
            controller.showTimeoutMs = 1_000
            controller.show()
            view.findViewById<View>(R.id.pov_audio).requestFocus()
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            assertTrue(view.findViewById<View>(R.id.pov_audio).hasFocus())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001))
            assertEquals(View.GONE, controller.visibility)
            chromeIds.forEach { assertFalse("Chrome leaked outside controller: $it", rendered(view.findViewById(it))) }
            assertFalse(rendered(view.findViewById(androidx.media3.ui.R.id.exo_buffering)))
            controller.show()
            assertTrue(controller.isFullyVisible)
            assertTrue(rendered(view.findViewById(androidx.media3.ui.R.id.exo_progress)))
            assertTrue(rendered(view.findViewById(R.id.pov_audio)))
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    @Test fun `all feature actions and loading indicator are physically within one bottom controller`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.show()
            view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 960, 540)
            val scrim = view.findViewById<View>(R.id.pov_bottom_scrim)
            val scrimBounds = Rect()
            assertTrue(scrim.getGlobalVisibleRect(scrimBounds))
            assertTrue("Controls should occupy only the lower part of the video", scrimBounds.top > 270)
            assertEquals("Playback strip should remain compact", 124, scrimBounds.height())
            assertEquals(16f, view.findViewById<android.widget.TextView>(R.id.pov_playback_title).textSize, 0.01f)
            for (id in chromeIds) {
                val child = view.findViewById<View>(id)
                val bounds = Rect()
                assertTrue("Missing visible bounds: $id", child.getGlobalVisibleRect(bounds))
                assertTrue("Control outside bottom scrim: $id", scrimBounds.contains(bounds))
                var parent = child.parent
                while (parent is View && parent !== controller) parent = parent.parent
                assertSame(controller, parent)
            }
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    @Test fun `paused indefinite controls still disappear as one root when explicitly hidden`() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        val view = LayoutInflater.from(activity).inflate(R.layout.pov_player, null, false) as PlayerView
        activity.setContentView(view)
        val binding = PlayerControlVisibility.bind(view)
        try {
            val controller = view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
            controller.hide(); controller.showTimeoutMs = 0; controller.show()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertTrue(controller.isFullyVisible)
            controller.hide()
            assertEquals(View.GONE, controller.visibility)
            chromeIds.forEach { assertFalse(view.findViewById<View>(it).isShown) }
        } finally { binding.release(); owner.pause().stop().destroy() }
    }

    private fun rendered(view: View): Boolean {
        val bounds = Rect()
        return view.isShown && view.alpha > 0 && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }
}

