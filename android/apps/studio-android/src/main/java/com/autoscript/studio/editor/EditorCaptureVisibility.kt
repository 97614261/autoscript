package com.autoscript.studio

import android.content.Context
import android.provider.Settings
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToLong

internal fun editorCaptureSettleMillis(requestedDelay: Long, windowScale: Float, transitionScale: Float): Long {
    fun bounded(value: Float) = if (value.isFinite() && value in 0f..10f) value else 1f
    val scale = max(bounded(windowScale), bounded(transitionScale))
    return max(requestedDelay.coerceAtLeast(0), max(500L, (300 * scale + 200).roundToLong()))
}

/** Hide synchronously: a Compose state change alone can leave the native dim layer in capture. */
internal suspend fun awaitEditorCaptureSurface(context: Context, session: EditorOverlaySession?, key: String,
    requestedDelay: Long = 0L) = withContext(Dispatchers.Main.immediate) {
    session?.suppress(key)
    val scales = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) to
            Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
    }.getOrDefault(1f to 1f)
    // Dialog.hide removes its decor before WindowManager finishes fading the separate dim surface.
    // Use the main choreographer, not the hidden Compose window's paused frame clock.
    withTimeout(1_500L) {
        repeat(2) {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }
    }
    delay(editorCaptureSettleMillis(requestedDelay, scales.first, scales.second))
    check(session == null || !session.isClosed && session.hidden) { "截图窗口状态已变化，请重试" }
}
