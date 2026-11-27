package dev.pov.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.View

/** A round full-screen button: four corner brackets, drawn (no font has to carry the symbol). */
internal class FullscreenIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val path = Path()

    init {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80000000.toInt()) }
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val inset = w * 0.3f; val arm = w * 0.14f
        val l = inset; val t = h * 0.3f; val r = w - inset; val b = h - h * 0.3f
        path.reset()
        path.moveTo(l, t + arm); path.lineTo(l, t); path.lineTo(l + arm, t)
        path.moveTo(r - arm, t); path.lineTo(r, t); path.lineTo(r, t + arm)
        path.moveTo(r, b - arm); path.lineTo(r, b); path.lineTo(r - arm, b)
        path.moveTo(l + arm, b); path.lineTo(l, b); path.lineTo(l, b - arm)
        canvas.drawPath(path, paint)
    }
}
