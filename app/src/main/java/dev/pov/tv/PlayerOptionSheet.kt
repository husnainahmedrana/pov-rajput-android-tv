package dev.pov.tv

import android.app.Dialog
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class PlayerSheetChoice(
    val label: String,
    val checked: Boolean = false,
    val selectable: Boolean = true,
    /** A second, muted line and source badges (quality, size, HDR...) make a richer row, as in the source list. */
    val detail: String = "",
    val badges: List<SourceBadge> = emptyList(),
    /** Service logos (drawable ids) on the row's right, as in the source list: where a link is cached, or its add-on. */
    val logos: List<Int> = emptyList(),
    /** Holding OK on the row (e.g. a link: play it on another debrid service). */
    val hold: (() -> Unit)? = null,
    /** The link now playing: a green "Now playing" mark, and choosing it just closes the panel. */
    val playing: Boolean = false,
    val action: () -> Unit,
)

/** A single TV-focusable bottom sheet style for audio, subtitles, boost, and player messages. */
internal data class PlayerSheetColumn(val title: String, val detail: String, val choices: List<PlayerSheetChoice>)

internal object PlayerOptionSheet {
    fun create(context: Context, title: String, detail: String, choices: List<PlayerSheetChoice>, accent: Int): Dialog =
        create(context, title, detail, listOf(PlayerSheetColumn("", "", choices)), accent, 0)

    fun create(context: Context, title: String, detail: String, columns: List<PlayerSheetColumn>, accent: Int, focusColumn: Int): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            tag = "pov_player_sheet"; orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(22), dp(32), dp(24))
            // See-through like the Streams panel (80% black), so the video and subtitles show faintly behind; rounded top.
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0x2EFFFFFF)
            }
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply {
            text = title; textSize = 23f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val close = Button(context).apply {
            text = L10n.tr(R.string.t_done, "Done"); textSize = 13f; isAllCaps = false; minWidth = dp(90)
            setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(0x24FFFFFF); cornerRadius = dp(10).toFloat() }
            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply { setColor(if (focused) Color.WHITE else 0x24FFFFFF); cornerRadius = dp(10).toFloat() }
                setTextColor(if (focused) Color.BLACK else Color.WHITE)
            }
            setOnClickListener { dialog.dismiss() }
        }
        heading.addView(close, LinearLayout.LayoutParams(dp(96), dp(44)))
        content.addView(heading)
        if (detail.isNotBlank()) content.addView(TextView(context).apply {
            text = detail; textSize = 13f; setTextColor(0xFFC4C4C8.toInt())
            setPadding(0, dp(3), dp(12), dp(14))
        }, LinearLayout.LayoutParams(-1, -2))
        val split = columns.size > 1
        // Two columns (Audio | Subtitles) sit side by side in the landscape player; D-pad left/right moves between them.
        val holder = if (split) LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) } else content
        val rich = columns.any { column -> column.choices.any { it.badges.isNotEmpty() || it.detail.isNotBlank() } }
        val maxHeight = (context.resources.displayMetrics.heightPixels * (if (split) 0.5 else if (rich) 0.62 else 0.44)).toInt()
        val buttons = columns.mapIndexed { column, sheetColumn ->
            val choices = sheetColumn.choices
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val columnButtons = choices.mapIndexed { index, choice ->
                if (choice.badges.isNotEmpty() || choice.detail.isNotBlank()) return@mapIndexed richRow(context, dialog, choice,
                    if (split) "pov_sheet_choice_${column}_$index" else "pov_sheet_choice_$index").also {
                    rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
                Button(context).apply {
                    tag = if (split) "pov_sheet_choice_${column}_$index" else "pov_sheet_choice_$index"
                    text = if (choice.checked) "✓   ${choice.label}" else "     ${choice.label}"
                    contentDescription = choice.label + if (choice.checked) L10n.tr(R.string.t_selected_2, ", selected") else ""
                    textSize = 14f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    maxLines = 2; minHeight = dp(47); setPadding(dp(14), dp(7), dp(14), dp(7))
                    isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv
                    fun style(focused: Boolean) {
                        // Focused rows turn white with dark text; the current choice keeps a light outline.
                        setTextColor(if (focused) Color.BLACK else if (choice.selectable) Color.WHITE else 0xFF8E8E93.toInt())
                        background = GradientDrawable().apply {
                            setColor(if (focused) Color.WHITE else if (choice.checked) 0x33FFFFFF else 0x14FFFFFF)
                            cornerRadius = dp(10).toFloat()
                            if (!focused && choice.checked) setStroke(dp(1), 0x80FFFFFF.toInt())
                        }
                    }
                    style(false)
                    setOnFocusChangeListener { _, focused -> style(focused) }
                    // Unavailable formats remain focusable/readable, but cannot request playback.
                    setOnClickListener { if (choice.selectable) { dialog.dismiss(); choice.action() } }
                    choice.hold?.let { hold -> setOnLongClickListener { if (choice.selectable) { dialog.dismiss(); hold() }; true } }
                }.also { rows.addView(it, LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(6) }) }
            }
            val scroll = ScrollView(context).apply { isFillViewport = false; addView(rows) }
            val height = LinearLayout.LayoutParams(-1, minOf(dp(if (rich) 104 else 55) * choices.size, maxHeight).coerceAtLeast(dp(50)))
            if (!split) content.addView(scroll, height) else {
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                box.addView(TextView(context).apply {
                    text = sheetColumn.title; textSize = 16f; setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(-1, -2))
                if (sheetColumn.detail.isNotBlank()) box.addView(TextView(context).apply {
                    text = sheetColumn.detail; textSize = 12f; setTextColor(0xFFC4C4C8.toInt()); setPadding(0, dp(2), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                box.addView(scroll, height.apply { topMargin = dp(10) })
                holder.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { if (column > 0) leftMargin = dp(24) })
            }
            columnButtons
        }
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // No extra dimming: the sheet's own 80% black matches the Streams panel.
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
            // Full width from the first frame and a soft fade in and out instead of the zoom-in, so the sheet never opens
            // small and grows. (The player panels keep this softer fade; hold-OK menus use the quicker QuickFade.)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_Toast)
        }
        dialog.setOnShowListener {
            (context as? Activity)?.window?.decorView?.systemUiVisibility?.let { flags ->
                dialog.window?.decorView?.systemUiVisibility = flags
            }
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.window?.setGravity(Gravity.BOTTOM)
            val column = focusColumn.coerceIn(0, columns.lastIndex)
            val selected = columns[column].choices.indexOfFirst { it.checked }.takeIf { it >= 0 } ?: 0
            buttons[column].getOrNull(selected)?.let { button -> button.post { button.requestFocus() } }
        }
        return dialog
    }

    /**
     * A source-list style row: badges, the name, and a muted detail line. The badges have dark fills, so focus is a
     * light tint with a white outline rather than a white fill.
     */
    internal fun richRow(context: Context, dialog: Dialog, choice: PlayerSheetChoice, id: String): View {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        // The text column; logos (when any) sit on the right, centred, as on the source list's cards.
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        return LinearLayout(context).apply {
            tag = id; orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = (choice.badges.map { it.label } + listOf(choice.label, choice.detail)).filter(String::isNotBlank).joinToString(". ") +
                if (choice.checked) L10n.tr(R.string.t_playing_2, ", playing") else ""
            addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            if (choice.logos.isNotEmpty()) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                choice.logos.forEach { logo ->
                    addView(android.widget.ImageView(context).apply {
                        setImageResource(logo); adjustViewBounds = true; maxWidth = dp(70)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(-2, dp(22)).apply { leftMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            if (choice.playing) column.addView(TextView(context).apply {
                text = L10n.tr(R.string.t_now_playing_2, "▶  NOW PLAYING"); textSize = 12f; setTextColor(0xFF46D369.toInt()); letterSpacing = 0.08f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (choice.badges.isNotEmpty()) column.addView(SourceBadgeStrip(context, choice.badges), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            column.addView(TextView(context).apply {
                text = if (choice.checked) "✓  ${choice.label}" else choice.label
                textSize = 14f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))
            if (choice.detail.isNotBlank()) column.addView(TextView(context).apply {
                text = choice.detail; textSize = 12f; setTextColor(0xFFC4C4C8.toInt()); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            fun style(focused: Boolean) {
                background = GradientDrawable().apply {
                    // The source list's see-through cards: a dark glass fill, lighter when focused.
                    setColor(if (focused) 0xD93A3A40.toInt() else if (choice.checked) 0xB33A3A40.toInt()
                        else (ThemeSpec.FLIX.palette.panel and 0x00FFFFFF) or (0x99 shl 24))
                    cornerRadius = dp(10).toFloat()
                    if (focused) setStroke(dp(2), Color.WHITE) else if (choice.playing) setStroke(dp(2), 0xFF46D369.toInt())
                    else if (choice.checked) setStroke(dp(1), 0x80FFFFFF.toInt())
                }
            }
            style(false)
            setOnFocusChangeListener { _, focused -> style(focused) }
            setOnClickListener { if (choice.selectable) { dialog.dismiss(); choice.action() } }
            choice.hold?.let { hold -> setOnLongClickListener { if (choice.selectable) { dialog.dismiss(); hold() }; true } }
        }
    }
}

