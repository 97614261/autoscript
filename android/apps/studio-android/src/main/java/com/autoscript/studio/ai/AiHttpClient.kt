package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AiServiceException(message: String) : Exception(message)

/** One explicitly started request, bounded on wire and cancellable even during a blocking read. */
internal class AiHttpCall(private val provider: AiProvider) {
    private val lock = Any()
    private var cancelled = false
    private var connection: HttpURLConnection? = null

    fun cancel() {
        val active = synchronized(lock) { cancelled = true; connection.also { connection = null } }
        active?.disconnect()
    }

    suspend fun complete(messages: List<AiMessage>, tokenBudget: Int = 4096): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            workers.execute {
                try {
                    val result = request(messages, tokenBudget)
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is CancellationException, is AiServiceException -> failure
                        is SocketTimeoutException -> AiServiceException("模型响应超时，可重试或更换模型")
                        else -> AiServiceException("无法连接模型接口，请检查地址、网络和证书")
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            continuation.resumeWithException(AiServiceException("上一次连接仍在清理，请稍后重试"))
        }
    }

    private fun request(messages: List<AiMessage>, tokenBudget: Int): String {
        provider.validate()
        require(messages.size in 1..14 && tokenBudget in 16..8192)
        val body = JsonObject().apply {
            addProperty("model", provider.model.trim()); addProperty("stream", false)
            addProperty(if (provider.completionTokens) "max_completion_tokens" else "max_tokens", tokenBudget)
            add("messages", JsonArray().apply { messages.forEach { message ->
                require(message.role in setOf("system", "user", "assistant"))
                add(JsonObject().apply { addProperty("role", message.role); addProperty("content", message.content) })
            } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 384 * 1024) { "AI 上下文过大，请缩小选择范围或开始新对话" }
        val active = provider.endpoint().toURL().openConnection() as HttpURLConnection
        synchronized(lock) {
            if (cancelled) { active.disconnect(); throw CancellationException() }
            connection = active
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        try {
            active.requestMethod = "POST"; active.instanceFollowRedirects = false
            active.connectTimeout = 12_000; active.readTimeout = 90_000; active.doOutput = true
            active.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            active.setRequestProperty("Accept", "application/json")
            if (provider.apiKey.isNotEmpty()) active.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            active.setFixedLengthStreamingMode(body.size)
            active.outputStream.use { it.write(body) }
            val status = active.responseCode
            if (status != 200) throw AiServiceException(when (status) {
                401, 403 -> "接口拒绝访问，请检查密钥和模型权限"
                404 -> "接口或模型不存在，请检查完整地址和模型名称"
                429 -> "请求过快或额度不足，请稍后重试或检查账户额度"
                in 300..399 -> "接口要求跳转，请填写最终 HTTPS 接口地址"
                400, 422 -> "接口不接受请求，请检查模型及输出上限参数设置"
                else -> "模型服务暂不可用（HTTP $status），可稍后重试"
            })
            if (active.contentLengthLong > MAX_RESPONSE) throw AiServiceException("模型返回内容过大，请缩小任务范围")
            val response = ByteArrayOutputStream()
            active.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (synchronized(lock) { cancelled }) throw CancellationException()
                    if (System.nanoTime() >= deadline) throw SocketTimeoutException()
                    active.readTimeout = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 90_000).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (response.size() + count > MAX_RESPONSE) throw AiServiceException("模型返回内容过大，请缩小任务范围")
                    response.write(buffer, 0, count)
                }
            }
            return parseAiCompletion(response.toString("UTF-8"))
        } finally {
            synchronized(lock) { if (connection === active) connection = null }
            active.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE = 512 * 1024
        val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "studio-ai-http").apply { isDaemon = true } })
    }
}

internal fun parseAiCompletion(body: String): String {
    try {
        val json = aiJsonObject(body, 512 * 1024)
        val choices = json.getAsJsonArray("choices")
        if (choices == null || choices.size() != 1) throw AiServiceException("模型返回格式无效，没有得到唯一答复")
        val choice = choices[0].asJsonObject
        if (choice["finish_reason"]?.asString == "length") throw AiServiceException("回答达到输出上限，请缩小任务或分段生成")
        if (choice["finish_reason"]?.asString in setOf("content_filter", "tool_calls", "function_call"))
            throw AiServiceException("模型未返回可用文字，请调整需求后重试")
        val message = choice.getAsJsonObject("message")
        val value = message?.get("content")
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank())
            throw AiServiceException("模型没有返回文字，请调整需求后重试")
        return value.asString.also { if (it.toByteArray(Charsets.UTF_8).size > 128 * 1024) throw AiServiceException("回答过长，请分段生成") }
    } catch (failure: AiServiceException) { throw failure }
    catch (_: Exception) { throw AiServiceException("模型返回格式无效，请确认接口兼容聊天补全协议") }
}
