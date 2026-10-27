package com.autoscript.studio

import android.app.Dialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class EditorOverlayStatus(val projectId: String, val ready: Boolean = false)
internal object EditorOverlaySessions {
    private val mutableStatus = MutableStateFlow<EditorOverlayStatus?>(null)
    val status = mutableStatus.asStateFlow()
    fun publish(value: EditorOverlayStatus?) { mutableStatus.value = value }
}

internal val LocalEditorOverlay = staticCompositionLocalOf<EditorOverlaySession?> { null }
// Descendant pickers belong above their own prompt, not behind it.
internal val LocalStudioPrompt = staticCompositionLocalOf { false }

internal interface EditorSessionWindow {
    fun visibility(visible: Boolean)
    fun close()
}

internal interface EditorObserverWindow : EditorSessionWindow

/** Session-owned state; hiding never disposes the editor or its dialogs/drafts. */
internal class EditorOverlaySession(private val updateWindow: () -> Unit, private val windowFailure: () -> Unit = {}) {
    var hidden by mutableStateOf(false)
        private set
    var restoreRequest by mutableIntStateOf(0)
        private set
    var dockBounds: Rect = Rect(0f, 0f, 1f, 1f)
        private set
    var dockModal = false
        private set
    var workArea by mutableStateOf<Rect?>(null)
        private set
    var canvasVisible = false
        private set
    var closeRequest: (() -> Unit)? = null
    var panelExpanded by mutableStateOf(true)
        private set
    var realtimeLogRequested by mutableStateOf(false)
    val shouldSampleRealtimeLog: Boolean get() = realtimeLogRequested && panelExpanded && !hidden && !closed
    private var suppressionKey: String? = null
    private var restored = false
    private var systemRequest = false
    private val dialogs = linkedSetOf<Dialog>()
    private val dialogOrder = StudioDialogOrder<Any>()
    private val childWindows = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EditorSessionWindow, Boolean>())
    private var observerWindow: EditorObserverWindow? = null
    private var closed = false
    private var failureReported = false
    val isClosed: Boolean get() = closed
    val hasOpenDialogs: Boolean get() = dialogs.isNotEmpty() || childWindows.isNotEmpty()
    fun windowFailed() {
        if (!closed && !failureReported) { failureReported = true; windowFailure() }
    }
    private fun applyVisibility() {
        if (closed) return
        hidden = systemRequest || (suppressionKey != null && !restored)
        // Android Dialog.hide keeps isShowing=true; show must be unconditional to restore its decor.
        dialogOrder.windows.forEach { window ->
            if (!closed && dialogOrder.contains(window)) present(window)
        }
        observerWindow?.let { runCatching { it.visibility(!hidden && !dialogOrder.hasPrompt) }.onFailure { windowFailed() } }
        updateWindow()
    }
    private fun present(window: Any): Boolean {
        val visible = !hidden && dialogOrder.canPresent(window)
        return runCatching {
            when (window) {
                is Dialog -> if (visible) window.show() else window.hide()
                is EditorSessionWindow -> window.visibility(visible)
            }
            if (visible) dialogOrder.presented(window)
        }.onFailure { windowFailed() }.isSuccess
    }
    fun suppress(key: String?) {
        if (closed) return
        if (suppressionKey == key) return
        suppressionKey = key
        restored = false
        applyVisibility()
    }
    fun releaseSuppression(key: String) { if (!closed && suppressionKey == key) suppress(null) }
    fun systemRequest(active: Boolean) { if (closed) return; systemRequest = active; applyVisibility() }
    fun restore() { if (closed) return; restored = true; panelExpanded = true; restoreRequest++; applyVisibility() }
    fun panel(value: Boolean) { if (!closed && panelExpanded != value) { panelExpanded = value; updateWindow() } }
    fun bounds(value: Rect) { if (!closed && dockBounds != value) { dockBounds = value; updateWindow() } }
    fun dockLayout(modal: Boolean) { if (!closed && dockModal != modal) { dockModal = modal; updateWindow() } }
    fun workArea(value: Rect) { if (!closed && workArea != value) workArea = value }
    fun canvas(value: Boolean) { if (!closed && canvasVisible != value) { canvasVisible = value; updateWindow() } }
    fun register(dialog: Dialog, prompt: Boolean = false) {
        if (closed) { runCatching { dialog.dismiss() }; return }
        if (dialog in dialogs) return
        if (dialogs.size + childWindows.size >= 16) { windowFailed(); return }
        dialogs += dialog
        dialogOrder.register(dialog, prompt)
        applyVisibility()
    }
    fun unregister(dialog: Dialog) {
        dialogs -= dialog; dialogOrder.remove(dialog); runCatching { dialog.dismiss() }
        applyVisibility()
    }
    fun registerChild(window: EditorSessionWindow, prompt: Boolean = false): Boolean {
        if (window in childWindows) return true
        if (closed || dialogs.size + childWindows.size >= 16) {
            runCatching { window.close() }
            if (!closed) windowFailed()
            return false
        }
        childWindows += window
        dialogOrder.register(window, prompt)
        if (present(window)) {
            observerWindow?.let { runCatching { it.visibility(!hidden && !dialogOrder.hasPrompt) }.onFailure { windowFailed() } }
            return !closed && childWindows.contains(window)
        }
        dialogOrder.remove(window)
        if (childWindows.remove(window)) runCatching { window.close() }
        return false
    }
    fun unregisterChild(window: EditorSessionWindow) {
        dialogOrder.remove(window)
        if (childWindows.remove(window)) runCatching { window.close() }
        applyVisibility()
    }
    fun refreshChild(window: EditorSessionWindow) {
        if (!closed && childWindows.contains(window)) present(window)
    }
    fun registerObserver(window: EditorObserverWindow): Boolean {
        if (observerWindow === window && !closed) return true
        if (closed || observerWindow != null) { runCatching { window.close() }; return false }
        observerWindow = window
        if (runCatching { window.visibility(!hidden && !dialogOrder.hasPrompt) }.onFailure { windowFailed() }.isSuccess) {
            return true
        }
        if (observerWindow === window) {
            observerWindow = null
            runCatching { window.close() }
        }
        return false
    }
    fun unregisterObserver(window: EditorObserverWindow) {
        if (observerWindow !== window) return
        observerWindow = null
        runCatching { window.close() }
    }
    fun closeDialogs() {
        if (closed) return
        closed = true; hidden = true; panelExpanded = false; canvasVisible = false; dockModal = false
        dialogOrder.clear()
        dialogs.toList().forEach { runCatching { it.dismiss() } }; dialogs.clear(); closeRequest = null
        val children = childWindows.toList(); childWindows.clear()
        val observer = observerWindow; observerWindow = null
        children.forEach { runCatching { it.close() } }
        observer?.let { runCatching { it.close() } }
        updateWindow()
    }
}
