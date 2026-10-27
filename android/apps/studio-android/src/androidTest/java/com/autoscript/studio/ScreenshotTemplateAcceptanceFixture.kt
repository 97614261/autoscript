package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.ScreenshotPreviewResult
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Same screenshot PNG/ROI path as the editor; only newly owned public pixels and scripts. */
internal object ScreenshotTemplateAcceptanceFixture {
    fun run(test: Instrumentation, owner: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-"))
        var snapshot = store.updateVariables(id, initial.manifest.variables +
            listOf("qaFound", "qaX", "qaY").map { ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER) } +
            ProjectVariable("qaFrame", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE), initial.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() +
            setOf("screen.capture", "vision.template", "core.task"), snapshot.manifest.capabilities.toSet())
        var sample = 0
        val textureRegion = java.util.concurrent.atomic.AtomicReference<ImageToolCodeGen.Roi>()
        RootCaptureGeometryFixture.run(test, owner, client, record, drawContent = { canvas, surface ->
            val left = surface.width * 3 / 4
            val top = surface.height / 5
            val paint = android.graphics.Paint().apply { isAntiAlias = false }
            for (y in 0 until 19) for (x in 0 until 19) {
                paint.color = android.graphics.Color.rgb((x * 31 + y * 7) % 256,
                    (x * 13 + y * 43) % 256, (x * 47 + y * 23) % 256)
                canvas.drawRect((left + x).toFloat(), (top + y).toFloat(), (left + x + 1).toFloat(), (top + y + 1).toFloat(), paint)
            }
            val location = IntArray(2).also(surface::getLocationOnScreen)
            textureRegion.set(ImageToolCodeGen.Roi(location[0] + left, location[1] + top,
                location[0] + left + 19, location[1] + top + 19))
        }) { _, _, _, _ ->
            val capture = runBlocking { withTimeout(20_000) { client.capturePreviewWhenReady {} } }
            check(capture is ScreenshotPreviewResult.Success) { "QA_TEMPLATE_CAPTURE" }
            val image = try { requireNotNull(BitmapFactory.decodeFile(capture.file.path)) } finally { capture.file.delete() }
            val roi = requireNotNull(textureRegion.get())
            val geometry = "${image.width}x${image.height}-${++sample}"
            val saved = try { cropAndImportTemplate(store, snapshot, image, roi, test.targetContext,
                imageName = "public-$geometry.png", rememberScreenRegion = true) } finally { image.recycle() }
            snapshot = saved.first
            val hint = imageRecognitionFile(snapshot, saved.second).inputStream().use(ScreenshotTemplatePng::read)
            check(hint?.roi == roi) { "QA_TEMPLATE_ORIGINAL_ROI" }
            val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
            val inputs = recognitionTemplateRegionInputs(recognitionPropertyTexts(contract,
                initialRecognitionArguments(contract, snapshot)), hint, true) + mapOf(
                "imagePath" to saved.second, "autoCapture" to "true", "frameVariable" to "qaFrame",
                "foundVariable" to "qaFound", "xVariable" to "qaX", "yVariable" to "qaY", "similarityPermille" to "1000")
            val parsed = parseRecognitionArguments(contract, inputs, snapshot.manifest.resources)
            check(parsed.second == null) { "QA_TEMPLATE_FORM" }
            val flow = snapshot.manifest.flows.single()
            val source = StudioWorkflowAcceptanceFixture.source(listOf(
                VisualBlockTree(requireNotNull(BlockCatalog.find("control.repeat")), JsonObject().apply { addProperty("times", 1) },
                    mapOf("body" to listOf(VisualBlockTree(contract, requireNotNull(parsed.first)),
                        VisualBlockTree(requireNotNull(BlockCatalog.find("task.sleep")), JsonObject().apply { addProperty("milliseconds", 60_000) }))))), flow.rootBlockId)
            snapshot = store.saveFlows(store.openProject(id), mapOf(flow.flowId to source))
            val nodes = source.lineSequence().filter(String::isNotBlank).map(::JSONObject).filter { it.has("kind") }.toList()
            val tail = nodes.last().getString("nodeId")
            for (nodeTest in listOf(false, true)) record("screenshot-template-$geometry-${if (nodeTest) "node-test" else "run"}") {
                val compiled = if (nodeTest) client.compileVisualNodeTest(id, flow.flowId, nodes.first().getString("nodeId"))
                    else client.compileVisualProject(id)
                check(compiled is VisualCompileResult.Success) { "QA_TEMPLATE_COMPILE:$compiled" }
                val frozen = store.openProject(id)
                val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId, flow.flowId,
                    if (nodeTest) nodes.first().getString("nodeId") else null).forEditorRun(frozen)
                check(runBlocking { withTimeout(30_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                    plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                    requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_TEMPLATE_START" }
                try {
                    runBlocking { withTimeout(15_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == tail } } }
                    val result = client.debugSnapshot(id)
                    check(result.status == RuntimeDebugReply.SUCCESS) { "QA_TEMPLATE_DEBUG" }
                    val values = result.variables.associate { it.name to it.value }
                    check(values["qaFound"] == "1" && values["qaX"] == roi.left.toString() && values["qaY"] == roi.top.toString()) {
                        "QA_TEMPLATE_MISMATCH:$geometry:${values.filterKeys { it in setOf("qaFound", "qaX", "qaY") }}"
                    }
                } finally {
                    check(client.requestStop()) { "QA_TEMPLATE_STOP" }
                    runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState in setOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED) } } }
                    check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "QA_TEMPLATE_STOP_FAILED" }
                }
            }
        }
        return store.openProject(id)
    }
}
