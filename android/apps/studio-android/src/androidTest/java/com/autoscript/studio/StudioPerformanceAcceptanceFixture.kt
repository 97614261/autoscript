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
    private data class Workload(val name: String, val region: JsonObject, val found: Boolean = true,
        val regionCapture: Boolean = false, val templates: List<String> = listOf("base"),
        val similarity: Int = 1000, val tolerance: Int = 0, val independent: Boolean = false)

    fun run(instrumentation: Instrumentation, owner: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, case: (String, () -> Unit) -> Unit, expanded: Boolean = false, mixed: Boolean = false): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-")) { "QA_PERFORMANCE_IDENTITY" }
        var snapshot = store.openProject(id)
        snapshot = store.updateVariables(id, snapshot.manifest.variables + listOf("qaPf", "qaPx", "qaPy", "qaBegin", "qaEnd", "qaDuration")
            .map { ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER) } +
            ProjectVariable("qaMapped", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING), snapshot.manifest.variables)
        if(mixed) snapshot=store.updateVariables(id,snapshot.manifest.variables+listOf("qaCf","qaCx","qaCy","qaCoverage","qaScore")
            .map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER)}+
            ProjectVariable("qaGlyph",ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING),snapshot.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + setOf("vision.template", "input.basic") +
            (if(mixed) setOf("vision.pixel","ocr.glyph") else emptySet()), snapshot.manifest.capabilities.toSet())
        snapshot = store.updateDebugSettings(id, snapshot.manifest.debugSettings.copy(recordVisionMetrics = true), snapshot.manifest.debugSettings)
        val size = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
        check(size.x >= 480 && size.y >= 800) { "QA_PERFORMANCE_SCREEN_TOO_SMALL" }
        val measurementDeadline = SystemClock.elapsedRealtime() + 480_000
        val pixels = IntArray(200 * 122) { i -> Color.rgb(32 + i * 31 % 180, 24 + i * 43 % 190, 16 + i * 13 % 200) }
        if(mixed) for(row in 0 until 52) for(column in 0 until 36)
            pixels[row*200+column]=if(row in 2..49 && column in 2..33) Color.RED else Color.WHITE
        val bitmap = Bitmap.createBitmap(pixels, 200, 122, Bitmap.Config.ARGB_8888)
        val localX = size.x - 240
        val localY = size.y - 380
        val surface = object : View(owner) {
            private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(13, 17, 19))
                canvas.drawBitmap(bitmap, localX.toFloat(), localY.toFloat(), paint)
            }
            override fun onTouchEvent(event: android.view.MotionEvent) = true
        }
        try {
            instrumentation.runOnMainSync { owner.setContentView(surface) }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            instrumentation.runOnMainSync { surface.getLocationOnScreen(location) }
            val x = localX + location[0]; val y = localY + location[1]
            check(surface.width >= localX + 200 && surface.height >= localY + 122 && x + 220 <= size.x && y + 142 <= size.y) { "QA_PERFORMANCE_SURFACE_BOUNDS" }
            // A laid-out View can precede its first presented frame. Keep readiness outside timing.
            val presentedDeadline = SystemClock.elapsedRealtime() + 5_000
            var presented = false
            while (!presented && SystemClock.elapsedRealtime() < presentedDeadline) {
                val screen = instrumentation.uiAutomation.takeScreenshot()
                if (screen != null) try {
                    if (x >= 0 && y >= 0 && x + 200 <= screen.width && y + 122 <= screen.height) {
                        val observed = IntArray(pixels.size)
                        screen.getPixels(observed, 0, 200, x, y, 200, 122)
                        presented = observed.contentEquals(pixels)
                    }
                } finally { screen.recycle() }
                if (!presented) SystemClock.sleep(25)
            }
            check(presented) { "QA_PERFORMANCE_SURFACE_NOT_PRESENTED" }
            val data = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
            val paths = snapshot.manifest.resources.map { it["path"].asString }.toSet()
            snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "QA_performance_200x122.png", ByteArrayInputStream(data), paths)
            val path = snapshot.manifest.resources.single { it["path"].asString !in paths }["path"].asString
            val templatePaths = mutableMapOf("base" to path)
            var dictionaryPath:String?=null
            if(mixed) {
                val before=snapshot.manifest.resources.map {it["path"].asString}.toSet()
                val glyphs=List(65) {index ->
                    val bits=ByteArray(32*48/8) {-1}
                    if(index<64) bits[index]=(bits[index].toInt() xor 128).toByte()
                    MakerGlyph("I",32,48,bits)
                }
                val encoded=GlyphDictionaryMaker.encode(glyphs)
                snapshot=store.importResource(id,ProjectResourceKind.GLYPH_DICTIONARY,"QA_shared_I.asglyph",ByteArrayInputStream(encoded),before)
                dictionaryPath=snapshot.manifest.resources.single {it["path"].asString !in before}["path"].asString
            }
            val offsets = mapOf("crop0" to (0 to 0), "crop1" to (60 to 40), "crop2" to (120 to 82))
            if (expanded) for (key in listOf("relaxed", "transparent") + offsets.keys) {
                val candidate = offsets[key]?.let { (left, top) -> Bitmap.createBitmap(bitmap, left, top, 80, 40) }
                    ?: Bitmap.createBitmap(pixels.mapIndexed { index, color ->
                        when {
                            key == "transparent" && index % 5 == 0 -> Color.TRANSPARENT
                            key == "relaxed" && index % 20 == 0 -> Color.rgb(255, 0, 255)
                            else -> color
                        }
                    }.toIntArray(), 200, 122, Bitmap.Config.ARGB_8888)
                try {
                    val encoded = ByteArrayOutputStream().apply { check(candidate.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
                    val beforePaths = snapshot.manifest.resources.map { it["path"].asString }.toSet()
                    snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "QA_performance_$key.png", ByteArrayInputStream(encoded), beforePaths)
                    templatePaths[key] = snapshot.manifest.resources.single { it["path"].asString !in beforePaths }["path"].asString
                } finally { candidate.recycle() }
            }
            val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
            fun args(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply { pairs.forEach { (key, value) ->
                when (value) { is Number -> addProperty(key, value); is JsonObject -> add(key, value); else -> addProperty(key, value.toString()) }
            } }
            fun block(kind: String, values: JsonObject = JsonObject(), children: Map<String, List<VisualBlockTree>> = emptyMap()) =
                VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), values, children)
            val small = args("left" to x, "top" to y, "right" to (x + 220), "bottom" to (y + 142))
            val local = args("left" to 10, "top" to 10, "right" to 230, "bottom" to 152)
            val conditions = if(mixed) listOf(Workload("mixed-shared-full",small),Workload("mixed-shared-region",local,regionCapture=true)) else if (!expanded) listOf(
                Workload("small-roi-front", small),
                Workload("large-roi-late", args("left" to 0, "top" to 0, "right" to size.x, "bottom" to size.y)),
                Workload("large-roi-missing", args("left" to 0, "top" to 0, "right" to size.x, "bottom" to y), found = false),
                Workload("three-matches-shared-fresh-frame", small, templates = List(3) { "base" }),
                Workload("three-matches-independent-fresh-frames", small, templates = List(3) { "base" }, independent = true),
            ) else listOf(
                Workload("full-small-exact", small), Workload("region-small-exact", local, regionCapture = true),
                Workload("full-small-relaxed", small, templates = listOf("relaxed"), similarity = 900, tolerance = 12),
                Workload("region-small-relaxed", local, regionCapture = true, templates = listOf("relaxed"), similarity = 900, tolerance = 12),
                Workload("full-small-transparent", small, templates = listOf("transparent")),
                Workload("region-small-transparent", local, regionCapture = true, templates = listOf("transparent")),
                Workload("three-distinct-shared-full", small, templates = offsets.keys.toList()),
                Workload("three-distinct-independent-full", small, templates = offsets.keys.toList(), independent = true),
                Workload("three-distinct-shared-region", local, regionCapture = true, templates = offsets.keys.toList()),
            )
            for (workload in conditions) case("performance-${workload.name}") {
                val name = workload.name; val region = workload.region; val found = workload.found
                val matchesForLoop = workload.templates.map { key -> block("vision.findimage", args("frameVariable" to "qaFrame", "imagePath" to templatePaths.getValue(key),
                        "tolerance" to workload.tolerance, "similarityPermille" to workload.similarity, "region" to region, "direction" to 0,
                        "foundVariable" to "qaPf", "xVariable" to "qaPx", "yVariable" to "qaPy")) } + if(mixed) {
                    val left=if(workload.regionCapture) 10 else x;val top=if(workload.regionCapture) 10 else y
                    val glyphRegion=args("left" to left,"top" to top,"right" to left+36,"bottom" to top+52)
                    listOf(block("vision.findcolor",args("frameVariable" to "qaFrame","rgb" to 0xff0000,"tolerance" to 0,"region" to glyphRegion,
                        "foundVariable" to "qaCf","xVariable" to "qaCx","yVariable" to "qaCy")),
                        block("ocr.glyph",args("frameVariable" to "qaFrame","dictionaryPath" to requireNotNull(dictionaryPath),"foregroundRgb" to 0xff0000,
                            "tolerance" to 0,"similarityPermille" to 1000,"spaceGapColumns" to 0,"region" to glyphRegion,
                            "textVariable" to "qaGlyph","coverageVariable" to "qaCoverage","scoreVariable" to "qaScore")))
                } else emptyList()
                val invalidate=block("input.tap",args("x" to (x + 1), "y" to (y + 1)))
                val capture=if (workload.regionCapture) block("screen.captureregion", args("resultVariable" to "qaFrame",
                    "left" to (x-10), "top" to (y-10), "right" to (x+220), "bottom" to (y+142)))
                    else block("screen.capture",args("resultVariable" to "qaFrame"))
                val release=block("screen.release",args("frameVariable" to "qaFrame"))
                val count=matchesForLoop.size
                val metricCount=workload.templates.size
                val independent=workload.independent
                // Real Root input invalidates the production 80ms cache, including between captures.
                // Include that input in the complete timer, and use this same workload for both packages.
                val mapping = if (expanded) listOf(block("screen.mappoint",args("frameVariable" to "qaFrame", "x" to 0, "y" to 0,
                    "xVariable" to "qaPx", "yVariable" to "qaPy", "resultVariable" to "qaMapped"))) else emptyList()
                val operations=if(independent) matchesForLoop.flatMap { listOf(invalidate,capture,it)+mapping+release }
                    else listOf(invalidate,capture)+matchesForLoop+mapping+release
                val mixedOracles=if(mixed) listOf("qaGlyph" to "QA_MIXED_GLYPH","qaCf" to "QA_MIXED_COLOR","qaScore" to "QA_MIXED_SCORE").map {(variable,message) ->
                    block("task.log",args("level" to "info","message" to message,"valueVariable" to variable))} else emptyList()
                val body=listOf(block("system.elapsed",args("resultVariable" to "qaBegin"))) + operations + listOf(
                    block("system.elapsed",args("resultVariable" to "qaEnd")),
                    block("variable.calculate",args("name" to "qaDuration","expression" to "qaEnd - qaBegin")),
                    block("task.log",args("level" to "info","message" to "QA_PIPELINE_MS","valueVariable" to "qaDuration"))) + mixedOracles + listOf(
                    // Observation pacing is outside the measured region. Keep the production
                    // bounded journal and its zero-drop assertion, rather than enlarging it.
                    block("task.sleep",args("milliseconds" to 25)))
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
                // Bound the whole fixture, but give three fresh captures the corresponding
                // workload budget. This is not a latency pass threshold; publish actual timings.
                val remaining = measurementDeadline - SystemClock.elapsedRealtime()
                check(remaining > 0) { "QA_PERFORMANCE_DEADLINE" }
                val workloadBudget = 90_000L * if (independent) count else 1
                try {
                    runBlocking { withTimeout(minOf(workloadBudget, remaining)) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                } catch (failure: Throwable) {
                    val terminal = client.runtimeStates.value
                    instrumentation.sendStatus(1, Bundle().apply {
                        putString("stream", "QA_PERFORMANCE_STATE:${terminal.engineState}:${terminal.message?.take(1200)}\n")
                    })
                    throw failure
                }
                val proof = client.debugSnapshot(id)
                check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 && proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_PERFORMANCE_RUN_CHANGED" }
                val values = proof.variables.associate { it.name to it.value }
                check(values["qaPf"] == if (found) "1" else "0") { "QA_PERFORMANCE_MATCH_RESULT" }
                val offset=offsets[workload.templates.last()] ?: (0 to 0)
                val expectedX=(if(workload.regionCapture) 10 else x)+offset.first
                val expectedY=(if(workload.regionCapture) 10 else y)+offset.second
                if (found) check(values["qaPx"] == expectedX.toString() && values["qaPy"] == expectedY.toString()) { "QA_PERFORMANCE_COORDINATES:$name:${values["qaPx"]},${values["qaPy"]}" }
                if(mixed) check(values["qaCf"]=="1" && values["qaCx"]==(expectedX+2).toString() && values["qaCy"]==(expectedY+2).toString() &&
                    values["qaGlyph"]=="I" && values["qaCoverage"]=="1000" && values["qaScore"]=="1000") {"QA_MIXED_FRAME_RESULTS"}
                if (expanded) {
                    val mapped=JSONObject(requireNotNull(values["qaMapped"]))
                    check(mapped.getInt("x")==x+offset.first && mapped.getInt("y")==y+offset.second) { "QA_REGION_SCREEN_MAPPING" }
                }
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
                val captures = events.filter { it.optInt("opcode") == if(workload.regionCapture) 5014 else 5000 }
                val matches = events.filter { it.optInt("opcode") in setOf(5101,5107) }
                instrumentation.sendStatus(1, Bundle().apply { putString("stream", "QA_PERFORMANCE_COUNTS:" + JSONObject()
                    .put("case", name).put("runId", closed.id).put("operations", JSONArray(events.groupBy { it.optInt("opcode") to it.optString("status") }
                        .map { (key, values) -> JSONObject().put("opcode", key.first).put("status", key.second).put("count", values.size) })).toString() + "\n") })
                check(captures.size == 111*(if(independent) count else 1) && matches.size == 111*metricCount && captures.all { it.optString("status") == "completed" } &&
                    matches.all { it.optString("status") == if (found) "found" else "not_found" }) { "QA_PERFORMANCE_SAMPLE_COUNT" }
                check(captures.all { it.has("captureCacheHit") && !it.getBoolean("captureCacheHit") }) { "QA_PERFORMANCE_REQUIRES_FRESH_CAPTURES" }
                fun summary(values: List<JSONObject>): JSONObject {
                    val warm = values.drop(11*(if(values===captures) {if(independent) count else 1} else metricCount)).map { it.getLong("elapsedUs") }.sorted()
                    fun p(f:Double)=warm[(kotlin.math.ceil(warm.size*f).toInt()-1).coerceIn(0,warm.lastIndex)]
                    return JSONObject().put("firstCallUs", values.first().getLong("elapsedUs")).put("warmupCalls", 10)
                        .put("samples", warm.size).put("p50Us", p(.5)).put("p95Us", p(.95)).put("p99Us", p(.99))
                }
                // valueVariable logs the value instead of the configured message. This owned
                // script has exactly four ordered records per mixed iteration, never user logs.
                val loggedValues=RuntimeLogFiles.read(owner.filesDir,closed.id,"script.log").lineSequence()
                    .filter { runtimeConsoleChannel(it) == RuntimeConsoleChannel.SCRIPT }.map {it.substringAfterLast(' ')}.toList()
                if(mixed) check(loggedValues.size==111*4 && loggedValues.chunked(4).all {it.drop(1)==listOf("I","1","1000")}) {"QA_MIXED_LOOP_RESULTS"}
                val pipeline=(if(mixed) loggedValues.chunked(4).map {it.first()} else loggedValues).mapNotNull(String::toLongOrNull)
                check(pipeline.size==111) { "QA_PIPELINE_SAMPLE_COUNT" }
                pipeline.forEachIndexed { sample, elapsed ->
                    val captureCount=if(independent) count else 1
                    val stageUs=captures.drop(sample*captureCount).take(captureCount).sumOf {it.getLong("elapsedUs")} +
                        matches.drop(sample*metricCount).take(metricCount).sumOf {it.getLong("elapsedUs")}
                    check(elapsed*1000+2000>=stageUs) { "QA_PIPELINE_CLOCK_UNDERCOUNTS_STAGES" }
                }
                val warmPipeline=pipeline.drop(11).sorted()
                val pipelineSummary=JSONObject().put("firstCallMs",pipeline.first()).put("samples",warmPipeline.size)
                    .put("p50Ms",warmPipeline[49]).put("p95Ms",warmPipeline[94]).put("p99Ms",warmPipeline[98])
                    .put("clockResolutionMs",1).put("observationPacingMs",25)
                    .put("includes","public Root tap + capture + matching + release + script dispatch; excludes following log write and 25ms observation pacing")
                val warmCaptures=captures.drop(11*(if(independent) count else 1))
                val captureCount = if(independent) count else 1
                val backendPipelines = pipeline.drop(11).withIndex().groupBy { sample ->
                    warmCaptures.drop(sample.index * captureCount).take(captureCount)
                        .map { it.getString("captureBackend") }.distinct().sorted().joinToString("+")
                }
                val byBackend = JSONObject()
                for ((backend, samples) in backendPipelines) {
                    val values = samples.map { it.value }.sorted()
                    fun percentile(f: Double) = values[(kotlin.math.ceil(values.size * f).toInt() - 1).coerceIn(0, values.lastIndex)]
                    byBackend.put(backend, JSONObject().put("samples", values.size).put("p50Ms", percentile(.5))
                        .put("p95Ms", percentile(.95)).put("p99Ms", percentile(.99)))
                }
                pipelineSummary.put("mixedCaptureBackends", backendPipelines.size > 1)
                fun stageSummary(group:String):JSONObject {
                    val summary=JSONObject()
                    val names=warmCaptures.flatMap { event -> event.optJSONObject(group)?.keys()?.asSequence()?.toList().orEmpty() }.toSortedSet()
                    for(field in names) {
                        val values=warmCaptures.mapNotNull { event -> event.optJSONObject(group)?.takeIf { it.has(field) }?.getLong(field) }.sorted()
                        fun p(f:Double)=values[(kotlin.math.ceil(values.size*f).toInt()-1).coerceIn(0,values.lastIndex)]
                        summary.put(field,JSONObject().put("samples",values.size).put("p50",p(.5)).put("p95",p(.95)).put("p99",p(.99)))
                    }
                    return summary
                }
                val captureBreakdown=JSONObject().put("backendCounts",JSONObject().apply {
                    warmCaptures.groupingBy { it.getString("captureBackend") }.eachCount().forEach { (backend,count) -> put(backend,count) }
                }).put("transportCounts",JSONObject().apply {
                    warmCaptures.groupingBy { it.getString("captureTransport") }.eachCount().forEach { (transport,count) -> put(transport,count) }
                }).put("stagesUs",stageSummary("captureStagesUs")).put("transferUs",stageSummary("captureTransferUs"))
                    .put("scope","warm captures only; overlapping acquisition/bridge/transport phases and percentiles must not be added")
                val result = JSONObject().put("case", name).put("runId", closed.id).put("sdk", Build.VERSION.SDK_INT)
                    .put("deviceAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("screen", JSONArray(listOf(size.x, size.y)))
                    .put("template", JSONArray(if(workload.templates.first() in offsets) listOf(80,40) else listOf(200, 122)))
                    .put("templateVariants",JSONArray(workload.templates)).put("roi", JSONObject(region.toString()))
                    .put("similarity", workload.similarity).put("tolerance", workload.tolerance).put("direction", 0)
                    .put("regionReadback",workload.regionCapture).put("explicitMappingTimed",expanded)
                    .put("capture", summary(captures)).put("match", summary(matches)).put("captureBreakdown",captureBreakdown)
                    .put("pipeline",pipelineSummary).put("pipelineByCaptureBackend",byBackend)
                    .put("matchesPerIteration",count).put("recordedTemplateMetricsPerIteration",metricCount)
                    .put("mixedGlyphVariants",if(mixed) 65 else 0).put("capturesPerIteration",if(independent) count else 1)
                    .put("scope", "warm authenticated Root; public Root tap before each capture included in complete timer; first call is not process cold start; stage percentiles not additive")
                    .put("cacheInvalidation", "public-root-tap-before-each-capture")
                instrumentation.sendStatus(1, Bundle().apply { putString("stream", "QA_PERFORMANCE:$result\n") })
            }
            return store.openProject(id)
        } finally {
            instrumentation.runOnMainSync { owner.setContentView(View(owner)) }
            bitmap.recycle()
        }
    }
}
