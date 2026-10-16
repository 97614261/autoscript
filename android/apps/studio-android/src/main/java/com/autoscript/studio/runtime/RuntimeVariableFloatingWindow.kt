package com.autoscript.studio

import android.graphics.Point
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

/** A bounded non-modal native window. No fullscreen touch-catching backdrop is installed. */
@Composable
internal fun RuntimeVariableFloatingWindow(
    collapsed: Boolean, maximized: Boolean, onRestore: () -> Unit, onDismiss: () -> Unit,
    onVisibility: (Boolean) -> Unit, content: @Composable ((Float, Float) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val host = LocalEditorOverlay.current
    val parent = rememberCompositionContext()
    val children by rememberUpdatedState(content)
    val dismiss by rememberUpdatedState(onDismiss)
    val visibility by rememberUpdatedState(onVisibility)
    val restore by rememberUpdatedState(onRestore)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val owner = remember(context, host) {
        RuntimeVariableNativeWindow(context, host != null, { visibility(it) }, { dismiss() }, { restore() })
    }
    val view = remember(owner) {
        ComposeView(context).apply {
            setParentCompositionContext(parent)
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) { owner.detached() }
            })
            setContent { children(owner::move) }
        }
    }
    SideEffect {
        runCatching { owner.configure(view, density.density, configuration.fontScale, collapsed, maximized) }.onFailure { owner.failed() }
    }
    DisposableEffect(owner, view) {
        if (host == null) owner.visibility(true) else host.registerObserver(owner)
        onDispose {
            if (host == null) owner.close() else host.unregisterObserver(owner)
            view.disposeComposition()
        }
    }
}

private class RuntimeVariableNativeWindow(
    context: android.content.Context, overlay: Boolean, private val onVisibility: (Boolean) -> Unit,
    private val onFailure: () -> Unit, private val onRestore: () -> Unit,
) : EditorObserverWindow {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val dialog = ComponentDialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        window!!.apply {
            if (overlay) setType(editorOverlayWindowType())
            setBackgroundDrawableResource(android.R.color.transparent)
            WindowCompat.setDecorFitsSystemWindows(this, true)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setGravity(Gravity.TOP or Gravity.START)
        }
    }
    private var attached = false
    private var closed = false
    private var visible = false
    private var density = 1f
    private var fontScale = 1f
    private var collapsed = false
    private var maximized = false
    private var x = 12f
    private var y = 64f

    fun configure(view: ComposeView, density: Float, fontScale: Float, collapsed: Boolean, maximized: Boolean) {
        if (closed) return
        val first = !attached
        if (!attached) { dialog.setContentView(view); attached = true; x *= density; y *= density }
        this.density = density; this.fontScale = fontScale; this.collapsed = collapsed; this.maximized = maximized
        layout()
        if (first) visibility(visible)
    }
    private fun layout() {
        if (closed) return
        val size = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(size)
        val rect = runtimeVariableWindowRect(size.x, size.y, (260 * density).roundToInt(),
            (320 * density * fontScale.coerceAtLeast(1f)).roundToInt(), x.roundToInt(), y.roundToInt(), maximized, collapsed,
            (studioCompactControlHeight(32, fontScale) * density).roundToInt())
        if (!maximized || collapsed) { x = rect.x.toFloat(); y = rect.y.toFloat() }
        dialog.window?.attributes = dialog.window!!.attributes.apply {
            width = rect.width; height = rect.height; gravity = Gravity.TOP or Gravity.START; this.x = rect.x; this.y = rect.y
        }
    }
    fun move(dx: Float, dy: Float) {
        if (closed || !visible) return
        if (maximized && !collapsed) { maximized = false; onRestore() }
        x += dx; y += dy
        runCatching { layout() }.onFailure { failed() }
    }
    override fun visibility(visible: Boolean) {
        if (closed) return
        this.visible = visible
        if (!attached) return
        runCatching {
            if (visible) { dialog.show(); layout() } else dialog.hide()
        }.onSuccess { if (!closed) onVisibility(visible) }.onFailure { failed() }
    }
    fun detached() { if (!closed) failed() }
    fun failed() {
        if (closed) return
        close(); onFailure()
    }
    override fun close() {
        if (closed) return
        closed = true; visible = false; onVisibility(false)
        runCatching { dialog.dismiss() }
    }
}
