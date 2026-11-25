package dev.pov.tv

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The player's one-time work before its first picture, done in the background while the viewer is choosing a link:
 * the decoder lists for the usual video and audio formats (Media3 asks Android for each the first time, 100–500 ms
 * on a TV box), the device's video limits, and loading the FFmpeg audio library. Once per app run; harmless if a
 * video starts before it ends (Media3 caches these lists itself and the work is shared).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlayerPrewarm {
    private val done = AtomicBoolean()
    private val MIMES = listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9,
        MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_OPUS)

    fun run() {
        if (!done.compareAndSet(false, true)) return
        Thread({
            for (mime in MIMES) try { MediaCodecUtil.getDecoderInfos(mime, false, false) } catch (_: Throwable) {}
            try { DecoderLimits.maxHeight(); DeviceVideoSupport.caps() } catch (_: Throwable) {}
            try { androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() } catch (_: Throwable) {}
        }, "player-prewarm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
