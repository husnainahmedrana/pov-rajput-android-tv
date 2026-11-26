package dev.pov.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks

internal enum class VideoCompatibilityReason { EXCEEDS_CAPABILITIES, UNSUPPORTED, NO_VIDEO, NO_SELECTED_VIDEO }

/** Display only fixed codec names and numeric dimensions, never file labels or raw codec strings. */
internal data class VideoCompatibilityIssue(
    val reason: VideoCompatibilityReason,
    val codec: String? = null,
    val width: Int = Format.NO_VALUE,
    val height: Int = Format.NO_VALUE,
    val dolbyProfile: Int? = null,
) {
    val message: String get() {
        val device = if (DeviceLayout.tv) "TV" else "device"
        val format = listOfNotNull(codec,
            if (width > 0 && height > 0) "${width}×${height}" else null).joinToString(" at ")
        val explanation = when (reason) {
            VideoCompatibilityReason.EXCEEDS_CAPABILITIES ->
                L10n.tr(R.string.t_the_video_format_x_exceeds_this, "The video format (%1\$s) exceeds this %2\$s's reported decoder capabilities.", format.ifBlank { "unknown" }, device)
            VideoCompatibilityReason.UNSUPPORTED ->
                L10n.tr(R.string.t_this_x_cannot_decode_the_video, "This %1\$s cannot decode the video format (%2\$s).", device, format.ifBlank { "unknown" })
            VideoCompatibilityReason.NO_VIDEO -> L10n.tr(R.string.t_this_copy_has_no_video_track, "This copy has no video track available for playback.")
            VideoCompatibilityReason.NO_SELECTED_VIDEO -> L10n.tr(R.string.t_this_copy_has_no_video_track_2, "This copy has no video track selected for playback.")
        }
        val detail = if (dolbyProfile == 5) L10n.tr(R.string.t_dolby_vision_profile_5_has_no, " Dolby Vision Profile 5 has no HDR10 base picture; this copy requires compatible Dolby Vision decoding.") else ""
        return L10n.tr(R.string.t_x_x_playback_was_paused_to, "%1\$s%2\$s Playback was paused to avoid playing only audio. Choose a compatible copy, such as 1080p H.264 (AVC), or another format supported by this %3\$s.", explanation, detail, device)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class VideoCompatibilityException(val issue: VideoCompatibilityIssue) : PlaybackException(
    issue.message, null,
    if (issue.reason == VideoCompatibilityReason.EXCEEDS_CAPABILITIES)
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES else PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

/** Uses renderer capability reports, with no assumptions about TV manufacturer or video title. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object VideoPlaybackCompatibility {
    private data class Candidate(val format: Format, val support: Int, val selected: Boolean)

    /**
     * [strict] (the TV) stops on a track the decoder says exceeds its capabilities: the TCL kills the app on those.
     * Phone decoders report "exceeds" for many files they play well (4K, high levels), so there only a format with no
     * decoder at all stops playback; a real decoder failure still ends in the player's error.
     */
    fun issue(tracks: Tracks, playbackState: Int, actualVideoFormat: Format? = null, strict: Boolean = DeviceLayout.tv): VideoCompatibilityIssue? {
        fun playable(candidate: Candidate) = candidate.support == C.FORMAT_HANDLED ||
            (candidate.support == C.FORMAT_EXCEEDS_CAPABILITIES && (!strict || hardwareFits(candidate.format)))
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return null
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).map { index ->
                Candidate(group.getTrackFormat(index), group.getTrackSupport(index), group.isTrackSelected(index))
            }
        }
        if (video.isEmpty()) {
            // Demuxers may report empty/audio-only tracks before the complete media is ready.
            return if (playbackState == Player.STATE_READY) VideoCompatibilityIssue(VideoCompatibilityReason.NO_VIDEO) else null
        }
        val selected = video.filter { it.selected }
        // An adaptive selection can contain several representations. Only an actual matching
        // unsupported format proves that its handled alternatives aren't currently being used.
        val actual = actualVideoFormat?.let { format -> selected.firstOrNull { it.format == format } }
        if (actual != null && !playable(actual)) return failure(actual)
        if (selected.any { playable(it) }) return null
        if (selected.isNotEmpty()) return failure(selected.first())
        if (video.none { playable(it) }) {
            return failure(video.firstOrNull { it.support == C.FORMAT_EXCEEDS_CAPABILITIES } ?: video.first())
        }
        return if (playbackState == Player.STATE_READY) VideoCompatibilityIssue(VideoCompatibilityReason.NO_SELECTED_VIDEO) else null
    }

    private val fits = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * The device's own hardware decoder for this codec takes this size at this frame rate (24–30 fps when the file
     * doesn't say), although the track's profile/level label is above what the decoder advertises: e.g. a 4K H.264 film
     * labelled level 5.2 on a decoder listed up to 5.1 but able to decode 3840×2160 at 30 fps. The label is a ceiling,
     * not the film's real demand, so such a track is tried instead of refused. Dolby Vision keeps the strict check.
     */
    private fun hardwareFits(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.VIDEO_DOLBY_VISION || format.width <= 0 || format.height <= 0) return false
        val fps = if (format.frameRate > 0) format.frameRate.toDouble() else 30.0
        return fits.getOrPut("$mime ${format.width}x${format.height}@$fps") {
            runCatching {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && (android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated) &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        info.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(format.width, format.height, fps) == true
                }
            }.getOrDefault(false)
        }
    }

    private fun failure(candidate: Candidate): VideoCompatibilityIssue = VideoCompatibilityIssue(
        if (candidate.support == C.FORMAT_EXCEEDS_CAPABILITIES) VideoCompatibilityReason.EXCEEDS_CAPABILITIES
        else VideoCompatibilityReason.UNSUPPORTED,
        codecLabel(candidate.format.sampleMimeType), candidate.format.width, candidate.format.height,
        DolbyVisionCompatibility.profile(candidate.format),
    )

    private fun codecLabel(mime: String?): String = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264 (AVC)"
        MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        else -> L10n.tr(R.string.t_video_format, "video format")
    }
}
