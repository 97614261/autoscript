package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class StudioCloudClientTest {
    private val token = "a".repeat(64)
    private val key = "b".repeat(64)
    private fun session(origin: String) = StudioAccountSession(origin, 7, "公开测试账号", token, key, 1_999_999_999_999, true)
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private data class Response(val body: ByteArray, val status: Int = 200, val type: String = "application/json", val extra: String = "",
        val declaredLength: Int = body.size, val drop: Boolean = false)
    private fun json(value: String) = Response(value.toByteArray(Charsets.UTF_8))
    private class Fixture(private val action: (Request) -> Response) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val stopped = AtomicBoolean()
        @Volatile private var failure: Throwable? = null
        val origin = "http://127.0.0.1:${server.localPort}/"
        private val worker = Thread {
            try {
                while (!stopped.get()) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val output = StringBuilder()
                        while (true) { val ch = input.read(); if (ch < 0 || ch == 10) break; if (ch != 13) output.append(ch.toChar()) }
                        return output.toString()
                    }
                    val start = line().split(' ')
                    val headers = linkedMapOf<String, String>()
                    while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length); var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
                    val result = action(Request(start[0], start[1], headers, body))
                    if (!result.drop) socket.getOutputStream().write(("HTTP/1.1 ${result.status} Fixture\r\nContent-Type: ${result.type}\r\nContent-Length: ${result.declaredLength}\r\n${result.extra}Connection: close\r\n\r\n").toByteArray() + result.body)
                }
            } catch (error: Throwable) { if (!stopped.get()) failure = error }
        }.apply { isDaemon = true; start() }
        override fun close() { stopped.set(true); server.close(); worker.join(6000); failure?.let { throw AssertionError("Local account fixture failed", it) } }
    }

    private fun rejects(block: () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected rejection")
    }

    @Test fun requiresExplicitHttpAndRejectsCredentialBearingOrigins() {
        rejects { StudioCloudClient("http://example.com", false) }
        rejects { StudioCloudClient("https://user:secret@example.com", false) }
        rejects { StudioCloudClient("https://example.com/?token=secret", false) }
        rejects { StudioCloudClient("https://example.com", false, session("https://elsewhere.example/")) }
    }

    @Test fun sessionDisplayNeverContainsTokenOrEncryptionKey() {
        val value = session("https://example.com/")
        assertFalse(value.toString().contains(token)); assertFalse(value.toString().contains(key))
        rejects { value.copy(token = "bad").validate() }
    }

    @Test fun loginUsesCamelCaseContractAndReturnsStableAccountCredentials() {
        Fixture { request ->
            assertEquals("POST", request.method); assertEquals("/api/studio/v1/auth/login", request.path)
            assertFalse(request.headers.containsKey("authorization"))
            val body = JsonParser.parseString(request.body.toString(Charsets.UTF_8)).asJsonObject
            assertEquals("测试账号", body["userName"].asString); assertEquals("公开测试密码123", body["password"].asString)
            json("""{"code":200,"msg":"操作成功","data":{"account":{"accountId":7,"userName":"测试账号"},"token":"$token","backupKey":"$key","expiresAt":1999999999999}}""")
        }.use { server -> StudioCloudClient(server.origin, true).use { client ->
            val value = client.login("测试账号", "公开测试密码123", true)
            assertEquals(7L, value.accountId); assertEquals(key, value.backupKey)
        } }
    }

    @Test fun registerCarriesConfirmationAndDisplaysBusinessFailure() {
        Fixture { request ->
            assertEquals("/api/studio/v1/auth/register", request.path)
            assertEquals("confirmation", JsonParser.parseString(request.body.toString(Charsets.UTF_8)).asJsonObject["confirmPassword"].asString)
            json("""{"code":500,"msg":"两次密码不一致"}""")
        }.use { server -> StudioCloudClient(server.origin, true).use { client ->
            val failure = rejects { client.register("公开账号", "password", "confirmation") }
            assertTrue(failure is StudioCloudFailure); assertEquals("两次密码不一致", (failure as StudioCloudFailure).userMessage)
        } }
    }

    @Test fun changePasswordUsesAuthenticatedEndpointAndKeepsBackupKey() {
        Fixture { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/studio/v1/auth/password", request.path)
            assertEquals("Bearer $token", request.headers["authorization"])
            val body = JsonParser.parseString(request.body.toString(Charsets.UTF_8)).asJsonObject
            assertEquals(setOf("oldPassword", "newPassword", "confirmPassword"), body.keySet())
            assertEquals("old-password", body["oldPassword"].asString)
            assertEquals("new-password", body["newPassword"].asString)
            assertEquals("new-password", body["confirmPassword"].asString)
            json("""{"code":200,"msg":"密码已修改"}""")
        }.use { server ->
            val active = session(server.origin)
            StudioCloudClient(server.origin, true, active).use { it.changePassword("old-password", "new-password", "new-password") }
            assertEquals(key, active.backupKey)
        }
    }

    @Test fun passwordLimitsCountUtf8Bytes() {
        assertFalse(studioPasswordInputValid("1234567"))
        assertTrue(studioPasswordInputValid("12345678"))
        assertTrue(studioPasswordInputValid("中".repeat(24)))
        assertFalse(studioPasswordInputValid("中".repeat(25)))
    }

    @Test fun encryptedBackupUploadListDownloadReplaceAndDelete() {
        val id = "c".repeat(32)
        val label = ServerBackupCrypto.encryptName("公开脚本和图像", key, id)
        var stored = byteArrayOf()
        var record: JsonObject? = null
        Fixture { request ->
            assertEquals("Bearer $token", request.headers["authorization"])
            when {
                request.method == "POST" -> {
                    if (request.path.endsWith("/replace")) assertEquals("\"${record!!["sha256"].asString}\"", request.headers["if-match"])
                    val meta = JsonParser.parseString(ServerBackupCrypto.unbase64(request.headers.getValue("x-backup-metadata")).toString(Charsets.UTF_8)).asJsonObject
                    assertEquals(ServerBackupCrypto.sha(request.body), meta["sha256"].asString)
                    stored = request.body
                    record = meta.apply { addProperty("createdAt", 1_800_000_000_000) }
                    json(JsonObject().apply { addProperty("code", 200); add("data", record) }.toString())
                }
                request.path.contains("/content") -> Response(stored, type = "application/octet-stream", extra = "ETag: \"${record!!["sha256"].asString}\"\r\n")
                request.method == "DELETE" -> { record = null; json("""{"code":200,"msg":"操作成功"}""") }
                else -> json(JsonObject().apply { addProperty("code", 200); addProperty("total", if (record == null) 0 else 1)
                    add("rows", JsonArray().apply { record?.let { add(it) } }) }.toString())
            }
        }.use { server ->
            StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                val file = File.createTempFile("cloud-owned-", ".zgb")
                val downloaded = File.createTempFile("cloud-owned-download-", ".zgb")
                try {
                    val plain = "公开QA项目zip与资源".toByteArray()
                    file.outputStream().use { ServerBackupCrypto.encrypt(plain.inputStream(), it, key, id) }
                    val uploaded = client.upload(file, id, label)
                    assertEquals(uploaded, client.list().single())
                    client.download(uploaded, downloaded)
                    val output = java.io.ByteArrayOutputStream()
                    downloaded.inputStream().use { ServerBackupCrypto.decrypt(it, output, key, id) }
                    assertArrayEquals(plain, output.toByteArray())
                    file.outputStream().use { ServerBackupCrypto.encrypt("修改后的公开项目".byteInputStream(), it, key, id) }
                    val replaced = client.upload(file, id, label, uploaded)
                    assertNotEquals(uploaded.sha256, replaced.sha256)
                    client.delete(replaced); assertTrue(client.list().isEmpty())
                } finally { file.delete(); downloaded.delete() }
            }
        }
    }

    @Test fun expiredSessionIsAnAuthenticationError() {
        Fixture { json("""{"code":401,"msg":"认证失败"}""") }.use { server ->
            StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                val failure = rejects { client.list() }
                assertEquals("CLOUD_SESSION_EXPIRED", failure.message)
            }
        }
    }

    @Test fun duplicateJsonAndRedirectAreRejected() {
        Fixture { json("""{"code":200,"code":401,"rows":[],"total":0}""") }.use { server ->
            StudioCloudClient(server.origin, true, session(server.origin)).use { rejects { it.list() } }
        }
        Fixture { Response(byteArrayOf(), 302, extra = "Location: https://elsewhere.example/\r\n") }.use { server ->
            StudioCloudClient(server.origin, true).use { client ->
                assertEquals("CLOUD_REDIRECT_REJECTED", rejects { client.login("公开账号", "password", true) }.message)
            }
        }
    }

    @Test fun closedClientRejectsMoreWork() {
        val client = StudioCloudClient("https://example.com", false)
        client.close()
        assertTrue(rejects { client.list() } is CancellationException)
    }

    @Test fun lostUploadReplyDoesNotAutomaticallyRepeatWrite() {
        var uploads = 0
        val id = "c".repeat(32)
        val file = File.createTempFile("cloud-lost-reply-", ".zgb")
        try {
            file.writeBytes(ByteArray(64) { 17 })
            Fixture { request ->
                assertEquals("POST", request.method);uploads++
                assertArrayEquals(file.readBytes(), request.body)
                Response(byteArrayOf(), drop = true)
            }.use { server -> StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                rejects { client.upload(file, id, ServerBackupCrypto.encryptName("公开项目", key, id)) }
                assertEquals(1, uploads)
            } }
        } finally { file.delete() }
    }

    @Test fun truncatedRestoreDownloadNeverCountsAsComplete() {
        val id = "c".repeat(32)
        val bytes = ByteArray(64) { 29 }
        val digest = ServerBackupCrypto.sha(bytes)
        val item = ServerBackupRecord(id, ServerBackupCrypto.encryptName("公开项目", key, id), bytes.size.toLong(), digest, 1_800_000_000_000)
        val file = File.createTempFile("cloud-truncated-", ".zgb")
        try {
            Fixture { Response(bytes.copyOf(32), type = "application/octet-stream", extra = "ETag: \"$digest\"\r\n", declaredLength = bytes.size) }
                .use { server -> StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                    rejects { client.download(item, file) }
                    assertTrue(file.length() < item.bytes)
                } }
        } finally { file.delete() }
    }

    @Test fun capacityAndOverloadRemainReadableBusinessFailures() {
        for ((code, message) in listOf(409 to "备份数量或容量已满", 429 to "请求过于频繁，请稍后重试")) {
            Fixture { json("""{"code":$code,"msg":"$message"}""") }.use { server ->
                StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                    val failure = rejects { client.list() } as StudioCloudFailure
                    assertEquals(code, failure.code);assertEquals(message, failure.userMessage)
                }
            }
        }
    }

    @Test fun cancellationDuringPendingResponseRemainsCancellation() {
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var outcome: Throwable? = null
        Fixture { received.countDown();check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            Response(byteArrayOf(), drop = true)
        }.use { server ->
            StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                val request = Thread { outcome = rejects { client.list() } }.apply { start() }
                assertTrue(received.await(2, java.util.concurrent.TimeUnit.SECONDS))
                val cancel = Thread { client.close() }.apply { start() }
                Thread.sleep(100);release.countDown()
                cancel.join(2500);request.join(2500)
                assertFalse(cancel.isAlive);assertFalse(request.isAlive)
                assertTrue(outcome is CancellationException)
            }
        }
    }

    @Test fun stalledUploadReplyTimesOutWithoutRepeatingWrite() {
        val release = java.util.concurrent.CountDownLatch(1)
        var uploads = 0
        val file = File.createTempFile("cloud-stalled-reply-", ".zgb")
        val id = "c".repeat(32)
        try {
            file.writeBytes(ByteArray(64) { 31 })
            Fixture { uploads++;release.await(40, java.util.concurrent.TimeUnit.SECONDS)
                Response(byteArrayOf(), drop = true)
            }.use { server -> StudioCloudClient(server.origin, true, session(server.origin)).use { client ->
                val began = System.nanoTime()
                val failure = try { rejects { client.upload(file, id, ServerBackupCrypto.encryptName("公开项目", key, id)) } }
                    finally { release.countDown() }
                val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began)
                assertTrue(failure is java.io.IOException)
                assertTrue("request timeout must remain bounded", elapsed in 25..40)
                assertEquals(1, uploads)
            } }
        } finally { release.countDown();file.delete() }
    }
}
