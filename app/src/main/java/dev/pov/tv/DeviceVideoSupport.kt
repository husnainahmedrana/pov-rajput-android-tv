package dev.pov.tv

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import dev.pov.tv.data.SourceItem

/**
 * What this device's decoders can really play, used to rank links: ones it likely can't play go last, are marked,
 * and are never auto-played. Phones differ widely (a 1080p-only chip, no 10-bit HEVC, no AV1, no Dolby Vision), and
 * Android always lists Google's software decoders, which claim 4K but can't keep up, so they count only to 1080p.
 * Unknown capabilities and unlabelled links are never marked.
 */
internal object DeviceVideoSupport {
    internal data class Caps(
        /** Tallest 16:9 picture per codec ("hevc", "avc", "av1", "vp9"), from hardware decoders, else software up to 1080p. */
        val maxHeight: Map<String, Int>,
        /** A hardware HEVC decoder with the Main 10 profile (HDR and 10-bit releases). */
        val hevc10: Boolean,
        val dolbyVision: Boolean,
    )

    @Volatile private var cached: Caps? = null
    fun caps(): Caps = cached ?: compute().also { cached = it }

    private const val SOFTWARE_LIMIT = 1080
    private val HEIGHTS = listOf(4320, 2160, 1440, 1080, 720, 480)
    private val TYPES = mapOf("video/hevc" to "hevc", "video/avc" to "avc", "video/av01" to "av1", "video/x-vnd.on2.vp9" to "vp9")

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
        else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)

    private fun compute(): Caps {
        val decoders = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } } catch (_: Exception) { emptyList() }
        val heights = mutableMapOf<String, Int>()
        var hevc10 = false
        var dolbyVision = false
        decoders.forEach { info ->
            info.supportedTypes.forEach { type ->
                val mime = type.lowercase()
                if (mime == "video/dolby-vision") { dolbyVision = true; return@forEach }
                val codec = TYPES[mime] ?: return@forEach
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
                val video = caps.videoCapabilities ?: return@forEach
                val hw = hardware(info)
                val tallest = HEIGHTS.firstOrNull { h -> runCatching { video.isSizeSupported(h * 16 / 9, h) }.getOrDefault(false) } ?: return@forEach
                val usable = if (hw) tallest else minOf(tallest, SOFTWARE_LIMIT)
                heights[codec] = maxOf(heights[codec] ?: 0, usable)
                if (hw && codec == "hevc" && caps.profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }) hevc10 = true
            }
        }
        return Caps(heights, hevc10, dolbyVision)
    }

    /** "TV" or "DEVICE" for the short capital notes in the link list. */
    val noun: String get() = if (DeviceLayout.tv) L10n.tr(R.string.t_noun_tv_caps, "TV") else L10n.tr(R.string.t_noun_device_caps, "DEVICE")

    /** The note [problem] gives a Dolby Vision-only link this device can't show ("Hide Dolby Vision-only" compares against it). */
    fun dolbyVisionOnlyNote(): String = L10n.tr(R.string.t_dolby_vision_only_not_supported_on, "DOLBY VISION ONLY · NOT SUPPORTED ON THIS %1\$s", noun)

    /** Why [source] likely won't play here, as a short capital note for the link list, or null when it should. */
    fun problem(source: SourceItem, caps: Caps = caps()): String? = problem("${source.title} ${source.detail}".take(4000), source.qualityHeight, caps)

    private val patterns = HashMap<String, Regex>()

    internal fun problem(name: String, height: Int?, caps: Caps): String? {
        if (caps.maxHeight.isEmpty()) return null
        // Compiled once: building these for every link froze the TV on long link lists.
        fun has(pattern: String) = synchronized(patterns) { patterns.getOrPut(pattern) { Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z0-9])") } }.containsMatchIn(name)
        val dv = has("DV|DOVI|DOLBY[ ._-]*VISION")
        val hdrBase = has("HDR10\\+|HDR10PLUS|HDR10|HDR|HLG")
        // Dolby Vision with no HDR10/HLG picture underneath (Profile 5, common on web releases) needs a real DV decoder.
        if (dv && !hdrBase && !caps.dolbyVision) return dolbyVisionOnlyNote()
        val codec = when {
            has("AV1") -> "av1"
            has("HEVC|[HX][ ._-]?265") -> "hevc"
            has("AVC|[HX][ ._-]?264") -> "avc"
            has("VP9") -> "vp9"
            // Unlabelled 4K and HDR releases are nearly always HEVC; the rest are usually H.264.
            (height ?: 0) >= 2160 || dv || hdrBase -> "hevc"
            else -> "avc"
        }
        val label = mapOf("hevc" to "HEVC", "avc" to "H.264", "av1" to "AV1", "vp9" to "VP9").getValue(codec)
        val limit = caps.maxHeight[codec] ?: return L10n.tr(R.string.t_x_not_supported_on_this_x, "%1\$s · NOT SUPPORTED ON THIS %2\$s", label, noun)
        if ((height ?: 0) > limit) return L10n.tr(R.string.t_above_this_x_s_x_x, "ABOVE THIS %1\$s'S %2\$s %3\$s LIMIT", noun, DecoderLimits.label(limit), label)
        // 10-bit HEVC (all HDR) without a hardware Main 10 decoder falls back to software: fine at 1080p, not above.
        if (codec == "hevc" && !caps.hevc10 && (dv || hdrBase || has("10[ ._-]?BITS?")) && (height ?: 0) > SOFTWARE_LIMIT)
            return L10n.tr(R.string.t_10_bit_hevc_above_1080p_not, "10-BIT HEVC ABOVE 1080p · NOT SUPPORTED ON THIS %1\$s", noun)
        return null
    }
}
