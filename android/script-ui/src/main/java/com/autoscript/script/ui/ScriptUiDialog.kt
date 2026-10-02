package com.autoscript.script.ui

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import kotlin.math.roundToInt

/** Keeps one View tree alive across minimize/restore and rotation; no engine or Compose dependencies. */
class ScriptUiDialog(context: Context, val content: ScriptUiWindowView, private val onCancel: () -> Unit) {
    private val dialog = Dialog(context)
    private var position: Pair<Int, Int>? = null
    private var lastBounds: UiScreenBounds? = null
    private var lastSize: UiWindowSize? = null
    private var closed = false
    private var hidden = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { onCancel() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        }
        content.onViewportChanged = { resize() }
        content.onDrag = { dx, dy ->
            val bounds = ScriptUiDisplay.bounds(context, content)
            val size = content.desiredSize(bounds)
            val origin = position ?: bounds.centered(size)
            position = bounds.clamp(origin.first + dx.roundToInt(), origin.second + dy.roundToInt(), size)
            update(bounds, size)
        }
    }
    fun show() {
        if (closed) return
        hidden = false
        dialog.show()
        resize()
        content.requestApplyInsets()
    }
    fun hide() {
        hidden = true
        (content.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(content.windowToken, 0)
        dialog.hide()
    }
    fun setBusy(value: Boolean) {
        content.setBusy(value)
        dialog.setCancelable(!value)
        dialog.setCanceledOnTouchOutside(!value)
    }
    fun close() {
        closed = true; content.onViewportChanged = null; content.onDrag = null
        dialog.dismiss()
    }
    fun resize() {
        if (closed || hidden || !dialog.isShowing) return
        val bounds = ScriptUiDisplay.bounds(content.context, content)
        val size = content.desiredSize(bounds)
        if (bounds == lastBounds && size == lastSize) return
        val previous = lastBounds
        if (previous != null && (previous.width != bounds.width || previous.height != bounds.height)) position = null
        update(bounds, size)
    }
    private fun update(bounds: UiScreenBounds, size: UiWindowSize) {
        val target = position?.let { bounds.clamp(it.first, it.second, size) } ?: bounds.centered(size)
        position = target; lastBounds = bounds; lastSize = size
        dialog.window?.let { window ->
            window.attributes = window.attributes.apply {
                width = size.width; height = size.height; gravity = Gravity.TOP or Gravity.START
                x = target.first; y = target.second; dimAmount = .2f
            }
        }
    }
}
