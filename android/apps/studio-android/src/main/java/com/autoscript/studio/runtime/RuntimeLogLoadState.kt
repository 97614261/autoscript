package com.autoscript.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class RuntimeLogOperation(val failureMessage: String) {
    HISTORY("读取运行历史失败，请刷新重试"),
    SNAPSHOT("读取此运行日志失败，请刷新重试"),
    EXPORT("日志导出失败，请确认保存位置可写"),
}

/** Independent operations must not clear or preserve each other's failures. */
internal data class RuntimeLogLoadErrors(
    val history: String? = null,
    val snapshot: String? = null,
    val export: String? = null,
) {
    fun completed(operation: RuntimeLogOperation, result: Result<*>): RuntimeLogLoadErrors {
        val message = if (result.isFailure) operation.failureMessage else null
        return when (operation) {
            RuntimeLogOperation.HISTORY -> copy(history = message)
            RuntimeLogOperation.SNAPSHOT -> copy(snapshot = message)
            RuntimeLogOperation.EXPORT -> copy(export = message)
        }
    }

    fun visible(historyVisible: Boolean): String? =
        export ?: if (historyVisible) history ?: snapshot else null
}

/** Switching runs, refreshing or closing is cancellation, not an unreadable log. */
internal suspend fun <T> runtimeLogAttempt(block: suspend () -> T): Result<T> = try {
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(failure)
}
