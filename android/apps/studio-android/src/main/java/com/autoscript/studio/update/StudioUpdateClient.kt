package com.autoscript.studio

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** A single bounded worker for update metadata and explicitly confirmed APK requests. */
internal object StudioUpdateClient {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "zhigou-app-update").apply { isDaemon = true } })

    suspend fun check(site: StudioUpdateSite, abis: List<String>, sdk: Int): StudioUpdateRelease? =
        perform(15_000L) { request ->
            val connection = request.open(site.manifest, "application/json")
            require(connection.contentLengthLong <= 65536)
            val data = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    request.checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65536)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            selectStudioUpdate(data, abis, sdk)
        }

    internal suspend fun <T> perform(timeoutMillis: Long, discard: (T) -> Unit = {},
        operation: (StudioUpdateRequest) -> T): T = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { pending ->
            val request = StudioUpdateRequest { pending.isActive }
            val work = AtomicReference<Future<*>?>()
            fun cancelWork() {
                request.close()
                work.get()?.cancel(true)
                worker.purge()
            }
            pending.invokeOnCancellation { cancelWork() }
            try {
                work.set(worker.submit {
                    if (!pending.isActive) return@submit
                    try {
                        val result = operation(request)
                        pending.resume(result, onCancellation = { _, value, _ -> discard(value) })
                    } catch (failure: Exception) {
                        if (pending.isActive) pending.resumeWithException(failure)
                    } finally { request.close() }
                })
                if (!pending.isActive) cancelWork()
            } catch (failure: Exception) { if (pending.isActive) pending.resumeWithException(failure) }
        }
    }
}

internal class StudioUpdateRequest(private val active: () -> Boolean) : AutoCloseable {
    private val connection = AtomicReference<HttpURLConnection?>()
    fun checkpoint() { check(active() && !Thread.currentThread().isInterrupted) { "Update cancelled" } }
    fun open(uri: URI, accept: String): HttpURLConnection {
        checkpoint()
        val request = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            instanceFollowRedirects = false; useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "Zhigou-App-Update")
        }
        connection.set(request)
        checkpoint()
        require(request.responseCode == 200) { "Unexpected update response" }
        require(request.contentEncoding == null || request.contentEncoding.equals("identity", true))
        return request
    }
    override fun close() { connection.getAndSet(null)?.disconnect() }
}
