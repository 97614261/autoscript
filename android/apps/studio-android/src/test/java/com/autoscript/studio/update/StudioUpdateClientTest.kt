package com.autoscript.studio

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StudioUpdateClientTest {
    @Test fun cancelledQueuedRequestReleasesSlotForImmediateRetry() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cancelledOperationRan = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            StudioUpdateClient.perform(5_000) {
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                "first"
            }
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                StudioUpdateClient.perform(5_000) { cancelledOperationRan = true }
            }
            queued.cancelAndJoin()
            val retry = async(start = CoroutineStart.UNDISPATCHED) { StudioUpdateClient.perform(5_000) { "retry" } }
            release.countDown()
            assertEquals("first", first.await())
            assertEquals("retry", retry.await())
            assertFalse(cancelledOperationRan)
        } finally { release.countDown(); first.cancelAndJoin() }
    }

    private fun <T> withServer(response: String, test: (StudioUpdateSite) -> T): T {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val thread = Thread {
                runCatching { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine(); if (line == null || line.isEmpty()) break }
                    socket.getOutputStream().write(response.toByteArray())
                } }
            }.apply { isDaemon = true; start() }
            try { return test(StudioUpdateSite.parse("http://127.0.0.1:${server.localPort}")) }
            finally { server.close(); thread.join(3500) }
        }
    }

    @Test fun readsBoundedManualManifest() {
        val body = "{\"available\":false}"
        withServer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body") { site ->
            runBlocking { assertNull(StudioUpdateClient.check(site, listOf("arm64-v8a"), 32)) }
        }
    }

    @Test fun rejectsRedirectInsteadOfFollowingRemoteLocation() {
        withServer("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/untrusted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { site ->
            runBlocking {
                try { StudioUpdateClient.check(site, listOf("arm64-v8a"), 32); fail("Redirect must fail") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun rejectsOversizeLengthBeforeReadingBody() {
        withServer("HTTP/1.1 200 OK\r\nContent-Length: 65537\r\nConnection: close\r\n\r\n") { site ->
            runBlocking {
                try { StudioUpdateClient.check(site, listOf("arm64-v8a"), 32); fail("Oversize must fail") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }
}
