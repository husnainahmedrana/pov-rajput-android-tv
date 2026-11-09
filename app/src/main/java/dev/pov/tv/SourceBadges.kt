package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.pov.tv.data.AddonBadges
import dev.pov.tv.data.DebridServices
import dev.pov.tv.data.Easynews
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceLanguageOrder
import dev.pov.tv.data.SourceMetadata
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Badge kinds. Quality, size, HDR, audio, languages and source each have their own gradient ("Aurora" set);
 * the cached / not cached status keeps its flat green and grey.
 */
internal enum class SourceBadgeTone { UHD, FULL_HD, HD, OTHER, NEUTRAL, CACHED, UNCACHED, SIZE, HDR, AUDIO, LANGUAGE, SOURCE }
/** [mbps] (size badges only) picks the heat step of the size badge's gradient; [parts] are the grouped tags (Elite draws each). */
internal data class SourceBadge(val label: String, val tone: SourceBadgeTone = SourceBadgeTone.NEUTRAL, val quality: Boolean = false,
                                val mbps: Double? = null, val parts: List<String> = listOf(label))

/** How link badges look, chosen in Settings → Sources (Color by type by default). Kept in plain preferences: it's a look, not a secret. */
internal enum class BadgeStyle(val id: String, val label: String, val description: String) {
    COLOR_BY_TYPE("color", "Color by type", "One flat colour per kind of tag, as a thin outline: quality, size, HDR, audio, languages, source."),
    AURORA("aurora", "Aurora", "Gradient chips in cool aurora colours; the size badge heats up with the bitrate."),
    SUNSET("sunset", "Sunset", "Gradient chips in warm sunset colours; the size badge heats up with the bitrate."),
    ELITE("elite", "Elite", "Logo-style badges: metal resolution plates, wordmarks and icons.");
    companion object {
        const val KEY = "badge_style"
        private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("pov_ui", Context.MODE_PRIVATE)
        // Color by type until the viewer picks another style.
        fun current(context: Context): BadgeStyle = preferences(context).getString(KEY, null).let { id -> entries.firstOrNull { it.id == id } } ?: COLOR_BY_TYPE
        fun save(context: Context, style: BadgeStyle) = preferences(context).edit().putString(KEY, style.id).apply()
    }
}

/**
 * Compact link list (on by default): one-line names (the focused link shows two), the source at the end of the
 * badge row, smaller badges and tighter spacing, so about twice as many links fit on the TV.
 */
internal object LinkListPreferences {
    const val KEY = "compact_links"
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("pov_ui", Context.MODE_PRIVATE)
    fun compact(context: Context) = preferences(context).getBoolean(KEY, true)
    fun setCompact(context: Context, on: Boolean) = preferences(context).edit().putBoolean(KEY, on).apply()
}

/** Release-name tags are provider claims, not a probe of the selected video's capabilities. */
internal object SourceBadges {
    /** The release name of a link: providers often add lines of seeders, sizes and emoji around it; the longest line is the name. */
    fun name(source: SourceItem): String {
        val name = source.title.lines().map(String::trim).filter(String::isNotBlank).maxByOrNull { it.length } ?: source.title
        return when (PlaybackDebrid.addon(source)) {
            // CNCVerse file names carry the server in front and the size behind: "V-Cloud[FSL Server] Movie.2026.mkv[8.8 GB]".
            PlaybackDebrid.CNCVERSE -> name.replace(CNC_SERVER, "").replace(CNC_SIZE, "").trim().ifBlank { name }
            // PenguPlay adds machine text after "|^|", and some of its file names are only that text
            // ("4KWEB-DLMKV~24.3Mbps….pad-MoviesDrives"): then its description names the title and the format.
            PlaybackDebrid.PENGUPLAY -> name.substringBefore("|^|").trim().takeUnless { it.isBlank() || ".pad-" in it }
                ?: penguName(source) ?: name
            else -> name
        }
    }
    private val CNC_SERVER = Regex("^[^\\s\\[\\]]+\\[[^\\]]*]\\s+")
    private val CNC_SIZE = Regex("\\s*\\[[\\d.,]+\\s*[KMGT]i?B]\\s*$", RegexOption.IGNORE_CASE)
    // "Source: 4KHDHub · R2" runs until the next line, which in the joined detail starts with an emoji (" · 💾 …").
    private val SOURCE_LINE = Regex("Source:\\s*(.+?)(?=\\s·\\s[^\\p{L}\\p{N}\\s]|\\n|$)")
    private val TITLE_LINE = Regex("🍿\\s*([^·\\n]+)")
    private val FORMAT_LINE = Regex("🎞️?\\s*(.+?)(?=\\s·\\s[^\\p{L}\\p{N}\\s]|\\n|$)")
    private fun penguName(source: SourceItem): String? {
        val title = TITLE_LINE.find(source.detail)?.groupValues?.get(1)?.trim() ?: return null
        // The format line without the bitrate (the size badge already shows it).
        val format = FORMAT_LINE.find(source.detail)?.groupValues?.get(1).orEmpty().split('•').map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("~") }.joinToString(" · ")
        return if (format.isBlank()) title else "$title · $format"
    }

    /**
     * The line under the release name: the add-on's name, or for PenguPlay and CNCVerse the site and server they
     * found the link on ("4KHDHub · R2", "VegaMovies · V-Cloud FSL Server"), since their logo already names the add-on.
     */
    fun origin(source: SourceItem): String {
        val addon = PlaybackDebrid.addon(source) ?: return source.provider
        // AIOStreams: the indexer the NZB came from, with the add-on's name.
        if (AddonBadges.usenet(addon)) return listOf(source.indexer, source.provider).filter(String::isNotBlank).joinToString(" · ")
        val found = SOURCE_LINE.find(source.detail)?.groupValues?.get(1) ?: return source.provider
        return found.split('•', '·').map { part ->
            part.trim().removeSuffix("Provider").replace('[', ' ').replace("]", "").replace(Regex("\\s+"), " ").trim()
        }.filter(String::isNotBlank).joinToString(" · ").ifBlank { source.provider }
    }

    /** Whether a link plays at once: cached (and where), a direct link, checked when played, or not cached. */
    fun status(source: SourceItem, checkOnPlay: List<String>): SourceBadge {
        val cachedOn = source.cachedServices.map(DebridServices::name)
        return when {
            // A debrid add-on's link: the service it comes from, and whether it is cached there.
            source.directUrl.isNotEmpty() && source.addonCached == false && source.servedBy in DebridServices.IDS ->
                SourceBadge("NOT CACHED · ${DebridServices.name(source.servedBy!!).uppercase()}", SourceBadgeTone.UNCACHED)
            source.directUrl.isNotEmpty() && source.servedBy in DebridServices.IDS ->
                SourceBadge("CACHED · ${DebridServices.name(source.servedBy!!).uppercase()}", SourceBadgeTone.CACHED)
            source.directUrl.isNotEmpty() -> SourceBadge("DIRECT", SourceBadgeTone.CACHED)
            cachedOn.isNotEmpty() -> SourceBadge("CACHED · ${cachedOn.joinToString(" + ").uppercase()}", SourceBadgeTone.CACHED)
            checkOnPlay.isNotEmpty() -> SourceBadge("CHECK ON PLAY", SourceBadgeTone.UNCACHED)
            else -> SourceBadge("NOT CACHED", SourceBadgeTone.UNCACHED)
        }
    }

    /**
     * The services a link plays from at once, in their own order: debrid caches, or for direct links Easynews or an
     * add-on with its own logo (PenguPlay, CNCVerse).
     */
    fun cachedOn(source: SourceItem): List<String> =
        if (source.directUrl.isNotEmpty()) {
            val addon = PlaybackDebrid.addon(source)
            // AIOStreams: its logo, then where it streams from (AltMount and the viewer's news provider, or the debrid), then the indexer.
            // The AltMount and StreamNZB add-ons: their logo, the news provider, then the indexer.
            if (AddonBadges.standaloneUsenet(addon)) listOfNotNull(addon, source.newsProvider.ifBlank { null }, AddonBadges.indexer(source.indexer)).distinct()
            else if (addon == PlaybackDebrid.AIOSTREAMS) listOfNotNull(addon,
                source.servedBy?.takeIf { it == AddonBadges.ALTMOUNT },
                if (source.usenet || source.servedBy == AddonBadges.ALTMOUNT) source.newsProvider.ifBlank { null } else source.servedBy,
                AddonBadges.indexer(source.indexer)).distinct()
            // Other add-ons: their logo (or Easynews), then the debrid service a debrid add-on named for the link.
            else listOfNotNull(PlaybackDebrid.EASYNEWS.takeIf { Easynews.isHost(source.directUrl) } ?: addon,
                source.servedBy?.takeIf { it in DebridServices.IDS }).distinct()
        }
        // Torrents: the scraper that found it (Torrentio, Comet…), then where it is cached.
        else (listOfNotNull(source.scraper) + source.cachedServices).distinct()

    /**
     * The clean badge set for a link row: quality and size, then whether it plays at once, then the rest. With [logos]
     * the row shows the logos of [cachedOn] instead, so the cached (or Easynews) badge is left out.
     */
    fun forRow(source: SourceItem, languages: SourceLanguageOrder?, runtimeMinutes: Int?, checkOnPlay: List<String>, logos: Boolean = false): List<SourceBadge> =
        from(source, languages, runtimeMinutes).toMutableList().apply {
            // Logos say where a link plays from, but not that a debrid add-on still has to download it.
            // A scraper's logo on a torrent says where it came from, not that it is cached.
            if (!logos || cachedOn(source).isEmpty() || source.addonCached == false ||
                source.directUrl.isEmpty() && source.cachedServices.isEmpty()) add(minOf(2, size), status(source, checkOnPlay))
            if (source.inLibrary) add(minOf(1, size), SourceBadge("IN LIBRARY", SourceBadgeTone.CACHED))
        }

    /** [runtimeMinutes] (the title's runtime, when known) adds the estimated bitrate beside the size. */
    fun from(source: SourceItem, languages: SourceLanguageOrder? = null, runtimeMinutes: Int? = null): List<SourceBadge> = buildList {
        add(SourceBadge(SourceMetadata.qualityLabel(source.qualityHeight), when (source.qualityHeight) {
            2160, 4320 -> SourceBadgeTone.UHD
            1080 -> SourceBadgeTone.FULL_HD
            720 -> SourceBadgeTone.HD
            else -> SourceBadgeTone.OTHER
        }, quality = true))
        // Related tags share one badge ("DV · HDR10+", "BLU-RAY · REMUX · HEVC"), so a row stays short.
        fun group(parts: List<String>, tone: SourceBadgeTone, mbps: Double? = null) {
            if (parts.isNotEmpty()) add(SourceBadge(parts.joinToString(" · "), tone, mbps = mbps, parts = parts))
        }
        val mbps = AutoplayLimit.mbps(source.sizeBytes, runtimeMinutes)
        group(listOfNotNull(SourceMetadata.sizeLabel(source.sizeBytes), mbps?.let { "≈${Math.round(it)} Mbps" }), SourceBadgeTone.SIZE, mbps)
        val metadata = "${source.title} ${source.detail}".take(4000)
        fun has(pattern: String) = Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z0-9])").containsMatchIn(metadata)
        group(buildList {
            if (has("DV|DOVI|DOLBY[ ._-]*VISION")) add("DV")
            when {
                has("HDR10\\+|HDR10PLUS") -> add("HDR10+")
                has("HDR10") -> add("HDR10")
                has("HDR") -> add("HDR")
            }
            if (has("HLG")) add("HLG")
            if (has("SDR") && isEmpty()) add("SDR")
        }, SourceBadgeTone.HDR)
        group(audio(metadata), SourceBadgeTone.AUDIO)
        if (languages != null) {
            fun name(code: String) = SubtitlePreferences.LANGUAGES.firstOrNull { it.first == code }?.second?.uppercase() ?: code.uppercase()
            group(buildList {
                when {
                    languages.audioMatch(source) -> add(name(languages.audio))
                    languages.multiAudio(source) -> add("MULTI AUDIO")
                }
                if (languages.subtitleMatch(source)) add("${name(languages.subtitle)} SUBS")
            }, SourceBadgeTone.LANGUAGE)
        }
        group(buildList {
            when {
                has("WEB[ ._-]?DL") -> add("WEB-DL")
                has("WEB[ ._-]?RIP") -> add("WEBRip")
                has("BLU[ ._-]?RAY|BDRIP|BDREMUX") -> add("BLU-RAY")
            }
            if (has("(?:BD[ ._-]?)?REMUX")) add("REMUX")
            when {
                has("AV1") -> add("AV1")
                has("HEVC|[HX][ ._-]?265") -> add("HEVC")
                has("AVC|[HX][ ._-]?264") -> add("H.264")
            }
            if (has("10[ ._-]?BITS?")) add("10bit")
        }, SourceBadgeTone.SOURCE)
    }

    // Audio tags may run straight into the channel count ("DDP5.1", "DD+7.1"), so only a letter ends them.
    private fun audioTag(pattern: String) = Regex("(?i)(?<![a-z0-9])(?:$pattern)(?![a-z])")
    private val AUDIO_CODECS = listOf(
        "TrueHD" to audioTag("TRUE[ ._-]?HD"),
        "DTS-HD MA" to audioTag("DTS[ ._-]?HD[ ._-]?MA"),
        "DTS:X" to audioTag("DTS[ ._:-]?X"),
        "DTS-HD" to audioTag("DTS[ ._-]?HD"),
        "DTS" to audioTag("DTS"),
        "DDP" to audioTag("DDP|DD\\+|E[ ._-]?AC[ ._-]?3|DOLBY[ ._-]?DIGITAL[ ._-]?PLUS"),
        "DD" to audioTag("DD|AC[ ._-]?3|DOLBY[ ._-]?DIGITAL"),
        "FLAC" to audioTag("FLAC"),
        "Opus" to audioTag("OPUS"),
        "AAC" to audioTag("AAC"),
    )
    // "5.1", "7.1", "2.0" (also "DDP5.1", "5 1"), never part of a longer number such as "H.265" or "1080.1".
    private val CHANNELS = Regex("(?<!\\d)(?<!\\d\\.)([257])[ .]([01])(?![\\d])")
    private val ATMOS = audioTag("ATMOS")

    /** The audio of a release: its two best codecs, the most channels named, and Atmos ("DTS-HD MA · DDP · 5.1 · Atmos"). */
    fun audio(metadata: String): List<String> {
        val codecs = AUDIO_CODECS.filter { (_, pattern) -> pattern.containsMatchIn(metadata) }.map { it.first }
            // "DTS-HD MA" also matches "DTS-HD" and "DTS"; "DDP" written "DD+" also matches "DD".
            .let { found -> found.filterNot { codec -> found.any { it != codec && it.startsWith(codec) } } }
            .let { found -> if ("DDP" in found) found - "DD" else found }
            .take(2)
        val channels = CHANNELS.findAll(metadata).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.maxOrNull()
        return buildList {
            addAll(codecs)
            if (channels != null && (codecs.isNotEmpty() || channels != "2.0")) add(channels)
            if (ATMOS.containsMatchIn(metadata)) add("Atmos")
        }
    }

    /** A badge's gradient, left to right, in the Aurora (default) or Sunset set. */
    fun gradient(badge: SourceBadge, style: BadgeStyle = BadgeStyle.AURORA): Pair<String, String>? = if (style == BadgeStyle.SUNSET) sunset(badge) else when (badge.tone) {
        SourceBadgeTone.UHD -> "#FFE34D" to "#FF9F1C"
        SourceBadgeTone.FULL_HD -> "#6CF5B8" to "#18B67C"
        SourceBadgeTone.HD -> "#D7ACFF" to "#8C7BFF"
        SourceBadgeTone.OTHER -> "#BDE6FF" to "#6FA8FF"
        SourceBadgeTone.SIZE -> heat(badge.mbps) ?: ("#22E1FF" to "#9B6BFF")
        SourceBadgeTone.HDR -> "#FF4FD8" to "#B45CFF"
        SourceBadgeTone.AUDIO -> "#34E89E" to "#19C3D6"
        SourceBadgeTone.LANGUAGE -> "#FFB347" to "#FF5E5E"
        SourceBadgeTone.SOURCE -> "#EEF2F8" to "#8496B3"
        SourceBadgeTone.NEUTRAL, SourceBadgeTone.CACHED, SourceBadgeTone.UNCACHED -> null
    }

    private fun sunset(badge: SourceBadge): Pair<String, String>? = when (badge.tone) {
        SourceBadgeTone.UHD -> "#FFE34D" to "#FF8A3D"
        SourceBadgeTone.FULL_HD -> "#9CF2C4" to "#22A87A"
        SourceBadgeTone.HD -> "#F08BFF" to "#9B6BFF"
        SourceBadgeTone.OTHER -> "#FFD36B" to "#FF8A3D"
        SourceBadgeTone.SIZE -> heat(badge.mbps) ?: ("#FF8A3D" to "#FF3D9A")
        SourceBadgeTone.HDR -> "#FF5F9E" to "#FF9A5C"
        SourceBadgeTone.AUDIO -> "#8F6BFF" to "#FF6FB5"
        SourceBadgeTone.LANGUAGE -> "#FFD36B" to "#FF8A3D"
        SourceBadgeTone.SOURCE -> "#6C7CFF" to "#3EC6FF"
        SourceBadgeTone.NEUTRAL, SourceBadgeTone.CACHED, SourceBadgeTone.UNCACHED -> null
    }

    /** Color by type: one flat colour per kind of badge (palette A). */
    fun flatColor(badge: SourceBadge): String? = when (badge.tone) {
        SourceBadgeTone.UHD -> "#FFE34D"
        SourceBadgeTone.FULL_HD -> "#FF8891"
        SourceBadgeTone.HD -> "#D7ACFF"
        SourceBadgeTone.OTHER -> "#BDE6FF"
        SourceBadgeTone.SIZE -> "#7FD6FF"
        SourceBadgeTone.HDR -> "#F59BFF"
        SourceBadgeTone.AUDIO -> "#5FE3CF"
        SourceBadgeTone.LANGUAGE -> "#FFB66E"
        SourceBadgeTone.SOURCE -> "#8DB6FF"
        SourceBadgeTone.NEUTRAL, SourceBadgeTone.CACHED, SourceBadgeTone.UNCACHED -> null
    }

    /** Size heats up with the bitrate: cool for light links, red for heavy ones; null when the bitrate is unknown. */
    fun heat(mbps: Double?): Pair<String, String>? = when (mbps) {
            null -> null
            else -> when {
                mbps <= 5 -> "#5CF0FF" to "#2E9BFF"
                mbps <= 12 -> "#22E1FF" to "#9B6BFF"
                mbps <= 22 -> "#9B6BFF" to "#FF4FD8"
                mbps <= 40 -> "#FF4FD8" to "#FF7A45"
                else -> "#FF7A45" to "#FF2E4D"
            }
    }
}

/** Wrap chips instead of clipping metadata or introducing a second remote focus target. */
internal class SourceBadgeStrip(context: Context, badges: List<SourceBadge>, style: BadgeStyle = BadgeStyle.current(context),
                                private val compact: Boolean = false, trailing: String = "") : ViewGroup(context) {
    private fun px(value: Int) = (value * DeviceLayout.unit(resources)).roundToInt().coerceAtLeast(1)
    private val gap = px(if (style == BadgeStyle.ELITE) 8 else 6)
    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        badges.forEach { badge ->
            // Elite draws each tag as its own logo-style badge; the cached status stays a plain chip.
            if (style == BadgeStyle.ELITE && badge.tone !in setOf(SourceBadgeTone.CACHED, SourceBadgeTone.UNCACHED, SourceBadgeTone.NEUTRAL)) {
                EliteBadges.views(context, badge, ::px).forEach { addView(EliteBadges.boxed(context, it, ::px), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
                return@forEach
            }
            // Color by type: one flat colour per kind, as a thin outline on a dark tint of it, with matching text.
            val flat = if (style == BadgeStyle.COLOR_BY_TYPE) SourceBadges.flatColor(badge)?.let(Color::parseColor) else null
            val gradient = if (style == BadgeStyle.COLOR_BY_TYPE) null else SourceBadges.gradient(badge, style)
            val (ink, fill, border) = when {
                badge.tone == SourceBadgeTone.CACHED -> Triple("#8CF5B0", "#10301C", "#3E9A5E")
                badge.tone == SourceBadgeTone.UNCACHED -> Triple("#C8C8CC", "#26262A", "#5A5A60")
                flat != null -> Triple(hex(flat), hex(mix(flat, 0.13f)), hex(mix(flat, 0.55f)))
                else -> Triple("#FFFFFF", "#273442", "#66788A")
            }
            addView(TextView(context).apply {
                text = badge.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(if (badge.quality) (if (compact) 14 else 16) else (if (compact) 12 else 13)).toFloat())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                // Quality is a filled gradient with dark text; the others a gradient outline with white text.
                setTextColor(Color.parseColor(if (gradient != null && badge.quality) "#10131A" else ink))
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                if (compact) { setPadding(px(7), px(3), px(7), px(3)); minHeight = px(24) } else { setPadding(px(8), px(4), px(8), px(4)); minHeight = px(28) }
                isFocusable = false; isClickable = false
                tag = "source-badge:${badge.label}"
                background = if (gradient == null) GradientDrawable().apply {
                    setColor(Color.parseColor(fill)); setStroke(px(1), Color.parseColor(border)); cornerRadius = px(6).toFloat()
                } else badgeBackground(Color.parseColor(gradient.first), Color.parseColor(gradient.second), filled = badge.quality)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        // Compact cards put the link's source ("4KHDHub · R2", the add-on) at the end of the row, in grey.
        if (trailing.isNotBlank()) addView(TextView(context).apply {
            text = trailing; setTextColor(Color.parseColor("#9AA3B2")); includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12).toFloat()); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL; minHeight = px(if (compact) 24 else 28); isFocusable = false
            tag = "source-origin"
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    /** [color] blended over the dark card colour: a small [share] for a tint, a larger one for the outline. */
    private fun mix(color: Int, share: Float) = Color.rgb(
        (Color.red(color) * share + 0x11 * (1 - share)).roundToInt(),
        (Color.green(color) * share + 0x14 * (1 - share)).roundToInt(),
        (Color.blue(color) * share + 0x1A * (1 - share)).roundToInt())
    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    /** A gradient chip: filled, or a gradient outline around a dark tint of the same gradient. */
    private fun badgeBackground(start: Int, end: Int, filled: Boolean): android.graphics.drawable.Drawable {
        val radius = px(6).toFloat()
        val edge = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end)).apply { cornerRadius = radius }
        if (filled) return edge
        fun tint(color: Int) = Color.rgb(
            (Color.red(color) * 0.26f + 0x11 * 0.74f).roundToInt(),
            (Color.green(color) * 0.26f + 0x14 * 0.74f).roundToInt(),
            (Color.blue(color) * 0.26f + 0x1A * 0.74f).roundToInt())
        val inside = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(tint(start), tint(end))).apply { cornerRadius = radius - 1 }
        val stroke = max(1, (1.5f * DeviceLayout.unit(resources)).roundToInt())
        return android.graphics.drawable.LayerDrawable(arrayOf(edge, inside)).apply { setLayerInset(1, stroke, stroke, stroke, stroke) }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else MeasureSpec.getSize(widthMeasureSpec)
        var x = 0; var y = 0; var rowHeight = 0; var widest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > available) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            widest = max(widest, x + child.measuredWidth)
            x += child.measuredWidth + gap; rowHeight = max(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(y + rowHeight, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0; var y = 0; var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + child.measuredWidth > width) { y += rowHeight + gap; x = 0; rowHeight = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap; rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}
