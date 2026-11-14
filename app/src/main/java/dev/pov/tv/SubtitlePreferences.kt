package dev.pov.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import java.util.Locale

/** Device-local text subtitle preferences, shared by fullscreen and mini-player views. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SubtitlePreferences(private val context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE)
    var transparentBackground: Boolean
        get() = preferences.getBoolean("transparent_background", true)
        set(value) { preferences.edit().putBoolean("transparent_background", value).remove("bg_opacity").apply() }

    /** Font: "" keeps the device's caption font; otherwise one of [FONTS]. Lato until the viewer picks another. */
    var font: String
        get() = preferences.getString("font", DEFAULT_FONT).orEmpty().takeIf { value -> value.isEmpty() || FONTS.any { it.id == value } } ?: DEFAULT_FONT
        set(value) { require(value.isEmpty() || FONTS.any { it.id == value }); preferences.edit().putString("font", value).apply() }
    val fontLabel: String get() = FONTS.firstOrNull { it.id == font }?.label ?: L10n.tr(R.string.t_device_default, "Device default")
    /** Text and outline colors (white on a black edge until changed); null keeps the device's caption colors. */
    var textColor: Int?
        get() = preferences.getInt("text_color", Color.WHITE).takeIf { it != DEVICE_COLOR }
        set(value) { preferences.edit().putInt("text_color", value ?: DEVICE_COLOR).apply() }
    var edgeColor: Int?
        get() = preferences.getInt("edge_color", Color.BLACK).takeIf { it != DEVICE_COLOR }
        set(value) { preferences.edit().putInt("edge_color", value ?: DEVICE_COLOR).apply() }
    /** A CaptionStyleCompat EDGE_TYPE_* (drop shadow until changed), or -1 for the device default. */
    var edgeType: Int
        get() = preferences.getInt("edge_type", CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW).takeIf { it == -1 || it in EDGE_TYPES.map { type -> type.first } } ?: -1
        set(value) { require(value == -1 || value in EDGE_TYPES.map { it.first }); preferences.edit().putInt("edge_type", value).apply() }
    val edgeLabel: String get() = EDGE_TYPES.firstOrNull { it.first == edgeType }?.second ?: L10n.tr(R.string.t_device_default, "Device default")
    /** Background box opacity in percent (0 = none), or -1 for the device default. The older "Transparent background" is 0. */
    var backgroundOpacity: Int
        get() = if (preferences.contains("bg_opacity")) preferences.getInt("bg_opacity", 0).takeIf { it == -1 || it in OPACITIES } ?: 0
            else if (transparentBackground) 0 else -1
        set(value) { require(value == -1 || value in OPACITIES)
            preferences.edit().putInt("bg_opacity", value).putBoolean("transparent_background", value == 0).apply() }
    val backgroundLabel: String get() = when (backgroundOpacity) { -1 -> L10n.tr(R.string.t_device_default, "Device default"); 0 -> L10n.tr(R.string.t_none, "None"); else -> "${colorName(backgroundColor)} · $backgroundOpacity%" }
    var backgroundColor: Int
        get() = preferences.getInt("bg_color", Color.BLACK)
        set(value) { preferences.edit().putInt("bg_color", value).apply() }
    private val custom: Boolean get() = font.isNotEmpty() || textColor != null || edgeColor != null || edgeType != -1 || preferences.contains("bg_opacity")

    /** One-tap looks: font, colors, outline and background together. */
    fun applyPreset(id: String) {
        val edit = preferences.edit()
        listOf("font", "text_color", "edge_color", "edge_type", "bg_opacity", "bg_color").forEach(edit::remove)
        edit.apply()
        when (id) {
            "netflix" -> { font = "inter"; textColor = Color.WHITE; edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; edgeColor = Color.BLACK; backgroundOpacity = 0; boldText = false }
            "prime" -> { font = "arimo"; textColor = Color.WHITE; edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE; edgeColor = 0xFF1A1A1A.toInt(); backgroundOpacity = 0; boldText = true }
            "youtube" -> { font = "roboto"; textColor = Color.WHITE; edgeType = CaptionStyleCompat.EDGE_TYPE_NONE; backgroundColor = Color.BLACK; backgroundOpacity = 75; boldText = false }
            "classic" -> { font = "open-sans"; textColor = 0xFFFFE600.toInt(); edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE; edgeColor = Color.BLACK; backgroundOpacity = 0; boldText = true }
            // Back to the app's defaults: bold white Lato, medium, black drop shadow, no box.
            else -> { transparentBackground = true; boldText = true; sizePercent = 100 }
        }
    }
    /** Find subtitles online when a video has none in the chosen language. */
    /**
     * English subtitles when none are in the chosen language. Its own switch since the audio one split off; until it is
     * set, it follows the old shared "English fallback" switch, so nothing changes for existing viewers.
     */
    var englishFallback: Boolean
        get() = if (preferences.contains("english_fallback")) preferences.getBoolean("english_fallback", true) else AudioPreferences(context).englishFallback
        set(value) { preferences.edit().putBoolean("english_fallback", value).apply() }
    fun withFallback(code: String): Array<String> = (listOf(code) + if (englishFallback) listOf("en") else emptyList()).distinct().toTypedArray()
    var autoFetch: Boolean
        get() = preferences.getBoolean("auto_fetch", true)
        set(value) { preferences.edit().putBoolean("auto_fetch", value).apply() }
    var boldText: Boolean
        get() = preferences.getBoolean("bold_text", true)
        set(value) { preferences.edit().putBoolean("bold_text", value).apply() }

    var sizePercent: Int
        get() = preferences.getInt("size_percent", 100).takeIf { it in SIZES } ?: 100
        set(value) { require(value in SIZES); preferences.edit().putInt("size_percent", value).apply() }
    val sizeLabel: String get() = SIZE_LABELS[SIZES.indexOf(sizePercent)]

    var positionPercent: Int
        get() = preferences.getInt("position_percent", DEFAULT_POSITION).takeIf { it == -1 || it in 1..30 } ?: DEFAULT_POSITION
        set(value) { require(value == -1 || value in 1..30); preferences.edit().putInt("position_percent", value).apply() }
    val positionLabel: String get() = if (positionPercent < 0) L10n.tr(R.string.t_default, "Default") else L10n.tr(R.string.t_x_from_bottom, "%1\$s%% from bottom", positionPercent)

    fun positionedCues(cues: List<Cue>): List<Cue> {
        val selected = positionPercent
        if (selected < 0) return cues
        return cues.map { cue ->
            if (cue.text == null || cue.verticalType != Cue.TYPE_UNSET) cue else cue.buildUpon()
                .setLine(1f - selected / 100f, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        }
    }

    var language: String
        get() = preferences.getString("language", "en").orEmpty().takeIf { value -> LANGUAGES.any { it.first == value } }.orEmpty()
        set(value) { require(LANGUAGES.any { it.first == value }); preferences.edit().putString("language", value).apply() }
    val languageLabel: String get() = LANGUAGES.first { it.first == language }.second

    fun trackParameters(current: TrackSelectionParameters): TrackSelectionParameters {
        val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        return when (val selected = language) {
            "off" -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            "" -> {
                val defaults = TrackSelectionParameters.Builder(context).build()
                builder.setPreferredTextLanguages(*defaults.preferredTextLanguages.toTypedArray())
                    .setPreferredTextRoleFlags(defaults.preferredTextRoleFlags)
                    .setIgnoredTextSelectionFlags(defaults.ignoredTextSelectionFlags)
                    .setSelectUndeterminedTextLanguage(defaults.selectUndeterminedTextLanguage)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT in defaults.disabledTrackTypes).build()
            }
            else -> builder.setPreferredTextLanguages(*withFallback(selected)).setPreferredTextRoleFlags(0)
                .setSelectUndeterminedTextLanguage(false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        }
    }

    internal fun style(base: CaptionStyleCompat): CaptionStyleCompat {
        val opacity = backgroundOpacity
        val background = when (opacity) { -1 -> base.backgroundColor; 0 -> Color.TRANSPARENT
            else -> (backgroundColor and 0x00FFFFFF) or ((opacity * 255 / 100) shl 24) }
        val edge = when {
            edgeType != -1 -> edgeType
            // No box: keep the text readable with an outline unless the device already draws an edge.
            opacity == 0 && base.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else -> base.edgeType
        }
        val face = if (font.isNotEmpty()) typeface(context, font, boldText)
            else if (boldText) Typeface.create(base.typeface ?: Typeface.DEFAULT, Typeface.BOLD) else base.typeface
        return CaptionStyleCompat(textColor ?: base.foregroundColor, background,
            if (opacity == -1) base.windowColor else Color.TRANSPARENT, edge, edgeColor ?: base.edgeColor, face)
    }

    /** A sample line drawn exactly as the player draws subtitles, for the Subtitle style page. */
    fun preview(): android.view.View {
        val frame = android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3A5A78.toInt(), 0xFF8C7A5B.toInt(), 0xFF22262E.toInt())).apply { cornerRadius = 12 * context.resources.displayMetrics.density }
            clipToOutline = true
        }
        val subtitles = androidx.media3.ui.SubtitleView(context)
        val captions = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        val base = captions?.userStyle?.let(CaptionStyleCompat::createFromCaptionStyle) ?: CaptionStyleCompat.DEFAULT
        subtitles.setApplyEmbeddedStyles(false)
        subtitles.setStyle(if (transparentBackground || boldText || custom) style(base) else base)
        subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22f * sizePercent / 100f)
        subtitles.setCues(listOf(Cue.Builder().setText(L10n.tr(R.string.t_this_is_how_your_subtitles_will, "This is how your subtitles will look.")).setLine(0.82f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END).build()))
        frame.addView(subtitles, android.widget.FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    fun applyTo(view: PlayerView) {
        val subtitles = view.subtitleView ?: return
        view.player?.let { subtitles.setCues(positionedCues(it.currentCues.cues)) }
        val override = transparentBackground || boldText || custom
        // Embedded cue spans/windows can override background and font weight. Restore them
        // when both options are off, keeping the original rendering as the default.
        subtitles.setApplyEmbeddedStyles(!override)
        subtitles.setApplyEmbeddedFontSizes(!override && sizePercent == 100)
        val captions = view.context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        subtitles.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
            sizePercent / 100f * (captions?.fontScale ?: 1f))
        if (!override) {
            subtitles.setUserDefaultStyle()
            return
        }
        val base = captions?.userStyle?.let(CaptionStyleCompat::createFromCaptionStyle) ?: CaptionStyleCompat.DEFAULT
        subtitles.setStyle(style(base))
    }

    companion object {
        @Volatile private var languages: Pair<Locale, List<Pair<String, String>>>? = null
        /** Every language by code, named in the app's language (rebuilt when it changes). */
        val LANGUAGES: List<Pair<String, String>> get() {
            val locale = Locale.getDefault()
            languages?.takeIf { it.first == locale }?.let { return it.second }
            val names = Locale.getISOLanguages().map { code ->
                code to Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
            }.distinctBy { it.first }.sortedBy { it.second }
            val list = listOf("" to L10n.tr(R.string.t_automatic_device_preference, "Automatic (device preference)"), "off" to L10n.tr(R.string.t_off, "Off")) + names
            languages = locale to list
            return list
        }
        val COMMON_LANGUAGES = listOf("", "off", "en", "ur", "hi", "ar", "es", "fr", "de", "pt", "zh", "ja", "ko")
        val SIZES = listOf(80, 100, 130)
        const val DEFAULT_FONT = "lato"
        /** Stored for "Device default" colors; nearly invisible, so it is never a real choice. */
        private const val DEVICE_COLOR = 0x00000001
        data class Font(val id: String, val label: String, private val about: String) {
            val description: String get() = UiText.of(about)
        }
        /** Free fonts only: Netflix Sans and Amazon Ember are private, so look-alikes stand in. Built-in ones ship with Android. */
        val FONTS = listOf(
            Font("roboto", "Roboto", "YouTube's captions on Android"),
            Font("arimo", "Arimo", "The Arial style of Netflix's subtitle rules"),
            Font("inter", "Inter", "Clean and modern, like Netflix Sans and Amazon Ember"),
            Font("open-sans", "Open Sans", "Very readable on TVs"),
            Font("noto-sans", "Noto Sans", "Very readable on TVs"),
            Font("lato", "Lato", "Rounder and friendly"),
            Font("mono", "Mono", "Typewriter style (YouTube's monospaced)"),
            Font("serif", "Serif", "Book style (YouTube's serif)"),
            Font("casual", "Casual", "Handwritten feel (YouTube's casual)"),
            Font("cursive", "Cursive", "Joined-up script (YouTube's cursive)"),
            Font("smallcaps", "Small caps", "Capital letters only (YouTube's small capitals)"),
        )
        private val BUILT_IN = mapOf("roboto" to "sans-serif", "mono" to "monospace", "serif" to "serif",
            "casual" to "casual", "cursive" to "cursive", "smallcaps" to "sans-serif-smallcaps")
        private val loaded = HashMap<String, Typeface>()
        fun typeface(context: Context, id: String, bold: Boolean): Typeface {
            BUILT_IN[id]?.let { family -> return Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL) }
            val file = "subtitle-fonts/$id-${if (bold) "bold" else "regular"}.ttf"
            return synchronized(loaded) { loaded.getOrPut(file) {
                runCatching { Typeface.createFromAsset(context.applicationContext.assets, file) }.getOrDefault(Typeface.DEFAULT)
            } }
        }
        val EDGE_TYPES get() = listOf(CaptionStyleCompat.EDGE_TYPE_NONE to L10n.tr(R.string.t_none, "None"), CaptionStyleCompat.EDGE_TYPE_OUTLINE to L10n.tr(R.string.t_outline, "Outline"),
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW to L10n.tr(R.string.t_drop_shadow, "Drop shadow"), CaptionStyleCompat.EDGE_TYPE_RAISED to L10n.tr(R.string.t_raised, "Raised"),
            CaptionStyleCompat.EDGE_TYPE_DEPRESSED to L10n.tr(R.string.t_depressed, "Depressed"))
        val OPACITIES = listOf(0, 25, 50, 75, 100)
        val COLORS get() = listOf(Color.WHITE to L10n.tr(R.string.t_white, "White"), 0xFFFFE600.toInt() to L10n.tr(R.string.t_yellow, "Yellow"), 0xFF00E5FF.toInt() to L10n.tr(R.string.t_cyan, "Cyan"), 0xFF00E676.toInt() to L10n.tr(R.string.t_green, "Green"),
            0xFFFF80AB.toInt() to L10n.tr(R.string.t_pink, "Pink"), 0xFFFFAB40.toInt() to L10n.tr(R.string.t_orange, "Orange"), 0xFFCCCCCC.toInt() to L10n.tr(R.string.t_light_grey, "Light grey"), 0xFF555555.toInt() to L10n.tr(R.string.t_dark_grey, "Dark grey"),
            0xFF1A1A1A.toInt() to L10n.tr(R.string.t_near_black, "Near black"), Color.BLACK to L10n.tr(R.string.t_black, "Black"))
        fun colorName(color: Int?) = if (color == null) L10n.tr(R.string.t_device_default, "Device default") else COLORS.firstOrNull { it.first == color }?.second ?: L10n.tr(R.string.t_custom, "Custom")
        val PRESETS get() = listOf("netflix" to L10n.tr(R.string.t_netflix_style_white_inter_with_a, "Netflix-style · white Inter with a drop shadow"), "prime" to L10n.tr(R.string.t_prime_style_white_arimo_with_a, "Prime-style · white Arimo with a soft outline"),
            "youtube" to L10n.tr(R.string.t_youtube_style_white_roboto_on_a, "YouTube-style · white Roboto on a 75% black box"), "classic" to L10n.tr(R.string.t_classic_yellow_yellow_open_sans_with, "Classic yellow · yellow Open Sans with a black outline"),
            "default" to L10n.tr(R.string.t_app_default_bold_white_lato_medium, "App default · bold white Lato, medium, black drop shadow, no box"))
        /** New installs: bold white Lato, medium, black drop shadow, no box, 3% above the bottom, English. */
        const val DEFAULT_POSITION = 3
        val SIZE_LABELS get() = listOf(L10n.tr(R.string.t_small, "Small"), L10n.tr(R.string.t_medium, "Medium"), L10n.tr(R.string.t_large, "Large"))
    }
}
