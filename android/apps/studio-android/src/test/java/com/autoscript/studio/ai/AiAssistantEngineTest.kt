package com.autoscript.studio

import com.autoscript.project.store.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AiAssistantEngineTest {
    private fun context() = AiEditorContext("main", "公开流程", "block-main", "", emptySet(), null, null,
        ProjectManifestDocument(projectId = "public-qa", name = "公开项目", sourceMode = ProjectSourceMode.VISUAL,
            flows = listOf(ProjectFlow("main", "flows/main.jsonl", "block-main")),
            variables = listOf(ProjectVariable("次数", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
                ProjectVariable("其他插件变量", ProjectVariableScope.FLOW, flowId = "other", type = ProjectVariableType.STRING)),
            resources = listOf(JsonObject().apply { addProperty("path", "assets/images/public.png"); addProperty("kind", "image") })),
        "公开测试错误")

    private fun serve(vararg replies: String, action: (AiProvider, List<JsonObject>) -> Unit) {
        val requests = mutableListOf<JsonObject>()
        var failure: Throwable? = null
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = Thread {
                try {
                    replies.forEach { reply -> server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val headers = ByteArrayOutputStream()
                        while (!headers.toString("US-ASCII").endsWith("\r\n\r\n")) {
                            val byte = input.read(); check(byte >= 0 && headers.size() < 16_384); headers.write(byte)
                        }
                        val length = headers.toString("US-ASCII").lineSequence().first { it.startsWith("Content-Length:", true) }
                            .substringAfter(':').trim().toInt()
                        check(length in 1..393_216)
                        val body = ByteArray(length); var offset = 0
                        while (offset < length) { val size = input.read(body, offset, length - offset); check(size > 0); offset += size }
                        requests += JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
                        val response = JsonObject().apply { add("choices", JsonArray().apply { add(JsonObject().apply {
                            addProperty("finish_reason", "stop"); add("message", JsonObject().apply { addProperty("content", reply) })
                        }) }) }.toString().toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray() + response)
                    } }
                } catch (error: Throwable) { failure = error }
            }.apply { isDaemon = true; start() }
            try { action(AiProvider("http://127.0.0.1:${server.localPort}/v1", "PUBLIC_QA_KEY", "public-model"), requests) }
            finally { server.close(); worker.join(6000) }
        }
        failure?.let { throw AssertionError("Public local model fixture failed", it) }
        assertEquals(replies.size, requests.size)
    }

    @Test fun twoStepGenerationUsesActualContractsAndVisibleProjectDeclarations() {
        serve("""{"kinds":["control.repeat","task.sleep"]}""",
            """{"message":"重复等待","blocks":[{"kind":"control.repeat","args":{"times":2},"children":{"body":[{"kind":"task.sleep","args":{"milliseconds":500}}]}}]}""") { provider, requests ->
            val progress = mutableListOf<String>()
            val answer = runBlocking { withTimeout(5000) { AiAssistantEngine(provider).answer(context(), AiMode.GENERATE, "等待500毫秒，重复两次", emptyList(), progress::add) } }
            assertEquals("control.repeat", answer.blocks.single().kind)
            val source = aiCandidate("", "block-main", emptySet(), null, answer, AiMode.GENERATE, AiInsertPlace.END, null, VisualProjectIds()).source
            assertEquals(2, parseVisualNodes(source, "block-main").size)
            assertEquals(2, progress.size)
            val selection = requests[0].getAsJsonArray("messages")[0].asJsonObject["content"].asString
            assertTrue(selection.contains("task.sleep")); assertFalse(selection.contains("legacy.tap"))
            val messages = requests[1].getAsJsonArray("messages")
            assertTrue(messages[0].asJsonObject["content"].asString.contains("milliseconds"))
            val sent = messages.last().asJsonObject["content"].asString
            assertTrue(sent.contains("次数")); assertTrue(sent.contains("assets/images/public.png"))
            assertFalse(sent.contains("其他插件变量")); assertFalse(sent.contains("PUBLIC_QA_KEY"))
        }
    }

    @Test fun followUpIncludesPriorTurnAndChangesOnlySelectedArguments() {
        val editor = VisualEditorState.create("", "block-main")
        editor.insertBlocks(listOf(requireNotNull(BlockCatalog.find("task.sleep")) to JsonObject().apply { addProperty("milliseconds", 500) }), EditorInsertPosition.BELOW)
        val id = editor.rows.single().nodeId
        val ctx = context().copy(source = editor.currentSource, selection = setOf(id), primary = id)
        val reply = """{"message":"改成1秒","updates":[{"nodeId":"$id","args":{"milliseconds":1000}}]}"""
        serve("""{"kinds":["task.sleep"]}""", reply) { provider, requests ->
            val answer = runBlocking { AiAssistantEngine(provider).answer(ctx, AiMode.MODIFY, "改成1秒", listOf(AiTurn("之前的公开需求", "{\"message\":\"公开答复\"}"))) {} }
            val result = aiCandidate(ctx.source, ctx.root, ctx.selection, ctx.primary, answer, AiMode.MODIFY, AiInsertPlace.END, null, VisualProjectIds())
            assertEquals(1, result.changed); assertEquals(ctx.source, editor.currentSource)
            assertEquals("之前的公开需求", requests[1].getAsJsonArray("messages")[1].asJsonObject["content"].asString)
        }
    }

    @Test fun explanationAndDiagnosisNeedOnlyOneRequestAndCannotChangeTheProject() {
        for (mode in listOf(AiMode.EXPLAIN, AiMode.DIAGNOSE)) serve("""{"message":"当前插件为空，可以先添加等待操作。"}""") { provider, requests ->
            val result = runBlocking { AiAssistantEngine(provider).answer(context(), mode, "说明一下", emptyList()) {} }
            assertFalse(result.hasChanges)
            assertTrue(requests.single().getAsJsonArray("messages").last().asJsonObject["content"].asString.contains("公开测试错误"))
        }
    }

    @Test fun unknownFunctionStopsBeforeGeneration() {
        serve("""{"kinds":["shell.invented"]}""") { provider, _ ->
            try { runBlocking { AiAssistantEngine(provider).answer(context(), AiMode.GENERATE, "编造函数", emptyList()) {} }; fail("Unknown function must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("不可用函数")) }
        }
    }

    @Test fun missingVariableRemainsARequestToUserAndCannotBeApplied() {
        serve("""{"kinds":[]}""", """{"message":"先声明结果变量","missingResources":["整数变量：结果"]}""") { provider, _ ->
            val result = runBlocking { AiAssistantEngine(provider).answer(context(), AiMode.GENERATE, "识别后保存结果", emptyList()) {} }
            assertFalse(result.hasChanges); assertEquals(listOf("整数变量：结果"), result.missingResources)
        }
    }

    @Test fun explanationRejectsModelAttemptToModify() {
        serve("""{"message":"删除全部","replaceSelection":true}""") { provider, _ ->
            try { runBlocking { AiAssistantEngine(provider).answer(context(), AiMode.EXPLAIN, "解释", emptyList()) {} }; fail("Read only mode must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("不会修改")) }
        }
    }
}
