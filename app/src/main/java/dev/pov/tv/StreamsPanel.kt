package dev.pov.tv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The player's Streams panel: a see-through layer over the playing video, the title and a column of sources on the
 * left (All, then each add-on with its count), and the links on the right with the playing one pinned first.
 * Moving onto a source filters the links straight away; Right goes into them, Left back to the sources, and Back
 * shows all links again before it closes the panel. On a phone the sources are tapped.
 */
internal object StreamsPanel {
    /** One link: the add-ons that returned it (a file several found is one link), its row, and whether it is playing. */
    data class Entry(val sources: List<String>, val choice: PlayerSheetChoice, val playing: Boolean)

    fun create(context: Context, title: String, entries: List<Entry>): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // About 80% black: the video keeps playing faintly behind the panel.
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(32), dp(28), dp(28), dp(20))
        }
        // Each add-on once, busiest first; a link found by several counts under each of them.
        val sources = entries.flatMap { it.sources }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title, 26f, Color.WHITE, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2))
        left.addView(text("${sources.size} ${if (sources.size == 1) "add-on" else "add-ons"} · ${entries.size} streams", 15f, 0xFFB8C0CC.toInt()),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(18) })
        val sourceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(sourceList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(dp(if (DeviceLayout.phone) 220 else 300), -1))

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), dp(4), dp(4)) }
        val scroll = ScrollView(context).apply { addView(rows); clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(28) })
        val linkViews = entries.mapIndexed { index, entry ->
            PlayerOptionSheet.richRow(context, dialog, entry.choice, "pov_stream_$index").also {
                rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }

        var filter: String? = null
        val sourceRows = mutableListOf<TextView>()
        val painters = mutableListOf<() -> Unit>()
        fun firstVisibleLink(): View? = linkViews.firstOrNull { it.visibility == View.VISIBLE }
        fun apply(next: String?) {
            filter = next
            // The playing link stays pinned at the top whatever the filter.
            entries.forEachIndexed { index, entry -> linkViews[index].visibility = if (next == null || next in entry.sources || entry.playing) View.VISIBLE else View.GONE }
            painters.forEach { it() }
            scroll.scrollTo(0, 0)
        }
        fun sourceRow(key: String, label: String, count: Int): TextView {
            val row = text("$label   ·   $count", 17f, Color.WHITE).apply {
                tag = key; isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                setPadding(dp(18), dp(12), dp(18), dp(12)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            painters += {
                val chosen = (filter ?: "") == key
                row.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat()
                    setColor(if (row.isFocused) Color.WHITE else if (chosen) 0x33FFFFFF else Color.TRANSPARENT) }
                row.setTextColor(if (row.isFocused) Color.BLACK else Color.WHITE)
            }
            // TV: landing on a source filters at once. Phone: a tap filters, a second tap on it shows all again.
            row.setOnFocusChangeListener { _, focused -> if (focused && DeviceLayout.tv) apply(key.ifEmpty { null }) else painters.forEach { it() } }
            row.setOnClickListener { apply(if (!DeviceLayout.tv && key.isNotEmpty() && filter == key) null else key.ifEmpty { null }) }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { firstVisibleLink()?.requestFocus(); true } else false
            }
            return row
        }
        sourceRows += sourceRow("", "All", entries.size).also { sourceList.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
        sources.forEach { source ->
            sourceRows += sourceRow(source, source, entries.count { source in it.sources }).also { sourceList.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
        }
        // Left from a link goes back to the source that is showing.
        linkViews.forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    (sourceRows.firstOrNull { it.tag == (filter ?: "") } ?: sourceRows.first()).requestFocus(); true
                } else false
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            // Back shows all links again first, then closes the panel.
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && filter != null) {
                apply(null); sourceRows.first().let { row -> if (row.isFocused) row.requestFocus() }; true
            } else keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && filter != null
        }

        val frame = FrameLayout(context)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(android.R.style.Animation_Toast)
        }
        painters.forEach { it() }
        dialog.setOnShowListener {
            (context as? android.app.Activity)?.window?.decorView?.systemUiVisibility?.let { flags -> dialog.window?.decorView?.systemUiVisibility = flags }
            // Start on the playing link (it is first), else the first link.
            (linkViews.firstOrNull() ?: sourceRows.first()).requestFocus()
        }
        return dialog
    }
}
