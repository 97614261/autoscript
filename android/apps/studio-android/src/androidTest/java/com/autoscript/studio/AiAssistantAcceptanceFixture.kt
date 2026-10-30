package com.autoscript.studio

import android.app.Instrumentation
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The service only sees a freshly owned public project and PUBLIC_QA_KEY. */
internal object AiAssistantAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val ui = UsageTestUi(test)
        val flow = initial.manifest.flows.single()
        val baseline = store.saveFlows(initial, mapOf(flow.flowId to ""))
        val ids = VisualProjectIds(baseline.flowSources.values, listOf(flow.rootBlockId))
        val editor = VisualEditorState.createProjectScoped("", flow.rootBlockId, ids)
        val editors = mapOf(flow.flowId to editor)
        val transactions = EditorProjectEdits()
        var request by mutableStateOf<AiEditorContext?>(null)
        var revision by mutableIntStateOf(0)
        var applied = 0
        val repository = object : AiSettingsRepository {
            @Volatile var value = AiProvider()
            override fun read() = value
            override fun save(value: AiProvider) { value.validate(); this.value = value }
        }
        fun open() = test.runOnMainSync {
            request = AiEditorContext(flow.flowId, "公开 AI 流程", flow.rootBlockId, editor.currentSource,
                editor.selectedNodeIds.toSet(), editor.selectedNodeId, null, baseline.manifest, "公开 QA 报错")
        }
        fun field(label: String, value: String) {
            fun editable(): android.view.accessibility.AccessibilityNodeInfo? {
                val anchor = ui.label(label) ?: return null
                val bounds = try { android.graphics.Rect().also(anchor::getBoundsInScreen) } finally { anchor.recycle() }
                return ui.find { node -> node.isEditable && android.graphics.Rect.intersects(bounds, android.graphics.Rect().also(node::getBoundsInScreen)) }
            }
            ui.await("AI field ready:$label") { editable()?.let { it.recycle(); true } == true }
            val node = requireNotNull(editable()) { "QA_AI_FIELD_MISSING:$label" }
            try { check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) }
            finally { node.recycle() }
            ui.await("AI input committed:$label") { editable()?.let { current -> try {
                if (current.isPassword) current.text?.length == value.length else current.text?.toString() == value
            } finally { current.recycle() } } == true }
        }
        fun ask(text: String) { field("补充需求…", text); ui.absent("停止"); ui.click("发送") }
        fun close() {
            ui.click("关闭")
            if (ui.exists("关闭 AI？")) ui.click("确定")
            ui.absent("AI 助手")
        }
        PublicModel().use { model ->
            test.runOnMainSync { activity.setContent { AutoScriptTheme {
                @Suppress("UNUSED_VARIABLE") val observe = revision
                AiEditorHost(request, ids, baseline, editors, flow.flowId, null, client, store, transactions,
                    canMutate = { true }, onChanged = { applied++; revision++ }, onDismiss = { request = null },
                    settingsRepository = repository)
            } } }
            try {
                record("ai-keystore-encrypted-own-namespace-roundtrip-and-tamper-rejection") {
                    val name = "studio-ai-qa-${UUID.randomUUID()}"
                    val settings = AiSettingsStore(test.targetContext, name)
                    val value = AiProvider(model.url, "PUBLIC_QA_KEY", "public-model")
                    val prefs = test.targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                    try {
                        settings.save(value); check(settings.read() == value)
                        check(prefs.all.values.none { it.toString().contains("PUBLIC_QA_KEY") })
                        check(prefs.edit().putString("configuration", "INVALID").commit())
                        check(runCatching { settings.read() }.isFailure)
                    } finally {
                        check(prefs.edit().clear().commit())
                        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$name-settings-v1") }
                    }
                }
                record("ai-real-settings-test-connection-save-without-user-settings") {
                    open(); ui.wait("模型名称")
                    field("接口地址（含 /v1 或完整 /chat/completions）", model.url)
                    field("API 密钥（本地无鉴权服务可留空）", "PUBLIC_QA_KEY")
                    field("模型名称", "public-model")
                    model.reply("OK"); ui.click("测试连接"); ui.wait("连接成功，模型可用；尚未保存配置")
                    ui.click("保存配置"); ui.wait("AI 配置已保存")
                    check(repository.value.model == "public-model"); check(editor.currentSource.isEmpty())
                }
                record("ai-real-dialog-nested-preview-native-full-validation-apply-and-undo-redo") {
                    model.reply("""{"kinds":["control.repeat","task.sleep"]}""")
                    model.reply("""{"message":"公开测试：重复等待两次","blocks":[{"kind":"control.repeat","args":{"times":2},"children":{"body":[{"kind":"task.sleep","args":{"milliseconds":500}}]}}]}""")
                    ask("公开测试：等待500毫秒，重复两次")
                    ui.wait("公开测试：重复等待两次"); check(editor.currentSource.isEmpty())
                    ui.click("预览修改"); ui.wait("校验通过，确认后应用到草稿，可撤销")
                    check(editor.currentSource.isEmpty()); check(store.openProject(initial.manifest.projectId).flowSources == baseline.flowSources)
                    ui.screenshot("ai-first-preview")
                    ui.click("确认应用"); ui.absent("AI 助手")
                    val after = editor.currentSource
                    check(applied == 1 && editor.rows.size == 2 && editor.rows.last().depth == 1)
                    test.runOnMainSync { check(editor.undo()); check(editor.currentSource.isEmpty()); check(editor.redo()); check(editor.currentSource == after) }
                    check(store.openProject(initial.manifest.projectId).flowSources == baseline.flowSources)
                }
                record("ai-selected-update-full-validation-feedback-followup-and-apply") {
                    test.runOnMainSync { editor.selectOnly(editor.rows.last().nodeId) }
                    val node = editor.rows.last().nodeId
                    open(); ui.wait("AI 助手"); ui.click("修改")
                    model.reply("""{"kinds":["task.sleep"]}""")
                    model.reply("""{"message":"公开无效等待","updates":[{"nodeId":"$node","args":{"milliseconds":-1}}]}""")
                    ask("公开测试无效参数"); ui.wait("公开无效等待"); ui.click("预览修改")
                    ui.await("native compiler rejected invalid argument") { ui.find { it.text?.toString()?.startsWith("方案校验未通过：") == true }?.let { it.recycle(); true } == true }
                    check(editor.nodeArguments(node)?.get("milliseconds")?.asInt == 500)
                    model.reply("""{"kinds":["task.sleep"]}""")
                    model.reply("""{"message":"公开已调整为1秒","updates":[{"nodeId":"$node","args":{"milliseconds":1000}}]}""")
                    ask("改为1000毫秒"); ui.wait("公开已调整为1秒")
                    check(model.lastRequest.contains("完整项目校验反馈"))
                    ui.click("预览修改"); ui.wait("校验通过，确认后应用到草稿，可撤销")
                    ui.click("确认应用"); ui.absent("AI 助手")
                    check(applied == 2 && editor.nodeArguments(node)?.get("milliseconds")?.asInt == 1000)
                    check(store.openProject(initial.manifest.projectId).flowSources == baseline.flowSources)
                }
                record("ai-explain-diagnose-followup-and-discard-without-mutation") {
                    val before = editor.currentSource
                    open(); ui.wait("AI 助手"); ui.click("解释")
                    model.reply("""{"message":"公开解释：循环等待两次。"}""")
                    ask("解释当前插件"); ui.wait("公开解释：循环等待两次。")
                    model.reply("""{"message":"公开追问：每次等待1秒。"}""")
                    ask("每次多久"); ui.wait("公开追问：每次等待1秒。")
                    check(model.lastRequest.contains("解释当前插件"))
                    ui.click("排错"); model.reply("""{"message":"公开排错：检查参数和目标画面。"}""")
                    ask("分析报错"); ui.wait("公开排错：检查参数和目标画面。")
                    ui.absent("预览修改"); close(); check(editor.currentSource == before)
                }
                record("ai-cancel-pending-request-retry-and-close-discard") {
                    val before = editor.currentSource
                    open(); ui.wait("AI 助手"); ui.click("解释")
                    val received = model.received.get()
                    val release = CountDownLatch(1)
                    model.reply("""{"message":"不应应用的延迟回复"}""", release)
                    ask("公开延迟测试")
                    ui.await("request reached public service") { model.received.get() > received }
                    ui.click("停止"); ui.wait("已停止生成，项目未修改"); release.countDown()
                    model.reply("""{"message":"公开取消后重试成功"}""")
                    ask("公开重试"); ui.wait("公开取消后重试成功")
                    field("补充需求…", "尚未发送的公开草稿")
                    ui.click("关闭"); ui.wait("关闭 AI？"); ui.click("返回"); ui.wait("AI 助手")
                    close(); check(editor.currentSource == before)
                }
                record("ai-stale-draft-blocks-preview-and-keeps-newer-edit") {
                    open(); ui.wait("AI 助手")
                    model.reply("""{"kinds":["task.sleep"]}""")
                    model.reply("""{"message":"公开过期方案","blocks":[{"kind":"task.sleep","args":{"milliseconds":10}}]}""")
                    ask("追加等待10毫秒"); ui.wait("公开过期方案")
                    test.runOnMainSync {
                        val node = editor.rows.last().nodeId
                        check(editor.updateArguments(node, JsonObject().apply { addProperty("milliseconds", 1500) })); revision++
                    }
                    ui.wait("项目或运行状态已变化，请关闭后重新打开 AI")
                    val before = editor.currentSource
                    close(); check(editor.currentSource == before && applied == 2)
                }
                check(client.runtimeStates.value.engineState !in setOf(com.autoscript.core.model.RuntimeEngineState.RUNNING, com.autoscript.core.model.RuntimeEngineState.PAUSED))
                check(model.failure == null) { "QA_AI_PUBLIC_SERVER_FAILED" }
                return store.openProject(initial.manifest.projectId)
            } catch (failure: Throwable) {
                val labels = linkedSetOf<String>()
                ui.find { node -> if (!node.isEditable) node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(700) }; false }
                test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_AI_VISIBLE:${labels.joinToString(" | ")}\nQA_AI_REQUESTS:${model.received.get()}\nQA_AI_MODEL_FAILURE:${model.failure?.javaClass?.simpleName}\n") })
                runCatching { ui.screenshot("ai-failure") }
                throw failure
            } finally { test.runOnMainSync { request = null } }
        }
    }

    private class PublicModel : java.io.Closeable {
        private data class Reply(val body: String, val gate: CountDownLatch?)
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/v1"
        private val replies = LinkedBlockingQueue<Reply>()
        val received = AtomicInteger()
        @Volatile var lastRequest = ""
        @Volatile var failure: Throwable? = null
        @Volatile private var closed = false
        private val worker = Thread {
            try { while (!closed) server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream(); val headers = ByteArrayOutputStream()
                while (!headers.toString("US-ASCII").endsWith("\r\n\r\n")) {
                    val byte = input.read(); check(byte >= 0 && headers.size() < 16_384); headers.write(byte)
                }
                val length = headers.toString("US-ASCII").lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                check(length in 1..393_216)
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                lastRequest = String(bytes, Charsets.UTF_8)
                check(JsonParser.parseString(lastRequest).asJsonObject["model"].asString == "public-model")
                received.incrementAndGet()
                val reply = requireNotNull(replies.poll(5, TimeUnit.SECONDS)); reply.gate?.await(8, TimeUnit.SECONDS)
                val content = JsonObject().apply { add("choices", JsonArray().apply { add(JsonObject().apply {
                    addProperty("finish_reason", "stop"); add("message", JsonObject().apply { addProperty("content", reply.body) })
                }) }) }.toString().toByteArray(Charsets.UTF_8)
                runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray() + content) }
            } } catch (error: Throwable) { if (!closed) failure = error }
        }.apply { isDaemon = true; name = "qa-ai-public-model"; start() }
        fun reply(body: String, gate: CountDownLatch? = null) { replies.put(Reply(body, gate)) }
        override fun close() { closed = true; server.close(); worker.interrupt(); worker.join(2000) }
    }
}
