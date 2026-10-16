package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeLogEventCodec
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Opt-in test APK only. No user project, mutable screen cache, or model replacement. */
internal object StudioPerformanceAcceptanceFixture {
    fun run(instrumentation: Instrumentation, owner: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, case: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-")) { "QA_PERFORMANCE_IDENTITY" }
        var snapshot = store.openProject(id)
        snapshot = store.updateVariables(id, snapshot.manifest.variables + listOf("qaPf", "qaPx", "qaPy")
            .map { ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER) }, snapshot.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + "vision.template", snapshot.manifest.capabilities.toSet())
        snapshot = store.updateDebugSettings(id, snapshot.manifest.debugSettings.copy(recordVisionMetrics = true), snapshot.manifest.debugSettings)
        val size = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
        check(size.x >= 480 && size.y >= 800) { "QA_PERFORMANCE_SCREEN_TOO_SMALL" }
        val pixels = IntArray(200 * 122) { i -> Color.rgb(32 + i * 31 % 180, 24 + i * 43 % 190, 16 + i * 13 % 200) }
        val bitmap = Bitmap.createBitmap(pixels, 200, 122, Bitmap.Config.ARGB_8888)
        val localX = size.x - 240
        val localY = size.y - 380
        val surface = object : View(owner) {
            private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(13, 17, 19))
                canvas.drawBitmap(bitmap, localX.toFloat(), localY.toFloat(), paint)
            }
        }
        try {
            instrumentation.runOnMainSync { owner.setContentView(surface) }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            instrumentation.runOnMainSync { surface.getLocationOnScreen(location) }
            val x = localX + location[0]; val y = localY + location[1]
            check(surface.width >= localX + 200 && surface.height >= localY + 122 && x + 220 <= size.x && y + 142 <= size.y) { "QA_PERFORMANCE_SURFACE_BOUNDS" }
            val data = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            val paths = snapshot.manifest.resources.map { it["path"].asString }.toSet()
            snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "QA_performance_200x122.png", ByteArrayInputStream(data), paths)
            val path = snapshot.manifest.resources.single { it["path"].asString !in paths }["path"].asString
            val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
            fun args(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply { pairs.forEach { (key, value) ->
                when (value) { is Number -> addProperty(key, value); is JsonObject -> add(key, value); else -> addProperty(key, value.toString()) }
            } }
            fun block(kind: String, values: JsonObject = JsonObject(), children: Map<String, List<VisualBlockTree>> = emptyMap()) =
                VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), values, children)
            val conditions = listOf(
                Triple("small-roi-front", args("left" to x, "top" to y, "right" to (x + 220), "bottom" to (y + 142)), true),
                Triple("large-roi-late", args("left" to 0, "top" to 0, "right" to size.x, "bottom" to size.y), true),
                Triple("large-roi-missing", args("left" to 0, "top" to 0, "right" to size.x, "bottom" to y), false),
            )
            for ((name, region, found) in conditions) case("performance-$name") {
                val body = listOf(block("screen.capture", args("resultVariable" to "qaFrame")),
                    block("vision.findimage", args("frameVariable" to "qaFrame", "imagePath" to path,
                        "tolerance" to 0, "similarityPermille" to 1000, "region" to region, "direction" to 0,
                        "foundVariable" to "qaPf", "xVariable" to "qaPx", "yVariable" to "qaPy")),
                    block("screen.release", args("frameVariable" to "qaFrame")))
                val source = StudioWorkflowAcceptanceFixture.source(listOf(
                    block("control.repeat", args("times" to 111), mapOf("body" to body)),
                    block("task.sleep", args("milliseconds" to 60_000))), flow.rootBlockId)
                snapshot = store.saveFlows(store.openProject(id), mapOf(flow.flowId to source))
                val tail = JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
                val oldRuns = RuntimeLogFiles.list(owner.filesDir).map { it.id }.toSet()
                val compiled = client.compileVisualProject(id)
                check(compiled is VisualCompileResult.Success) { "QA_PERFORMANCE_COMPILE" }
                val frozen = store.openProject(id)
                val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
                check(runBlocking { withTimeout(25_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                    plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                    requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_PERFORMANCE_START" }
                runBlocking { withTimeout(90_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                val proof = client.debugSnapshot(id)
                check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 && proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_PERFORMANCE_RUN_CHANGED" }
                val values = proof.variables.associate { it.name to it.value }
                check(values["qaPf"] == if (found) "1" else "0") { "QA_PERFORMANCE_MATCH_RESULT" }
                if (found) check(values["qaPx"] == x.toString() && values["qaPy"] == y.toString()) { "QA_PERFORMANCE_COORDINATES" }
                check(client.requestStop()) { "QA_PERFORMANCE_STOP" }
                runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.STOPPED || it.engineState == RuntimeEngineState.FAILED } } }
                check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "QA_PERFORMANCE_STOP_FAILED" }
                var run: RuntimeLogFiles.Run? = null
                val flushDeadline = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < flushDeadline) {
                    run = RuntimeLogFiles.list(owner.filesDir).singleOrNull { it.id !in oldRuns && it.metadata["projectId"] == id }
                    if (run?.metadata?.containsKey("endedAtMs") == true) break
                    SystemClock.sleep(25)
                }
                val closed = requireNotNull(run)
                check(closed.metadata["status"] == "stopped" && closed.metadata.containsKey("endedAtMs") &&
                    closed.metadata["droppedRecords"] == "0" && closed.metadata["truncatedRecords"] == "0") { "QA_PERFORMANCE_LOG_INCOMPLETE" }
                val decoder = RuntimeLogEventCodec.Decoder()
                val events = RuntimeLogFiles.read(owner.filesDir, closed.id, "events.jsonl").lineSequence().filter(String::isNotBlank)
                    .mapNotNull(decoder::expand).map(::JSONObject).filter { it.optString("kind") == "vision" }.toList()
                val captures = events.filter { it.optInt("opcode") == 5000 }
                val matches = events.filter { it.optInt("opcode") in setOf(5101, 5107) }
                instrumentation.sendStatus(1, Bundle().apply { putString("stream", "QA_PERFORMANCE_COUNTS:" + JSONObject()
                    .put("case", name).put("runId", closed.id).put("operations", JSONArray(events.groupBy { it.optInt("opcode") to it.optString("status") }
                        .map { (key, values) -> JSONObject().put("opcode", key.first).put("status", key.second).put("count", values.size) })).toString() + "\n") })
                check(captures.size == 111 && matches.size == 111 && captures.all { it.optString("status") == "completed" } &&
                    matches.all { it.optString("status") == if (found) "found" else "not_found" }) { "QA_PERFORMANCE_SAMPLE_COUNT" }
                fun summary(values: List<JSONObject>): JSONObject {
                    val warm = values.drop(11).map { it.getLong("elapsedUs") }.sorted()
                    return JSONObject().put("firstCallUs", values.first().getLong("elapsedUs")).put("warmupCalls", 10)
                        .put("samples", warm.size).put("p50Us", warm[49]).put("p95Us", warm[94]).put("p99Us", warm[98])
                }
                val result = JSONObject().put("case", name).put("runId", closed.id).put("sdk", Build.VERSION.SDK_INT)
                    .put("deviceAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("screen", JSONArray(listOf(size.x, size.y)))
                    .put("template", JSONArray(listOf(200, 122))).put("roi", JSONObject(region.toString()))
                    .put("similarity", 1000).put("tolerance", 0).put("direction", 0)
                    .put("capture", summary(captures)).put("match", summary(matches))
                    .put("scope", "warm authenticated Root; fresh capture each iteration; first call is not process cold start; stage percentiles not additive")
                instrumentation.sendStatus(1, Bundle().apply { putString("stream", "QA_PERFORMANCE:$result\n") })
            }
            return store.openProject(id)
        } finally {
            instrumentation.runOnMainSync { owner.setContentView(View(owner)) }
            bitmap.recycle()
        }
    }
}
