package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class VideoPlaybackCompatibilityTest {
    private val avc = Format.Builder().setSampleMimeType("video/avc").setWidth(1920).setHeight(1080).build()
    private val hevc = Format.Builder().setSampleMimeType("video/hevc").setWidth(7680).setHeight(4320)
        .setCodecs("hvc1.2.4.L180.90").build()
    private val audio = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
    private fun group(vararg formats: Format, support: IntArray = IntArray(formats.size) { C.FORMAT_HANDLED },
        selected: BooleanArray = BooleanArray(formats.size) { true }, adaptive: Boolean = false) =
        Tracks.Group(TrackGroup(*formats), adaptive, support, selected)
    private fun issue(vararg groups: Tracks.Group, state: Int = Player.STATE_READY, actual: Format? = null) =
        VideoPlaybackCompatibility.issue(Tracks(groups.toList()), state, actual)

    @Test fun `initial empty tracks are not rejected during idle or loading`() {
        assertNull(VideoPlaybackCompatibility.issue(Tracks.EMPTY, Player.STATE_IDLE))
        assertNull(VideoPlaybackCompatibility.issue(Tracks.EMPTY, Player.STATE_BUFFERING))
    }

    @Test fun `early nonvideo groups do not fail before demuxing is ready`() {
        assertNull(issue(group(audio), state = Player.STATE_BUFFERING))
    }

    @Test fun `ready audio only media gives a useful no video error`() {
        val result = requireNotNull(issue(group(audio)))
        assertEquals(VideoCompatibilityReason.NO_VIDEO, result.reason)
        assertTrue(result.message.contains("no video track"))
        assertTrue(result.message.contains("1080p H.264"))
    }

    @Test fun `ready empty tracks cannot silently be treated as a video`() {
        assertEquals(VideoCompatibilityReason.NO_VIDEO,
            VideoPlaybackCompatibility.issue(Tracks.EMPTY, Player.STATE_READY)?.reason)
    }

    @Test fun `selected exceeds capability HEVC fails with safe resolution and error code`() {
        val result = requireNotNull(issue(group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES))))
        assertEquals(VideoCompatibilityReason.EXCEEDS_CAPABILITIES, result.reason)
        assertTrue(result.message.contains("HEVC (H.265)"))
        assertTrue(result.message.contains("7680×4320"))
        assertFalse(result.message.contains("hvc1.2.4.L180.90"))
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            VideoCompatibilityException(result).errorCode)
    }

    @Test fun `unsupported video is blocked while buffering before audio can continue alone`() {
        val result = issue(group(hevc, support = intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
            selected = booleanArrayOf(false)), state = Player.STATE_BUFFERING)
        assertEquals(VideoCompatibilityReason.UNSUPPORTED, result?.reason)
    }

    @Test fun `handled selected video survives an unavailable alternative group`() {
        assertNull(issue(group(avc), group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES),
            selected = booleanArrayOf(false))))
    }

    @Test fun `adaptive selection containing a handled representation remains allowed`() {
        assertNull(issue(group(avc, hevc, support = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES),
            adaptive = true)))
    }

    @Test fun `actual handled adaptive representation stays allowed`() {
        assertNull(issue(group(avc, hevc, support = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES),
            adaptive = true), actual = avc))
    }

    @Test fun `actual unsupported adaptive representation is not hidden by its handled alternative`() {
        assertEquals(VideoCompatibilityReason.EXCEEDS_CAPABILITIES,
            issue(group(avc, hevc, support = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_EXCEEDS_CAPABILITIES),
                adaptive = true), actual = hevc)?.reason)
    }

    @Test fun `selected unsupported video is not approved by an unselected handled group`() {
        assertEquals(VideoCompatibilityReason.EXCEEDS_CAPABILITIES,
            issue(group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES)),
                group(avc, selected = booleanArrayOf(false)))?.reason)
    }

    @Test fun `no selection waits during loading but is actionable when ready`() {
        val video = group(avc, selected = booleanArrayOf(false))
        assertNull(issue(video, state = Player.STATE_BUFFERING))
        assertEquals(VideoCompatibilityReason.NO_SELECTED_VIDEO, issue(video)?.reason)
    }

    @Test fun `reported DRM or type failures use unsupported error without raw labels`() {
        val privateMetadata = hevc.buildUpon().setLabel("never display this file label")
            .setCodecs("never display this raw codec metadata").setId("never display this source ID").build()
        for (support in listOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_UNSUPPORTED_DRM)) {
            val result = requireNotNull(issue(group(privateMetadata, support = intArrayOf(support))))
            val error = VideoCompatibilityException(result)
            assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, error.errorCode)
            assertFalse(result.message.contains("never display"))
            assertNull(error.cause)
        }
    }

    @Test fun `unselected exceeds only candidates give the resolution of the constrained format`() {
        val result = requireNotNull(issue(
            group(avc, support = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE), selected = booleanArrayOf(false)),
            group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES), selected = booleanArrayOf(false))))
        assertEquals(VideoCompatibilityReason.EXCEEDS_CAPABILITIES, result.reason)
        assertEquals(7680, result.width)
    }

    @Test fun `ended and idle stale track metadata do not open new failures`() {
        val unsupported = group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES))
        assertNull(issue(unsupported, state = Player.STATE_ENDED))
        assertNull(issue(unsupported, state = Player.STATE_IDLE))
    }

    @Test fun `unavailable audio or subtitles do not block handled video`() {
        val subtitles = Format.Builder().setSampleMimeType("application/pgs").build()
        assertNull(issue(group(avc), group(audio, support = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE)),
            group(subtitles, support = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE))))
    }

    @Test fun `codec and resolution never act as hardcoded compatibility limits`() {
        for (mime in listOf("video/hevc", "video/avc", "video/av01", "video/dolby-vision")) {
            for ((width, height) in listOf(1920 to 1080, 3840 to 2160, 7680 to 4320)) {
                val supported = Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).build()
                assertNull("$mime $width x $height", issue(group(supported), actual = supported))
            }
        }
    }

    @Test fun `unknown MIME metadata and invalid dimensions cannot leak into messages`() {
        val unknown = Format.Builder().setSampleMimeType("video/x-private-format-name")
            .setWidth(Format.NO_VALUE).setHeight(Format.NO_VALUE).build()
        val result = requireNotNull(issue(group(unknown, support = intArrayOf(C.FORMAT_UNSUPPORTED_TYPE))))
        assertFalse(result.message.contains("x-private-format-name"))
        assertFalse(result.message.contains("-1"))
        assertTrue(result.message.contains("video format"))
    }

    @Test fun `phones try a track the decoder says exceeds its capabilities, but never one it cannot decode`() {
        val exceeds = group(hevc, support = intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES))
        assertNull(VideoPlaybackCompatibility.issue(Tracks(listOf(exceeds)), Player.STATE_READY, strict = false))
        assertEquals(VideoCompatibilityReason.EXCEEDS_CAPABILITIES,
            VideoPlaybackCompatibility.issue(Tracks(listOf(exceeds)), Player.STATE_READY, strict = true)?.reason)
        val unsupported = group(hevc, support = intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE))
        assertEquals(VideoCompatibilityReason.UNSUPPORTED,
            VideoPlaybackCompatibility.issue(Tracks(listOf(unsupported)), Player.STATE_READY, strict = false)?.reason)
    }
}
