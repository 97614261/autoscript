package com.autoscript.studio

import android.os.Build
import android.graphics.Rect
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp

internal fun studioLegacyImeOverlap(rootHeight: Int, bottomInset: Int, contentTop: Int, contentHeight: Int): Int {
    if (rootHeight <= 0 || bottomInset !in 0 until rootHeight || contentHeight <= 0) return 0
    return (contentTop.toLong() + contentHeight - (rootHeight - bottomInset))
        .coerceIn(0L, contentHeight.toLong()).toInt()
}

@Composable
private fun dialogImeOverlapPadding(): androidx.compose.ui.unit.Dp {
    val view = LocalView.current
    var overlap by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val update = Runnable {
            val root = view.rootView
            val location = IntArray(2).also(view::getLocationOnScreen)
            val origin = IntArray(2).also(root::getLocationOnScreen)
            val insets = root.rootWindowInsets
            val inset = if (Build.VERSION.SDK_INT >= 30) {
                val frame = Rect().also(root::getWindowVisibleDisplayFrame)
                studioKeyboardBottomInset(origin[1], root.height, frame.bottom,
                    insets?.isVisible(android.view.WindowInsets.Type.ime()) == true,
                    insets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0)
            } else {
                @Suppress("DEPRECATION")
                insets?.systemWindowInsetBottom ?: 0
            }
            overlap = studioLegacyImeOverlap(root.height, inset, location[1] - origin[1], view.height)
        }
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { update.run() }
        observer.addOnGlobalLayoutListener(listener)
        view.post(update)
        onDispose {
            view.removeCallbacks(update)
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(LocalDensity.current) { overlap.toDp() }
}

internal fun studioKeyboardBottomInset(rootTop: Int, rootHeight: Int, visibleBottom: Int,
    imeVisible: Boolean, reportedBottom: Int): Int {
    if (!imeVisible || rootHeight <= 0) return 0
    // Some floating native dialogs report a visible IME with a zero bottom inset.
    val hidden = (rootTop.toLong() + rootHeight - visibleBottom).coerceIn(0L, rootHeight.toLong()).toInt()
    return maxOf(reportedBottom.coerceAtLeast(0), hidden)
}

@Composable
private fun StudioDialogBackdrop(properties: DialogProperties, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val bottom = dialogImeOverlapPadding()
    // Keep the native root fixed. Only its still-overlapping content is inset, once.
    Box(Modifier.fillMaxSize().padding(bottom = bottom).consumeWindowInsets(PaddingValues(bottom = bottom))
        .clickable(remember { MutableInteractionSource() }, null) {
            if (properties.dismissOnClickOutside) onDismiss()
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.wrapContentSize().then(if (properties.usePlatformDefaultWidth) Modifier.fillMaxWidth(.9f) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) {}) { content() }
    }
}

internal fun editorOverlayWindowType(): Int = if (Build.VERSION.SDK_INT >= 26)
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}

internal fun studioDialogResizeSoftInputMode(current: Int): Int =
    (current and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

private fun Window.resizeForKeyboard() {
    if (Build.VERSION.SDK_INT < 30) {
        // Both flags are required for legacy WindowManager to resize instead of panning the panel.
        addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
    }
    setSoftInputMode(studioDialogResizeSoftInputMode(attributes.softInputMode))
}

/** Existing content and Activity behavior stay unchanged; only the native window host differs. */
@Composable
internal fun StudioDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    prompt: Boolean = false, content: @Composable () -> Unit) {
    val host = LocalEditorOverlay.current
    val promptLayer = prompt || LocalStudioPrompt.current
    val parentDensity = LocalDensity.current
    if (host == null) {
        androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.resizeForKeyboard()
                // Custom-width panels need a viewport root, not a floating root that pans the fixed footer.
                if (Build.VERSION.SDK_INT < 30 || !properties.usePlatformDefaultWidth) window?.setLayout(
                    if (properties.usePlatformDefaultWidth) window.attributes.width else WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT)
            }
            // A native dialog creates a new Compose root. Keep the caller's text scale
            // rather than resetting an explicitly provided density at that boundary.
            CompositionLocalProvider(LocalDensity provides parentDensity, LocalStudioPrompt provides promptLayer) {
                if (Build.VERSION.SDK_INT < 30 || !properties.usePlatformDefaultWidth)
                    StudioDialogBackdrop(properties, onDismissRequest, content) else content()
            }
        }
        return
    }
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val children by rememberUpdatedState(content)
    val currentProperties by rememberUpdatedState(properties)
    val currentDensity by rememberUpdatedState(parentDensity)
    val dialog = remember(context, host, promptLayer) {
        ComponentDialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window!!.apply {
                setType(editorOverlayWindowType())
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                resizeForKeyboard()
            }
            onBackPressedDispatcher.addCallback(this) { if (currentProperties.dismissOnBackPress) dismiss() }
            val dialogOwner = this
            val view = ComposeView(context).apply {
                setParentCompositionContext(parent)
                setContent {
                    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dialogOwner,
                        LocalDensity provides currentDensity, LocalStudioPrompt provides promptLayer) {
                        StudioDialogBackdrop(currentProperties, { dismiss() }) { children() }
                    }
                }
            }
            setContentView(view, FrameLayout.LayoutParams(-1, -1))
        }
    }
    SideEffect {
        runCatching {
            dialog.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, properties.decorFitsSystemWindows)
                window.attributes = window.attributes.apply { width = -1; height = -1; dimAmount = .25f }
                if (properties.securePolicy == SecureFlagPolicy.SecureOn) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }.onFailure { host.windowFailed() }
    }
    DisposableEffect(dialog) {
        host.register(dialog, prompt = promptLayer)
        onDispose { host.unregister(dialog) }
    }
}
