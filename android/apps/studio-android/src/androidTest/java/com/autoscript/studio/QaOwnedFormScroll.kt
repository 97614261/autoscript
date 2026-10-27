package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Real vertical swipes confined to the current owned QA form, above any IME. */
internal fun scrollOwnedQaForm(test: Instrumentation, forward: Boolean, reached: () -> Boolean = { false }): Boolean {
    val windows = test.uiAutomation.windows.sortedByDescending { it.layer }
    val keyboardTop = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().also(it::getBoundsInScreen).top }
    val window = windows.firstOrNull { candidate ->
        val root = candidate.root ?: return@firstOrNull false
        try { root.packageName?.toString() == test.targetContext.packageName &&
            !root.className.toString().contains("PopupDecorView") } finally { root.recycle() }
    } ?: return false
    val windowBounds = Rect().also(window::getBoundsInScreen)
    val viewports = mutableListOf<Rect>()
    var budget = 512
    fun collect(node: AccessibilityNodeInfo) {
        try {
            if (--budget < 0 || !node.refresh()) return
            for (index in 0 until node.childCount.coerceAtMost(64)) node.getChild(index)?.let(::collect)
            if (node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.intersect(windowBounds)) {
                    keyboardTop?.let { bounds.bottom = minOf(bounds.bottom, it) }
                    if (bounds.height() >= 48 && bounds.width() >= 32) viewports += bounds
                }
            }
        } finally { node.recycle() }
    }
    window.root?.let(::collect)
    // A vertical swipe does not advance horizontal toolbars. Try nested lists
    // before their enclosing form; never tap a control outside an observed viewport.
    for (bounds in viewports.distinct().take(4)) {
        val top = bounds.top + 12f
        val bottom = bounds.bottom - 12f
        val down = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val from = if (forward) bottom else top
            val to = if (forward) top else bottom
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN
                10 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }, bounds.exactCenterX(), from + (to - from) * step / 10f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(test.uiAutomation.injectInputEvent(event, true)) { "QA_OWNED_SCROLL_REJECTED" } }
            finally { event.recycle() }
            SystemClock.sleep(20)
        }
        try { test.uiAutomation.waitForIdle(100, 1000) } catch (_: java.util.concurrent.TimeoutException) { }
        if (reached()) return true
    }
    try { test.uiAutomation.waitForIdle(100, 1000) } catch (_: java.util.concurrent.TimeoutException) { }
    return viewports.isNotEmpty()
}
