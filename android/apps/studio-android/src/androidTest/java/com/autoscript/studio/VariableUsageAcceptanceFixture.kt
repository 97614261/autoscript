package com.autoscript.studio

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.*
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Public names through the actual overlay, followed by real compiler/Lua/host execution. */
internal object VariableUsageAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-"))
        val ui = UsageTestUi(test)
        var snapshot = store.createFlow(id, "qa_secondary", initial.manifest.flows.map { it.flowId }.toSet())
        val main = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
        val second = snapshot.manifest.flows.first { it.flowId != main.flowId }
        val active = setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)
        fun args(vararg pairs: Pair<String, Any>) = JsonObject().apply { pairs.forEach { (key, value) ->
            when (value) { is Number -> addProperty(key, value); is Boolean -> addProperty(key, value); else -> addProperty(key, value.toString()) }
        } }
        fun block(kind: String, vararg pairs: Pair<String, Any>): VisualBlockTree {
            val contract = requireNotNull(BlockCatalog.find(kind))
            val values = initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, main.flowId, true) ?: JsonObject()
            args(*pairs).entrySet().forEach { (key, value) -> values.add(key, value) }
            if (kind in setOf("flow.argument.set", "flow.return.set") && values.get("valueVariable")?.asString?.isNotBlank() == true) values.remove("value")
            return VisualBlockTree(contract, values)
        }
        fun calculation(name: String, expression: String) = block("variable.calculate", "name" to name, "expression" to expression)
        fun refresh() { snapshot = store.openProject(id) }
        fun open() {
            test.runOnMainSync { test.targetContext.startService(Intent(test.targetContext, EditorOverlayService::class.java)
                .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, id)) }
            runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == id && it.ready } } }
            ui.wait("变量")
        }
        fun close() {
            ui.click("保存并关闭编辑窗")
            runBlocking { withTimeout(10_000) { EditorOverlaySessions.status.first { it == null } } }
            refresh()
        }
        fun manager(scope: String = "局部") { ui.click("变量"); ui.wait("变量管理"); ui.click(scope) }
        fun add(name: String, type: String = "整数", scope: String = "局部") {
            ui.click("新增"); ui.wait("新增${scope}变量"); ui.replaceFirst(name)
            if (type != "整数") { ui.click("整数"); ui.wait("变量类型"); ui.click(type) }
            ui.click("添加"); ui.absent("新增${scope}变量")
        }
        fun save() { ui.click("保存"); ui.absent("变量管理"); refresh() }
        fun stop() {
            if (client.runtimeStates.value.engineState in active) {
                val proof = client.debugSnapshot(id)
                check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 &&
                    proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_VARIABLE_RUN_CHANGED" }
                check(client.requestStop())
                runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState !in active } } }
            }
        }
        fun compile(nodes: List<VisualBlockTree>, secondary: List<VisualBlockTree> = emptyList()): Pair<String, VisualCompileResult> {
            val source = StudioWorkflowAcceptanceFixture.source(nodes + block("task.sleep", "milliseconds" to 60_000), main.rootBlockId)
            snapshot = store.saveFlows(store.openProject(id), mapOf(main.flowId to source,
                second.flowId to StudioWorkflowAcceptanceFixture.source(secondary.ifEmpty { listOf(block("task.noop")) }, second.rootBlockId)))
            return source to client.compileVisualProject(id)
        }
        fun start(source: String, compiled: VisualCompileResult) {
            check(compiled is VisualCompileResult.Success) { "QA_VARIABLE_COMPILE:$compiled" }
            val frozen = store.openProject(id)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
            check(runBlocking { withTimeout(30_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_VARIABLE_START" }
        }
        fun run(nodes: List<VisualBlockTree>, secondary: List<VisualBlockTree> = emptyList(), inspect: (RuntimeDebugReply) -> Unit) {
            val (source, compiled) = compile(nodes, secondary)
            try {
                start(source, compiled)
                val tail = JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
                runBlocking { withTimeout(15_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                val reply = client.debugSnapshot(id)
                check(reply.status == RuntimeDebugReply.SUCCESS && !reply.variablesTruncated) { "QA_VARIABLE_READ" }
                inspect(reply)
            } finally { stop() }
        }
        fun RuntimeDebugReply.value(name: String, scope: String = "local") = variables.single { it.name == name && it.scope == scope }.value
        fun assertValue(reply: RuntimeDebugReply, name: String, expected: String, scope: String = "local") {
            check(reply.value(name, scope) == expected) { "QA_VARIABLE_VALUE:$scope:$name:${reply.value(name, scope)}:$expected" }
        }
        try {
            record("variables-real-editor-create-four-chinese-types-and-save") {
                open(); manager()
                add("次数"); add("比例", "浮点"); add("文本", "字符"); add("图像", "图像")
                ui.screenshot("variables-four-types")
                save()
                check(snapshot.manifest.variables.filter { it.flowId == main.flowId }.map { it.type }.toSet() == ProjectVariableType.entries.toSet())
            }
            record("variables-reopen-global-local-shadowing-and-save") {
                manager(); ui.wait("次数"); add("同名"); add("结果"); save()
                manager("全局"); add("总数", scope = "全局"); add("同名", scope = "全局"); add("返回值", scope = "全局"); save()
                check(snapshot.manifest.variables.count { it.name == "同名" } == 2)
            }
            record("variables-reject-duplicate-and-invalid-name-in-real-form") {
                manager(); ui.click("新增"); ui.wait("新增局部变量")
                ui.replaceFirst("次数"); ui.click("添加"); ui.wait("同一作用域已存在变量：次数")
                ui.replaceFirst("1错误"); ui.click("添加"); ui.wait("支持中文、英文字母、数字和下划线，不能以数字开头，最长64字符")
                ui.click("取消"); ui.absent("新增局部变量"); ui.click("取消"); ui.absent("变量管理")
                check(store.openProject(id).manifest.variables == snapshot.manifest.variables)
            }
            record("variables-real-search-and-clear") {
                manager(); ui.replaceFirst("文本"); ui.wait("文本"); check(!ui.exists("次数"))
                ui.replaceFirst(""); ui.wait("次数"); ui.click("取消"); ui.absent("变量管理")
            }
            record("variables-discard-retains-saved-declarations") {
                manager(); add("放弃的草稿"); ui.click("取消"); ui.wait("放弃变量更改？")
                ui.click("取消"); ui.absent("放弃变量更改？"); ui.reveal("放弃的草稿")
                ui.click("取消"); ui.click("放弃更改"); ui.absent("变量管理")
                check(store.openProject(id).manifest.variables.none { it.name == "放弃的草稿" })
            }
            record("variables-delete-confirm-cancel-and-save") {
                manager(); add("临时变量"); save(); manager()
                ui.reveal("删除变量 临时变量"); ui.click("删除变量 临时变量"); ui.wait("删除变量 临时变量")
                ui.click("取消"); ui.wait("临时变量")
                ui.click("删除变量 临时变量"); ui.click("删除"); save()
                check(snapshot.manifest.variables.none { it.name == "临时变量" })
            }
            record("variables-real-calculation-form-inserts-and-persists") {
                manager(); ui.rowAction("次数", "计算"); ui.wait("变量赋值 / 计算")
                ui.replaceFirst("40 + 2"); ui.click("加入"); ui.absent("变量管理")
                ui.await("saved calculation") { store.openProject(id).flowSources.getValue(main.flowId).contains("40 + 2") }
                close()
                val source = snapshot.flowSources.getValue(main.flowId)
                check(source.lineSequence().filter(String::isNotBlank).map(::JSONObject).any {
                    it.getString("kind") == "variable.calculate" && it.getJSONObject("args").getString("name") == "次数"
                })
            }
            record("variables-rename-rewrites-real-saved-block-reference") {
                open(); manager(); ui.click("编辑变量 次数"); ui.wait("编辑变量"); ui.replaceFirst("计数")
                ui.click("保存修改"); save(); close()
                val nodes = snapshot.flowSources.getValue(main.flowId).lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                check(nodes.any { it.getString("kind") == "variable.calculate" && it.getJSONObject("args").getString("name") == "计数" })
                check(snapshot.manifest.variables.none { it.name == "次数" })
            }
            record("variables-defaults-from-real-runtime") {
                run(emptyList()) { reply ->
                    assertValue(reply, "计数", "0"); check(reply.value("比例").toDouble() == 0.0)
                    assertValue(reply, "文本", ""); assertValue(reply, "总数", "0", "global")
                }
            }
            record("variables-chinese-set-copy-and-arithmetic-real-runtime") {
                run(listOf(block("variable.set", "name" to "计数", "value" to 40),
                    calculation("计数", "计数 + 2"), block("variable.copy", "sourceName" to "计数", "name" to "结果"),
                    calculation("总数", "结果 * 2"))) { reply ->
                    assertValue(reply, "计数", "42"); assertValue(reply, "结果", "42"); assertValue(reply, "总数", "84", "global")
                }
            }
            record("variables-plus-signed-text-to-integer-and-float-real-runtime") {
                run(listOf(calculation("文本", "\"+190\""), calculation("计数", "int(文本)"), calculation("比例", "float(\"-12.75\")"))) { reply ->
                    assertValue(reply, "计数", "190"); check(reply.value("比例").toDouble() == -12.75)
                }
            }
            record("variables-increment-decrement-and-text-concatenation-real-runtime") {
                run(listOf(calculation("计数", "10"), calculation("计数", "计数 + 1"), calculation("计数", "计数 - 1"),
                    calculation("文本", "\"计数=\" .. text(计数) .. \"\\n比例=\" .. text(1.5)"))) { reply ->
                    assertValue(reply, "计数", "10"); assertValue(reply, "文本", "计数=10\n比例=1.5")
                }
            }
            record("variables-unicode-character-functions-real-runtime") {
                run(listOf(calculation("文本", "replace(trim(\" 智构 AbC \"),\"AbC\",lower(\"XYZ\"))"),
                    calculation("计数", "len(文本)"), calculation("结果", "find(文本,\"xyz\")"))) { reply ->
                    assertValue(reply, "文本", "智构 xyz"); assertValue(reply, "计数", "6"); assertValue(reply, "结果", "4")
                }
            }
            record("variables-byte-functions-and-integer-division-real-runtime") {
                run(listOf(calculation("文本", "byteSub(\"智构ABC\",0,6)"), calculation("计数", "byteLen(文本)"),
                    calculation("结果", "idiv(-7,3)"), calculation("总数", "irem(-7,3)"))) { reply ->
                    assertValue(reply, "文本", "智构"); assertValue(reply, "计数", "6"); assertValue(reply, "结果", "-2"); assertValue(reply, "总数", "-1", "global")
                }
            }
            record("variables-cross-plugin-local-isolation-and-global-sharing") {
                val variables = snapshot.manifest.variables + listOf(ProjectVariable("同名", ProjectVariableScope.FLOW, second.flowId, ProjectVariableType.INTEGER))
                snapshot = store.updateVariables(id, variables, snapshot.manifest.variables)
                run(listOf(calculation("同名", "11"), calculation("总数", "5"), block("flow.call", "targetFlowId" to second.flowId),
                    calculation("结果", "同名")), listOf(calculation("同名", "22"), calculation("总数", "总数 + 同名"))) { reply ->
                    assertValue(reply, "同名", "11"); assertValue(reply, "结果", "11"); assertValue(reply, "总数", "27", "global")
                    assertValue(reply, "同名", "0", "global")
                }
            }
            record("variables-plugin-argument-and-return-real-runtime") {
                run(listOf(block("flow.argument.set", "index" to 1, "value" to 21), block("flow.call", "targetFlowId" to second.flowId),
                    block("flow.return.get", "index" to 1, "targetVariable" to "结果")),
                    listOf(block("flow.argument.get", "index" to 1, "targetVariable" to "同名"), calculation("同名", "同名 * 2"),
                        block("flow.return.set", "index" to 1, "valueVariable" to "同名"))) { reply -> assertValue(reply, "结果", "42") }
            }
            record("variables-backup-restore-preserves-declarations-and-source") {
                refresh()
                val bytes = java.io.ByteArrayOutputStream().apply { store.exportProjectBackup(id, this) }.toByteArray()
                val restored = store.importProjectBackup(bytes.inputStream())
                try { check(restored.manifest.variables == snapshot.manifest.variables && restored.flowSources == snapshot.flowSources) }
                finally { store.deleteProject(restored.manifest.projectId) }
            }
            record("variables-type-and-undeclared-name-rejected-by-real-compiler") {
                check(compile(listOf(calculation("计数", "\"文本不能直接作为整数\""))).second is VisualCompileResult.Invalid)
                check(compile(listOf(calculation("未声明", "1"))).second is VisualCompileResult.Invalid)
                check(compile(listOf(calculation("图像", "1"))).second is VisualCompileResult.Invalid)
            }
            record("variables-real-runtime-observer-open-before-run-and-close") {
                val (source, compiled) = compile(listOf(calculation("计数", "42")))
                open(); ui.click("更多"); ui.click("运行变量"); ui.wait("关闭运行变量")
                try {
                    start(source, compiled)
                    val tail = JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
                    runBlocking { withTimeout(15_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                    ui.wait("关闭运行变量"); SystemClock.sleep(500); ui.screenshot("variables-live-observer")
                    check(client.debugSnapshot(id).value("计数") == "42")
                    ui.click("关闭运行变量"); ui.absent("关闭运行变量")
                } finally { stop() }
                close()
            }
            record("variables-delete-referenced-declaration-retains-block-and-diagnoses") {
                compile(listOf(calculation("计数", "42")))
                open(); manager(); ui.click("删除变量 计数"); ui.click("删除"); save(); close()
                check(snapshot.flowSources.getValue(main.flowId).contains("计数"))
                val result = client.compileVisualProject(id)
                check(result is VisualCompileResult.Invalid && result.diagnostic.nodeId != null) { "QA_VARIABLE_DELETE_DIAGNOSTIC" }
            }
            return store.openProject(id)
        } catch (failure: Throwable) {
            runCatching { ui.screenshot("variables-failure") }
            test.sendStatus(1, Bundle().apply { putString("stream", "QA_VARIABLE_FAILURE:${failure.message?.take(800)}\n") })
            throw failure
        } finally {
            stop()
            for (title in listOf("变量类型", "新增局部变量", "新增全局变量", "编辑变量", "变量赋值 / 计算")) {
                if (ui.exists(title) && ui.exists("取消")) runCatching { ui.click("取消") }
            }
            if (ui.exists("变量管理")) runCatching { ui.click("取消"); if (ui.exists("放弃变量更改？")) ui.click("放弃更改") }
            if (ui.exists("关闭运行变量")) runCatching { ui.click("关闭运行变量") }
            if (EditorOverlaySessions.status.value?.projectId == id) runCatching { close() }
        }
    }
}
