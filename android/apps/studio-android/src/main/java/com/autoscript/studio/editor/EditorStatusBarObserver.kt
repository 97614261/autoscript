package com.autoscript.studio

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager

/** Observe system bars without changing their visibility or adding a probe window. */
@Suppress("DEPRECATION")
internal class EditorStatusBarObserver(
    private val view: View,
    private val manager: WindowManager,
    private val onChanged: () -> Unit,
) : AutoCloseable {
    var top: Int = 0
        private set
    private var legacyVisible: Boolean? = null
    private var closed = false
    private var queued = false
    private val refresh = Runnable {
        queued = false
        if (!closed) {
            val next = readTop()
            if (top != next) { top = next; onChanged() }
        }
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { enqueue() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { enqueue() }
        override fun onViewDetachedFromWindow(v: View) { view.removeCallbacks(refresh); queued = false }
    }

    init {
        top = readTop()
        view.setOnApplyWindowInsetsListener { _, insets -> enqueue(); insets }
        if (Build.VERSION.SDK_INT < 30) {
            view.setOnSystemUiVisibilityChangeListener { visibility ->
                legacyVisible = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
                enqueue()
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
        view.addOnAttachStateChangeListener(attachListener)
    }

    private fun enqueue() {
        if (!closed && !queued) { queued = true; view.post(refresh) }
    }

    private fun readTop(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            // Metrics cover the display, unlike the local insets of the small cropped native window.
            val insets = runCatching { manager.maximumWindowMetrics.windowInsets }.getOrNull()
            if (insets != null) return if (insets.isVisible(WindowInsets.Type.statusBars()))
                insets.getInsets(WindowInsets.Type.statusBars()).top else 0
        }
        if (!view.isAttachedToWindow) return 0
        val frame = Rect()
        view.getWindowVisibleDisplayFrame(frame)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val localTop = view.rootWindowInsets?.systemWindowInsetTop ?: 0
        val measuredTop = maxOf(frame.top, if (localTop > 0) location[1] + localTop else 0)
        val id = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        val height = if (id != 0) view.resources.getDimensionPixelSize(id) else 0
        return editorLegacyStatusBarTop(legacyVisible, measuredTop, height, top)
    }

    fun configurationChanged() {
        if (closed) return
        top = 0
        top = readTop()
        enqueue()
    }

    override fun close() {
        if (closed) return
        closed = true
        view.removeCallbacks(refresh); queued = false
        view.setOnApplyWindowInsetsListener(null)
        if (Build.VERSION.SDK_INT < 30) {
            view.setOnSystemUiVisibilityChangeListener(null)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
        view.removeOnAttachStateChangeListener(attachListener)
    }
}
