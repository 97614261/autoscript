package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiHttpClientTest {
    private fun completion(content: String, finish: String = "stop") = JsonObject().apply {
        add("choices", com.google.gson.JsonArray().apply { add(JsonObject().apply {
            addProperty("finish_reason", finish)
            add("message", JsonObject().apply { addProperty("content", content) })
        }) })
    }.toString()
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    private fun serve(status: Int = 200, body: String, headers: String = "", inspect: (String) -> Unit = {}, action: (AiProvider) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            var serverFailure: Throwable? = null
            val worker = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val head = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; head += line }
                    val length = head.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val data = CharArray(length); var offset = 0
                    while (offset < data.size) { val read = reader.read(data, offset, data.size - offset); if (read < 0) break; offset += read }
                    inspect(head.joinToString("\n") + "\n\n" + String(data, 0, offset))
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n$headers" + "Connection: close\r\n\r\n").toByteArray() + bytes)
                } } catch (failure: Throwable) { serverFailure = failure }
            }.apply { isDaemon = true; start() }
            try { action(AiProvider("http://127.0.0.1:${server.localPort}/v1", "PUBLIC_QA_KEY", "qa-model")) }
            finally { server.close(); worker.join(6000) }
            serverFailure?.let { throw AssertionError("Local fixture server failed", it) }
        }
    }
    @Test fun normalizesBaseAndFullEndpointWithoutLeakingKeys() {
        assertEquals("https://example.com/v1/chat/completions", AiProvider("https://example.com/v1/").endpoint().toString())
        assertEquals("https://example.com/v1/chat/completions", AiProvider("https://example.com/v1/chat/completions").endpoint().toString())
        assertFalse(AiProvider(apiKey = "PUBLIC_TEST_KEY").toString().contains("PUBLIC_TEST_KEY"))
    }
    @Test fun rejectsCleartextRemoteCredentialsQueryAndHeaderInjection() {
        for (address in listOf("http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/#fragment", "file:///tmp/ai"))
            rejects { AiProvider(address).endpoint() }
        rejects { AiProvider("https://example.com", "secret\r\nX-Test: injected", "model").validate() }
    }
    @Test fun completionRejectsTruncatedFilteredAndToolCallResponses() {
        assertEquals("hello", parseAiCompletion(completion("hello")))
        for (reason in listOf("length", "content_filter", "tool_calls")) {
            try { parseAiCompletion(completion("partial", reason)); fail("Must reject $reason") } catch (_: AiServiceException) { }
        }
        try { parseAiCompletion("{\"choices\":[]}"); fail("No unique reply") } catch (_: AiServiceException) { }
    }
    @Test fun sendsAuthenticatedBoundedChatRequestAndReadsText() {
        serve(body = completion("OK"), inspect = { request ->
            assertTrue(request.startsWith("POST /v1/chat/completions "))
            assertTrue(request.contains("Authorization: Bearer PUBLIC_QA_KEY", true))
            val json = JsonParser.parseString(request.substringAfter("\n\n")).asJsonObject
            assertEquals("qa-model", json["model"].asString)
            assertEquals(64, json["max_tokens"].asInt); assertFalse(json["stream"].asBoolean)
        }) { provider -> runBlocking { assertEquals("OK", AiHttpCall(provider).complete(listOf(AiMessage("user", "test")), 64)) } }
    }
    @Test fun supportsNewModelTokenField() {
        serve(body = completion("OK"), inspect = { request ->
            val json = JsonParser.parseString(request.substringAfter("\n\n")).asJsonObject
            assertEquals(64, json["max_completion_tokens"].asInt); assertFalse(json.has("max_tokens"))
        }) { provider -> runBlocking { AiHttpCall(provider.copy(completionTokens = true)).complete(listOf(AiMessage("user", "test")), 64) } }
    }
    @Test fun neverFollowsRedirectOrExposesProviderErrorBody() {
        for (status in listOf(302, 401, 429, 500)) serve(status, "PRIVATE_PROVIDER_ERROR", "Location: https://example.com/steal\r\n") { provider ->
            runBlocking {
                try { AiHttpCall(provider).complete(listOf(AiMessage("user", "test"))); fail("HTTP error must fail") }
                catch (failure: AiServiceException) { assertFalse(failure.message.orEmpty().contains("PRIVATE_PROVIDER_ERROR")) }
            }
        }
    }
    @Test fun cancellationReturnsPromptlyWithoutWaitingForReadTimeout() = runBlocking {
        val accepted = CountDownLatch(1); val release = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val thread = Thread { runCatching { server.accept().use { accepted.countDown(); release.await(5, TimeUnit.SECONDS) } } }.apply { isDaemon = true; start() }
            val call = AiHttpCall(AiProvider("http://127.0.0.1:${server.localPort}", model = "qa"))
            try {
                val job = async(start = CoroutineStart.UNDISPATCHED) { call.complete(listOf(AiMessage("user", "test"))) }
                assertTrue(withContext(Dispatchers.IO) { accepted.await(3, TimeUnit.SECONDS) })
                withTimeout(1500) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally { call.cancel(); release.countDown(); server.close(); withContext(Dispatchers.IO) { thread.join(1500) } }
        }
    }
}
