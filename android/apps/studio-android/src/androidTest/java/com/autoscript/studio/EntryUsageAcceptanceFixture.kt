package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject

internal object EntryUsageAcceptanceFixture {
    fun run(stage: String, test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val ui = UsageTestUi(test)
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("value" to ProjectVariableType.INTEGER, "argument" to ProjectVariableType.INTEGER,
            "text" to ProjectVariableType.STRING)
        var panel by mutableStateOf("")
        var args by mutableStateOf(JsonObject())
        var accepted by mutableStateOf<JsonObject?>(null)
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            if (panel == "ai") EditorEntryDialog(EditorToolPanel.AI, { panel = "" }, onInsert = { error("QA_AI_INSERTED_FAKE_RESULT") })
            else if (panel.isNotEmpty()) ConfiguredEntryArgumentsDialog(requireNotNull(BlockCatalog.find(panel)), args,
                rt.snapshot.manifest.variables, rt.snapshot.manifest.flows, rt.main.flowId,
                listOf("value", "argument", "text"), rt.snapshot.manifest.debugSettings, null,
                { panel = "" }, { accepted = it; panel = "" }, "QA加入")
        } } }
        try {
            when (stage) {
                "flow" -> {
                    val snapshot = store.createFlow(rt.id, "qa_worker", rt.snapshot.manifest.flows.map { it.flowId }.toSet(), "公开调用测试")
                    val worker = snapshot.manifest.flows.single { it.flowId != rt.main.flowId }
                    fun saveWorker(success: Boolean, explicitReturn: Boolean = false) {
                        val nodes = listOf(rt.block("flow.argument.get", "index" to 1, "targetVariable" to "argument"),
                            rt.set("value", "argument * 2"), rt.block("flow.return.set", "index" to 1, "valueVariable" to "value")) +
                            if (explicitReturn) listOf(rt.block("flow.return"), rt.set("value", "-999"))
                            else listOf(rt.block("flow.status.return", "success" to success))
                        store.saveFlows(store.openProject(rt.id), mapOf(worker.flowId to StudioWorkflowAcceptanceFixture.source(nodes, worker.rootBlockId)))
                    }
                    for (success in listOf(true, false)) record("flow-arguments-return-and-real-${if (success) "success" else "failure"}-branch") {
                        saveWorker(success)
                        val call = rt.block("flow.call.branch", "targetFlowId" to worker.flowId, "arguments" to JsonObject()).copy(children = mapOf(
                            "success" to listOf(rt.set("text", "\"成功\"")), "failure" to listOf(rt.set("text", "\"失败\""))))
                        rt.run(listOf(rt.block("flow.argument.set", "index" to 1, "value" to 21), call,
                            rt.block("flow.return.get", "index" to 1, "targetVariable" to "value"))) {
                            check(it["argument"] == "21" && it["value"] == "42" && it["text"] == if (success) "成功" else "失败")
                        }
                    }
                    record("flow-ordinary-call-explicit-return-and-label-jump") {
                        saveWorker(true, true)
                        rt.run(listOf(rt.block("flow.argument.set", "index" to 1, "value" to 5),
                            rt.block("flow.call", "targetFlowId" to worker.flowId, "arguments" to JsonObject()),
                            rt.block("flow.return.get", "index" to 1, "targetVariable" to "value"),
                            rt.block("control.goto", "name" to "完成"), rt.set("value", "-1"), rt.block("control.label", "name" to "完成"))) {
                            check(it["value"] == "10")
                        }
                    }
                }
                "output" -> {
                    for (kind in listOf("task.runprompt", "task.prompt", "task.log", "task.comment", "task.message")) record("output-real-form-$kind") {
                        val initialArgs = if (kind == "task.message") rt.block(kind, "expression" to "\"公开表达式\"", "channel" to "runPrompt").arguments
                            else rt.block(kind, "message" to "公开输出").arguments
                        test.runOnMainSync { args = initialArgs; accepted = null; panel = kind }
                        ui.click("QA加入"); ui.await("output form saved") { accepted != null && panel.isEmpty() }
                        rt.run(listOf(VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), requireNotNull(accepted))), during = {
                            if (kind == "task.runprompt") ui.wait("运行提示：公开输出（点击设置，拖动移动）")
                        }) { }
                        if (kind == "task.runprompt") ui.absent("运行提示：公开输出（点击设置，拖动移动）")
                    }
                    record("output-variable-name-value-expression-and-real-log") {
                        val marker = "PUBLIC_QA_VALUE_42"
                        rt.run(listOf(rt.set("value", "42"), rt.block("task.promptenabled", "enabled" to true),
                            rt.block("task.logenabled", "enabled" to true),
                            rt.block("task.runprompt", "message" to "结果", "valueVariable" to "value"),
                            rt.block("task.log", "level" to "info", "message" to marker),
                            rt.block("task.comment", "message" to "PUBLIC_QA_COMMENT_NEVER_EXECUTES")),
                            during = { ui.wait("运行提示：42（点击设置，拖动移动）") }) {
                            check(client.recentRuntimeLogs(100).any { marker in it })
                            check(client.recentRuntimeLogs(100).none { "PUBLIC_QA_COMMENT_NEVER_EXECUTES" in it })
                        }
                    }
                }
                "ai" -> record("ai-project-context-required-close-and-back-without-sending") {
                    repeat(2) { attempt ->
                        test.runOnMainSync { panel = "ai" }; ui.wait("AI 助手")
                        if (attempt == 0) ui.click("关闭") else check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        ui.absent("AI 助手")
                    }
                }
                else -> error("QA_UNKNOWN_ENTRY_STAGE")
            }
            return store.openProject(rt.id)
        } finally { test.runOnMainSync { panel = "" }; rt.stop() }
    }
}
