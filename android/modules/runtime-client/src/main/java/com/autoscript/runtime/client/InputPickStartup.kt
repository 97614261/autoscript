package com.autoscript.runtime.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class InputPickStartOutcome { ACCEPTED, REJECTED, STALE }

/** Binder cannot be forcibly interrupted; cancellation cleans up once the bounded call returns. */
internal suspend fun startInputPickOffUi(
    start: () -> Boolean,
    isCurrent: () -> Boolean,
    clearPending: () -> Unit,
    cancel: () -> Unit,
): InputPickStartOutcome {
    val accepted = try {
        withContext(Dispatchers.IO) { start() }
    } catch (cancelled: CancellationException) {
        clearPending()
        withContext(NonCancellable + Dispatchers.IO) { runCatching(cancel) }
        throw cancelled
    } catch (_: Exception) { false }
    if (!isCurrent()) {
        clearPending()
        withContext(Dispatchers.IO) { runCatching(cancel) }
        return InputPickStartOutcome.STALE
    }
    return if (accepted) InputPickStartOutcome.ACCEPTED else InputPickStartOutcome.REJECTED
}
