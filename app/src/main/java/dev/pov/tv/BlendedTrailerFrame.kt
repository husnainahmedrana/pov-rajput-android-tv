package dev.pov.tv

import android.content.Context
import android.graphics.*
import android.widget.FrameLayout

/** Alpha-feather the Home trailer into the existing artwork; no solid panel edges. */
internal class BlendedTrailerFrame(context:Context, private val blendEdges:Boolean):FrameLayout(context) {
    private val mask=Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var leftMask:Shader?=null
    private var bottomMask:Shader?=null
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w>0 && h>0 && blendEdges) {
            leftMask=LinearGradient(0f,0f,w*0.16f,0f,Color.TRANSPARENT,Color.WHITE,Shader.TileMode.CLAMP)
            bottomMask=LinearGradient(0f,h*0.82f,0f,h.toFloat(),Color.WHITE,Color.TRANSPARENT,Shader.TileMode.CLAMP)
        }
    }
    override fun dispatchDraw(canvas:Canvas) {
        if(!blendEdges) { super.dispatchDraw(canvas);return }
        val saved=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null)
        super.dispatchDraw(canvas)
        for(shader in listOf(leftMask,bottomMask)) if(shader!=null) {
            mask.shader=shader;canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),mask)
        }
        canvas.restoreToCount(saved)
    }
}
