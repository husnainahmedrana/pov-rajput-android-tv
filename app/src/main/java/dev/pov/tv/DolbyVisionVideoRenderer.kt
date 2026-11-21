package dev.pov.tv

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.util.concurrent.ConcurrentHashMap

/** Capability reporting and decoder input use the same verified base format. Native DV is untouched. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class DolbyVisionVideoRenderer(builder: Builder, private val appContext: Context, private val mode: DolbyVisionMode) : MediaCodecVideoRenderer(builder) {
    private val formats = ConcurrentHashMap<Format, Format>()
    private var baseLayerActive = false
    /** Profile 7 played as Profile 8.1: each access unit's RPU is converted and its enhancement layer dropped. */
    private var profile81Active = false

    private fun compatible(format: Format, selector: MediaCodecSelector): Format =
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION || mode == DolbyVisionMode.STANDARD) format else formats.getOrPut(format) {
        val hdr = (appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        val transfers = buildSet {
            if (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdr) add(C.COLOR_TRANSFER_ST2084)
            if (Display.HdrCapabilities.HDR_TYPE_HLG in hdr) add(C.COLOR_TRANSFER_HLG)
            // Most phone screens report no HDR at all. Their decoders still play the HDR10/HLG picture inside a
            // Profile 7/8 file (Android tone-maps it for the screen), which beats refusing the link outright.
            if (DeviceLayout.phone && isEmpty()) { add(C.COLOR_TRANSFER_ST2084); add(C.COLOR_TRANSFER_HLG) }
        }
        val dvScreen = mode == DolbyVisionMode.AUTO && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdr
        fun decodes(candidate: Format) = try { selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).any { it.isFormatSupported(appContext, candidate) } }
            catch (_: Exception) { null }
        val native = if (dvScreen) decodes(format) ?: true else false   // A failed query must not force a different interpretation.
        // Profile 7 on a DV screen whose decoder takes Profile 8 only (Amlogic boxes): play it as Profile 8.1 Dolby
        // Vision (RPU converted, enhancement layer dropped) instead of falling back to the HDR10 base layer.
        val converted = if (dvScreen && !native && DolbyVisionCompatibility.profile(format) == 7)
            DolbyVisionCompatibility.profile81Format(format)?.takeIf { decodes(it) == true } else null
        converted ?: DolbyVisionCompatibility.baseFormat(format, mode, native, transfers) ?: format
    }

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, compatible(format, mediaCodecSelector))

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val original = checkNotNull(formatHolder.format)
        val mapped = compatible(original, MediaCodecSelector.DEFAULT)
        // Equal Format values can be represented by different objects between capability queries
        // and stream delivery. Object identity must never enable HEVC filtering for another codec.
        baseLayerActive = original.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && mapped.sampleMimeType == MimeTypes.VIDEO_H265
        profile81Active = mapped.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && DolbyVisionCompatibility.profile(original) == 7 &&
            DolbyVisionCompatibility.profile(mapped) == 8
        if (profile81Active) android.util.Log.i("PovPlayer", "Dolby Vision Profile 7 plays as Profile 8.1 (${original.codecs} -> ${mapped.codecs})")
        return if (baseLayerActive || profile81Active) super.onInputFormatChanged(FormatHolder().apply {
            format = mapped; drmSession = formatHolder.drmSession
        }) else super.onInputFormatChanged(formatHolder)
    }

    override fun shouldDiscardDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (super.shouldDiscardDecoderInputBuffer(buffer)) return true
        if (!baseLayerActive && !profile81Active) return false
        try {
            check(!buffer.isEncrypted) { "Encrypted Dolby Vision cannot use base-layer processing" }
            val data = buffer.data ?: return false
            val written = data.position()
            val read = data.duplicate().apply { position(0); limit(written) }
            val size = if (profile81Active) DolbyVisionCompatibility.convertToProfile81(read) else DolbyVisionCompatibility.retainBaseLayer(read)
            data.position(size)
            if (size == 0) buffer.clear()
            return size == 0
        } catch (error: RuntimeException) {
            throw createRendererException(error, codecInputFormat, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        }
    }
}
