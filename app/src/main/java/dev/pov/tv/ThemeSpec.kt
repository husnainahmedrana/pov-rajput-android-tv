package dev.pov.tv

import android.content.Context

/**
 * Flix: living backdrop, left menu, landscape shelves (the Editorial theme was retired in 0.3.3).
 * Dimensions use a 960 x 540 logical canvas scaled to the screen.
 * Theme changes contain presentation only; account and playback state are separate.
 */
enum class ThemeSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val palette: ThemePalette,
    private val tvLayout: ThemeLayout,
) {
    FLIX(
        id = "flix",
        displayName = "Flix",
        description = "Living backdrop, rotating Top 10 feature, title logos, and a hidden menu.",
        palette = ThemePalette(
            background = 0xFF141414.toInt(), panel = 0xFF232323.toInt(),
            text = 0xFFFFFFFF.toInt(), muted = 0xFFB3B3B3.toInt(),
            accent = BuildConfig.BRAND_ACCENT, secondaryAccent = BuildConfig.BRAND_ACCENT_2,
            buttonText = 0xFF000000.toInt(), border = 0xFF2F2F2F.toInt(),
            navSelected = 0x33FFFFFF, progress = BuildConfig.BRAND_ACCENT,
            primaryButton = 0xFFFFFFFF.toInt(),
        ),
        tvLayout = ThemeLayout(
            navigation = NavigationPlacement.LEFT_RAIL, hero = HeroComposition.FULL_BLEED,
            cards = CardFormat.LANDSCAPE, contentInsetDp = 36, railWidthDp = 0,
            headerHeightDp = 0, heroHeightDp = 300, cardWidthDp = 190, cardHeightDp = 107,
            cardGapDp = 9, cornerRadiusDp = 4, titleSizeSp = 46,
            titleTypeface = "sans-serif-black", titleLetterSpacing = 0f,
            sectionTitleSizeSp = 15, focusScale = 1.09f,
        ),
    );

    /** The phone app draws Flix on a ~400-unit portrait canvas with a bottom navigation bar. */
    val layout: ThemeLayout get() = wideLayout.let { base ->
        // Settings → Home → Card shape · Posters: tall 2:3 cards, so poster services show on every row.
        if (posterSize.isEmpty()) base else {
            val width = posterWidth(posterSize, DeviceLayout.phone)
            base.copy(cards = CardFormat.POSTER, cardWidthDp = width, cardHeightDp = width * 3 / 2)
        }
    }

    /** The usual wide-card layout, whatever the card shape: Continue Watching always keeps these wide cards. */
    val wideLayout: ThemeLayout get() = (if (DeviceLayout.phone) tvLayout.copy(
        navigation = NavigationPlacement.BOTTOM_BAR, contentInsetDp = 14, headerHeightDp = 0,
        heroHeightDp = 380, cardWidthDp = 150, cardHeightDp = 84, cardGapDp = 8,
        titleSizeSp = 30, sectionTitleSizeSp = 15, focusScale = 1f,
    ) else tvLayout)

    companion object {
        /** Card shape (Settings → Home): "" wide backdrops, else poster cards "small", "medium" or "large"; read at start-up. */
        @Volatile var posterSize = ""
        val POSTER_SIZES = listOf("small", "medium", "large")
        /** Poster card widths (height is 1.5×): phone about 3.8 / 3.2 / 2.7 across, TV about 7.8 / 6.5 / 5.6 across. */
        fun posterWidth(size: String, phone: Boolean): Int = when (size) {
            "small" -> if (phone) 92 else 106
            "large" -> if (phone) 132 else 150
            else -> if (phone) 112 else 128
        }

        /** Unknown, retired or older preferences (including "streaming") fall back to Flix. */
        fun fromId(id: String?): ThemeSpec = entries.firstOrNull { it.id == id } ?: FLIX
    }
}

data class ThemePalette(
    val background: Int,
    val panel: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val secondaryAccent: Int,
    val buttonText: Int,
    val border: Int,
    val navSelected: Int,
    val progress: Int,
    val primaryButton: Int = accent,
    val success: Int = 0xFF38E965.toInt(),
)

data class ThemeLayout(
    val navigation: NavigationPlacement,
    val hero: HeroComposition,
    val cards: CardFormat,
    val contentInsetDp: Int,
    val railWidthDp: Int,
    val headerHeightDp: Int,
    val heroHeightDp: Int,
    val cardWidthDp: Int,
    val cardHeightDp: Int,
    val cardGapDp: Int,
    val cornerRadiusDp: Int,
    val titleSizeSp: Int,
    val titleTypeface: String,
    val titleLetterSpacing: Float,
    val sectionTitleSizeSp: Int,
    val focusScale: Float,
)

enum class NavigationPlacement { LEFT_RAIL, TOP_BAR, BOTTOM_BAR }
enum class HeroComposition { FULL_BLEED, SPLIT }
enum class CardFormat { LANDSCAPE, POSTER }

/** Appearance is a non-secret device preference and survives app updates. */
class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pov_appearance", Context.MODE_PRIVATE)

    // Flix is the only theme; an older saved choice (such as "editorial") falls back to it.
    fun selected(): ThemeSpec = ThemeSpec.fromId(preferences.getString(KEY, null))

    fun select(theme: ThemeSpec) {
        preferences.edit().putString(KEY, theme.id).apply()
    }

    /** Card shape: "" for the usual wide backdrops, or a poster size ("small", "medium", "large"). */
    var posterSize: String
        get() = preferences.getString(CARDS_KEY, null)?.takeIf { it in ThemeSpec.POSTER_SIZES }.orEmpty()
        set(value) { preferences.edit().apply { if (value in ThemeSpec.POSTER_SIZES) putString(CARDS_KEY, value) else remove(CARDS_KEY) }.apply() }

    private companion object { const val KEY = "theme"; const val CARDS_KEY = "cards.posters" }
}
