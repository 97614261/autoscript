package com.autoscript.studio

import java.net.URI

internal data class AiProvider(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val completionTokens: Boolean = false,
) {
    override fun toString(): String = "AiProvider(configured=${baseUrl.isNotBlank() && model.isNotBlank()}, keyPresent=${apiKey.isNotEmpty()})"

    fun endpoint(): URI {
        val uri = try { URI(baseUrl.trim()) } catch (_: Exception) { error("接口地址无效") }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
            "接口请使用 HTTPS；本机调试可使用 HTTP"
        }
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && baseUrl.length <= 512) { "接口地址不能包含账号、查询参数或片段" }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return URI("${uri.scheme}://${uri.rawAuthority}${if (path.endsWith("/chat/completions")) path else "$path/chat/completions"}")
    }

    fun validate() {
        endpoint()
        require(model.isNotBlank() && model.length <= 160 && model.none { it.isISOControl() }) { "请填写模型名称" }
        require(apiKey.length <= 2048 && apiKey.all { it.code in 33..126 }) { "密钥包含空格或无效字符" }
    }
}

internal enum class AiMode(val title: String) { GENERATE("生成"), MODIFY("修改"), EXPLAIN("解释"), DIAGNOSE("排错") }
internal data class AiMessage(val role: String, val content: String)
internal data class AiTurn(val question: String, val response: String)
