package dev.pov.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import dev.pov.tv.data.LinkRules
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceLanguageOrder
import dev.pov.tv.data.SourceSortOrder

internal class SourceSortPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("pov_sources", Context.MODE_PRIVATE)
    var order: SourceSortOrder
        get() = SourceSortOrder(prefs.getBoolean("quality_descending", true), prefs.getBoolean("size_ascending", false),
            SourceSortOrder.priority(prefs.getString("priority", null)), prefs.getBoolean("library_first", true),
            rules.takeIf { preferredFirst && it.preferActive }?.let { rules ->
                val ranks = HashMap<String, Int>()
                val rank: (SourceItem) -> Int = { source -> ranks.getOrPut(source.title) { rules.rank(source.title) } }
                rank
            })
        set(value) { prefs.edit().putBoolean("quality_descending", value.qualityDescending).putBoolean("size_ascending", value.sizeAscending)
            .putString("priority", value.priority.joinToString(",")).apply() }
    /** Links already in a library (AIOStreams, StreamNZB, AltMount) lead everything else, still in this sort order. */
    var libraryFirst: Boolean
        get() = prefs.getBoolean("library_first", true)
        set(value) { prefs.edit().putBoolean("library_first", value).apply() }
    /** The Preferred row: the viewer's words, ready-made switches and trusted release groups move matching links up. */
    var preferredFirst: Boolean
        get() = prefs.getBoolean("preferred_first", true)
        set(value) { prefs.edit().putBoolean("preferred_first", value).apply() }
    /** Links matching your own Preferred words stay with the links that play at once, even when not cached. */
    var wordsBeatCached: Boolean
        get() = prefs.getBoolean("words_beat_cached", false)
        set(value) { prefs.edit().putBoolean("words_beat_cached", value).apply() }
    /** Off: the search stops once Best per quality has filled 4K, 1080p and 720p. On by default (keep searching). */
    var keepSearching: Boolean
        get() = prefs.getBoolean("keep_searching", true)
        set(value) { prefs.edit().putBoolean("keep_searching", value).apply() }
    /** Best per quality: keep only this many links of each quality (4K, 1080p, 720p, lower) in sort order; 0 = all. */
    var bestPerQuality: Int
        get() = prefs.getInt("best_per_quality", 0).coerceIn(BEST_PER_QUALITY_RANGE)
        set(value) { prefs.edit().putInt("best_per_quality", value.coerceIn(BEST_PER_QUALITY_RANGE)).apply() }
    /** Preferred and Link filters rules (words, switches, groups, patterns), saved together. */
    var rules: LinkRules.Rules
        get() = LinkRules.Rules.fromJson(prefs.getString("link_rules", null))
        set(value) { prefs.edit().putString("link_rules", value.toJson()).apply() }
    var languageFirst: Boolean
        get() = prefs.getBoolean("language_first", true)
        set(value) { prefs.edit().putBoolean("language_first", value).apply() }
    /** The audio and subtitle languages from Settings, or null when language ranking is off or both are automatic. */
    val languages: SourceLanguageOrder?
        get() = if (!languageFirst) null
            else AudioPreferences(context).let { audio ->
                SourceLanguageOrder(audio.language, SubtitlePreferences(context).language, if (audio.englishFallback) "en" else "")
            }.takeIf { it.active }
    private val languageLabel: String?
        get() = languages?.let { order -> if (order.audio.isNotEmpty()) "${languageName(order.audio)} first" else "${languageName(order.subtitle)} subtitles first" }
    /** The sort in its chosen order, e.g. "Highest quality · English first · Smallest first". */
    val label: String get() = order.priority.mapNotNull { key ->
        when (key) {
            SourceSortOrder.LIBRARY -> "Library first".takeIf { libraryFirst }
            SourceSortOrder.LANGUAGE -> languageLabel
            SourceSortOrder.PREFERRED -> "Preferred first".takeIf { preferredFirst && rules.preferActive }
            SourceSortOrder.QUALITY -> if (order.qualityDescending) "Highest quality" else "Lowest quality"
            else -> if (order.sizeAscending) "Smallest first" else "Largest first"
        }
    }.joinToString(" · ")

    fun sorted(sources: List<SourceItem>): List<SourceItem> = languages?.sorted(sources, order) ?: order.sorted(sources)

    companion object {
        private const val EDIT_WIDTH = 52
        private fun languageName(code: String) = SubtitlePreferences.LANGUAGES.firstOrNull { it.first == code }?.second ?: code

        /**
         * Link sorting, like Debrid services: Library, Language, Quality and Size as rows in their order. ▲ and ▼ move a row,
         * the button beside it flips its direction, and the line below shows the result. Closing it re-sorts the links.
         */
        val BEST_PER_QUALITY_RANGE = 0..10
        private fun qualityBucket(source: SourceItem): Int { val height = source.qualityHeight ?: 0
            return when { height >= 2160 -> 0; height >= 1080 -> 1; height >= 720 -> 2; else -> 3 } }
        /** The first [perQuality] links of each quality, in the order given (the sort order); all when 0. */
        fun bestPerQuality(sorted: List<SourceItem>, perQuality: Int): List<SourceItem> {
            if (perQuality <= 0) return sorted
            val counts = IntArray(4)
            return sorted.filter { source -> val bucket = qualityBucket(source); (counts[bucket] < perQuality).also { if (it) counts[bucket]++ } }
        }

        /** Words and switches in the Preferred row, e.g. "PSA, FLUX · 2 switches". */
        fun preferredSummary(rules: LinkRules.Rules): String {
            val switches = rules.preferPresets.size
            return listOfNotNull(rules.preferWords.take(3).joinToString(", ").takeIf { it.isNotBlank() }?.let { if (rules.preferWords.size > 3) "$it…" else it },
                "$switches ${if (switches == 1) "switch" else "switches"}".takeIf { switches > 0 },
                "own pattern".takeIf { rules.preferPattern.isNotBlank() }).joinToString(" · ")
                .ifEmpty { "Nothing yet: Edit adds words like PSA or FLUX, switches and release groups" }
        }

        fun show(context: Context, changed: () -> Unit, editPreferred: (() -> Unit)? = null) {
            val preferences = SourceSortPreferences(context)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val audio = AudioPreferences(context).language; val subtitle = SubtitlePreferences(context).language.takeUnless { it == "off" }.orEmpty()
            val languageSummary = listOfNotNull(audio.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} audio" },
                subtitle.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} subtitles" }).joinToString(", ").ifEmpty { "set an audio or subtitle language in Settings" }
            fun name(key: String) = when (key) { SourceSortOrder.LIBRARY -> "Library"; SourceSortOrder.LANGUAGE -> "Language"; SourceSortOrder.PREFERRED -> "Preferred"; SourceSortOrder.QUALITY -> "Quality"; else -> "File size" }
            var dialog: AlertDialog? = null
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
            var modified = false

            fun button(label: String, tagName: String, enabled: Boolean, action: () -> Unit) = TextView(context).apply {
                text = label; tag = tagName; gravity = Gravity.CENTER; includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(dp(14), 0, dp(14), 0); minWidth = dp(46)
                isFocusable = enabled; isFocusableInTouchMode = DeviceLayout.tv; isClickable = enabled; alpha = if (enabled) 1f else 0.3f
                fun paint(focused: Boolean) {
                    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (focused) Color.WHITE else 0x33FFFFFF) }
                    setTextColor(if (focused) Color.BLACK else Color.WHITE)
                }
                paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }
                setOnClickListener { if (enabled) action() }
            }

            lateinit var render: (String?) -> Unit
            render = { focus ->
                list.removeAllViews()
                val order = preferences.order
                order.priority.forEachIndexed { index, key ->
                    // Phone: the name and its line on top, the buttons in a row underneath, so nothing gets squeezed.
                    val phone = DeviceLayout.phone
                    val row = LinearLayout(context).apply { orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                        gravity = if (phone) Gravity.START else Gravity.CENTER_VERTICAL; setPadding(0, dp(if (phone) 9 else 7), 0, dp(if (phone) 9 else 7)) }
                    val controls = if (phone) LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL } else row
                    val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    words.addView(TextView(context).apply {
                        text = "${index + 1}.  ${name(key)}"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    if (key == SourceSortOrder.LANGUAGE || key == SourceSortOrder.LIBRARY || key == SourceSortOrder.PREFERRED) words.addView(TextView(context).apply {
                        text = when (key) { SourceSortOrder.LIBRARY -> "Links already in your AIOStreams, StreamNZB or AltMount library"
                            SourceSortOrder.PREFERRED -> preferredSummary(preferences.rules); else -> languageSummary }; setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = if (DeviceLayout.phone) 2 else 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(words, if (phone) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                    val direction = when (key) {
                        SourceSortOrder.LIBRARY -> if (preferences.libraryFirst) "First" else "Off"
                        SourceSortOrder.LANGUAGE -> if (preferences.languageFirst) "Preferred first" else "Off"
                        SourceSortOrder.PREFERRED -> if (preferences.preferredFirst) "First" else "Off"
                        SourceSortOrder.QUALITY -> if (order.qualityDescending) "Highest first" else "Lowest first"
                        else -> if (order.sizeAscending) "Smallest first" else "Largest first"
                    }
                    // The Preferred row's Edit shares the direction button's width, so every row's buttons line up.
                    val withEdit = key == SourceSortOrder.PREFERRED && editPreferred != null
                    controls.addView(button(direction, "way:$key", true) {
                        when (key) {
                            SourceSortOrder.LIBRARY -> preferences.libraryFirst = !preferences.libraryFirst
                            SourceSortOrder.LANGUAGE -> preferences.languageFirst = !preferences.languageFirst
                            SourceSortOrder.PREFERRED -> preferences.preferredFirst = !preferences.preferredFirst
                            SourceSortOrder.QUALITY -> preferences.order = preferences.order.copy(qualityDescending = !order.qualityDescending)
                            else -> preferences.order = preferences.order.copy(sizeAscending = !order.sizeAscending)
                        }
                        modified = true; render("way:$key")
                    }, if (phone) LinearLayout.LayoutParams(0, dp(40), 1f) else LinearLayout.LayoutParams(dp(if (withEdit) 150 - 8 - EDIT_WIDTH else 150), dp(40)).apply { leftMargin = dp(8) })
                    if (withEdit) controls.addView(button("Edit", "edit:$key", true) {
                        dialog?.dismiss(); editPreferred?.invoke()
                    }, if (phone) LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) } else LinearLayout.LayoutParams(dp(EDIT_WIDTH), dp(40)).apply { leftMargin = dp(8) })
                    fun move(step: Int) {
                        val keys = order.priority.toMutableList(); keys.add(index + step, keys.removeAt(index))
                        preferences.order = preferences.order.copy(priority = keys); modified = true
                        render(if (step < 0) "up:$key" else "down:$key")
                    }
                    controls.addView(button("▲", "up:$key", index > 0) { move(-1) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
                    controls.addView(button("▼", "down:$key", index < order.priority.lastIndex) { move(1) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(6) })
                    if (phone) row.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    list.addView(row)
                }
                list.addView(TextView(context).apply {
                    text = "Result: ${preferences.label}"; setTextColor(0xFFCFD5DE.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                // Focus follows the row that moved; at the top or bottom its ▲ or ▼ is off, so the other one takes it.
                if (focus != null) list.post {
                    val key = focus.substringAfter(':')
                    listOf(focus, "down:$key", "up:$key", "way:$key").firstNotNullOfOrNull { tag -> list.findViewWithTag<View>(tag)?.takeIf { it.isFocusable } }?.requestFocus()
                }
            }
            render(null)
            val created = AlertDialog.Builder(context).setTitle("Link sorting").setView(android.widget.ScrollView(context).apply { addView(list) })
                .setPositiveButton("Done", null).setNeutralButton("Reset to default", null).create()
            dialog = created
            created.setOnDismissListener { if (modified) changed() }
            created.show()
            // Back to the default: library, language, then quality (highest first), then size (largest first).
            created.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                preferences.order = SourceSortOrder(); preferences.languageFirst = true; preferences.libraryFirst = true; preferences.preferredFirst = true; modified = true
                render("way:${SourceSortOrder.LIBRARY}")
            }
            created.window?.setLayout(minOf(dp(720), context.resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            list.post { list.findViewWithTag<View>("way:${preferences.order.priority.first()}")?.requestFocus() }
        }
    }
}
