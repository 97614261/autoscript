package com.autoscript.studio

import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test

class ServerBackupClientTest {
    private val key = "ab".repeat(32)
    private class FakeServer(private val key: String, private val corrupt: String = "", private val replacement: Boolean = true) : Closeable {
        val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val error = AtomicReference<Throwable?>()
        private var stored: JsonObject? = null
        private var payload = byteArrayOf()
        private val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 3000
                    val input = client.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ArrayList<Byte>()
                        while (true) { val b = input.read(); require(b >= 0); if (b == 10) break; if (b != 13) bytes.add(b.toByte()) }
                        return bytes.toByteArray().toString(Charsets.US_ASCII)
                    }
                    val request = line().split(' ')
                    val method = request[0]; val path = request[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length)
                    java.io.DataInputStream(input).readFully(body)
                    val nonce = headers.getValue("x-publish-nonce")
                    val ticket = headers.getValue("x-publish-challenge")
                    val signature = headers.getValue("x-publish-signature")
                    val meta = headers["x-release-metadata"].orEmpty()
                    assertEquals(ServerBackupCrypto.sha(body), headers["x-publish-body-sha256"])
                    assertEquals(ServerBackupCrypto.mac(key, "zhigou-publish-request-v1", method, path, nonce, ticket,
                        headers["content-type"].orEmpty(), length.toString(), ServerBackupCrypto.sha(meta.toByteArray()),
                        headers["if-match"].orEmpty(), ServerBackupCrypto.sha(body)), signature)
                    var mime = "application/json"
                    val response = when {
                        path.endsWith("/challenge") -> """{"protocol":1,"nonce":"$nonce","challenge":"${"11".repeat(32)}","validForSeconds":120}""".toByteArray()
                        method == "POST" && (path.endsWith("/backups") || path.endsWith("/replace")) -> {
                            if (path.endsWith("/replace")) {
                                assertEquals("\"${stored!!["sha256"].asString}\"", headers["if-match"])
                                assertEquals("/api/project-backup/v1/backups/${stored!!["id"].asString}/replace", path)
                            } else assertTrue(headers["if-match"].isNullOrEmpty())
                            stored = JsonParser.parseString(ServerBackupCrypto.unbase64(meta).toString(Charsets.UTF_8)).asJsonObject.apply { addProperty("createdAt", 1234L) }
                            payload = body
                            """{"ok":true,"record":$stored}""".toByteArray()
                        }
                        path.endsWith("/delete") -> { val id = stored!!["id"].asString; stored = null; """{"ok":true,"id":"$id"}""".toByteArray() }
                        path.endsWith("/backups") -> """{"protocol":1,"records":[${stored ?: ""}],"bytes":${stored?.get("bytes") ?: 0}${if (replacement) ",\"capabilities\":[\"replace\"]" else ""}}""".toByteArray()
                        else -> { mime = "application/octet-stream"; payload }
                    }
                    val responseSignature = ServerBackupCrypto.mac(key, "zhigou-publish-response-v1", method, path, nonce, signature, "200", ServerBackupCrypto.sha(response))
                    val actual = if (corrupt == "body") response + byteArrayOf(32) else response
                    val head = if (corrupt == "redirect") "HTTP/1.1 302 Found\r\nLocation: http://example.invalid/\r\nContent-Length: 0\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${actual.size}\r\nX-Publish-Signature: ${if (corrupt == "signature") "00".repeat(32) else responseSignature}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
                    if (corrupt != "redirect") client.getOutputStream().write(actual)
                }
            } catch (failure: Throwable) { if (!socket.isClosed) error.set(failure) }
        }
        override fun close() { socket.close(); worker.join(4000); error.get()?.let { throw AssertionError(it) } }
    }
    @Test fun signedUploadListDownloadDelete() {
        FakeServer(key).use { server ->
            ServerBackupClient(server.origin, key).use { client ->
                assertTrue(client.list().isEmpty())
                val source = File.createTempFile("backup-test-", ".zgb")
                val destination = File.createTempFile("restore-test-", ".zgb")
                try {
                    val id = ServerBackupCrypto.id()
                    val label = ServerBackupCrypto.encryptName("公共合成项目", key, id)
                    source.outputStream().use { ServerBackupCrypto.encrypt(byteArrayOf(1, 2, 3).inputStream(), it, key, id) }
                    val record = client.upload(source, id, label)
                    assertEquals(listOf(record), client.list())
                    client.download(record, destination)
                    assertArrayEquals(source.readBytes(), destination.readBytes())
                    client.delete(record)
                    assertTrue(client.list().isEmpty())
                } finally { source.delete(); destination.delete() }
            }
        }
    }
    @Test fun signatureTamperingBodyTamperingAndRedirectReject() {
        for (corrupt in listOf("signature", "body", "redirect")) FakeServer(key, corrupt).use { server ->
            ServerBackupClient(server.origin, key).use { client ->
                try { client.list(); fail("must reject $corrupt") } catch (expected: Exception) { }
            }
        }
    }
    @Test fun signedReplaceKeepsIdAndBindsThePreviousDigest() {
        FakeServer(key).use { server ->
            ServerBackupClient(server.origin, key).use { client ->
                assertTrue(client.list().isEmpty())
                assertTrue(client.supportsReplacement)
                val source = File.createTempFile("backup-replace-", ".zgb")
                val destination = File.createTempFile("backup-replaced-", ".zgb")
                try {
                    val id = ServerBackupCrypto.id()
                    val label = ServerBackupCrypto.encryptName("公共合成项目", key, id)
                    source.outputStream().use { ServerBackupCrypto.encrypt(byteArrayOf(1).inputStream(), it, key, id) }
                    val old = client.upload(source, id, label)
                    source.outputStream().use { ServerBackupCrypto.encrypt(byteArrayOf(2, 3).inputStream(), it, key, id) }
                    val current = client.upload(source, id, label, replacing = old)
                    assertEquals(old.id, current.id)
                    assertNotEquals(old.sha256, current.sha256)
                    assertEquals(listOf(current), client.list())
                    client.download(current, destination)
                    destination.inputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        ServerBackupCrypto.decrypt(input, output, key, id)
                        assertArrayEquals(byteArrayOf(2, 3), output.toByteArray())
                    }
                } finally { source.delete(); destination.delete() }
            }
        }
    }
    @Test fun legacyServerStillListsAndAddsButDoesNotFallbackFromReplace() {
        FakeServer(key, replacement = false).use { server ->
            ServerBackupClient(server.origin, key).use { client ->
                assertTrue(client.list().isEmpty())
                assertFalse(client.supportsReplacement)
                val source = File.createTempFile("backup-legacy-", ".zgb")
                try {
                    val id = ServerBackupCrypto.id()
                    val label = ServerBackupCrypto.encryptName("旧服务合成项目", key, id)
                    source.outputStream().use { ServerBackupCrypto.encrypt(byteArrayOf(1).inputStream(), it, key, id) }
                    val old = client.upload(source, id, label)
                    try { client.upload(source, id, label, replacing = old); fail("must reject unsupported replacement") }
                    catch (expected: IllegalStateException) { assertEquals("BACKUP_REPLACE_UNSUPPORTED", expected.message) }
                    assertEquals(listOf(old), client.list())
                    try { client.upload(source, ServerBackupCrypto.id(), label, replacing = old); fail("must bind target ID") }
                    catch (expected: IllegalArgumentException) { assertEquals("BACKUP_REPLACE_TARGET_INVALID", expected.message) }
                } finally { source.delete() }
            }
        }
    }
    @Test fun closedClientNeverConnects() {
        val client = ServerBackupClient("http://127.0.0.1:1", key)
        client.close()
        try { client.list(); fail("must cancel") } catch (expected: java.util.concurrent.CancellationException) { }
    }
    @Test fun invalidOriginAndKeyReject() {
        for (origin in listOf("http://user:secret@example.test", "http://example.test/private", "file:///tmp")) {
            try { ServerBackupClient(origin, key); fail("invalid origin") } catch (expected: Exception) { }
        }
        try { ServerBackupClient("http://127.0.0.1", "bad"); fail("invalid key") } catch (expected: Exception) { }
    }
}
