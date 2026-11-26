package dev.pov.tv

import android.os.SystemClock
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Waits for a bigger cushion after a stall, and a bigger one again when stalls repeat: 10 s of video after the first,
 * 20 s after a second within five minutes, then 30 s. After five calm minutes it is back to Media3's own 5 s.
 *
 * A link whose download only just keeps up with the film's bitrate otherwise resumes with a few seconds in hand, drains
 * them at the next busy scene and stalls again every few seconds. A good connection never stalls, so never waits.
 *
 * Every LoadControl method is forwarded by hand: Kotlin's `by` delegation skips Java default methods, and Media3's
 * defaults throw ("getBackBufferDurationUs not implemented" crashed the player on 6685).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class RebufferGuard(
    private val delegate: LoadControl,
    /**
     * Starting (and after a seek): play once this long has been spent waiting, with at least [QUICK_MIN_US] buffered,
     * even if the delegate's bigger start buffer isn't full yet. A light file fills it quickly anyway; a heavy one (4K on
     * a slow line, about 15 s for 5.5 s of video) no longer waits for all of it. Null: the delegate alone decides.
     */
    private val quickStartMs: Long? = null,
) : LoadControl {
    private val stalls = ArrayDeque<Long>()
    private var wasRebuffering = false
    private var waitingSince = 0L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!parameters.rebuffering) {
            wasRebuffering = false
            if (delegate.shouldStartPlayback(parameters)) { waitingSince = 0L; return true }
            val quick = quickStartMs ?: return false
            val now = SystemClock.elapsedRealtime()
            if (waitingSince == 0L) waitingSince = now
            if (now - waitingSince >= quick && parameters.bufferedDurationUs >= QUICK_MIN_US) { waitingSince = 0L; return true }
            return false
        }
        waitingSince = 0L
        val now = SystemClock.elapsedRealtime()
        if (!wasRebuffering) {
            wasRebuffering = true
            stalls.addLast(now)
            while (stalls.isNotEmpty() && now - stalls.first() > CALM_MS) stalls.removeFirst()
        }
        val needUs = (cushionMs(stalls.size) * 1000L / parameters.playbackSpeed.coerceAtLeast(0.1f)).toLong()
        // The buffer can't grow any more (the delegate's byte limit): play with what there is.
        val full = !delegate.shouldContinueLoading(parameters) && parameters.bufferedDurationUs >= FULL_BUFFER_FLOOR_US
        val ready = (parameters.bufferedDurationUs >= needUs || full) && (delegate.shouldStartPlayback(parameters) || full)
        if (ready) wasRebuffering = false
        return ready
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    override fun onPrepared(playerId: PlayerId) { waitingSince = 0L; delegate.onPrepared(playerId) }
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onTracksSelected(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, renderers: Array<out Renderer>,
                                  trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) { waitingSince = 0L; delegate.onStopped(playerId) }
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    companion object {
        private const val CALM_MS = 5 * 60_000L
        private const val FULL_BUFFER_FLOOR_US = 2_000_000L
        /** Buffered video that a quick start still needs (the player's usual start amount). */
        private const val QUICK_MIN_US = 2_500_000L
        /** Usenet: waiting this long for the full start buffer is enough (see quickStartMs). */
        const val USENET_QUICK_START_MS = 3_500L

        /** The cushion to rebuild before playing again after [recentStalls] stalls in the last five minutes. */
        fun cushionMs(recentStalls: Int): Long = when {
            recentStalls <= 0 -> 5_000
            recentStalls == 1 -> 10_000
            recentStalls == 2 -> 20_000
            else -> 30_000
        }
    }
}
