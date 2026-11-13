package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A title, a message and two well-spaced buttons. Android's own dialog buttons sit flush against each other on a
 * phone (stacked "View update" / "Later"); these get a full-width column with a gap on the phone, a row on TV.
 */
internal object SpacedChoiceDialog {
    fun show(activity: Activity, title: String, message: String, primary: String, secondary: String, onPrimary: () -> Unit) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        var dialog: AlertDialog? = null
        fun button(label: String, main: Boolean, action: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(12), dp(18), dp(12)); minWidth = dp(120)
            isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
            contentDescription = label
            fun paint(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(when { focused -> Color.WHITE; main -> BuildConfig.BRAND_ACCENT; else -> 0x2EFFFFFF })
                    if (focused) setStroke(dp(2), BuildConfig.BRAND_ACCENT)
                }
                setTextColor(if (focused) Color.BLACK else if (main) BuildConfig.BRAND_ON_ACCENT else Color.WHITE)
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener { dialog?.dismiss(); action() }
        }
        val phone = DeviceLayout.phone
        val buttons = LinearLayout(activity).apply {
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(20), dp(4), dp(20), dp(16))
        }
        val main = button(primary, true, onPrimary)
        val other = button(secondary, false) {}
        if (phone) {
            buttons.addView(main, LinearLayout.LayoutParams(-1, -2))
            buttons.addView(other, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        } else {
            buttons.addView(other, LinearLayout.LayoutParams(-2, -2))
            buttons.addView(main, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }
        dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setView(buttons).create()
        dialog.setOnShowListener { main.requestFocus() }
        dialog.show()
    }
}
