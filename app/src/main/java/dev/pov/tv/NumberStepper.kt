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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A number picked with Up/Down on the remote (or the arrows on a phone) instead of typing it or choosing from a fixed
 * list: a large value with its unit, arrows above and below, and a bar showing where it sits between the lowest and
 * highest. Holding a key keeps counting and speeds up on long ranges. On TV, OK saves and Back cancels.
 */
internal object NumberStepper {
    private const val CARD = 0xFF1C1F26.toInt()
    private const val MUTED = 0xFF9AA3B2.toInt()
    private const val TRACK = 0x2EFFFFFF

    fun show(context: Context, title: String, detail: String, value: Int, range: IntRange,
             label: (Int) -> String = { it.toString() }, done: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); includeFontPadding = false
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        var current = value.coerceIn(range)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, 22, 1, 0x1FFFFFFF); setPadding(dp(28), dp(24), dp(28), dp(22))
        }
        card.addView(text(title, 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        if (detail.isNotBlank()) card.addView(text(detail, 13f, MUTED).apply { gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // The value: arrows above and below a large number with its unit underneath.
        val up = text("▲", 18f, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(6), dp(24), dp(6)) }
        val down = text("▼", 18f, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(6), dp(24), dp(6)) }
        val big = text("", 46f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val unit = text("", 14f, MUTED).apply { gravity = Gravity.CENTER }
        val dial = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(36), dp(14), dp(36), dp(16)); minimumWidth = dp(220)
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv
        }
        dial.addView(big, LinearLayout.LayoutParams(-2, -2))
        dial.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(up, LinearLayout.LayoutParams(-2, -2))
        column.addView(dial, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2); bottomMargin = dp(2) })
        column.addView(down, LinearLayout.LayoutParams(-2, -2))
        card.addView(column, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        // Where the value sits between the lowest and highest.
        val track = FrameLayout(context).apply { background = rounded(TRACK, 3) }
        val fill = View(context).apply { background = rounded(Color.WHITE, 3) }
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        card.addView(track, LinearLayout.LayoutParams(dp(280), dp(5)).apply { topMargin = dp(18) })
        val ends = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(text(label(range.first), 11f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        ends.addView(text(label(range.last), 11f, MUTED).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ends, LinearLayout.LayoutParams(dp(280), -2).apply { topMargin = dp(6) })

        fun paintDial(focused: Boolean) { dial.background = rounded(if (focused) 0x24FFFFFF else 0x14FFFFFF, 18, if (focused) 2 else 0, Color.WHITE) }
        fun render(animate: Boolean) {
            val shown = label(current)
            val number = Regex("^-?\\d+").find(shown)?.value
            big.text = number ?: shown
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (number != null) 46f else 32f)
            unit.text = if (number != null) shown.removePrefix(number).trim() else ""
            unit.visibility = if (unit.text.isNullOrBlank()) View.GONE else View.VISIBLE
            up.alpha = if (current < range.last) 1f else 0.25f; down.alpha = if (current > range.first) 1f else 0.25f
            dial.contentDescription = "$title: $shown"
            track.post {
                val span = (range.last - range.first).coerceAtLeast(1)
                fill.layoutParams = fill.layoutParams.apply { width = (track.width * (current - range.first) / span.toFloat()).roundToInt() }
                fill.requestLayout()
            }
            if (animate) { big.animate().cancel(); big.scaleX = 1.12f; big.scaleY = 1.12f; big.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }
        }
        fun step(by: Int) {
            val next = (current + by).coerceIn(range)
            if (next != current) { current = next; render(true) }
        }
        dial.setOnFocusChangeListener { _, focused -> paintDial(focused) }
        dial.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // Held down on a long range, it counts in fives after a moment, and in tens on a very long one; a single
            // press always moves by one, so any number can be picked.
            val span = range.last - range.first
            val size = when {
                event.repeatCount > 30 && span > 200 -> 10
                event.repeatCount > 6 && span > 20 -> 5
                else -> 1
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> { step(size); true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> { step(-size); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { done(current); dialog.dismiss(); true }
                else -> false
            }
        }
        up.setOnClickListener { step(1) }
        down.setOnClickListener { step(-1) }

        card.addView(text(if (DeviceLayout.tv) L10n.tr(R.string.t_change_ok_save_back_cancel, "▲ ▼  change      OK  save      Back  cancel") else L10n.tr(R.string.t_tap_or_to_change, "Tap ▲ or ▼ to change"), 12f, MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        // Touch screens get buttons; on TV, OK and Back do the same without leaving the number.
        if (!DeviceLayout.tv) {
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun pill(label: String, filled: Boolean, action: () -> Unit) = text(label, 15f, if (filled) Color.BLACK else Color.WHITE, true).apply {
                gravity = Gravity.CENTER; isClickable = true
                background = if (filled) rounded(Color.WHITE, 24) else rounded(Color.TRANSPARENT, 24, 1, 0x66FFFFFF)
                setOnClickListener { action() }
            }
            buttons.addView(pill(L10n.tr(R.string.t_cancel, "Cancel"), false) { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
            buttons.addView(pill(L10n.tr(R.string.t_save, "Save"), true) { done(current); dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(46), 1f))
            card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }

        val outer = FrameLayout(context).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        outer.addView(card, FrameLayout.LayoutParams(minOf(dp(440), context.resources.displayMetrics.widthPixels - dp(32)), -2, Gravity.CENTER))
        dialog.setContentView(outer)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        render(false); paintDial(false)
        dialog.show()
        dial.post { dial.requestFocus() }
    }
}
