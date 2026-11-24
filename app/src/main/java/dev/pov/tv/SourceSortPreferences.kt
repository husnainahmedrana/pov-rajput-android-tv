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
            prefs.getBoolean("newest_first", false),
            rules.takeIf { preferredFirst && it.preferActive }?.let { rules ->
                val ranks = HashMap<String, Int>()
                val rank: (SourceItem) -> Int = { source -> ranks.getOrPut(source.title) { rules.rank(source.title) } }
                rank
            },
            sourceOrder.takeIf { sourceFirst }?.let { origins ->
                val ranks = java.util.IdentityHashMap<SourceItem, Int>()
                val rank: (SourceItem) -> Int = { source -> ranks.getOrPut(source) { SourceOrigins.rank(source, origins) } }
                rank
            }, prefs.getBoolean("most_grabs_first", false))
        set(value) { prefs.edit().putBoolean("quality_descending", value.qualityDescending).putBoolean("size_ascending", value.sizeAscending)
            .putBoolean("newest_first", value.newestFirst).putBoolean("most_grabs_first", value.mostGrabsFirst).putString("priority", value.priority.joinToString(",")).apply() }
    /** Links already in a library (AIOStreams, StreamNZB, AltMount) lead everything else, still in this sort order. */
    var libraryFirst: Boolean
        get() = prefs.getBoolean("library_first", true)
        set(value) { prefs.edit().putBoolean("library_first", value).apply() }
    /** The Preferred row: the viewer's words, ready-made switches and trusted release groups move matching links up. */
    var preferredFirst: Boolean
        get() = prefs.getBoolean("preferred_first", true)
        set(value) { prefs.edit().putBoolean("preferred_first", value).apply() }
    /** The Source row: rank links by where they come from (see [SourceOrigins]). Off by default. */
    var sourceFirst: Boolean
        get() = prefs.getBoolean("source_first", false)
        set(value) { prefs.edit().putBoolean("source_first", value).apply() }
    /** The Source row's order: debrid services, Easynews, Usenet and other add-ons, best first. */
    var sourceOrder: List<String>
        get() = SourceOrigins.order(prefs.getString("source_order", null))
        set(value) { prefs.edit().putString("source_order", SourceOrigins.order(value.joinToString(",")).joinToString(",")).apply() }
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
                SourceLanguageOrder(audio.language, SubtitlePreferences(context).language, if (audio.englishFallback) "en" else "",
                    // Latino first, then the other Spanish links, unless the viewer chose a second language.
                    second = audio.secondLanguage.takeIf { audio.language.isNotEmpty() }.orEmpty()
                        .ifEmpty { if (audio.language == dev.pov.tv.data.SourceLanguages.LATIN_SPANISH) "es" else "" })
            }.takeIf { it.active }
    private val languageLabel: String?
        get() = languages?.let { order -> if (order.audio.isNotEmpty()) L10n.tr(R.string.t_x_first, "%1\$s first", languageName(order.audio)) else L10n.tr(R.string.t_x_subtitles_first, "%1\$s subtitles first", languageName(order.subtitle)) }
    /** The sort in its chosen order, e.g. "Highest quality · English first · Smallest first". */
    val label: String get() = order.priority.mapNotNull { key ->
        when (key) {
            SourceSortOrder.LIBRARY -> L10n.tr(R.string.t_library_first, "Library first").takeIf { libraryFirst }
            SourceSortOrder.LANGUAGE -> languageLabel
            SourceSortOrder.PREFERRED -> L10n.tr(R.string.t_preferred_first, "Preferred first").takeIf { preferredFirst && rules.preferActive }
            SourceSortOrder.QUALITY -> if (order.qualityDescending) L10n.tr(R.string.t_highest_quality, "Highest quality") else L10n.tr(R.string.t_lowest_quality, "Lowest quality")
            SourceSortOrder.AGE -> L10n.tr(R.string.t_newest_posts_first, "Newest NZBs first").takeIf { order.newestFirst }
            SourceSortOrder.GRABS -> L10n.tr(R.string.gs_most_grabbed_first, "Most grabbed first").takeIf { order.mostGrabsFirst }
            SourceSortOrder.SOURCE -> L10n.tr(R.string.t_source_first, "Source first").takeIf { sourceFirst }
            else -> if (order.sizeAscending) L10n.tr(R.string.t_smallest_first, "Smallest first") else L10n.tr(R.string.t_largest_first, "Largest first")
        }
    }.joinToString(" · ")

    fun sorted(sources: List<SourceItem>): List<SourceItem> = languages?.sorted(sources, order) ?: order.sorted(sources)

    companion object {
        private const val EDIT_WIDTH = 72
        /** Every row's direction button (with Edit beside it on Preferred and Source) spans this width, so the columns line up. */
        private const val WAY_WIDTH = 176
        private fun languageName(code: String) = (AudioPreferences.LANGUAGES + SubtitlePreferences.LANGUAGES).firstOrNull { it.first == code }?.second ?: code

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
                L10n.tr(R.string.t_own_pattern, "own pattern").takeIf { rules.preferPattern.isNotBlank() }).joinToString(" · ")
                .ifEmpty { L10n.tr(R.string.t_nothing_yet_edit_adds_words_like, "Nothing yet: Edit adds words like PSA or FLUX, switches and release groups") }
        }

        /** The Source row's line: its first few origins, e.g. "TorBox → Easynews → Offcloud…". */
        fun sourceSummary(order: List<String>): String =
            order.take(3).joinToString(" → ") { SourceOrigins.name(it) } + if (order.size > 3) "…" else ""

        /**
         * The Source row's submenu: every origin (debrid services, Easynews, Usenet, other add-ons) in its rank, moved with
         * ▲ and ▼ like Link sorting itself. [closed] says whether the order changed.
         */
        fun showSourceOrder(context: Context, closed: (Boolean) -> Unit) {
            val preferences = SourceSortPreferences(context)
            showOrder(context, L10n.tr(R.string.t_source_order, "Source order"),
                L10n.tr(R.string.t_source_order_hint, "Links from sources higher in this list come first. Torrents not cached anywhere come last."),
                { preferences.sourceOrder }, SourceOrigins::name, { preferences.sourceOrder = it }, closed)
        }

        /** A list moved with ▲ and ▼ (Source order, Prefer order): [read] and [save] the order, [closed] says whether it changed. */
        fun showOrder(context: Context, title: String, hint: String, read: () -> List<String>, name: (String) -> String,
                      save: (List<String>) -> Unit, closed: (Boolean) -> Unit) {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
            var changed = false
            fun button(label: String, tagName: String, enabled: Boolean, action: () -> Unit) = TextView(context).apply {
                text = label; tag = tagName; gravity = Gravity.CENTER; includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
                // One line that shrinks to fit: long translations ("Disattivato", "Bearbeiten") stay inside the button.
                setPadding(dp(8), 0, dp(8), 0); minWidth = dp(46); maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
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
                list.addView(TextView(context).apply {
                    text = hint
                    setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                val order = read()
                order.forEachIndexed { index, id ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
                    row.addView(TextView(context).apply {
                        text = "${index + 1}.  ${name(id)}"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    fun move(step: Int) {
                        save(order.toMutableList().apply { add(index + step, removeAt(index)) }); changed = true
                        render(if (step < 0) "up:$id" else "down:$id")
                    }
                    row.addView(button("▲", "up:$id", index > 0) { move(-1) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(8) })
                    row.addView(button("▼", "down:$id", index < order.lastIndex) { move(1) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
                    list.addView(row)
                }
                // Focus follows the moved source; at the top or bottom the other arrow takes it.
                list.post {
                    val id = focus?.substringAfter(':') ?: order.firstOrNull() ?: return@post
                    listOfNotNull(focus, "down:$id", "up:$id").firstNotNullOfOrNull { tag -> list.findViewWithTag<View>(tag)?.takeIf { it.isFocusable } }?.requestFocus()
                }
            }
            render(null)
            val created = AlertDialog.Builder(context).setTitle(title)
                .setView(MaxHeightScrollView(context, MaxHeightScrollView.dialogListHeight(context)).apply { addView(list) })
                .setPositiveButton(L10n.tr(R.string.t_done, "Done"), null).create()
            created.setOnDismissListener { closed(changed) }
            created.show()
            created.window?.setLayout(minOf(dp(560), context.resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun show(context: Context, changed: () -> Unit, editPreferred: (() -> Unit)? = null) {
            val preferences = SourceSortPreferences(context)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val audio = AudioPreferences(context).language; val subtitle = SubtitlePreferences(context).language.takeUnless { it == "off" }.orEmpty()
            val languageSummary = listOfNotNull(audio.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} audio" },
                subtitle.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} subtitles" }).joinToString(", ").ifEmpty { L10n.tr(R.string.t_set_an_audio_or_subtitle_language, "set an audio or subtitle language in Settings") }
            fun name(key: String) = when (key) { SourceSortOrder.LIBRARY -> L10n.tr(R.string.t_library, "Library"); SourceSortOrder.LANGUAGE -> L10n.tr(R.string.t_language, "Language"); SourceSortOrder.PREFERRED -> L10n.tr(R.string.t_preferred, "Preferred"); SourceSortOrder.QUALITY -> L10n.tr(R.string.t_quality, "Quality"); SourceSortOrder.AGE -> L10n.tr(R.string.t_age, "NZB age"); SourceSortOrder.GRABS -> L10n.tr(R.string.gs_grabs, "Grabs"); SourceSortOrder.SOURCE -> L10n.tr(R.string.t_source, "Source"); else -> L10n.tr(R.string.t_file_size, "File size") }
            var dialog: AlertDialog? = null
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), dp(4)) }
            var modified = false

            fun button(label: String, tagName: String, enabled: Boolean, action: () -> Unit) = TextView(context).apply {
                text = label; tag = tagName; gravity = Gravity.CENTER; includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
                // One line that shrinks to fit: long translations ("Disattivato", "Bearbeiten") stay inside the button.
                setPadding(dp(8), 0, dp(8), 0); minWidth = dp(46); maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
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
                    if (key == SourceSortOrder.LANGUAGE || key == SourceSortOrder.LIBRARY || key == SourceSortOrder.PREFERRED || key == SourceSortOrder.AGE || key == SourceSortOrder.GRABS || key == SourceSortOrder.SOURCE) words.addView(TextView(context).apply {
                        text = when (key) { SourceSortOrder.LIBRARY -> L10n.tr(R.string.t_links_already_in_your_aiostreams_streamnzb, "Links already in your AIOStreams, StreamNZB or AltMount library")
                            SourceSortOrder.AGE -> L10n.tr(R.string.t_usenet_links_newer_posts_miss, "Usenet links: newer NZBs are less likely to miss articles")
                            SourceSortOrder.GRABS -> L10n.tr(R.string.gs_hint, "Usenet links: more grabs usually means a complete release")
                            SourceSortOrder.PREFERRED -> preferredSummary(preferences.rules)
                            SourceSortOrder.SOURCE -> sourceSummary(preferences.sourceOrder); else -> languageSummary }; setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = if (DeviceLayout.phone) 2 else 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(words, if (phone) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                    val direction = when (key) {
                        SourceSortOrder.LIBRARY -> if (preferences.libraryFirst) L10n.tr(R.string.t_first, "First") else L10n.tr(R.string.t_off, "Off")
                        SourceSortOrder.LANGUAGE -> if (preferences.languageFirst) L10n.tr(R.string.t_preferred_first, "Preferred first") else L10n.tr(R.string.t_off, "Off")
                        SourceSortOrder.PREFERRED -> if (preferences.preferredFirst) L10n.tr(R.string.t_first, "First") else L10n.tr(R.string.t_off, "Off")
                        SourceSortOrder.QUALITY -> if (order.qualityDescending) L10n.tr(R.string.t_highest_first, "Highest first") else L10n.tr(R.string.t_lowest_first, "Lowest first")
                        SourceSortOrder.AGE -> if (order.newestFirst) L10n.tr(R.string.t_newest_first, "Newest first") else L10n.tr(R.string.t_off, "Off")
                        SourceSortOrder.GRABS -> if (order.mostGrabsFirst) L10n.tr(R.string.gs_most_first, "Most first") else L10n.tr(R.string.t_off, "Off")
                        SourceSortOrder.SOURCE -> if (preferences.sourceFirst) L10n.tr(R.string.t_first, "First") else L10n.tr(R.string.t_off, "Off")
                        else -> if (order.sizeAscending) L10n.tr(R.string.t_smallest_first, "Smallest first") else L10n.tr(R.string.t_largest_first, "Largest first")
                    }
                    // The Preferred row's Edit shares the direction button's width, so every row's buttons line up.
                    val withEdit = (key == SourceSortOrder.PREFERRED && editPreferred != null) || key == SourceSortOrder.SOURCE
                    controls.addView(button(direction, "way:$key", true) {
                        when (key) {
                            SourceSortOrder.LIBRARY -> preferences.libraryFirst = !preferences.libraryFirst
                            SourceSortOrder.LANGUAGE -> preferences.languageFirst = !preferences.languageFirst
                            SourceSortOrder.PREFERRED -> preferences.preferredFirst = !preferences.preferredFirst
                            SourceSortOrder.QUALITY -> preferences.order = preferences.order.copy(qualityDescending = !order.qualityDescending)
                            SourceSortOrder.AGE -> preferences.order = preferences.order.copy(newestFirst = !order.newestFirst)
                            SourceSortOrder.GRABS -> preferences.order = preferences.order.copy(mostGrabsFirst = !order.mostGrabsFirst)
                            SourceSortOrder.SOURCE -> preferences.sourceFirst = !preferences.sourceFirst
                            else -> preferences.order = preferences.order.copy(sizeAscending = !order.sizeAscending)
                        }
                        modified = true; render("way:$key")
                    }, if (phone) LinearLayout.LayoutParams(0, dp(40), 1f) else LinearLayout.LayoutParams(dp(if (withEdit) WAY_WIDTH - 8 - EDIT_WIDTH else WAY_WIDTH), dp(40)).apply { leftMargin = dp(8) })
                    if (withEdit) controls.addView(button(L10n.tr(R.string.t_edit, "Edit"), "edit:$key", true) {
                        // Source: its own small list (TorBox, Easynews, Usenet...) over this one; Link sorting stays open.
                        if (key == SourceSortOrder.SOURCE) showSourceOrder(context) { changedOrder ->
                            if (changedOrder) { if (!preferences.sourceFirst) preferences.sourceFirst = true; modified = true }
                            render("edit:$key")
                        } else { dialog?.dismiss(); editPreferred?.invoke() }
                    }, LinearLayout.LayoutParams(dp(EDIT_WIDTH), dp(40)).apply { leftMargin = dp(8) })
                    fun move(step: Int) {
                        val keys = order.priority.toMutableList(); keys.add(index + step, keys.removeAt(index))
                        preferences.order = preferences.order.copy(priority = keys); modified = true
                        render(if (step < 0) "up:$key" else "down:$key")
                    }
                    controls.addView(button("▲", "up:$key", index > 0) { move(-1) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(8) })
                    controls.addView(button("▼", "down:$key", index < order.priority.lastIndex) { move(1) }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
                    if (phone) row.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    list.addView(row)
                }
                list.addView(TextView(context).apply {
                    text = L10n.tr(R.string.t_result_x, "Result: %1\$s", preferences.label); setTextColor(0xFFCFD5DE.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                // Focus follows the row that moved; at the top or bottom its ▲ or ▼ is off, so the other one takes it.
                if (focus != null) list.post {
                    val key = focus.substringAfter(':')
                    listOf(focus, "down:$key", "up:$key", "way:$key", "edit:$key").firstNotNullOfOrNull { tag -> list.findViewWithTag<View>(tag)?.takeIf { it.isFocusable } }?.requestFocus()
                }
            }
            render(null)
            val created = AlertDialog.Builder(context).setTitle(L10n.tr(R.string.t_link_sorting, "Link sorting")).setView(MaxHeightScrollView(context, MaxHeightScrollView.dialogListHeight(context)).apply { addView(list) })
                .setPositiveButton(L10n.tr(R.string.t_done, "Done"), null).setNeutralButton(L10n.tr(R.string.t_reset_to_default, "Reset to default"), null).create()
            dialog = created
            created.setOnDismissListener { if (modified) changed() }
            created.show()
            // Back to the default: library, language, then quality (highest first), then size (largest first).
            created.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                preferences.order = SourceSortOrder(); preferences.languageFirst = true; preferences.libraryFirst = true; preferences.preferredFirst = true
                preferences.sourceFirst = false; preferences.sourceOrder = emptyList(); modified = true
                render("way:${SourceSortOrder.LIBRARY}")
            }
            created.window?.setLayout(minOf(dp(720), context.resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            list.post { list.findViewWithTag<View>("way:${preferences.order.priority.first()}")?.requestFocus() }
        }
    }
}
