package com.autoscript.script.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Native widgets and the designer canvas draw exactly the same selection marks. */
class ScriptUiIndicatorDrawable(private val scale: Float, private val circle: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    override fun getIntrinsicWidth() = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.INDICATOR, scale)
    override fun getIntrinsicHeight() = intrinsicWidth
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val checked = android.R.attr.state_checked in state
        val stroke = scale.coerceAtLeast(1f).coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f)
        val rect = RectF(bounds).apply { inset(stroke / 2, stroke / 2) }
        val radius = if (circle) minOf(rect.width(), rect.height()) / 2 else 3 * scale
        paint.color = ScriptUiControlVisuals.ACCENT; paint.alpha = opacity
        paint.style = if (checked && !circle) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (checked) {
            paint.style = Paint.Style.FILL
            if (circle) canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * .25f, paint)
            else {
                paint.color = Color.WHITE; paint.alpha = opacity; paint.style = Paint.Style.STROKE
                paint.strokeWidth = (1.8f * scale).coerceAtLeast(1f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                path.reset(); path.moveTo(rect.left + rect.width() * .23f, rect.centerY())
                path.lineTo(rect.left + rect.width() * .43f, rect.top + rect.height() * .7f)
                path.lineTo(rect.left + rect.width() * .78f, rect.top + rect.height() * .3f)
                canvas.drawPath(path, paint)
            }
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A foreground keeps arrows, pressed feedback and borders above images and child content. */
class ScriptUiOverlayDrawable(private val style: UiPresentation, private val scale: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    override fun isStateful() = style.control in setOf("BUTTON", "NAVIGATION")
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    private fun color(value: Int) { paint.color = value; paint.alpha = Color.alpha(value) * opacity / 255 }
    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        if (rect.isEmpty) return
        val radius = ScriptUiControlVisuals.cornerRadius(style.cornerPx * scale, rect.width(), rect.height())
        paint.style = Paint.Style.FILL
        if (isStateful && android.R.attr.state_enabled in state && android.R.attr.state_pressed in state) {
            color(ScriptUiControlVisuals.PRESSED); canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (style.control == "SELECT") {
            val x = rect.right - (style.visibleBorderWidthPx + ScriptUiControlMetrics.CHOICE_RESERVED / 2f) * scale
            val y = rect.centerY()
            color(Color.parseColor(style.textColor)); paint.style = Paint.Style.STROKE
            paint.strokeWidth = (1.5f * scale).coerceAtLeast(1f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            path.reset(); path.moveTo(x - 4 * scale, y - 2 * scale); path.lineTo(x, y + 2 * scale); path.lineTo(x + 4 * scale, y - 2 * scale)
            canvas.drawPath(path, paint)
        }
        if (style.visibleBorderWidthPx > 0) {
            val stroke = (style.visibleBorderWidthPx * scale).coerceAtLeast(1f).coerceAtMost(minOf(rect.width(), rect.height()))
            rect.inset(stroke / 2, stroke / 2)
            color(Color.parseColor(style.borderColor)); paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
            val innerRadius = (radius - stroke / 2).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, innerRadius, innerRadius, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
