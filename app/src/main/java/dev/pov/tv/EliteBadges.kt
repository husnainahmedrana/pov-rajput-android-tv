package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "Elite" badge style: logo-like resolution plates, metal wordmarks and small icons, all drawn here. It uses
 * no real Dolby, DTS or Blu-ray logos, only our own lettering, so the app can ship it publicly.
 */
internal object EliteBadges {
    private fun color(hex: String) = Color.parseColor(hex)
    private val CHROME = intArrayOf(color("#FFFFFF"), color("#F2F5F9"), color("#B8C0CC"), color("#FFFFFF"))
    private val BEVEL = floatArrayOf(0f, 0.45f, 0.55f, 1f)

    private class Plate(val background: IntArray, val stops: FloatArray, val edge: Int, val number: IntArray, val label: Int)
    private val PLATES = mapOf(
        SourceBadgeTone.UHD to Plate(intArrayOf(color("#FFF1A6"), color("#F3C437"), color("#B98510"), color("#F6D25A")), BEVEL, color("#6B4B00"),
            intArrayOf(color("#5A3C00"), color("#2A1A00"), color("#120A00"), color("#4A3000")), color("#1A1200")),
        SourceBadgeTone.FULL_HD to Plate(intArrayOf(color("#A8FFD8"), color("#22C98A"), color("#0E7A52"), color("#3FE0A2")), BEVEL, color("#064A31"),
            intArrayOf(color("#0B3A26"), color("#05180F"), color("#020A06"), color("#0B3A26")), color("#04150D")),
        SourceBadgeTone.HD to Plate(intArrayOf(color("#2B2F38"), color("#2B2F38"), color("#1F6FFF"), color("#1F6FFF")), floatArrayOf(0f, 0.55f, 0.55f, 1f),
            color("#9AA3B2"), intArrayOf(color("#FFFFFF"), color("#E3E7EE"), color("#9AA3B2"), color("#F1F4F8")), Color.WHITE),
    )
    private val SD_PLATE = Plate(intArrayOf(color("#2B2F38"), color("#2B2F38"), color("#9AA3B2"), color("#9AA3B2")), floatArrayOf(0f, 0.55f, 0.55f, 1f),
        color("#9AA3B2"), intArrayOf(color("#FFFFFF"), color("#E3E7EE"), color("#9AA3B2"), color("#F1F4F8")), Color.WHITE)

    /**
     * Each Elite badge sits in a rounded dark box with a thin edge, like the other styles' chips; resolution plates
     * already are their own box. The box takes the badge's tag so it can still be found.
     */
    fun boxed(context: Context, view: View, px: (Int) -> Int): View {
        if (view.background is BevelDrawable) return view
        return android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color("#D9161A22")); setStroke(px(1).coerceAtLeast(1), color("#4A5262")); cornerRadius = px(6).toFloat()
            }
            setPadding(px(7), px(1), px(7), px(1)); minimumHeight = px(28)
            tag = view.tag; contentDescription = view.contentDescription
            isFocusable = false; isClickable = false
            addView(view, android.widget.FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
        }
    }

    /** One badge (possibly several grouped tags) as Elite views. */
    fun views(context: Context, badge: SourceBadge, px: (Int) -> Int): List<View> = when (badge.tone) {
        SourceBadgeTone.UHD, SourceBadgeTone.FULL_HD, SourceBadgeTone.HD, SourceBadgeTone.OTHER -> listOf(plate(context, badge, px))
        // Size and bitrate: plain bold text tinted with its heat colour.
        SourceBadgeTone.SIZE -> listOf(text(context, badge.label, 13, px, solid = color(SourceBadges.heat(badge.mbps)?.first ?: "#7FD6FF")))
        SourceBadgeTone.LANGUAGE -> listOf(text(context, badge.label, 13, px, solid = color("#FFCF4D")))
        else -> badge.parts.map { part(context, it, px) }
    }

    private fun plate(context: Context, badge: SourceBadge, px: (Int) -> Int): View {
        val (number, label) = when (badge.label) {
            "4K" -> "4K" to "ULTRA HD"
            "1080p" -> "1080p" to "FULL HD"
            "720p" -> "720p" to "HD"
            else -> if (badge.label.matches(Regex("\\d{3,4}p"))) badge.label to "SD" else return text(context, badge.label, 13, px)
        }
        val style = PLATES[badge.tone] ?: SD_PLATE
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            minimumWidth = px(54); minimumHeight = px(28); setPadding(px(7), px(3), px(7), px(3))
            background = BevelDrawable(style.background, style.stops, style.edge, px(5).toFloat(), px(1).toFloat().coerceAtLeast(1.5f))
            addView(MetalText(context, style.number).apply {
                text = number; setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14).toFloat()); typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                setPadding(px(1), 0, px(1), 0)
            })
            addView(TextView(context).apply {
                text = label; setTextColor(style.label); includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(6).toFloat().coerceAtLeast(7f)); typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(1) })
            tag = "source-badge:${badge.label}"; contentDescription = "$number $label"
        }
    }

    /** One tag: a wordmark, a two-line mark with a small label, or an icon with a word. */
    private fun part(context: Context, tag: String, px: (Int) -> Int): View = when (tag) {
        "DV" -> stack(context, "DV", "VISION", px)
        "HDR10+" -> text(context, "HDR10+", 14, px, shader = Rainbow)
        "HDR10" -> text(context, "HDR10", 14, px, black = true, solid = color("#E8ECF2"))
        "HDR" -> stack(context, "HDR", "HIGH DYNAMIC", px)
        "Atmos" -> stack(context, "ATMOS", "3D AUDIO", px, spacing = 0.1f)
        "TrueHD" -> italic(context, "TRUEHD", intArrayOf(color("#9FD0FF"), color("#1F6FFF")), px)
        "DTS-HD MA" -> stack(context, "DTS·HD", "MASTER AUDIO", px)
        "DTS-HD" -> stack(context, "DTS·HD", "HIGH RES", px)
        "DTS:X", "DTS" -> text(context, tag, 14, px, black = true, metal = CHROME)
        "DDP" -> stack(context, "DIGITAL+", "SURROUND", px)
        "DD" -> stack(context, "DIGITAL", "SURROUND", px)
        "5.1", "7.1", "2.0" -> icon(context, EliteIcon.SPEAKER, color("#5CC8FF"), text(context, tag, 14, px, black = true, solid = color("#E8ECF2")), px)
        "HEVC" -> italic(context, "HEVC", intArrayOf(color("#B6FF7A"), color("#3FBF2A")), px)
        "H.264" -> italic(context, "AVC", intArrayOf(color("#D6B8FF"), color("#7B3DFF")), px)
        "AV1" -> italic(context, "AV1", intArrayOf(color("#FFD08A"), color("#FF7A45")), px)
        "REMUX" -> icon(context, EliteIcon.REMUX, color("#CFD5DE"), text(context, "REMUX", 12, px, solid = color("#E8ECF2"), spacing = 0.06f), px)
        "BLU-RAY" -> icon(context, EliteIcon.DISC, color("#3EA0FF"), text(context, "BLU-RAY", 12, px, solid = color("#5CC8FF")), px)
        "WEB-DL" -> icon(context, EliteIcon.CLOUD, color("#3EA0FF"), text(context, "WEB-DL", 12, px, solid = color("#5CC8FF")), px)
        "WEBRip" -> icon(context, EliteIcon.GLOBE, color("#3EA0FF"), text(context, "WEBRIP", 12, px, solid = color("#5CC8FF")), px)
        "10bit" -> icon(context, EliteIcon.DOTS, color("#FFCF4D"), text(context, "10BIT", 12, px, solid = color("#FFCF4D")), px)
        else -> text(context, tag.uppercase(), 13, px, black = true, metal = CHROME)
    }.apply { this.tag = "source-badge:$tag"; contentDescription = tag }

    private object Rainbow

    private fun text(context: Context, value: String, size: Int, px: (Int) -> Int, black: Boolean = false, solid: Int? = null,
                     metal: IntArray? = null, shader: Any? = null, spacing: Float = 0f): TextView {
        val view = when {
            metal != null -> MetalText(context, metal)
            shader === Rainbow -> MetalText(context, intArrayOf(color("#FF4F4F"), color("#FFB347"), color("#FFE34D"), color("#5FE36B"), color("#3EC6FF"), color("#9B6BFF"), color("#FF4FD8")), horizontal = true)
            else -> TextView(context).apply { setTextColor(solid ?: Color.WHITE) }
        }
        return view.apply {
            text = value; includeFontPadding = false; setSingleLine(true); letterSpacing = spacing
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
            typeface = Typeface.create(if (black || metal != null || shader != null) "sans-serif-black" else "sans-serif", if (black || metal != null || shader != null) Typeface.NORMAL else Typeface.BOLD)
            minHeight = px(26); gravity = Gravity.CENTER_VERTICAL
            // Gradient lettering would lose the edge of its last letter.
            setPadding(px(1), 0, px(3), 0)
        }
    }

    private fun italic(context: Context, value: String, colors: IntArray, px: (Int) -> Int) = MetalText(context, colors).apply {
        text = value; includeFontPadding = false; setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14).toFloat()); typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
        minHeight = px(26); gravity = Gravity.CENTER_VERTICAL
        // Slanted letters lean past the text box: keep room so the last one isn't cut off.
        setPadding(px(2), 0, px(5), 0)
    }

    private fun stack(context: Context, word: String, label: String, px: (Int) -> Int, spacing: Float = 0.02f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(26)
        val big = text(context, word, 13, px, metal = CHROME, spacing = spacing).apply { minHeight = 0 }
        val small = TextView(context).apply {
            text = label; setTextColor(color("#DDE2E9")); includeFontPadding = false; setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(6).toFloat().coerceAtLeast(7f)); typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); letterSpacing = 0.12f
        }
        // Each line gets the width its own text needs, so a long word (DIGITAL+) is never cut to its label's width.
        fun width(view: TextView) = (kotlin.math.ceil(view.paint.measureText(view.text.toString()).toDouble()).toInt() + view.paddingLeft + view.paddingRight + px(1))
        addView(big, LinearLayout.LayoutParams(width(big), -2))
        addView(small, LinearLayout.LayoutParams(width(small), -2).apply { topMargin = px(1) })
    }

    private fun icon(context: Context, kind: EliteIcon, tint: Int, label: TextView, px: (Int) -> Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(26)
        addView(EliteIconView(context, kind, tint), LinearLayout.LayoutParams(px(16), px(16)).apply { rightMargin = px(4) })
        addView(label)
    }
}

/** Text painted with a metal (or any) gradient, top to bottom, or left to right when [horizontal]. */
internal class MetalText(context: Context, private val colors: IntArray, private val horizontal: Boolean = false) : TextView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stops = if (colors.size == 4 && !horizontal) floatArrayOf(0f, 0.45f, 0.55f, 1f) else null
        paint.shader = if (horizontal) LinearGradient(0f, 0f, w.toFloat(), 0f, colors, stops, Shader.TileMode.CLAMP)
            else LinearGradient(0f, paddingTop.toFloat(), 0f, (h - paddingBottom).toFloat(), colors, stops, Shader.TileMode.CLAMP)
        invalidate()
    }
}

/** A rounded plate with a sharp bevel (the gradient's stops) and a thin dark edge. */
internal class BevelDrawable(private val colors: IntArray, private val stops: FloatArray, private val edge: Int,
                             private val radius: Float, private val stroke: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = edge; strokeWidth = stroke }
    override fun draw(canvas: Canvas) {
        val box = RectF(bounds)
        fill.shader = LinearGradient(0f, box.top, 0f, box.bottom, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, radius, radius, fill)
        box.inset(stroke / 2, stroke / 2)
        canvas.drawRoundRect(box, radius, radius, line)
    }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter = filter; line.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal enum class EliteIcon { SPEAKER, REMUX, DISC, CLOUD, GLOBE, DOTS }

/** Small line icons on a 24-unit grid, drawn rather than shipped as images. */
internal class EliteIconView(context: Context, private val kind: EliteIcon, tint: Int) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat(); if (size <= 0f) return
        canvas.save(); canvas.translate((width - size) / 2, (height - size) / 2); canvas.scale(size / 24f, size / 24f)
        ink.style = Paint.Style.STROKE
        when (kind) {
            EliteIcon.SPEAKER -> {
                ink.style = Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }, ink)
                ink.style = Paint.Style.STROKE
                canvas.drawArc(RectF(11f, 8f, 17f, 16f), -60f, 120f, false, ink)
                canvas.drawArc(RectF(11f, 4.5f, 21f, 19.5f), -60f, 120f, false, ink)
            }
            EliteIcon.REMUX -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), 200f, 130f, false, ink)
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), 20f, 130f, false, ink)
                canvas.drawPath(Path().apply { moveTo(18f, 3f); lineTo(18.5f, 7.5f); lineTo(14f, 7.5f) }, ink)
                canvas.drawPath(Path().apply { moveTo(6f, 21f); lineTo(5.5f, 16.5f); lineTo(10f, 16.5f) }, ink)
            }
            EliteIcon.DISC -> { canvas.drawCircle(12f, 12f, 9f, ink); canvas.drawCircle(12f, 12f, 2.5f, ink) }
            EliteIcon.CLOUD -> {
                canvas.drawPath(Path().apply {
                    moveTo(7f, 17f); cubicTo(3.5f, 17f, 3f, 11.5f, 6.5f, 10.5f); cubicTo(7.5f, 5.5f, 15.5f, 5f, 17f, 10f)
                    cubicTo(20.5f, 10.3f, 20.8f, 16.6f, 17f, 17f)
                }, ink)
                canvas.drawLine(12f, 11f, 12f, 19f, ink)
                canvas.drawPath(Path().apply { moveTo(9f, 16f); lineTo(12f, 19f); lineTo(15f, 16f) }, ink)
            }
            EliteIcon.GLOBE -> {
                canvas.drawCircle(12f, 12f, 9f, ink); canvas.drawLine(3f, 12f, 21f, 12f, ink)
                canvas.drawOval(RectF(8f, 3f, 16f, 21f), ink)
            }
            EliteIcon.DOTS -> {
                ink.style = Paint.Style.FILL
                canvas.drawCircle(12f, 12f, 2.2f, ink)
                for (step in 0 until 8) {
                    val angle = Math.toRadians(step * 45.0)
                    canvas.drawCircle(12f + 8f * Math.cos(angle).toFloat(), 12f + 8f * Math.sin(angle).toFloat(), 1.3f, ink)
                }
            }
        }
        canvas.restore()
    }
}
