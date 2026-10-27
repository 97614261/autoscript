package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Public asymmetric surface only; compares fresh regional pixels and exact screen lineage. */
internal object StudioRegionCaptureFixture {
    fun run(test: Instrumentation, owner: Activity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id=initial.manifest.projectId
        check(id.startsWith("project-qa-"))
        var snapshot=store.updateVariables(id,initial.manifest.variables+listOf(
            ProjectVariable("qaRegion",ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE),
            ProjectVariable("qaRegionProcessed",ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE),
            ProjectVariable("qaRegionA",ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER),
            ProjectVariable("qaRegionB",ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER),
            ProjectVariable("qaRegionMap",ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING),
            ProjectVariable("qaRegionFlipMap",ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING)),initial.manifest.variables)
        snapshot=store.updateCapabilities(id,snapshot.manifest.capabilities.toSet()+setOf("screen.capture","vision.pixel","core.task"),snapshot.manifest.capabilities.toSet())
        fun block(kind:String,vararg fields:Pair<String,Any>)=VisualBlockTree(requireNotNull(BlockCatalog.find(kind)),JsonObject().apply {
            fields.forEach { (key,value)->when(value) { is Number->addProperty(key,value);is JsonObject->add(key,value);else->addProperty(key,value.toString()) } }
        })
        RootCaptureGeometryFixture.run(test,owner,client,record) { a,b,firstColor,secondColor ->
            val left=a.x-3;val top=a.y-5;val right=b.x+4;val bottom=b.y+6
            check(left>=0 && top>=0)
            val flow=snapshot.manifest.flows.first {it.flowId==snapshot.manifest.entryFlowId}
            val program=StudioWorkflowAcceptanceFixture.source(listOf(
                block("screen.captureregion","left" to left,"top" to top,"right" to right,"bottom" to bottom,"resultVariable" to "qaRegion"),
                block("vision.getcolor","frameVariable" to "qaRegion","point" to JsonObject().apply {addProperty("x",3);addProperty("y",5)},"resultVariable" to "qaRegionA"),
                block("vision.getcolor","frameVariable" to "qaRegion","point" to JsonObject().apply {addProperty("x",b.x-left);addProperty("y",b.y-top)},"resultVariable" to "qaRegionB"),
                block("screen.mappoint","frameVariable" to "qaRegion","x" to 3,"y" to 5,"xVariable" to "","yVariable" to "","resultVariable" to "qaRegionMap"),
                block("screen.transform","frameVariable" to "qaRegion","resultVariable" to "qaRegionProcessed","mode" to "flipX","width" to 0,"height" to 0,"threshold" to 128),
                block("screen.mappoint","frameVariable" to "qaRegionProcessed","x" to 0,"y" to 0,"xVariable" to "","yVariable" to "","resultVariable" to "qaRegionFlipMap"),
                block("screen.release","frameVariable" to "qaRegion"),block("screen.release","frameVariable" to "qaRegionProcessed"),
                block("task.sleep","milliseconds" to 60_000)),flow.rootBlockId)
            snapshot=store.saveFlows(store.openProject(id),mapOf(flow.flowId to program))
            val tail=JSONObject(program.lineSequence().last(String::isNotBlank)).getString("nodeId")
            val compiled=client.compileVisualProject(id)
            check(compiled is VisualCompileResult.Success) { "QA_REGION_COMPILE:$compiled" }
            val frozen=store.openProject(id)
            val plan=RuntimeProjectPlan.fromVisualSnapshot(frozen,compiled.generationId).forEditorRun(frozen)
            check(runBlocking {withTimeout(25_000) { client.startProject(id,plan.luaSource,plan.resources,plan.capabilities,
                plan.designWidth,plan.designHeight,plan.scaleMode,floatingControl=false,scriptUiJson=plan.scriptUiJson,
                requiresPointerInput=plan.requiresPointerInput,requiredVisionComponents=plan.requiredVisionComponents) }}) { "QA_REGION_START" }
            try {
                runBlocking {withTimeout(20_000) {client.executionPosition.first {it?.projectId==id && it.nodeId==tail}}}
                val proof=client.debugSnapshot(id)
                check(proof.status==RuntimeDebugReply.SUCCESS && proof.sessionGeneration==client.runtimeStates.value.sessionGeneration)
                val values=proof.variables.associate {it.name to it.value}
                check(values["qaRegionA"]==(firstColor and 0xFFFFFF).toString() && values["qaRegionB"]==(secondColor and 0xFFFFFF).toString()) { "QA_REGION_PIXELS" }
                val mapped=JSONObject(requireNotNull(values["qaRegionMap"]))
                val flipped=JSONObject(requireNotNull(values["qaRegionFlipMap"]))
                check(mapped.getInt("x")==a.x && mapped.getInt("y")==a.y) { "QA_REGION_MAPPING" }
                check(flipped.getInt("x")==right-1 && flipped.getInt("y")==top) { "QA_REGION_TRANSFORM_MAPPING" }
            } finally {
                check(client.requestStop()) { "QA_REGION_STOP" }
                runBlocking {withTimeout(10_000) {client.runtimeStates.first {it.engineState in setOf(RuntimeEngineState.STOPPED,RuntimeEngineState.FAILED)}}}
                check(client.runtimeStates.value.engineState==RuntimeEngineState.STOPPED) { "QA_REGION_STOP_FAILED" }
            }
        }
        return store.openProject(id)
    }
}
