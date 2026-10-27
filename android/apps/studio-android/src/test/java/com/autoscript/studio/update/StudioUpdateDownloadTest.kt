package com.autoscript.studio

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StudioUpdateDownloadTest {
    private val data = ByteArray(1024) { (it % 251).toByte() }
    private fun release(bytes: ByteArray = data) = StudioUpdateRelease(BuildConfig.VERSION_CODE + 1,
        "0.2.0", listOf("arm64-v8a"), bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).hexUpdate(), "", false)
    private fun <T> response(body: ByteArray, advertised: Long = body.size.toLong(), status: Int = 200,
        action: (StudioUpdateSite, File) -> T): T {
        val root = Files.createTempDirectory("zhigou-update-test-").toFile()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val thread = Thread {
                runCatching { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Length: $advertised\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } }
            }.apply { isDaemon = true; start() }
            try { return action(StudioUpdateSite.parse("http://127.0.0.1:${server.localPort}"), root) }
            finally { server.close(); thread.join(3500); root.deleteRecursively() }
        }
    }
    private fun rejected(action: suspend () -> Unit) = runBlocking {
        try { action(); fail("Must reject") } catch (_: Exception) { }
    }

    @Test fun downloadsExactBytesAndVerifiesBeforePublishingReadyFile() = response(data) { site, root ->
        val cache = File(root, "cache")
        var verified = false
        val file = runBlocking { StudioUpdateDownload.download(site, release(), cache, { part, checkpoint ->
            checkpoint(); assertTrue(part.name.endsWith(".part")); assertArrayEquals(data, part.readBytes()); verified = true
        }, {}) }
        assertTrue(verified); assertTrue(file.name.endsWith(".apk"))
        StudioUpdateDownload.verifyBytes(file, release()) {}
        assertArrayEquals(data, file.readBytes())
        assertFalse(cache.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun badDigestAndSignatureRejectionRemoveOnlyOwnCandidate() {
        for (badSignature in listOf(false, true)) response(data) { site, root ->
            val unrelated = File(root, "keep.txt").apply { writeText("project is unrelated") }
            val cache = File(root, "cache")
            rejected { StudioUpdateDownload.download(site,
                if (badSignature) release() else release().copy(sha256 = "0".repeat(64)), cache,
                { _, _ -> if (badSignature) error("Foreign signer") }, {}) }
            assertEquals("project is unrelated", unrelated.readText())
            assertTrue(cache.listFiles().orEmpty().none { it.extension in listOf("part", "apk") })
        }
    }

    @Test fun truncatedOversizedAndRedirectedResponsesCannotProduceAnApk() {
        for ((body, advertised, status) in listOf(Triple(data.copyOf(8), data.size.toLong(), 200),
            Triple(data + data, (data.size * 2).toLong(), 200), Triple(data, data.size.toLong(), 302))) {
            response(body, advertised, status) { site, root ->
                val cache = File(root, "cache")
                rejected { StudioUpdateDownload.download(site, release(), cache, { _, _ -> fail("Must not verify") }, {}) }
                assertTrue(cache.listFiles().orEmpty().none { it.extension in listOf("part", "apk") })
            }
        }
    }

    @Test fun busyCacheAndUnknownFilesArePreserved() = response(data) { site, root ->
        val cache = File(root, "cache")
        StudioUpdateDownload.withCacheLease(cache) {
            rejected { StudioUpdateDownload.download(site, release(), cache, { _, _ -> }, {}) }
        }
        val unknown = File(cache, "keep.txt").apply { writeText("unowned") }
        rejected { StudioUpdateDownload.download(site, release(), cache, { _, _ -> }, {}) }
        assertEquals("unowned", unknown.readText())
    }

    @Test fun cancelledSocketCannotLeaveAReadyCandidate() {
        val root = Files.createTempDirectory("zhigou-update-cancel-").toFile()
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val thread = Thread {
                runCatching { server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
                        write(data, 0, 8); flush()
                    }
                    started.countDown(); finish.await(3, TimeUnit.SECONDS)
                } }
            }.apply { isDaemon = true; start() }
            try {
                runBlocking {
                    val job = launch { StudioUpdateDownload.download(StudioUpdateSite.parse("http://127.0.0.1:${server.localPort}"),
                        release(), File(root, "cache"), { _, _ -> fail("Cancelled file must not be verified") }, {}) }
                    kotlinx.coroutines.yield()
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    job.cancelAndJoin()
                }
            } finally { finish.countDown(); server.close(); thread.join(3500) }
            // The same worker must finish cancellation cleanup before the next queued operation.
            runBlocking { StudioUpdateClient.perform(5000) { } }
            assertTrue(File(root, "cache").listFiles().orEmpty().none { it.extension in listOf("part", "apk") })
        }
        root.deleteRecursively()
    }

    @Test fun updateCacheBudgetRejectsBeforeAnyNetworkRequest() = response(data) { site, root ->
        rejected { StudioUpdateDownload.download(site, release().copy(bytes = MAX_STUDIO_UPDATE_BYTES + 1),
            File(root, "cache"), { _, _ -> }, {}) }
        assertFalse(File(root, "cache").exists())
    }
}
