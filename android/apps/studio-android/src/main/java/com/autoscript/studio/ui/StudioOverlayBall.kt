package com.autoscript.studio

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.autoscript.brand.ui.BrandState
import com.autoscript.brand.ui.ZhigouMarkView
import kotlin.math.abs
import kotlin.math.roundToInt

/** A separate bounded window: no transparent touch-intercepting bridge to the editor panel. */
internal class StudioOverlayBall(context: Context, private val onClick: () -> Unit, private val onFailure: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val size = (35 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mounted = false
    private var failed = false
    private var visible = false
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false
    private val params = WindowManager.LayoutParams(size, size, editorOverlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) { setFitInsetsTypes(0); setFitInsetsSides(0) }
    }
    private val image = OverlayBallImage(context).apply {
        contentDescription = "智构 · 恢复窗口"
        setOnClickListener { onClick() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val screen = screen()
                        params.x = (originX + dx.roundToInt()).coerceIn(0, (screen.x - size).coerceAtLeast(0))
                        params.y = (originY + dy.roundToInt()).coerceIn(0, (screen.y - size).coerceAtLeast(0))
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val screen = screen()
                        params.x = if (params.x + size / 2 < screen.x / 2) 0 else (screen.x - size).coerceAtLeast(0)
                        update()
                    } else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
    }
    private fun screen() = Point().also { @Suppress("DEPRECATION") windows.defaultDisplay.getRealSize(it) }
    fun setBrandState(state: BrandState) { image.brandState = state }
    fun show(value: Boolean) {
        if (failed) return
        visible = value
        if (!mounted && !value) return
        if (!mounted) {
            params.y = (screen().y - size).coerceAtLeast(0) / 2
            runCatching { windows.addView(image, params); mounted = true }.onFailure { fail() }
        }
        resize()
    }
    fun resize() {
        if (!mounted) return
        val screen = screen()
        params.x = params.x.coerceIn(0, (screen.x - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.y - size).coerceAtLeast(0))
        params.alpha = if (visible) 1f else 0f
        // Window alpha alone leaves the native view accessible while it cannot receive input.
        image.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        update()
    }
    private fun fail() { if (!failed) { failed = true; onFailure() } }
    private fun update() { if (mounted && !failed) runCatching { windows.updateViewLayout(image, params) }.onFailure { fail() } }
    fun close() {
        if (mounted) runCatching { windows.removeViewImmediate(image) }
        mounted = false; visible = false
    }
}

/** A named clickable view also exposes the gesture's performClick path to accessibility checks. */
private class OverlayBallImage(context: Context) : ZhigouMarkView(context) {
    override fun performClick(): Boolean = super.performClick()
}
