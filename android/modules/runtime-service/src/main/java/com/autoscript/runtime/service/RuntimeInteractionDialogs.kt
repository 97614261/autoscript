package com.autoscript.runtime.service

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoscript.script.ui.ScriptDialogCompletion
import com.autoscript.script.ui.ScriptDialogRequest
import com.autoscript.script.ui.ScriptUiDisplay
import com.autoscript.script.ui.ScriptUiWindowView
import kotlin.math.roundToInt

/** Main-thread-only owner; shares the already prepared renderer, never reloads a private script. */
internal class RuntimeInteractionDialogs(private val context: Context, private val main: Handler) {
    private class Session(val token: Long, val dialog: Dialog, val layout: Boolean, val view: ScriptUiWindowView?, val cancelled: () -> Boolean,
        val completion: ScriptDialogCompletion, val cleanup: () -> Unit, val valid: () -> Boolean) {
        var timer: Runnable? = null
    }
    private var current: Session? = null
    val isOpen: Boolean get() = current != null
    val isLayout: Boolean get() = current?.layout == true

    fun open(token: Long, request: ScriptDialogRequest, cancelled: () -> Boolean, valid: () -> Boolean,
        view: ScriptUiWindowView?, beforeLayout: () -> (() -> Unit), complete: (Result<String>) -> Unit) {
        check(current == null) { "UI_DIALOG_BUSY" }
        check(valid() && !cancelled() && Settings.canDrawOverlays(context)) { "UI_SESSION_STALE" }
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = requireNotNull(dialog.window)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setType(if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.attributes = window.attributes.apply { setFitInsetsTypes(0) }
        }
        val layout = request.pageId != null
        val cleanup = if (layout) {
            requireNotNull(view)
            require(view.renderer.definition.pages.any { it.id == request.pageId })
            beforeLayout()
        } else ({})
        val completion = ScriptDialogCompletion(!layout) { result -> complete(Result.success(result)) }
        val session = Session(token, dialog, layout, view, cancelled, completion, cleanup, valid)
        current = session
        fun answer(result: String) { if (current === session) finish(result) }
        try {
            if (layout) {
                val child = requireNotNull(view)
                child.renderer.setDialogMode(true)
                child.setDialogTitle(child.renderer.definition.pages.single { it.id == request.pageId }.title)
                child.renderer.switchPage(requireNotNull(request.pageId))
                child.onViewportChanged = { if (current === session) resize() }
                (child.parent as? ViewGroup)?.removeView(child)
                dialog.setContentView(child)
            } else dialog.setContentView(confirmView(request, ::answer))
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(!request.blockOutside)
            dialog.setOnCancelListener { answer("dismiss") }
            dialog.setOnDismissListener { if (current === session) answer("dismiss") }
            dialog.show()
            if (layout) {
                // Keep the input-bearing overlay focusable and below the IME.
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                view?.requestApplyInsets()
            }
            resize()
            val timer = Runnable { if (current === session) answer("timeout") }
            session.timer = timer
            check(main.postDelayed(timer, request.timeoutMs)) { "UI_UNAVAILABLE" }
        } catch (error: RuntimeException) {
            clear()
            throw error
        }
    }

    fun close() { finish(if (isLayout) "close" else "dismiss") }
    fun cancelIfRequested(token: Long) {
        current?.takeIf { it.token == token && it.cancelled() }?.let { clear() }
    }
    fun clear() {
        val session = current ?: return
        current = null
        session.timer?.let(main::removeCallbacks)
        session.completion.cancel()
        session.dialog.setOnDismissListener(null)
        runCatching { session.dialog.dismiss() }
        session.cleanup()
    }
    private fun finish(result: String) {
        val session = current ?: return
        if (!session.valid() || session.cancelled()) { clear(); return }
        current = null
        session.timer?.let(main::removeCallbacks)
        session.dialog.setOnDismissListener(null)
        runCatching { session.dialog.dismiss() }
        session.cleanup()
        session.completion.finish(result)
    }
    fun resize() {
        val session = current ?: return
        // Layout dialogs may contain EditText controls. Use the dialog's attached
        // view when measuring the available area so ScriptUiDisplay subtracts the
        // visible IME inset instead of letting the keyboard cover the action bar.
        val bounds = ScriptUiDisplay.bounds(context, session.view ?: session.dialog.window?.decorView)
        val size = session.view?.desiredSize(bounds)
        val width = size?.width ?: minOf(dp(340), bounds.width - dp(16)).coerceAtLeast(1)
        val height = size?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        session.dialog.window?.let { window ->
            if (size != null) {
                // Center in the available area, not in the full display behind the IME.
                val target = bounds.centered(size)
                val previous = window.attributes
                if (previous.width != width || previous.height != height || previous.x != target.first || previous.y != target.second ||
                    previous.gravity != (Gravity.TOP or Gravity.START)) {
                    window.attributes = previous.apply {
                        this.width = width; this.height = height; gravity = Gravity.TOP or Gravity.START
                        x = target.first; y = target.second
                    }
                }
            } else {
                if (window.attributes.width != width || window.attributes.height != height) window.setLayout(width, height)
                window.setGravity(Gravity.CENTER)
            }
        }
    }
    private fun confirmView(request: ScriptDialogRequest, answer: (String) -> Unit): View {
        val bounds = ScriptUiDisplay.bounds(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
        }
        body.addView(TextView(context).apply {
            text = request.title; textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(Color.rgb(56,106,255)); setPadding(dp(12),dp(10),dp(12),dp(10)); maxLines = 3
        })
        val message = TextView(context).apply {
            text = request.message; textSize = 13f; setTextColor(Color.rgb(40,50,65)); setPadding(dp(14),dp(8),dp(14),dp(8))
        }
        message.measure(View.MeasureSpec.makeMeasureSpec(minOf(dp(340), bounds.width - dp(16)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        body.addView(ScrollView(context).apply { addView(message) }, LinearLayout.LayoutParams(-1, -2).apply {
            // Bounded multiline text scrolls inside the compact dialog.
            height = minOf(message.measuredHeight, dp(160), (bounds.height - dp(112)).coerceAtLeast(dp(24)))
        })
        body.addView(LinearLayout(context).apply {
            fun action(label: String, result: String) { addView(Button(context).apply {
                text = label; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0; gravity = Gravity.CENTER; includeFontPadding = false
                setPadding(dp(4),0,dp(4),0); setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.rgb(56,106,255))
                setOnClickListener { answer(result) }
            }, LinearLayout.LayoutParams(0, dp(40), 1f)) }
            action(request.cancelText, "cancel"); action(request.confirmText, "confirm")
        })
        return body
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
