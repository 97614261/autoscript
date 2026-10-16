package com.autoscript.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Once disk commit begins, acknowledge its outcome before honoring cancellation of a pending run. */
internal suspend fun <T> editorSaveCommit(write: suspend () -> T, acknowledge: (T) -> Unit): T {
    currentCoroutineContext().ensureActive()
    val saved = withContext(NonCancellable) { write().also(acknowledge) }
    currentCoroutineContext().ensureActive()
    return saved
}

/** UI transactions always release their busy state; cancellation is never a business failure. */
internal suspend fun editorOperation(
    onFailure: (Exception) -> Unit,
    onFinished: () -> Unit,
    operation: suspend () -> Unit,
) {
    try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    } finally {
        onFinished()
    }
}
