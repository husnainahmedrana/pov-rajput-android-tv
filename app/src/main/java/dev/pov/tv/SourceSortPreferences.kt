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
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.SourceLanguageOrder
import dev.pov.tv.data.SourceSortOrder

internal class SourceSortPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("pov_sources", Context.MODE_PRIVATE)
    var order: SourceSortOrder
        get() = SourceSortOrder(prefs.getBoolean("quality_descending", true), prefs.getBoolean("size_ascending", true),
            SourceSortOrder.priority(prefs.getString("priority", null)))
        set(value) { prefs.edit().putBoolean("quality_descending", value.qualityDescending).putBoolean("size_ascending", value.sizeAscending)
            .putString("priority", value.priority.joinToString(",")).apply() }
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
            SourceSortOrder.LANGUAGE -> languageLabel
            SourceSortOrder.QUALITY -> if (order.qualityDescending) "Highest quality" else "Lowest quality"
            else -> if (order.sizeAscending) "Smallest first" else "Largest first"
        }
    }.joinToString(" · ")

    fun sorted(sources: List<SourceItem>): List<SourceItem> = languages?.sorted(sources, order) ?: order.sorted(sources)

    companion object {
        private fun languageName(code: String) = SubtitlePreferences.LANGUAGES.firstOrNull { it.first == code }?.second ?: code

        /**
         * Link sorting, like Debrid services: Language, Quality and Size as rows in their order. ▲ and ▼ move a row,
         * the button beside it flips its direction, and the line below shows the result. Closing it re-sorts the links.
         */
        fun show(context: Context, changed: () -> Unit) {
            val preferences = SourceSortPreferences(context)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            val audio = AudioPreferences(context).language; val subtitle = SubtitlePreferences(context).language.takeUnless { it == "off" }.orEmpty()
            val languageSummary = listOfNotNull(audio.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} audio" },
                subtitle.takeIf { it.isNotEmpty() }?.let { "${languageName(it)} subtitles" }).joinToString(", ").ifEmpty { "set an audio or subtitle language in Settings" }
            fun name(key: String) = when (key) { SourceSortOrder.LANGUAGE -> "Language"; SourceSortOrder.QUALITY -> "Quality"; else -> "File size" }
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
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
                    val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    words.addView(TextView(context).apply {
                        text = "${index + 1}.  ${name(key)}"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    if (key == SourceSortOrder.LANGUAGE) words.addView(TextView(context).apply {
                        text = languageSummary; setTextColor(0xFF9AA3B2.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                    val direction = when (key) {
                        SourceSortOrder.LANGUAGE -> if (preferences.languageFirst) "Preferred first" else "Off"
                        SourceSortOrder.QUALITY -> if (order.qualityDescending) "Highest first" else "Lowest first"
                        else -> if (order.sizeAscending) "Smallest first" else "Largest first"
                    }
                    row.addView(button(direction, "way:$key", true) {
                        when (key) {
                            SourceSortOrder.LANGUAGE -> preferences.languageFirst = !preferences.languageFirst
                            SourceSortOrder.QUALITY -> preferences.order = preferences.order.copy(qualityDescending = !order.qualityDescending)
                            else -> preferences.order = preferences.order.copy(sizeAscending = !order.sizeAscending)
                        }
                        modified = true; render("way:$key")
                    }, LinearLayout.LayoutParams(dp(150), dp(40)).apply { leftMargin = dp(8) })
                    fun move(step: Int) {
                        val keys = order.priority.toMutableList(); keys.add(index + step, keys.removeAt(index))
                        preferences.order = preferences.order.copy(priority = keys); modified = true
                        render(if (step < 0) "up:$key" else "down:$key")
                    }
                    row.addView(button("▲", "up:$key", index > 0) { move(-1) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
                    row.addView(button("▼", "down:$key", index < order.priority.lastIndex) { move(1) }, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(6) })
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
            val dialog = AlertDialog.Builder(context).setTitle("Link sorting").setView(list)
                .setPositiveButton("Done", null).setNeutralButton("Reset to default", null).create()
            dialog.setOnDismissListener { if (modified) changed() }
            dialog.show()
            // Back to the default: language, then quality (highest first), then size (smallest first).
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                preferences.order = SourceSortOrder(); preferences.languageFirst = true; modified = true
                render("way:${SourceSortOrder.LANGUAGE}")
            }
            dialog.window?.setLayout(minOf(dp(720), context.resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            list.post { list.findViewWithTag<View>("way:${preferences.order.priority.first()}")?.requestFocus() }
        }
    }
}
