package dev.pov.tv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The hold-OK menu (Play, Streams, More Info...): a dark card in the style of the player's panels that fades in and out,
 * with a white highlight gliding between its options.
 */
internal object HoldMenu {
    fun show(context: Context, title: String, options: List<Pair<String, () -> Unit>>): Dialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(context).apply {
            tag = "pov_hold_menu"; orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2A30.toInt(), 0xFF17171A.toInt())).apply {
                cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x2EFFFFFF)
            }
        }
        card.addView(TextView(context).apply {
            text = title; textSize = 18f; setTextColor(Color.WHITE); maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(6), 0, dp(6), dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val views = options.mapIndexed { index, (label, action) ->
            TextView(context).apply {
                tag = "pov_hold_option_$index"; text = label; textSize = 15f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL; minHeight = dp(46); setPadding(dp(16), dp(8), dp(16), dp(8))
                isFocusable = true; isFocusableInTouchMode = DeviceLayout.tv; isClickable = true
                setOnClickListener { dialog.dismiss(); action() }
            }.also { rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
        }
        val pill = SlidingFocusPill(rows, views, radius = dp(12).toFloat())
        views.forEach { view -> view.setOnFocusChangeListener { _, focused -> pill.focusChanged(view, focused) } }
        card.addView(rows, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.5f; gravity = Gravity.CENTER }
            setLayout(minOf(dp(440), (context.resources.displayMetrics.widthPixels * 0.9f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        QuickFade.attach(dialog, card)
        dialog.setOnShowListener { QuickFade.fadeIn(card); views.firstOrNull()?.let { first -> first.post { first.requestFocus() } } }
        dialog.show()
        return dialog
    }
}
