package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Settings → Playback → Skip buttons (on by default) and Auto-play next episode (off by default). */
internal class SkipButtonPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pov_skip", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("buttons", true)
        set(value) { prefs.edit().putBoolean("buttons", value).apply() }
    var autoNext: Boolean
        get() = prefs.getBoolean("auto_next", false)
        set(value) { prefs.edit().putBoolean("auto_next", value).apply() }
}

/**
 * The player's Skip Intro / Recap / Credits and Next Episode button: a wide rounded outline in white with a soft glow
 * and a dark see-through middle. Focused (Up from the controls) the glass stays dark and the outline turns into a
 * glowing red-to-violet gradient, so selection shows without any white wash. An optional thin line along the bottom
 * shows the Next Episode countdown.
 */
internal class SkipPill(context: Context, @Suppress("UNUSED_PARAMETER") tv: Boolean) : FrameLayout(context) {
    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL); includeFontPadding = false
        gravity = Gravity.CENTER; maxLines = 1
    }
    private val glow = dp(9f)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(150, 8, 8, 12) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(3f) }
    private val shape = RectF()
    private var focused = false
    private var countdown: Float? = null
    var shownText: String = ""; private set

    init {
        // The glow is a blurred shadow around the outline, which needs software drawing; the view is small.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        isFocusable = true; isFocusableInTouchMode = false; isClickable = true; visibility = View.GONE; alpha = 0f
        val pad = glow.toInt()
        setPadding(pad + dp(30f).toInt(), pad, pad + dp(30f).toInt(), pad)
        minimumWidth = dp(210f).toInt() + pad * 2
        addView(label, LayoutParams(-2, -1, Gravity.CENTER))
        setOnFocusChangeListener { _, hasFocus -> focused = hasFocus; restyle() }
        restyle()
    }

    private fun restyle() {
        label.typeface = Typeface.create("sans-serif", if (focused) Typeface.BOLD else Typeface.NORMAL)
        border.strokeWidth = dp(if (focused) 3f else 2.5f)
        animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = glow + border.strokeWidth / 2
        shape.set(inset, inset, width - inset, height - inset)
        val radius = dp(12f)
        canvas.drawRoundRect(shape, radius, radius, fill)
        if (focused) {
            border.shader = LinearGradient(shape.left, 0f, shape.right, 0f, ACCENT_START, ACCENT_END, Shader.TileMode.CLAMP)
            border.setShadowLayer(glow * 1.15f, 0f, 0f, Color.argb(230, 255, 45, 110))
        } else {
            border.shader = null; border.color = Color.WHITE
            border.setShadowLayer(glow * 0.8f, 0f, 0f, Color.argb(170, 255, 255, 255))
        }
        canvas.drawRoundRect(shape, radius, radius, border)
        countdown?.let { progress ->
            // The countdown: a thin line along the bottom, inside the outline, growing left to right.
            val y = shape.bottom - dp(7f); val left = shape.left + radius; val right = shape.right - radius
            bar.shader = null; bar.color = Color.argb(60, 255, 255, 255)
            canvas.drawLine(left, y, right, y, bar)
            bar.shader = LinearGradient(left, 0f, right, 0f, ACCENT_START, ACCENT_END, Shader.TileMode.CLAMP)
            canvas.drawLine(left, y, left + (right - left) * progress.coerceIn(0f, 1f), y, bar)
        }
        super.onDraw(canvas)
    }

    /** [countdownProgress] 0..1 draws the countdown line; null hides it. */
    fun show(text: String, countdownProgress: Float?) {
        if (text != shownText) { label.text = text; shownText = text; contentDescription = text }
        if (countdownProgress != countdown) { countdown = countdownProgress; invalidate() }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            animate().cancel(); animate().alpha(1f).setDuration(180).start()
        }
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        shownText = ""; countdown = null
        animate().cancel()
        animate().alpha(0f).setDuration(160).withEndAction { if (alpha == 0f) visibility = View.GONE }.start()
    }

    val showing get() = visibility == View.VISIBLE && alpha > 0f

    private companion object {
        // Warm red to violet by default; a test build with its own brand colours uses those instead.
        private val customBrand = BuildConfig.BRAND_ACCENT != 0xFFE50914.toInt()
        val ACCENT_START = if (customBrand) BuildConfig.BRAND_ACCENT else Color.rgb(255, 45, 85)
        val ACCENT_END = if (customBrand) BuildConfig.BRAND_ACCENT_2 else Color.rgb(142, 45, 226)
    }
}
