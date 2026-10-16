package com.autoscript.studio

import android.os.Build
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat

internal fun editorOverlayWindowType(): Int = if (Build.VERSION.SDK_INT >= 26)
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}

/** Existing content and Activity behavior stay unchanged; only the native window host differs. */
@Composable
internal fun StudioDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit) {
    val host = LocalEditorOverlay.current
    val parentDensity = LocalDensity.current
    if (host == null) {
        androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
            // A native dialog creates a new Compose root. Keep the caller's text scale
            // rather than resetting an explicitly provided density at that boundary.
            CompositionLocalProvider(LocalDensity provides parentDensity) { content() }
        }
        return
    }
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val children by rememberUpdatedState(content)
    val currentProperties by rememberUpdatedState(properties)
    val currentDensity by rememberUpdatedState(parentDensity)
    val dialog = remember(context, host) {
        ComponentDialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window!!.apply {
                setType(editorOverlayWindowType())
                setBackgroundDrawableResource(android.R.color.transparent)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onBackPressedDispatcher.addCallback(this) { if (currentProperties.dismissOnBackPress) dismiss() }
            val dialogOwner = this
            val view = ComposeView(context).apply {
                setParentCompositionContext(parent)
                setContent {
                    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dialogOwner,
                        LocalDensity provides currentDensity) {
                        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                            if (currentProperties.dismissOnClickOutside) dismiss()
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.wrapContentSize().then(if (currentProperties.usePlatformDefaultWidth)
                                Modifier.fillMaxWidth(.9f) else Modifier).clickable(remember { MutableInteractionSource() }, null) {}) {
                                children()
                            }
                        }
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
        host.register(dialog)
        onDispose { host.unregister(dialog) }
    }
}
