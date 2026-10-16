package com.autoscript.brand.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Same untransformed bitmap everywhere; only background/light layers are animated. */
open class ZhigouMarkView(context: Context) : View(context) {
    var brandState: BrandState = BrandState.IDLE
        set(value) {
            if (field == value) return
            field = value; phase = 0f; refreshAnimation(); invalidate()
        }
    var surface: BrandSurface = BrandSurface.FLOATING
        set(value) { field = value; invalidate() }
    private val mark: Bitmap = sharedMark(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskMode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val floatingTint = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        BrandMotion.FLOATING_COLOR_SCALE, 0f, 0f, 0f, BrandMotion.FLOATING_RED_BIAS,
        0f, BrandMotion.FLOATING_COLOR_SCALE, 0f, 0f, BrandMotion.FLOATING_GREEN_BIAS,
        0f, 0f, BrandMotion.FLOATING_COLOR_SCALE, 0f, BrandMotion.FLOATING_BLUE_BIAS,
        0f, 0f, 0f, 1f, 0f,
    )))
    private val logoBounds = RectF()
    private val ringBounds = RectF()
    private val counterRingBounds = RectF()
    private val shaderMatrix = Matrix()
    private var orb: Shader? = null
    private var halo: Shader? = null
    private var shine: Shader? = null
    private var movingGlow: Shader? = null
    private var warmGlow: Shader? = null
    private var successGlow: Shader? = null
    private var failureGlow: Shader? = null
    private var movingSheen: Shader? = null
    private var cometGlow: Shader? = null
    private var sidePx = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var visibleToUser = false
    private var lastFrameAt = 0L

    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = minOf(w, h).toFloat()
        if (side <= 0f) return
        sidePx = side
        val cx = w / 2f; val cy = h / 2f
        // A single uniform scale preserves the source canvas, speed bars and all negative space.
        logoBounds.set(cx - side * .37f, cy - side * .37f, cx + side * .37f, cy + side * .37f)
        ringBounds.set(cx - side * .455f, cy - side * .455f, cx + side * .455f, cy + side * .455f)
        counterRingBounds.set(cx - side * .436f, cy - side * .436f, cx + side * .436f, cy + side * .436f)
        orb = RadialGradient(cx - side * .12f, cy - side * .16f, side * .72f,
            intArrayOf(0xFFCE4BFF.toInt(), 0xFF830DFA.toInt(), 0xFF3911DA.toInt()), floatArrayOf(0f, .57f, 1f), Shader.TileMode.CLAMP)
        // Peak at the rim, not beneath the opaque sphere: this halo is visible outside it.
        halo = RadialGradient(cx, cy, side * .5f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x8F9B28FF.toInt(), 0xCCF68CFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .66f, .78f, .85f, 1f), Shader.TileMode.CLAMP)
        shine = LinearGradient(0f, 0f, side * .25f, 0f,
            intArrayOf(Color.TRANSPARENT, 0x80FFFFFF.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        movingGlow = RadialGradient(0f, 0f, side * .26f,
            intArrayOf(0xC5FFFFFF.toInt(), 0x72E9A5FF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        warmGlow = RadialGradient(0f, 0f, side * .34f,
            intArrayOf(0xB8FFD68A.toInt(), 0x52FFAE64.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
        successGlow = RadialGradient(0f, 0f, side * .38f,
            intArrayOf(0xB8C8FFE0.toInt(), 0x52A2FFD1.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        failureGlow = RadialGradient(0f, 0f, side * .38f,
            intArrayOf(0xB8FFB2C8.toInt(), 0x52FF5C8B.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        movingSheen = LinearGradient(-side, 0f, side, 0f,
            intArrayOf(Color.TRANSPARENT, 0x65FFFFFF.toInt(), 0xA5F5B7FF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .42f, .58f, 1f), Shader.TileMode.CLAMP)
        cometGlow = RadialGradient(0f, 0f, side * .042f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xA6E794FF.toInt(), Color.TRANSPARENT), floatArrayOf(0f, .24f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f; val cy = height / 2f
        paint.style = Paint.Style.FILL; paint.alpha = 255; paint.xfermode = null; paint.colorFilter = null
        paint.shader = halo
        val active = brandState == BrandState.RUNNING || brandState == BrandState.EXECUTING
        val haloAlpha = when (brandState) {
            BrandState.IDLE -> 120 + (42 * BrandMotion.pulse(phase)).toInt()
            BrandState.EXECUTING -> 210 - (90 * BrandMotion.ignition(phase)).toInt()
            BrandState.RUNNING -> 190 + (48 * BrandMotion.pulse(phase)).toInt()
            BrandState.PAUSED -> 175
            BrandState.FAILED -> 190
            BrandState.SUCCESS -> 220
        }
        paint.alpha = haloAlpha
        canvas.drawCircle(cx, cy, side * .5f, paint)
        paint.alpha = 255
        when (surface) {
            BrandSurface.FLOATING -> { paint.shader = orb; canvas.drawCircle(cx, cy, side * .418f, paint) }
            BrandSurface.LIGHT, BrandSurface.DARK -> {
                paint.shader = null
                paint.color = if (surface == BrandSurface.LIGHT) Color.WHITE else 0xFF0D1424.toInt()
                canvas.drawCircle(cx, cy, side * .43f, paint)
            }
            BrandSurface.TRANSPARENT -> Unit
        }
        if (surface == BrandSurface.FLOATING) drawBackgroundMotion(canvas, cx, cy, side)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = side * .011f
        paint.color = when (brandState) {
            BrandState.EXECUTING -> 0xFFFFB6F6.toInt()
            BrandState.PAUSED -> 0xFFFFC978.toInt()
            BrandState.FAILED -> 0xFFFF7B98.toInt()
            BrandState.SUCCESS -> 0xFFB9FFD8.toInt()
            else -> 0xF5FFE1FF.toInt()
        }
        canvas.drawCircle(cx, cy, side * .42f, paint)
        when (brandState) {
            BrandState.EXECUTING -> drawIgnition(canvas, cx, cy, phase)
            BrandState.RUNNING -> {
            drawComet(canvas, cx, cy, ringBounds, BrandMotion.orbitDegrees(phase),
                BrandMotion.tailSweep(phase), 0xFFFFC5FF.toInt(), 1f)
                drawComet(canvas, cx, cy, counterRingBounds, BrandMotion.counterOrbitDegrees(phase),
                    -32f, 0xFFD9C9FF.toInt(), .65f)
            }
            BrandState.PAUSED -> drawPausedMarker(canvas, cx, cy)
            BrandState.FAILED -> drawFailedMarker(canvas, cx, cy)
            BrandState.SUCCESS -> {
                paint.color = 0xFFD8FFE7.toInt(); paint.alpha = ((1f - phase) * 230).toInt()
                canvas.drawCircle(cx, cy, side * (.39f + .09f * phase), paint)
            }
            BrandState.IDLE -> Unit
        }
        paint.style = Paint.Style.FILL; paint.alpha = 255; paint.color = Color.WHITE
        val flowing = active
        val layer = if (flowing) canvas.saveLayer(logoBounds, null) else -1
        paint.colorFilter = if (surface == BrandSurface.FLOATING) floatingTint else null
        canvas.drawBitmap(mark, null, logoBounds, paint)
        paint.colorFilter = null
        if (flowing) {
            shaderMatrix.setTranslate(width * BrandMotion.sweep(phase), 0f)
            shine?.setLocalMatrix(shaderMatrix)
            paint.shader = shine; paint.xfermode = maskMode
            canvas.drawRect(logoBounds, paint)
            paint.shader = null; paint.xfermode = null
            canvas.restoreToCount(layer)
        }
    }

    /** Motion inside the orb gives each state a readable visual center. */
    private fun drawBackgroundMotion(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val orbRadius = side * .418f
        when (brandState) {
            BrandState.IDLE -> {
                val x = cx + side * (.22f * sin(phase * Math.PI * 2.0).toFloat())
                val y = cy + side * (.14f * cos(phase * Math.PI * 2.0).toFloat())
                shaderMatrix.setTranslate(x, y)
                movingGlow?.setLocalMatrix(shaderMatrix)
                paint.shader = movingGlow; paint.style = Paint.Style.FILL; paint.alpha = 150
                canvas.drawCircle(cx, cy, orbRadius, paint)
            }
            BrandState.EXECUTING -> {
                val p = BrandMotion.ignition(phase)
                shaderMatrix.setTranslate(cx, cy)
                movingGlow?.setLocalMatrix(shaderMatrix)
                paint.shader = movingGlow; paint.style = Paint.Style.FILL; paint.alpha = ((1f - p) * 190f).toInt()
                canvas.drawCircle(cx, cy, orbRadius, paint)
                paint.shader = null; paint.style = Paint.Style.STROKE
                paint.color = 0xFFFFC6F8.toInt(); paint.alpha = ((1f - p) * 155f).toInt()
                paint.strokeWidth = side * (.018f - .006f * p)
                canvas.drawCircle(cx, cy, side * (.12f + .25f * p), paint)
            }
            BrandState.RUNNING -> {
                val travel = (phase * side * 2.4f) - side * 1.2f
                shaderMatrix.setRotate(-18f, cx, cy)
                shaderMatrix.postTranslate(travel, 0f)
                movingSheen?.setLocalMatrix(shaderMatrix)
                paint.shader = movingSheen; paint.style = Paint.Style.FILL; paint.alpha = 170
                canvas.drawCircle(cx, cy, orbRadius, paint)
            }
            BrandState.PAUSED -> {
                shaderMatrix.setTranslate(cx, cy)
                warmGlow?.setLocalMatrix(shaderMatrix)
                paint.shader = warmGlow; paint.style = Paint.Style.FILL; paint.alpha = 155
                canvas.drawCircle(cx, cy, orbRadius, paint)
            }
            BrandState.SUCCESS -> {
                shaderMatrix.setTranslate(cx, cy)
                successGlow?.setLocalMatrix(shaderMatrix)
                paint.shader = successGlow; paint.style = Paint.Style.FILL
                paint.alpha = ((1f - phase) * 200f).toInt()
                canvas.drawCircle(cx, cy, orbRadius, paint)
            }
            BrandState.FAILED -> {
                shaderMatrix.setTranslate(cx, cy)
                failureGlow?.setLocalMatrix(shaderMatrix)
                paint.shader = failureGlow; paint.style = Paint.Style.FILL; paint.alpha = 170
                canvas.drawCircle(cx, cy, orbRadius, paint)
            }
        }
        paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL
    }

    /** A short ignition cycle: expanding waves make start distinct from orbiting execution. */
    private fun drawIgnition(canvas: Canvas, cx: Float, cy: Float, progress: Float) {
        val p = BrandMotion.ignition(progress)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFFB6F6.toInt(); paint.alpha = ((1f - p) * 245f).toInt()
        paint.strokeWidth = sidePx * (.020f - .010f * p)
        canvas.drawCircle(cx, cy, sidePx * (.425f + .105f * p), paint)
        paint.color = 0xFFFFE1FF.toInt(); paint.alpha = ((1f - p) * 220f).toInt()
        paint.strokeWidth = sidePx * .008f
        canvas.drawArc(ringBounds, -135f + p * 360f, 74f, false, paint)
        canvas.drawArc(counterRingBounds, 45f - p * 260f, 42f, false, paint)
        paint.style = Paint.Style.FILL; paint.alpha = ((1f - p) * 235f).toInt(); paint.color = 0xFFFFFFFF.toInt()
        val sparkRadius = sidePx * (.465f + .035f * p)
        val sparkAngle = Math.toRadians((-120f + p * 300f).toDouble())
        canvas.drawCircle(cx + sparkRadius * cos(sparkAngle).toFloat(), cy + sparkRadius * sin(sparkAngle).toFloat(), sidePx * .016f, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** Pause is static and amber: two broad opposing cuts read as a frozen ring. */
    private fun drawPausedMarker(canvas: Canvas, cx: Float, cy: Float) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFFC978.toInt(); paint.alpha = 245; paint.strokeWidth = sidePx * .026f
        canvas.drawArc(ringBounds, -118f, 72f, false, paint)
        canvas.drawArc(ringBounds, 62f, 72f, false, paint)
        paint.strokeWidth = sidePx * .010f; paint.alpha = 150
        canvas.drawArc(counterRingBounds, -118f, 72f, false, paint)
        canvas.drawArc(counterRingBounds, 62f, 72f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** Failure stays still but has a clear red crossed-ring signature. */
    private fun drawFailedMarker(canvas: Canvas, cx: Float, cy: Float) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFF7B98.toInt(); paint.alpha = 215; paint.strokeWidth = sidePx * .012f
        canvas.drawArc(ringBounds, 28f, 72f, false, paint)
        canvas.drawArc(ringBounds, 208f, 72f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** Effect-only coordinates. Canvas/bitmap transforms never touch the logo. */
    private fun drawComet(canvas: Canvas, cx: Float, cy: Float, bounds: RectF, angle: Float,
        sweep: Float, color: Int, intensity: Float) {
        paint.shader = null; paint.color = color; paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // Eight short segments form a fading, tapered tail without per-frame allocations.
        for (i in 0 until 8) {
            val t = (i + 1) / 8f
            paint.alpha = (t * t * 235 * intensity).toInt()
            paint.strokeWidth = sidePx * (.004f + .008f * t)
            canvas.drawArc(bounds, angle - sweep + sweep * i / 8f, sweep / 8f, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT; paint.style = Paint.Style.FILL; paint.alpha = (255 * intensity).toInt()
        val radians = Math.toRadians(angle.toDouble())
        val x = cx + bounds.width() * .5f * cos(radians).toFloat()
        val y = cy + bounds.height() * .5f * sin(radians).toFloat()
        val save = canvas.save()
        canvas.translate(x, y)
        paint.shader = cometGlow
        canvas.drawCircle(0f, 0f, sidePx * .042f, paint)
        paint.shader = null; paint.color = Color.WHITE
        canvas.drawCircle(0f, 0f, sidePx * .012f, paint)
        canvas.restoreToCount(save)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); visibleToUser = isShown; refreshAnimation() }
    override fun onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible; refreshAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) stopAnimation() else refreshAnimation()
    }
    private fun stopAnimation() { animator?.cancel(); animator?.removeAllUpdateListeners(); animator = null }
    private fun refreshAnimation() {
        stopAnimation()
        if (!isAttachedToWindow || !visibleToUser || windowVisibility != VISIBLE || !isShown) return
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return
        val duration = BrandMotion.durationMs(brandState)
        if (duration == 0L) return
        lastFrameAt = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            repeatCount = if (BrandMotion.repeats(brandState)) ValueAnimator.INFINITE else 0
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                // ValueAnimator remains system-driven; limit this small overlay to ~30fps.
                if (now - lastFrameAt >= 30L || it.animatedFraction == 1f) {
                    phase = it.animatedValue as Float; lastFrameAt = now; invalidate()
                }
            }
            start()
        }
    }

    companion object {
        @Volatile private var cachedMark: Bitmap? = null
        private fun sharedMark(context: Context): Bitmap = cachedMark ?: synchronized(this) {
            cachedMark ?: requireNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.zhigou_brand_mark,
                BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 })).also { cachedMark = it }
        }
    }
}
