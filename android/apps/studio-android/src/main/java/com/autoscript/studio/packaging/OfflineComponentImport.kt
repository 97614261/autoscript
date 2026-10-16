package com.autoscript.studio

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Both fixed artifact families share bounded document IO, not their stores or semantics. */
internal suspend fun <T> importOfflineComponent(context: Context, uri: Uri,
    transaction: (() -> InputStream, () -> Unit) -> T): T = withTimeout(120_000L) {
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val opened = AtomicReference<ParcelFileDescriptor?>(null)
        val work = FutureTask {
            try {
                val start = System.nanoTime()
                val checkpoint = {
                    if (!continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException()
                    check(System.nanoTime() - start < 120_000_000_000L) { "COMPONENT_IMPORT_TIMEOUT" }
                }
                checkpoint()
                val result = transaction({
                    val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r", signal))
                    opened.set(descriptor); checkpoint()
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                }, checkpoint)
                continuation.resume(result)
            } catch (error: Throwable) { continuation.resumeWithException(error) }
            finally { runCatching { opened.getAndSet(null)?.close() } }
        }
        continuation.invokeOnCancellation {
            signal.cancel(); runCatching { opened.getAndSet(null)?.close() }
            work.cancel(true); OfflineComponentImports.pool.remove(work)
        }
        try { OfflineComponentImports.pool.execute(work) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            continuation.resumeWithException(IllegalStateException("COMPONENT_IMPORT_BUSY"))
        }
    }
}

private object OfflineComponentImports {
    val pool = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "offline-component-import").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
}
