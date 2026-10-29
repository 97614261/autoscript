package com.autoscript.runtime.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

internal class InputPointReticleView(context: Context, private val label: String, private val color: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    init {
        contentDescription = "$label 点准星；十字中心为实际坐标；拖动定位，点击设置；不会点击目标应用"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Match the integer center used when returning the physical selection coordinate.
        val x = (width / 2).toFloat()
        val y = (height / 2).toFloat()
        fun reticle(stroke: Float, ink: Int) {
            paint.color = ink
            paint.strokeWidth = stroke * density
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(x, y, 11f * density, paint)
            val gap = 3f * density
            val reach = 20f * density
            canvas.drawLine(x - reach, y, x - gap, y, paint)
            canvas.drawLine(x + gap, y, x + reach, y, paint)
            canvas.drawLine(x, y - reach, x, y - gap, paint)
            canvas.drawLine(x, y + gap, x, y + reach, paint)
        }
        reticle(4f, 0xDD14213D.toInt())
        reticle(2.5f, 0xFFFFFFFF.toInt())
        reticle(1.25f, color)
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFFFFFFF.toInt()
        paint.strokeWidth = density
        canvas.drawCircle(x, y, 1.25f * density, paint)

        paint.textSize = 10f * resources.displayMetrics.scaledDensity
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.strokeWidth = 2.5f * density
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(label, x + 13f * density, y - 12f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawText(label, x + 13f * density, y - 12f * density, paint)
    }
}
