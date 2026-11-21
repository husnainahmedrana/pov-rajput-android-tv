package dev.pov.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen card while a link is opened for another player (Kodi, Just Player…): from the moment the link is picked
 * until the other app is on screen, so picking a link never looks like nothing happened. The title, "Opening in Kodi",
 * Nabrio's frame strip (a travelling pulse while connecting, then filling as the start of the video arrives), the status
 * line and the Back key as a hint. Shown over whatever page the link was picked on. With a catalog title it looks like
 * the built-in player's loading screen: the title's logo pulsing over its blurred artwork.
 */
internal class ExternalOpeningOverlay(private val activity: Activity, private val accent: Int, title: String, player: String,
                                      private val imdbId: String = "") {
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val frames = List(FRAMES) { View(activity).apply { background = tile(0x26FFFFFF) } }
    private val line = TextView(activity).apply { setTextColor(0xFF9AA3AF.toInt()); textSize = 16f; gravity = Gravity.CENTER }
    private var root: FrameLayout? = null
    private var pulse: ValueAnimator? = null
    private var breathe: ValueAnimator? = null
    private var lit = -1
    private val art = android.widget.ImageView(activity).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f
        if (android.os.Build.VERSION.SDK_INT >= 31)
            setRenderEffect(android.graphics.RenderEffect.createBlurEffect(14 * density, 14 * density, android.graphics.Shader.TileMode.CLAMP))
    }
    private val logo = android.widget.ImageView(activity).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    val showing: Boolean get() = root != null

    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(logo, LinearLayout.LayoutParams(dp(420), dp(160)))
        addView(TextView(activity).apply {
            tag = "title"
            text = title; setTextColor(Color.WHITE); textSize = 34f; gravity = Gravity.CENTER; maxLines = 2
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(dp(40), 0, dp(40), 0)
        })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.t_opening_in_x, player); setTextColor(0xFFCBD2DA.toInt()); textSize = 17f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            frames.forEachIndexed { index, frame -> addView(frame, LinearLayout.LayoutParams(dp(12), dp(20)).apply { if (index > 0) leftMargin = dp(5) }) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(30) })
        addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "BACK"; setTextColor(0xFFE6EAF0.toInt()); textSize = 11f; letterSpacing = 0.08f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setStroke(dp(1), 0x99E6EAF0.toInt()) }
            })
            addView(TextView(activity).apply { text = activity.getString(R.string.t_cancel); setTextColor(0xFF9AA3AF.toInt()); textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
    }

    /** Shows (or updates) the card: [percent] of the video's start read, or -1 while still connecting. */
    fun show(status: String, percent: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (root == null) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            root = FrameLayout(activity).apply {
                setBackgroundColor(0xF00A0C10.toInt()); isClickable = true; isFocusable = true; alpha = 0f
                addView(art, FrameLayout.LayoutParams(-1, -1))
                addView(View(activity).apply {
                    background = GradientDrawable().apply {
                        gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = activity.resources.displayMetrics.widthPixels * 0.7f
                        colors = intArrayOf(0x33000000, 0xE6000000.toInt())
                    }
                }, FrameLayout.LayoutParams(-1, -1))
                addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }
            loadArtwork()
            // The logo (or the title until it arrives) breathes, as on the player's loading screen.
            breathe = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { val value = it.animatedValue as Float; logo.alpha = value; card.findViewWithTag<View>("title")?.alpha = value }
                start()
            }
            content.addView(root, ViewGroup.LayoutParams(-1, -1))
            root!!.requestFocus()
            root!!.animate().alpha(1f).setDuration(160).start()
        }
        line.text = if (percent in 0..99) "$status  $percent%" else status
        if (percent < 0) travel() else fill(percent)
    }

    /** The title's blurred artwork and logo (the same images the player's loading screen uses), when it has an IMDb id. */
    private fun loadArtwork() {
        if (!imdbId.matches(Regex("tt\\d+"))) return
        fun load(address: String, done: (android.graphics.Bitmap) -> Unit) = Thread {
            try {
                ArtworkClient.init(activity.applicationContext)
                val bytes = ArtworkClient.bytes(address)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) activity.runOnUiThread { if (root != null && !activity.isDestroyed) done(bitmap) }
            } catch (_: Exception) { /* The title text stays. */ }
        }.apply { isDaemon = true }.start()
        load("https://images.metahub.space/background/medium/$imdbId/img") { art.setImageBitmap(it); art.animate().alpha(0.5f).setDuration(300).start() }
        load("https://images.metahub.space/logo/medium/$imdbId/img") {
            logo.setImageBitmap(it); logo.visibility = View.VISIBLE; card.findViewWithTag<View>("title")?.visibility = View.GONE
        }
    }

    fun dismiss() {
        pulse?.cancel(); pulse = null
        breathe?.cancel(); breathe = null
        val view = root ?: return
        root = null
        view.animate().alpha(0f).setDuration(160).withEndAction { (view.parent as? ViewGroup)?.removeView(view) }.start()
    }

    /** Connecting: one lit frame travels along the strip. */
    private fun travel() {
        if (pulse != null) return
        pulse = ValueAnimator.ofInt(0, FRAMES - 1).apply {
            duration = 1_400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val at = animator.animatedValue as Int
                frames.forEachIndexed { index, frame ->
                    val distance = kotlin.math.abs(index - at)
                    frame.background = tile(if (distance == 0) accent else 0x26FFFFFF)
                    frame.alpha = if (distance <= 2) 1f - distance * 0.25f else 1f
                }
            }
            start()
        }
    }

    /** Reading the start of the video: the strip fills with the theme's colour. */
    private fun fill(percent: Int) {
        pulse?.cancel(); pulse = null
        val now = (percent * FRAMES / 100).coerceIn(0, FRAMES)
        if (now == lit) return
        lit = now
        frames.forEachIndexed { index, frame -> frame.alpha = 1f; frame.background = tile(if (index < now) accent else 0x26FFFFFF) }
    }

    private fun tile(color: Int) = GradientDrawable().apply { cornerRadius = 2 * density; setColor(color) }

    private companion object { const val FRAMES = 24 }
}
