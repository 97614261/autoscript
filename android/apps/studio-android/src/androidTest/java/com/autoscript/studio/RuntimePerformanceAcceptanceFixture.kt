package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Full compiled calls on a new owned project. No private scripts, IME or system changes. */
internal object RuntimePerformanceAcceptanceFixture {
    fun run(test: Instrumentation, activity: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-")) { "QA_RUNTIME_PERFORMANCE_IDENTITY" }
        var snapshot = initial
        val vars = listOf("qaRStart", "qaREnd", "qaRTime", "qaRN", "qaRIndex").map {
            ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER)
        } + listOf("qaRText", "qaRData").map {
            ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING)
        } + ProjectVariable("qaRF", ProjectVariableScope.GLOBAL, type = ProjectVariableType.NUMBER)
        snapshot = store.updateVariables(id, snapshot.manifest.variables + vars, snapshot.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + setOf("core.task", "core.math", "input.basic"), snapshot.manifest.capabilities.toSet())
        val flow = snapshot.manifest.flows.single()
        fun args(vararg pairs: Pair<String, Any>) = JsonObject().apply { pairs.forEach { (key, value) ->
            when (value) { is Number -> addProperty(key, value); else -> addProperty(key, value.toString()) }
        } }
        fun block(kind: String, values: JsonObject = JsonObject(), children: Map<String, List<VisualBlockTree>> = emptyMap()) =
            VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), values, children)
        fun waitFor(condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
            check(condition()) { "QA_RUNTIME_PERFORMANCE_WAIT" }
        }
        fun node(label: String): AccessibilityNodeInfo? {
            fun walk(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null || !current.refresh()) return null
                if (current.text?.toString() == label || current.contentDescription?.toString() == label) return current
                for (index in 0 until current.childCount) walk(current.getChild(index))?.let { return it }
                return null
            }
            return test.uiAutomation.windows.asReversed().firstNotNullOfOrNull { walk(it.root) }
        }
        fun click(label: String) {
            waitFor { node(label) != null }
            var target = node(label)
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "QA_RUNTIME_PERFORMANCE_CLICK:$label" }
        }
        fun closeEditor() {
            val editor = EditorOverlaySessions.status.value ?: return
            check(editor.projectId == id) { "QA_RUNTIME_EDITOR_CHANGED" }
            if (node("运行日志") != null) { click("关闭"); waitFor { node("运行日志") == null } }
            if (node("关闭运行变量") != null) click("关闭运行变量")
            test.runOnMainSync { activity.startService(Intent(activity, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
            runBlocking { withTimeout(10_000) { EditorOverlaySessions.status.first { it == null } } }
        }
        fun stopOwned(): Double {
            val proof = client.debugSnapshot(id)
            check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 && proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_RUNTIME_RUN_CHANGED" }
            val start = SystemClock.elapsedRealtimeNanos()
            check(client.requestStop()) { "QA_RUNTIME_STOP_REJECTED" }
            runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState in setOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED) } } }
            check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "QA_RUNTIME_STOP_FAILED" }
            return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        fun start(source: String, ui: String): String {
            snapshot = store.saveFlows(store.openProject(id), mapOf(flow.flowId to source))
            val compiled = client.compileVisualProject(id)
            check(compiled is VisualCompileResult.Success) { "QA_RUNTIME_COMPILE:$compiled" }
            val frozen = store.openProject(id)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
            if (ui != "closed") {
                test.runOnMainSync { ContextCompat.startForegroundService(activity, Intent(activity, EditorOverlayService::class.java)
                    .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, id)) }
                runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == id && it.ready } } }
                waitFor { node("打开项目设置") != null }
                if (ui == "logs") { click("日志"); waitFor { node("运行日志") != null } }
                if (ui == "watch") { click("更多"); click("运行变量"); waitFor { node("关闭运行变量") != null } }
            }
            check(runBlocking { withTimeout(30_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_RUNTIME_START" }
            return JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
        }
        fun emit(value: JSONObject) = test.sendStatus(1, Bundle().apply { putString("stream", "QA_RUNTIME_PERFORMANCE:$value\n") })
        val regex = listOf(
            block("text.regexmatch", args("text" to "智构A123 B456".repeat(100), "pattern" to "[0-9]+", "resultVariable" to "qaRN")),
            block("text.regexreplace", args("text" to "智构A123 B456", "pattern" to "[0-9]+", "replacement" to "$1", "resultVariable" to "qaRText")))
        val ordinary = listOf(
            block("text.sub", args("text" to "智构😀Ab09".repeat(300), "start" to 2, "length" to 4, "resultVariable" to "qaRText")),
            block("data.get", args("json" to (0 until 512).joinToString(",", "[", "]"), "key" to "512", "resultVariable" to "qaRData")),
            block("math.clamp", args("value" to 42, "min" to 0, "max" to 100, "resultVariable" to "qaRF")))
        try {
            for ((name, ui) in listOf("ordinary" to "closed", "regex" to "closed", "regex" to "editor", "regex" to "logs", "regex" to "watch")) {
                record("runtime-performance-$name-$ui") {
                    val before = RuntimeLogFiles.list(activity.filesDir).map { it.id }.toSet()
                    val operations = if (name == "regex") regex else ordinary
                    val iteration = listOf(block("system.elapsed", args("resultVariable" to "qaRStart")),
                        block("control.repeat", args("times" to 32), mapOf("body" to operations)),
                        block("system.elapsed", args("resultVariable" to "qaREnd")),
                        block("variable.calculate", args("name" to "qaRTime", "expression" to "qaREnd - qaRStart")),
                        block("task.log", args("level" to "info", "message" to "PUBLIC_QA_RUNTIME_MS", "valueVariable" to "qaRTime")),
                        block("task.sleep", args("milliseconds" to 25)))
                    val source = StudioWorkflowAcceptanceFixture.source(listOf(
                        block("control.repeat", args("times" to 111, "indexVariable" to "qaRIndex"), mapOf("body" to iteration)),
                        block("task.sleep", args("milliseconds" to 60_000))), flow.rootBlockId)
                    val tail = start(source, ui)
                    runBlocking { withTimeout(100_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                    val proof = client.debugSnapshot(id)
                    check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0) { "QA_RUNTIME_PROOF" }
                    val values = proof.variables.associate { it.name to it.value }
                    check(values["qaRIndex"] == "111") { "QA_RUNTIME_ITERATIONS" }
                    if (name == "regex") check(values["qaRN"] == "1" && values["qaRText"] == "智构A$1 B$1") { "QA_RUNTIME_REGEX_RESULT" }
                    else check(values["qaRText"] == "构😀Ab" && values["qaRData"] == "511" && values["qaRF"]?.toDoubleOrNull() == 42.0) { "QA_RUNTIME_ORDINARY_RESULT" }
                    val stopMs = stopOwned()
                    closeEditor()
                    var run: RuntimeLogFiles.Run? = null
                    waitFor {
                        run = RuntimeLogFiles.list(activity.filesDir).singleOrNull { it.id !in before && it.metadata["projectId"] == id }
                        run?.metadata?.containsKey("endedAtMs") == true
                    }
                    val closed = requireNotNull(run)
                    check(closed.metadata["status"] == "stopped" && closed.metadata["droppedRecords"] == "0" && closed.metadata["truncatedRecords"] == "0") { "QA_RUNTIME_LOG_INCOMPLETE" }
                    val times = RuntimeLogFiles.read(activity.filesDir, closed.id, "script.log").lineSequence()
                        .filter { runtimeConsoleChannel(it) == RuntimeConsoleChannel.SCRIPT }
                        .map { requireNotNull(it.substringAfterLast(' ').toLongOrNull()) }.toList()
                    check(times.size == 111 && times.all { it >= 0 }) { "QA_RUNTIME_SAMPLES" }
                    val warm = times.drop(11).sorted()
                    emit(JSONObject().put("workload", name).put("ui", ui).put("samples", warm.size).put("callsPerSample", 32 * operations.size)
                        .put("p50Ms", warm[49]).put("p95Ms", warm[94]).put("p99Ms", warm[98]).put("firstMs", times.first()).put("stopMs", stopMs)
                        .put("clockResolutionMs", 1).put("pacingMs", 25).put("scope", "compiled Lua + dispatch + operation + variable writes; following log and pacing excluded"))
                }
            }
            record("runtime-root-input-submit-versus-owned-target-delivery") {
                val ups = java.util.Collections.synchronizedList(mutableListOf<Long>())
                val activeTouch = java.util.concurrent.atomic.AtomicBoolean()
                val moves = java.util.concurrent.atomic.AtomicInteger()
                val view = object : View(activity) {
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> activeTouch.set(true)
                            MotionEvent.ACTION_MOVE -> moves.incrementAndGet()
                            MotionEvent.ACTION_UP -> { activeTouch.set(false); ups.add(SystemClock.elapsedRealtimeNanos()) }
                            MotionEvent.ACTION_CANCEL -> activeTouch.set(false)
                        }
                        return true
                    }
                }
                test.runOnMainSync { activity.setContentView(view) }
                test.waitForIdleSync()
                val location = IntArray(2)
                test.runOnMainSync { view.getLocationOnScreen(location) }
                check(view.width > 100 && view.height > 100) { "QA_INPUT_SURFACE" }
                val x = location[0] + view.width / 2; val y = location[1] + view.height / 2
                val before = RuntimeLogFiles.list(activity.filesDir).map { it.id }.toSet()
                val iteration = listOf(block("system.elapsed", args("resultVariable" to "qaRStart")),
                    block("input.tap", args("x" to x, "y" to y)), block("system.elapsed", args("resultVariable" to "qaREnd")),
                    block("variable.calculate", args("name" to "qaRTime", "expression" to "qaREnd - qaRStart")),
                    block("task.log", args("level" to "info", "message" to "PUBLIC_QA_INPUT_MS", "valueVariable" to "qaRTime")),
                    block("task.log", args("level" to "info", "message" to "PUBLIC_QA_INPUT_START", "valueVariable" to "qaRStart")),
                    block("task.sleep", args("milliseconds" to 25)))
                val tail = start(StudioWorkflowAcceptanceFixture.source(listOf(block("control.repeat", args("times" to 111), mapOf("body" to iteration)),
                    block("task.sleep", args("milliseconds" to 60_000))), flow.rootBlockId), "closed")
                runBlocking { withTimeout(100_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                waitFor { ups.size == 111 && !activeTouch.get() }
                stopOwned()
                var run: RuntimeLogFiles.Run? = null
                waitFor {
                    run = RuntimeLogFiles.list(activity.filesDir).singleOrNull { it.id !in before && it.metadata["projectId"] == id }
                    run?.metadata?.containsKey("endedAtMs") == true
                }
                val closed = requireNotNull(run)
                check(closed.metadata["droppedRecords"] == "0" && closed.metadata["truncatedRecords"] == "0") { "QA_INPUT_LOG_INCOMPLETE" }
                val logged = RuntimeLogFiles.read(activity.filesDir, closed.id, "script.log").lineSequence()
                    .filter { runtimeConsoleChannel(it) == RuntimeConsoleChannel.SCRIPT }
                    .map { requireNotNull(it.substringAfterLast(' ').toLongOrNull()) }.toList()
                check(logged.size == 222) { "QA_INPUT_SAMPLE_COUNT" }
                val submissions = logged.chunked(2).map { it.first() }.drop(11).sorted()
                val delivered = ups.toList().mapIndexed { index, now -> now / 1e6 - logged[index * 2 + 1] }.drop(11).sorted()
                check(delivered.all { it >= 0 }) { "QA_INPUT_CLOCK_DOMAIN" }
                emit(JSONObject().put("workload", "root-tap").put("samples", 100).put("ackP50Ms", submissions[49]).put("ackP95Ms", submissions[94])
                    .put("ackP99Ms", submissions[98]).put("targetP50Ms", delivered[49]).put("targetP95Ms", delivered[94]).put("targetP99Ms", delivered[98])
                    .put("pacingMs", 25).put("scope", "ack: script tap and backend acknowledgement; target: own View UP callback, not target rendering; overlapping metrics are not additive"))
                val moved = moves.get()
                start(StudioWorkflowAcceptanceFixture.source(listOf(block("input.swipe", args("x1" to x, "y1" to y, "x2" to x + 40, "y2" to y + 40, "durationMs" to 5000)),
                    block("task.sleep", args("milliseconds" to 60_000))), flow.rootBlockId), "closed")
                waitFor { activeTouch.get() && moves.get() > moved }
                val stopped = stopOwned()
                waitFor { !activeTouch.get() }
                emit(JSONObject().put("workload", "cancel-active-swipe").put("stopMs", stopped).put("touchReleased", true))
            }
            record("runtime-priority-stop-during-cpu-and-restart") {
                val source = StudioWorkflowAcceptanceFixture.source(listOf(block("control.repeat", args("times" to 1_000_000), mapOf("body" to regex))), flow.rootBlockId)
                start(source, "closed")
                runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == id } } }
                emit(JSONObject().put("workload", "cancel-cpu-regex").put("stopMs", stopOwned()).put("scope", "stop request through authenticated cleanup and terminal notification"))
                val tail = start(StudioWorkflowAcceptanceFixture.source(listOf(block("task.sleep", args("milliseconds" to 60_000))), flow.rootBlockId), "closed")
                runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                emit(JSONObject().put("workload", "cancel-wait-after-restart").put("stopMs", stopOwned()))
            }
        } finally {
            if (client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) stopOwned()
            closeEditor()
        }
        return store.openProject(id)
    }
}
