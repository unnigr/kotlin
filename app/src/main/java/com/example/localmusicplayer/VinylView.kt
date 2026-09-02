package com.example.localmusicplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.LinearGradient
import android.util.AttributeSet
import android.view.View

class VinylView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rotation = 0f
    private var spinning = false
    private val runnable = object : Runnable { override fun run() { if (spinning) { rotation = (rotation + 2.2f) % 360f; invalidate() }; postDelayed(this, 16) } }
    init { post(runnable) }
    fun setPlaying(value: Boolean) { spinning = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = (minOf(width, height) * 0.36f)
        paint.shader = LinearGradient(cx-r, cy-r, cx+r, cy+r, 0xFF202027.toInt(), 0xFF050507.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = 0xFF33333A.toInt()
        for (i in 1..7) canvas.drawCircle(cx, cy, r * (i/8f), paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFF111117.toInt(); canvas.drawCircle(cx, cy, r*0.25f, paint)
        paint.color = 0xFFEDEDED.toInt(); canvas.drawCircle(cx, cy, r*0.08f, paint)
    }
}
