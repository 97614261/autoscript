package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.script.ui.*
import com.google.gson.JsonObject
import org.json.JSONObject

/** Public layout, floating windows and bounded task lifecycle with actual visual compilation. */
internal object LibraryHostUsageFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        val ui = UsageTestUi(test)
        rt.variables("text" to ProjectVariableType.STRING, "value" to ProjectVariableType.INTEGER,
            "job" to ProjectVariableType.INTEGER, "saved" to ProjectVariableType.INTEGER)
        val definition = ScriptUiDefinition(version = 2, pages = listOf(UiPage()), fields = listOf(
            UiField(id = "publicField", label = "公开测试字段", kind = "text", required = false, initialValue = "初始",
                ui = UiPresentation(control = "INPUT", pageId = "main", x = 20, y = 20, width = 400, height = 48, fontPx = 20))))
        var snapshot = store.openProject(rt.id)
        snapshot = store.updateRunnerUi(rt.id, definition.toJson(), snapshot.manifest.runnerUi)
        val withWorker = store.createFlow(rt.id, "qa_host_worker", snapshot.manifest.flows.map { it.flowId }.toSet(), "公开后台任务")
        val worker = withWorker.manifest.flows.single { it.flowId != rt.main.flowId }
        fun saveWorker(nodes: List<VisualBlockTree>) {
            store.saveFlows(store.openProject(rt.id), mapOf(worker.flowId to StudioWorkflowAcceptanceFixture.source(nodes, worker.rootBlockId)))
        }
        fun call(kind: String, vararg args: Pair<String, Any>) = rt.block(kind,
            "targetFlowId" to worker.flowId, "arguments" to JsonObject(), "resultVariable" to "job", *args)
        try {
            record("library-ui.get-set-command-real-public-layout-values") {
                rt.run(listOf(rt.block("ui.set", "controlId" to "publicField", "value" to "公开修改"),
                    rt.block("ui.command", "controlId" to "publicField", "operation" to "text", "value" to "公开文字"),
                    rt.block("ui.get", "controlId" to "publicField", "resultVariable" to "text"))) { check(it["text"] == "公开文字") }
            }
            for (accept in listOf(true, false)) record("library-ui.confirm-real-${if (accept) "confirm" else "cancel"}") {
                rt.run(listOf(rt.block("ui.confirm", "title" to "公开QA确认", "message" to "仅测试公开内容",
                    "confirmText" to "QA确定", "cancelText" to "QA取消", "timeoutMs" to 6000, "resultVariable" to "text")), during = {
                    ui.wait("公开QA确认"); ui.click(if (accept) "QA确定" else "QA取消")
                }) { check(it["text"] == if (accept) "confirm" else "cancel") }
            }
            record("library-ui.dialog-and-dialogclose-return-real-close-result") {
                saveWorker(listOf(rt.block("task.sleep", "milliseconds" to 600), rt.block("ui.dialogclose")))
                rt.run(listOf(call("task.spawn"), rt.block("ui.dialog", "pageId" to "main", "blockOutside" to true,
                    "timeoutMs" to 6000, "resultVariable" to "text"))) { check(it["text"] == "close") }
            }
            record("library-floating.show-bounds-hide-and-close-real-window") {
                rt.run(listOf(rt.block("floating.show", "name" to "publicPanel", "x" to 80, "y" to 160,
                    "width" to 180, "height" to 70, "text" to "PUBLIC_QA_FLOAT"),
                    rt.block("floating.bounds", "name" to "publicPanel", "resultVariable" to "text"),
                    rt.block("floating.hide", "name" to "publicPanel"), rt.block("floating.close", "name" to "publicPanel"))) {
                    val bounds = JSONObject(it["text"].orEmpty()); check(bounds.getInt("width") > 0 && bounds.getInt("height") > 0)
                }
            }
            record("library-task.spawn-cancel-prevents-later-owned-worker-write") {
                saveWorker(listOf(rt.block("task.sleep", "milliseconds" to 600), rt.set("value", "999")))
                rt.run(listOf(rt.set("value", "0"), call("task.spawn"), rt.block("task.sleep", "milliseconds" to 100),
                    rt.block("task.cancel", "idVariable" to "job"), rt.block("task.sleep", "milliseconds" to 800))) { check(it["value"] == "0") }
            }
            record("library-timer.every-cancel-repeats-then-stops-actually") {
                saveWorker(listOf(rt.set("value", "value+1")))
                rt.run(listOf(rt.set("value", "0"), call("timer.every", "periodMs" to 50),
                    rt.block("task.sleep", "milliseconds" to 300), rt.block("timer.cancel", "idVariable" to "job"),
                    rt.set("saved", "value"), rt.block("task.sleep", "milliseconds" to 150))) {
                    check((it["value"]?.toIntOrNull() ?: 0) >= 2 && it["value"] == it["saved"])
                }
            }
            record("library-task.stop-prevents-following-sandbox-write") {
                rt.run(listOf(rt.block("files.write", "path" to "before-stop.txt", "text" to "PUBLIC"), rt.block("task.stop"),
                    rt.block("files.write", "path" to "after-stop.txt", "text" to "UNREACHABLE")), completed = true) { }
                rt.run(listOf(rt.block("files.read", "path" to "before-stop.txt", "resultVariable" to "text"),
                    rt.block("files.exists", "path" to "after-stop.txt", "resultVariable" to "value"))) {
                    check(it["text"] == "PUBLIC" && it["value"] == "0")
                }
            }
            record("library-task.restart-once-using-owned-persistent-guard") {
                val guard = rt.block("control.if", "variable" to "value", "operator" to "equals", "value" to "0").copy(children = mapOf(
                    "then" to listOf(rt.block("files.write", "path" to "restart-guard.txt", "text" to "PUBLIC"), rt.block("task.restart")),
                    "else" to listOf(rt.set("saved", "42"))))
                rt.run(listOf(rt.block("files.exists", "path" to "restart-guard.txt", "resultVariable" to "value"), guard)) { check(it["saved"] == "42") }
            }
            return store.openProject(rt.id)
        } finally { rt.stop() }
    }
}
