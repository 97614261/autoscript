package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Production workflow builders, real compiler and Root, with only an owned synthetic touch surface. */
internal object StudioWorkflowAcceptanceFixture {
    fun run(instrumentation: Instrumentation, owner: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, case: (String, () -> Unit) -> Unit, soakMillis: Long = 0L, extended: Boolean = false): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-")) { "QA_WORKFLOW_IDENTITY" }
        var snapshot = store.openProject(id)
        val variables = listOf("qaWs", "qaWc", "qaWe", "qaWa", "qaWo", "qaWfound", "qaWx", "qaWy", "qaWhits", "qaWarg", "qaWvalue")
            .map { ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER) }
        snapshot = store.updateVariables(id, snapshot.manifest.variables + variables, snapshot.manifest.variables)
        if (extended) snapshot = store.updateVariables(id, snapshot.manifest.variables +
            ProjectVariable("qaWresult", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING), snapshot.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + setOf("input.basic", "screen.capture", "vision.template", "core.task"), snapshot.manifest.capabilities.toSet())
        val taps = AtomicInteger()
        val moves = AtomicInteger()
        val enterKeys = AtomicInteger()
        val longestTouchMs = java.util.concurrent.atomic.AtomicLong()
        val size = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
        val px = size.x / 2
        val py = size.y / 2
        val position = IntArray(2)
        val pattern = IntArray(144) { i -> Color.rgb(32 + i * 31 % 180, 24 + i * 43 % 190, 16 + i * 13 % 200) }
        val surface = object : View(owner) {
            private val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(if (taps.get() % 2 == 0) Color.rgb(10, 20, 30) else Color.rgb(20, 80, 100))
                for (y in 0 until 12) for (x in 0 until 12) {
                    paint.color = pattern[y * 12 + x]
                    canvas.drawRect((px + x).toFloat(), (py + y).toFloat(), (px + x + 1).toFloat(), (py + y + 1).toFloat(), paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) moves.incrementAndGet()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    longestTouchMs.accumulateAndGet(event.eventTime - event.downTime, ::maxOf)
                    taps.incrementAndGet(); invalidate()
                }
                return true
            }
            override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) { enterKeys.incrementAndGet(); return true }
                return super.onKeyUp(keyCode, event)
            }
        }
        instrumentation.runOnMainSync { owner.setContentView(surface); surface.isFocusableInTouchMode = true; surface.requestFocus() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { surface.getLocationOnScreen(position) }
        val x = px + position[0]
        val y = py + position[1]
        check(surface.width > px + 12 && surface.height > py + 12 && x + 12 < size.x && y + 12 < size.y) { "QA_SURFACE_BOUNDS" }
        val bitmap = Bitmap.createBitmap(pattern, 12, 12, Bitmap.Config.ARGB_8888)
        val png = try { ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() }
            finally { bitmap.recycle() }
        val oldPaths = snapshot.manifest.resources.map { it["path"].asString }.toSet()
        snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "QA_workflow.png", ByteArrayInputStream(png), oldPaths)
        val path = snapshot.manifest.resources.single { it["path"].asString !in oldPaths }["path"].asString
        val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
        fun args(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply { pairs.forEach { (key, value) ->
            when (value) { is Number -> addProperty(key, value); is Boolean -> addProperty(key, value); is JsonObject -> add(key, value); else -> addProperty(key, value.toString()) }
        } }
        fun block(kind: String, values: JsonObject = JsonObject(), children: Map<String, List<VisualBlockTree>> = emptyMap()) =
            VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), values, children)
        fun set(name: String, value: String) = block("variable.calculate", args("name" to name, "expression" to value))
        val hold = block("task.sleep", args("milliseconds" to 60_000))
        val choices = snapshot.manifest.variables.map { CalculationVariable(it.name, it.type, "global") }
        fun waitBlocks(missing: Boolean, mode: RecognitionWaitMode, timeout: String): List<VisualBlockTree> {
            val region = if (missing) args("left" to (x - 40), "top" to y, "right" to (x - 20), "bottom" to (y + 20))
                else args("left" to x, "top" to y, "right" to (x + 20), "bottom" to (y + 20))
            val match = args("frameVariable" to "qaFrame", "imagePath" to path, "tolerance" to 0, "similarityPermille" to 1000,
                "region" to region, "foundVariable" to "qaWfound", "xVariable" to "qaWx", "yVariable" to "qaWy")
            return recognitionWaitBlocks(requireNotNull(BlockCatalog.find("vision.findimage")), match,
                RecognitionWaitDraft(mode = mode, timeout = timeout, interval = "50", attempts = "2",
                    start = "qaWs", clock = "qaWc", elapsed = "qaWe", attempt = "qaWa", outcome = "qaWo"), choices)
        }
        fun saveAndStart(forest: List<VisualBlockTree>): String {
            val source = source(forest + hold, flow.rootBlockId)
            snapshot = store.saveFlows(store.openProject(id), mapOf(flow.flowId to source))
            val compiled = client.compileVisualProject(id)
            if (compiled is VisualCompileResult.Invalid) {
                val kind = snapshot.flowSources.values.asSequence().flatMap { it.lineSequence() }.filter(String::isNotBlank).map(::JSONObject)
                    .firstOrNull { it.getString("nodeId") == compiled.diagnostic.nodeId }?.getString("kind") ?: "unavailable"
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", JSONObject()
                    .put("qaCompileDiagnostic", true).put("code", compiled.diagnostic.code?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "OTHER")
                    .put("kind", kind).toString() + "\n") })
            }
            check(compiled is VisualCompileResult.Success) { "QA_WORKFLOW_COMPILE" }
            val frozen = store.openProject(id)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
            val started = try { runBlocking { withTimeout(20_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                val state = client.runtimeStates.value
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_WORKFLOW_START_TIMEOUT:" + JSONObject()
                    .put("phase", state.phase.name).put("root", state.rootState.name).put("engine", state.engineState.name)
                    .put("editorOpen", EditorOverlaySessions.status.value != null).put("launchOpen", ScriptLaunchSessions.status.value != null) + "\n") })
                throw failure
            }
            check(started) { "QA_WORKFLOW_START" }
            return source
        }
        fun awaitHold(source: String) {
            val tail = JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
            try {
                runBlocking { withTimeout(15_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                val proof = client.debugSnapshot(id)
                val kind = source.lineSequence().filter(String::isNotBlank).map(::JSONObject)
                    .firstOrNull { it.getString("nodeId") == proof.nodeId }?.getString("kind") ?: "unavailable"
                val codes = setOf("LUA_RUNTIME_ERROR", "SCREEN_CAPTURE_FAILED", "FRAME_OPERATION_FAILED", "CAPABILITY_DENIED", "VISION_TIMEOUT")
                val observed = client.recentRuntimeLogs(16).flatMap { line -> codes.filter(line::contains) }.distinct()
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", JSONObject()
                    .put("qaWorkflowDiagnostic", true).put("engine", client.runtimeStates.value.engineState.name)
                    .put("debugStatus", proof.status).put("kind", kind).put("atTail", proof.nodeId == tail)
                    .put("publicQaCounters", JSONObject().apply {
                        for (name in listOf("qaWs", "qaWc", "qaWe", "qaWa", "qaWo", "qaWfound", "qaWhits")) {
                            proof.variables.singleOrNull { it.name == name }?.value?.toLongOrNull()?.takeIf { it in 0..1_000_000_000 }?.let { put(name, it) }
                        }
                    }).put("codes", org.json.JSONArray(observed)).toString() + "\n") })
                throw IllegalStateException("QA_WORKFLOW_HOLD_TIMEOUT", failure)
            }
        }
        fun read() = client.debugSnapshot(id).also { check(it.status == RuntimeDebugReply.SUCCESS) { "QA_WORKFLOW_DEBUG" } }.variables.associate { it.name to it.value }
        fun stop() {
            val proof = client.debugSnapshot(id)
            check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 &&
                proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_WORKFLOW_RUN_CHANGED" }
            check(client.requestStop()) { "QA_WORKFLOW_STOP" }
            try { runBlocking { withTimeout(8_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.STOPPED } } } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                val state = client.runtimeStates.value
                val actual = client.debugSnapshot(id)
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_WORKFLOW_STOP_TIMEOUT:" + JSONObject()
                    .put("phase", state.phase.name).put("root", state.rootState.name).put("engine", state.engineState.name)
                    .put("debugStatus", actual.status).put("nativeState", actual.runtimeState)
                    .put("sameRun", actual.runRevision == proof.runRevision && actual.sessionGeneration == proof.sessionGeneration) + "\n") })
                throw failure
            }
        }
        try {
            case("workflow-monotonic-clock-and-timed-yield") {
                val source = saveAndStart(listOf(
                    block("system.elapsed", args("resultVariable" to "qaWs")),
                    block("task.sleep", args("milliseconds" to 100)),
                    block("system.elapsed", args("resultVariable" to "qaWc")),
                    set("qaWe", "qaWc - qaWs")))
                awaitHold(source)
                check(requireNotNull(read()["qaWe"]).toLong() in 100..5_000) { "QA_WORKFLOW_CLOCK_RANGE" }
                stop()
            }
            case("workflow-real-capture-match-branch-tap-repeat") {
                val wait = waitBlocks(false, RecognitionWaitMode.APPEAR, "5000").toMutableList()
                val branch = wait.last()
                wait[wait.lastIndex] = branch.copy(children = branch.children + ("then" to listOf(
                    block("input.tap", args("x" to (x + 6), "y" to (y + 6))), set("qaWhits", "qaWhits + 1"))))
                val source = saveAndStart(listOf(set("qaWhits", "0"), block("control.repeat", args("times" to 2), mapOf("body" to wait))))
                awaitHold(source)
                val values = read()
                check(taps.get() == 2 && values["qaWhits"] == "2" && values["qaWo"] == "1" &&
                    values["qaWx"] == x.toString() && values["qaWy"] == y.toString()) { "QA_WORKFLOW_MATCH_OR_TAPS" }
                stop()
            }
            case("workflow-missing-image-retry-exhaustion-without-touch") {
                val before = taps.get()
                val source = saveAndStart(waitBlocks(true, RecognitionWaitMode.RETRY, "5000"))
                awaitHold(source)
                val values = read()
                check(values["qaWo"] == "0" && values["qaWa"] == "2" && taps.get() == before) { "QA_WORKFLOW_RETRY_RESULT" }
                stop()
            }
            case("workflow-missing-image-deadline-without-touch") {
                val before = taps.get()
                val source = saveAndStart(waitBlocks(true, RecognitionWaitMode.APPEAR, "1000"))
                awaitHold(source)
                val values = read()
                check(values["qaWo"] == "0" && requireNotNull(values["qaWe"]).toLong() >= 1000 && taps.get() == before) { "QA_WORKFLOW_DEADLINE_RESULT" }
                stop()
            }
            case("workflow-missing-image-priority-stop") {
                val source = saveAndStart(waitBlocks(true, RecognitionWaitMode.APPEAR, "60000"))
                val match = source.lineSequence().filter(String::isNotBlank).map(::JSONObject).first { it.getString("kind") == "vision.findimage" }.getString("nodeId")
                runBlocking { withTimeout(10_000) { client.executionPosition.first { p -> p?.projectId == id && (p.nodeId == match || p.recent.any { it.nodeId == match }) } } }
                val began = SystemClock.elapsedRealtime()
                stop()
                check(SystemClock.elapsedRealtime() - began < 8_000) { "QA_WORKFLOW_STOP_TIMEOUT" }
            }
            case("workflow-plugin-arguments-return-branch-and-jump") {
                val worker = snapshot.manifest.flows.first { it.flowId != flow.flowId }
                val workerSource = source(listOf(
                    block("flow.argument.get", args("index" to 1, "targetVariable" to "qaWarg")),
                    set("qaWvalue", "qaWarg * 2"), block("flow.return.set", args("index" to 1, "valueVariable" to "qaWvalue")),
                    block("flow.status.return", args("success" to true))), worker.rootBlockId)
                snapshot = store.saveFlows(store.openProject(id), mapOf(worker.flowId to workerSource))
                val source = saveAndStart(listOf(
                    block("flow.argument.set", args("index" to 1, "value" to 7)),
                    block("flow.call.branch", args("targetFlowId" to worker.flowId, "arguments" to JsonObject()), mapOf(
                        "success" to listOf(block("flow.return.get", args("index" to 1, "targetVariable" to "qaWvalue")), set("qaWvalue", "qaWvalue + 1")),
                        "failure" to listOf(set("qaWvalue", "-999")))),
                    block("control.goto", args("name" to "qaWorkflowDone")), set("qaWvalue", "-1000"),
                    block("control.label", args("name" to "qaWorkflowDone"))))
                awaitHold(source)
                val values = read()
                check(values["qaWarg"] == "7" && values["qaWvalue"] == "15") { "QA_WORKFLOW_CALL_RESULT" }
                stop()
            }
            if (extended) {
                case("workflow-generated-input-seven-actions-reach-real-view") {
                    val modes = com.autoscript.runtime.api.InputPointAction.entries
                    fun generated(action: com.autoscript.runtime.api.InputPointAction): List<VisualBlockTree> {
                        val reply = com.autoscript.runtime.api.InputPointPickReply(1, 0, action.wire,
                            size.x, size.y, x, y, x + 30, y + 20, 600)
                        return prepareInputBlocks(inputPointSnippet(reply, snapshot.manifest.design.width,
                            snapshot.manifest.design.height, snapshot.manifest.design.scaleMode), snapshot.manifest.capabilities.toSet())
                            .map { (contract, arguments) -> VisualBlockTree(contract, arguments) }
                    }
                    for (action in modes.take(4)) {
                        val before = taps.get()
                        val moved = moves.get()
                        val source = saveAndStart(generated(action))
                        awaitHold(source)
                        check(taps.get() == before + 1) { "QA_GENERATED_TOUCH_NOT_DELIVERED:${action.wire}" }
                        if (action.needsEnd) check(moves.get() > moved) { "QA_GENERATED_MOVE_NOT_DELIVERED" }
                        if (action == com.autoscript.runtime.api.InputPointAction.LONG_PRESS) check(longestTouchMs.get() >= 500)
                        stop()
                    }
                    val before = taps.get()
                    val source = saveAndStart(modes.drop(4).flatMap(::generated))
                    awaitHold(source)
                    check(taps.get() == before + 1) { "QA_GENERATED_POINTER_GROUP_NOT_DELIVERED" }
                    stop()
                    val idleUp = saveAndStart(generated(com.autoscript.runtime.api.InputPointAction.UP))
                    awaitHold(idleUp)
                    check(taps.get() == before + 1) { "QA_IDLE_POINTER_UP_CREATED_TOUCH" }
                    stop()
                }
                case("workflow-root-tap-longhold-swipe-and-enter-key-on-owned-view") {
                    val before = taps.get(); val moved = moves.get(); val keys = enterKeys.get()
                    val source = saveAndStart(listOf(
                        block("input.tap", args("x" to x, "y" to y)),
                        block("input.swipe", args("x1" to x, "y1" to y, "x2" to x, "y2" to y, "durationMs" to 600)),
                        block("input.swipe", args("x1" to (x - 20), "y1" to y, "x2" to (x + 20), "y2" to (y + 20), "durationMs" to 300)),
                        block("input.keyevent", args("keyCode" to android.view.KeyEvent.KEYCODE_ENTER))))
                    awaitHold(source)
                    check(taps.get() == before + 3 && moves.get() > moved && longestTouchMs.get() >= 500 && enterKeys.get() == keys + 1) { "QA_ROOT_GESTURE_OR_KEY_NOT_DELIVERED" }
                    stop()
                }
                case("workflow-root-continuous-pointer-down-move-up") {
                    val features = client.inputFeatures()
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_INPUT_FEATURES:$features\n") })
                    check(features and com.autoscript.runtime.api.RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER != 0) { "QA_DEVICE_CONTINUOUS_POINTER_UNSUPPORTED" }
                    val before = taps.get(); val moved = moves.get()
                    val source = saveAndStart(listOf(
                        block("input.pointerdown", args("x" to x, "y" to y)), block("task.sleep", args("milliseconds" to 150)),
                        block("input.pointermove", args("x" to (x + 20), "y" to (y + 10))), block("task.sleep", args("milliseconds" to 100)),
                        block("input.pointerup")))
                    awaitHold(source)
                    check(taps.get() == before + 1 && moves.get() > moved) { "QA_CONTINUOUS_POINTER_NOT_DELIVERED" }
                    stop()
                }
                case("workflow-serialized-captures-see-changed-pixels-and-color-roi") {
                    val before = taps.get()
                    val source = saveAndStart(listOf(
                        block("screen.capture", args("resultVariable" to "qaFrame")),
                        block("vision.getcolor", args("frameVariable" to "qaFrame", "point" to args("x" to (x - 20), "y" to y), "resultVariable" to "qaWarg")),
                        block("screen.release", args("frameVariable" to "qaFrame")),
                        block("input.tap", args("x" to (x + 6), "y" to (y + 6))), block("task.sleep", args("milliseconds" to 100)),
                        block("screen.capture", args("resultVariable" to "qaFrame")),
                        block("vision.getcolor", args("frameVariable" to "qaFrame", "point" to args("x" to (x - 20), "y" to y), "resultVariable" to "qaWvalue")),
                        block("vision.findcolor", args("frameVariable" to "qaFrame", "rgb" to (pattern[0] and 0xFFFFFF), "tolerance" to 0,
                            "region" to args("left" to x, "top" to y, "right" to (x + 1), "bottom" to (y + 1)),
                            "foundVariable" to "qaWfound", "xVariable" to "qaWx", "yVariable" to "qaWy")),
                        block("vision.comparecolor", args("frameVariable" to "qaFrame", "point" to args("x" to x, "y" to y),
                            "rgb" to (pattern[0] and 0xFFFFFF), "tolerance" to 0, "resultVariable" to "qaWo")),
                        block("vision.findmulticolor", JsonObject().apply {
                            addProperty("frameVariable", "qaFrame"); addProperty("anchorRgb", pattern[0] and 0xFFFFFF); addProperty("anchorTolerance", 0)
                            add("samples", com.google.gson.JsonArray().apply { add(args("x" to 1, "y" to 0, "rgb" to (pattern[1] and 0xFFFFFF), "tolerance" to 0)) })
                            add("region", args("left" to x, "top" to y, "right" to (x + 2), "bottom" to (y + 1)))
                            addProperty("foundVariable", "qaWa"); addProperty("xVariable", "qaWx"); addProperty("yVariable", "qaWy")
                        }),
                        block("screen.release", args("frameVariable" to "qaFrame"))))
                    awaitHold(source)
                    val values = read()
                    val first = if (before % 2 == 0) 0x0A141E else 0x145064
                    val second = if (before % 2 == 0) 0x145064 else 0x0A141E
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_PUBLIC_COLOR:" + JSONObject()
                        .put("beforeTaps", before).put("afterTaps", taps.get()).put("expectedFirst", first).put("expectedSecond", second)
                        .put("actualFirst", values["qaWarg"]).put("actualSecond", values["qaWvalue"])
                        .put("found", values["qaWfound"]).put("compare", values["qaWo"]).put("multi", values["qaWa"])
                        .put("x", values["qaWx"]).put("y", values["qaWy"]).put("expectedX", x).put("expectedY", y).toString() + "\n") })
                    check(values["qaWarg"] == first.toString() && values["qaWvalue"] == second.toString() &&
                        values["qaWfound"] == "1" && values["qaWo"] == "1" && values["qaWa"] == "1" &&
                        values["qaWx"] == x.toString() && values["qaWy"] == y.toString()) { "QA_CAPTURE_STALE_OR_COLOR_ROI" }
                    stop()
                }
                case("workflow-multiple-results-five-directions-and-dedicated-reader") {
                    for (direction in 0..4) {
                        val source = saveAndStart(listOf(
                            block("vision.findallimages", JsonObject().apply {
                                addProperty("frameVariable", "qaFrame"); addProperty("autoCapture", true)
                                add("imagePaths", com.google.gson.JsonArray().apply { add(path) })
                                addProperty("tolerance", 0); addProperty("similarityPermille", 1000)
                                add("region", args("left" to (x - 1), "top" to (y - 1), "right" to (x + 14), "bottom" to (y + 14)))
                                addProperty("direction", direction); addProperty("limit", 8); addProperty("resultVariable", "qaWresult")
                            }, mapOf("success" to listOf(set("qaWo", "1")), "failure" to listOf(set("qaWo", "-1")))),
                            block("vision.readresult", args("sourceVariable" to "qaWresult", "shape" to "IMAGES", "index" to 0, "field" to "count", "resultVariable" to "qaWarg")),
                            block("vision.readresult", args("sourceVariable" to "qaWresult", "shape" to "IMAGES", "index" to 1, "field" to "x", "resultVariable" to "qaWx")),
                            block("vision.readresult", args("sourceVariable" to "qaWresult", "shape" to "IMAGES", "index" to 1, "field" to "y", "resultVariable" to "qaWy"))))
                        awaitHold(source)
                        val values = read()
                        check(values["qaWo"] == "1" && values["qaWarg"] == "1" && values["qaWx"] == x.toString() && values["qaWy"] == y.toString()) { "QA_MULTI_RESULT_OR_DIRECTION" }
                        stop()
                    }
                }
                case("workflow-transparent-template-matching-does-not-rescale-resource") {
                    val transparent = Bitmap.createBitmap(pattern.mapIndexed { index, pixel -> if (index % 3 == 0) Color.TRANSPARENT else pixel }.toIntArray(), 12, 12, Bitmap.Config.ARGB_8888)
                    val data = try { ByteArrayOutputStream().apply { check(transparent.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() }
                        finally { transparent.recycle() }
                    val old = store.openProject(id)
                    snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "QA_transparent.png", ByteArrayInputStream(data), old.manifest.resources.map { it["path"].asString }.toSet())
                    val alphaPath = snapshot.manifest.resources.single { resource -> old.manifest.resources.none { it["path"] == resource["path"] } }["path"].asString
                    val source = saveAndStart(listOf(block("screen.capture", args("resultVariable" to "qaFrame")),
                        block("vision.findimage", args("frameVariable" to "qaFrame", "imagePath" to alphaPath, "tolerance" to 0,
                            "similarityPermille" to 1000, "region" to args("left" to x, "top" to y, "right" to (x + 12), "bottom" to (y + 12)),
                            "foundVariable" to "qaWfound", "xVariable" to "qaWx", "yVariable" to "qaWy")),
                        block("screen.release", args("frameVariable" to "qaFrame"))))
                    awaitHold(source)
                    check(read()["qaWfound"] == "1" && java.io.File(snapshot.directory, alphaPath).readBytes().contentEquals(data)) { "QA_ALPHA_TEMPLATE_CHANGED_OR_NOT_FOUND" }
                    stop()
                }
                case("workflow-released-frame-error-is-not-miss-and-next-run-recovers") {
                    val before = taps.get()
                    saveAndStart(listOf(block("screen.capture", args("resultVariable" to "qaFrame")),
                        block("screen.release", args("frameVariable" to "qaFrame")),
                        block("vision.getcolor", args("frameVariable" to "qaFrame", "point" to args("x" to x, "y" to y), "resultVariable" to "qaWarg")),
                        block("input.tap", args("x" to x, "y" to y))))
                    runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.FAILED } } }
                    check(taps.get() == before) { "QA_BAD_FRAME_EXECUTED_NEXT_ACTION" }
                    val source = saveAndStart(listOf(set("qaWvalue", "47")))
                    awaitHold(source); check(read()["qaWvalue"] == "47") { "QA_FAILED_RUN_POISONED_NEXT_RUN" }; stop()
                }
                case("workflow-rapid-start-stop-rebind-has-no-stale-highlights") {
                    repeat(5) {
                        val source = saveAndStart(listOf(set("qaWvalue", (100 + it).toString())))
                        awaitHold(source)
                        val generation = client.runtimeStates.value.sessionGeneration
                        val listenerField = RuntimeClient::class.java.getDeclaredField("stateListener").apply { isAccessible = true }
                        val staleListener = requireNotNull(listenerField.get(client) as? com.autoscript.runtime.api.IRuntimeStateListener)
                        instrumentation.runOnMainSync {
                            // Queue an old terminal callback, then invalidate it before main-thread delivery.
                            staleListener.onRuntimeStateChanged(requireNotNull(generation),
                                com.autoscript.runtime.api.RuntimeProtocol.STATE_STOPPED,
                                com.autoscript.runtime.api.RuntimeProtocol.ROOT_READY, null)
                            client.unbind(); client.bind()
                        }
                        try {
                            runBlocking { withTimeout(8_000) { client.executionPosition.first { it?.projectId == id && it.sessionGeneration == generation } } }
                        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                            val state = client.runtimeStates.value
                            val position = client.executionPosition.value
                            val proof = client.debugSnapshot(id)
                            instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_WORKFLOW_REBIND_TIMEOUT:" + JSONObject()
                                .put("iteration", it).put("phase", state.phase.name).put("root", state.rootState.name)
                                .put("engine", state.engineState.name).put("sameGeneration", state.sessionGeneration == generation)
                                .put("positionPresent", position != null).put("positionSameGeneration", position?.sessionGeneration == generation)
                                .put("positionSameProject", position?.projectId == id).put("debugStatus", proof.status)
                                .put("nativeState", proof.runtimeState).put("debugSameGeneration", proof.sessionGeneration == generation) + "\n") })
                            throw failure
                        }
                        val currentPosition = requireNotNull(client.executionPosition.value)
                        instrumentation.runOnMainSync {
                            staleListener.onRuntimeStateChanged(requireNotNull(generation),
                                com.autoscript.runtime.api.RuntimeProtocol.STATE_STOPPED,
                                com.autoscript.runtime.api.RuntimeProtocol.ROOT_READY, null)
                            staleListener.onExecutionPosition(currentPosition.sessionGeneration, currentPosition.runRevision + 1,
                                id, currentPosition.flowId, "qa-stale-node", currentPosition.sampleSequence + 1,
                                emptyArray(), emptyArray())
                        }
                        instrumentation.waitForIdleSync()
                        check(client.runtimeStates.value.engineState == RuntimeEngineState.RUNNING &&
                            client.executionPosition.value == currentPosition) { "QA_OLD_BINDING_CALLBACK_ACCEPTED" }
                        check(read()["qaWvalue"] == (100 + it).toString()) { "QA_STALE_RUN_VALUES" }
                        stop()
                        check(client.executionPosition.value == null) { "QA_TERMINAL_POSITION_NOT_CLEARED" }
                    }
                }
                case("workflow-root-recording-cancel-or-explicit-unsupported-recovery") {
                    var accepted = 0
                    var unsupported = 0
                    repeat(3) {
                        runBlocking { withTimeout(5_000) { client.runtimeStates.first { state ->
                            state.phase == com.autoscript.core.model.RuntimeConnectionPhase.CONNECTED &&
                                state.engineState == RuntimeEngineState.STOPPED && state.rootState == com.autoscript.core.model.RuntimeRootState.READY
                        } } }
                        val attempt = runBlocking { withTimeout(15_000) { client.startRecording(id, flow.flowId, 5000, 128) } }
                        if (attempt.isFailure) {
                            val message = attempt.exceptionOrNull()?.message.orEmpty()
                            val reason = when {
                                "未就绪" in message -> "NOT_READY"
                                "事件源" in message -> "UNSUPPORTED_DEVICE"
                                "仍在运行" in message || "停止脚本" in message -> "BUSY"
                                "项目/插件无效" in message -> "INVALID_PROJECT"
                                "会话已变化" in message -> "SESSION_CHANGED"
                                else -> Regex("[（(]([0-9]{1,2})[）)]$").find(message)?.groupValues?.get(1)?.let { "ENGINE_STATUS_$it" } ?: "ENGINE_OR_OVERLAY_ERROR"
                            }
                            instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_RECORDING_START_FAILURE:$reason\n") })
                            if (reason == "UNSUPPORTED_DEVICE") { unsupported++; return@repeat }
                        }
                        val recording = attempt.getOrThrow()
                        accepted++
                        check(runBlocking { client.cancelRecording(recording) }.isSuccess) { "QA_RECORDING_CANCEL_FAILED" }
                        check(client.consumeRecordingCompletion(recording) == null) { "QA_CANCELLED_RECORDING_PUBLISHED" }
                    }
                    check(accepted == 3 || unsupported == 3) { "QA_RECORDING_SUPPORT_CHANGED" }
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_RECORDING_OUTCOME:accepted=$accepted;unsupported=$unsupported;cancelChecks=$accepted\n") })
                    val source = saveAndStart(listOf(set("qaWvalue", "51")))
                    awaitHold(source); check(read()["qaWvalue"] == "51") { "QA_CANCELLED_RECORDING_LEFT_BUSY" }; stop()
                }
                case("workflow-runtime-log-active-protection-terminal-flush-and-exact-cleanup") {
                    val previousRuns = com.autoscript.runtime.api.RuntimeLogFiles.list(owner.filesDir).map { it.id }.toSet()
                    val source = saveAndStart(listOf(block("task.log", args("level" to "info", "message" to "PUBLIC_QA_LOG_CLEANUP"))))
                    awaitHold(source)
                    fun newRun() = com.autoscript.runtime.api.RuntimeLogFiles.list(owner.filesDir).singleOrNull {
                        it.metadata["projectId"] == id && it.id !in previousRuns && it.metadata["status"] == "running"
                    }
                    val end = SystemClock.elapsedRealtime() + 5_000
                    while (newRun()?.metadata?.get("status") != "running" && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
                    if (newRun() == null) instrumentation.sendStatus(1, android.os.Bundle().apply {
                        putString("stream", "QA_LOG_VISIBILITY:" + JSONObject().put("ownNewStatuses", org.json.JSONArray(
                            com.autoscript.runtime.api.RuntimeLogFiles.list(owner.filesDir).filter {
                                it.metadata["projectId"] == id && it.id !in previousRuns
                            }.map { it.metadata["status"] ?: "unknown" }))
                            .put("writerFailure", client.recentRuntimeLogs(16).any { "文件日志保存不完整" in it }) + "\n")
                    })
                    val run = requireNotNull(newRun()) { "QA_RUNTIME_LOG_MISSING" }
                    val protected = com.autoscript.runtime.api.RuntimeLogFiles.cleanup(owner.filesDir, id, setOf(run.id))
                    check(protected.protected == setOf(run.id) && protected.deleted.isEmpty()) { "QA_ACTIVE_LOG_DELETED" }
                    stop()
                    fun ownRun() = com.autoscript.runtime.api.RuntimeLogFiles.list(owner.filesDir).singleOrNull { it.id == run.id }
                    val until = SystemClock.elapsedRealtime() + 5_000
                    while (ownRun()?.metadata?.get("status") !in setOf("stopped", "ended") && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
                    check(ownRun()?.id == run.id && ownRun()?.metadata?.get("status") in setOf("stopped", "ended")) { "QA_RUNTIME_LOG_NOT_CLOSED" }
                    val text = com.autoscript.runtime.api.RuntimeLogFiles.read(owner.filesDir, run.id, "script.log")
                    check(text.contains("PUBLIC_QA_LOG_CLEANUP")) { "QA_SCRIPT_LOG_NOT_FLUSHED" }
                    val result = com.autoscript.runtime.api.RuntimeLogFiles.cleanup(owner.filesDir, id, setOf(run.id))
                    check(result.deleted == setOf(run.id) && result.failed.isEmpty() && store.openProject(id).flowSources == snapshot.flowSources) { "QA_LOG_CLEANUP_SCOPE" }
                }
            }
            if (soakMillis > 0L) case("whole-app-capture-match-input-log-soak") {
                check(soakMillis in 60_000L..1_800_000L) { "QA_SOAK_DURATION_BUDGET" }
                val beforeTaps = taps.get()
                val loopBody = listOf(
                    block("screen.capture", args("resultVariable" to "qaFrame")),
                    block("vision.findimage", args("frameVariable" to "qaFrame", "imagePath" to path,
                        "tolerance" to 0, "similarityPermille" to 1000,
                        "region" to args("left" to x, "top" to y, "right" to (x + 20), "bottom" to (y + 20)),
                        "foundVariable" to "qaWfound", "xVariable" to "qaWx", "yVariable" to "qaWy")),
                    block("screen.release", args("frameVariable" to "qaFrame")),
                    block("control.if", args("variable" to "qaWfound", "operator" to "equals", "value" to 1), mapOf(
                        "then" to listOf(block("input.tap", args("x" to (x + 6), "y" to (y + 6))), set("qaWhits", "qaWhits + 1")),
                        "else" to listOf(set("qaWvalue", "qaWvalue + 1")))),
                    block("task.log", args("level" to "info", "message" to "PUBLIC_QA_SOAK_ITERATION")),
                    block("task.sleep", args("milliseconds" to 100)))
                saveAndStart(listOf(set("qaWhits", "0"), set("qaWvalue", "0"),
                    block("control.repeat", args("times" to 100_000), mapOf("body" to loopBody))))
                val began = SystemClock.elapsedRealtime()
                var previousHits = -1L
                var runRevision = 0L
                val manager = owner.getSystemService(android.app.ActivityManager::class.java)
                val runner = manager.runningAppProcesses.single { it.processName == "${owner.packageName}:runner" }
                val generation = client.runtimeStates.value.sessionGeneration
                val samples = org.json.JSONArray()
                fun sample() {
                    val reply = client.debugSnapshot(id)
                    check(reply.status == RuntimeDebugReply.SUCCESS && reply.sessionGeneration == generation && reply.runRevision > 0) { "QA_SOAK_IDENTITY_CHANGED" }
                    if (runRevision == 0L) runRevision = reply.runRevision
                    check(reply.runRevision == runRevision && reply.runtimeState == com.autoscript.runtime.api.RuntimeProtocol.STATE_RUNNING) { "QA_SOAK_RUN_CHANGED" }
                    val values = reply.variables.associate { it.name to it.value }
                    val hits = requireNotNull(values["qaWhits"]).toLong()
                    val misses = requireNotNull(values["qaWvalue"]).toLong()
                    check(hits > previousHits && misses == 0L) { "QA_SOAK_NO_PROGRESS_OR_MATCH_FAILED" }
                    check(kotlin.math.abs(taps.get() - beforeTaps - hits) <= 1L) { "QA_SOAK_TOUCH_COUNTER_MISMATCH" }
                    previousHits = hits
                    val memory = manager.getProcessMemoryInfo(intArrayOf(runner.pid)).single()
                    check(memory.totalPss > 0) { "QA_SOAK_MEMORY_SAMPLE_UNAVAILABLE" }
                    check(manager.runningAppProcesses.single { it.processName == runner.processName }.pid == runner.pid) { "QA_SOAK_PROCESS_CHANGED" }
                    val record = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - began).put("iterations", hits)
                        .put("runnerPssKiB", memory.totalPss).put("runnerNativePssKiB", memory.nativePss)
                        .put("runnerDalvikPssKiB", memory.dalvikPss).put("runnerOtherPssKiB", memory.otherPss)
                        .put("runnerPrivateDirtyKiB", memory.totalPrivateDirty).put("runnerPrivateCleanKiB", memory.totalPrivateClean)
                        .put("runnerJavaHeapKiB", memory.getMemoryStat("summary.java-heap"))
                        .put("runnerNativeHeapKiB", memory.getMemoryStat("summary.native-heap"))
                        .put("runnerGraphicsKiB", memory.getMemoryStat("summary.graphics"))
                        .put("runnerNativeAccountingAvailable", memory.nativePss > 0)
                        .put("studioNativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                    samples.put(record)
                    // Full accounting is retained once in the final bounded summary.
                    val progress = JSONObject().put("elapsedMs", record.getLong("elapsedMs")).put("iterations", hits)
                        .put("runnerPssKiB", memory.totalPss)
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_SOAK_SAMPLE:$progress\n") })
                }
                // This is test observation, not a production execution polling mechanism.
                while (SystemClock.elapsedRealtime() - began < soakMillis) {
                    // At most 60 records even in the 30-minute run; keep the shared report budget.
                    val next = minOf(began + soakMillis, SystemClock.elapsedRealtime() + 30_000)
                    while (SystemClock.elapsedRealtime() < next) {
                        if (client.runtimeStates.value.engineState != RuntimeEngineState.RUNNING) {
                            val terminal = client.runtimeStates.value
                            instrumentation.sendStatus(1, android.os.Bundle().apply {
                                putString("stream", "QA_SOAK_TERMINAL:${terminal.engineState}:${terminal.message?.take(1200)}\n")
                            })
                        }
                        check(client.runtimeStates.value.engineState == RuntimeEngineState.RUNNING) { "QA_SOAK_UNEXPECTED_TERMINAL" }
                        SystemClock.sleep(minOf(500L, next - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                    sample()
                }
                val loadElapsedMs = SystemClock.elapsedRealtime() - began
                check(client.requestPause()) { "QA_SOAK_PAUSE_REJECTED" }
                runBlocking { withTimeout(8_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.PAUSED } } }
                val finalHits = requireNotNull(read()["qaWhits"]).toLong()
                check(kotlin.math.abs(taps.get() - beforeTaps - finalHits) <= 1L && finalHits >= 100) { "QA_SOAK_FINAL_COUNTER_MISMATCH" }
                val stoppedAt = SystemClock.elapsedRealtime()
                stop()
                val stopMs = SystemClock.elapsedRealtime() - stoppedAt
                val idleSamples = org.json.JSONArray()
                repeat(4) { index ->
                    if (index > 0) SystemClock.sleep(20_000)
                    check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED &&
                        client.runtimeStates.value.sessionGeneration == generation &&
                        manager.runningAppProcesses.single { it.processName == runner.processName }.pid == runner.pid) { "QA_SOAK_IDLE_IDENTITY_CHANGED" }
                    val memory = manager.getProcessMemoryInfo(intArrayOf(runner.pid)).single()
                    check(memory.totalPss > 0) { "QA_SOAK_IDLE_MEMORY_UNAVAILABLE" }
                    idleSamples.put(JSONObject().put("afterStopMs", SystemClock.elapsedRealtime() - stoppedAt)
                        .put("runnerPssKiB", memory.totalPss).put("runnerNativePssKiB", memory.nativePss)
                        .put("runnerJavaHeapKiB", memory.getMemoryStat("summary.java-heap")))
                }
                val summary = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - began).put("iterations", finalHits)
                    .put("loadElapsedMs", loadElapsedMs).put("stopMs", stopMs).put("samples", samples).put("postStopSamples", idleSamples)
                    .put("screen", org.json.JSONArray(listOf(size.x, size.y))).put("roi", org.json.JSONArray(listOf(x, y, x + 20, y + 20)))
                    .put("template", org.json.JSONArray(listOf(12, 12))).put("algorithm", "rust-rgba-first").put("similarityPermille", 1000)
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_SOAK_SUMMARY:$summary\n") })
            }
            return store.openProject(id)
        } finally {
            if (client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) stop()
            instrumentation.runOnMainSync { owner.setContentView(View(owner)) }
        }
    }

    internal fun source(forest: List<VisualBlockTree>, root: String): String {
        val lines = mutableListOf<String>()
        var serial = 0
        val namespace = java.util.UUID.randomUUID().toString()
        fun visit(nodes: List<VisualBlockTree>, block: String, parent: String?, depth: Int) {
            nodes.forEachIndexed { order, tree ->
                val id = "qa-workflow-$namespace-${serial++}"
                val slots = tree.children.keys.associateWith { "$id-$it" }
                val row = JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", tree.contract.nodeVersion)
                    .put("nodeId", id).put("blockId", block).put("parentId", parent ?: JSONObject.NULL).put("depth", depth)
                    .put("orderKey", "a%05d".format(java.util.Locale.ROOT, order)).put("kind", tree.contract.kind).put("args", JSONObject(tree.arguments.toString()))
                if (slots.isNotEmpty()) row.put("childBlocks", JSONObject(slots))
                lines += row.toString()
                tree.children.forEach { (slot, children) -> visit(children, slots.getValue(slot), id, depth + 1) }
            }
        }
        visit(forest, root, null, 0)
        return lines.joinToString("\n", postfix = "\n")
    }
}
