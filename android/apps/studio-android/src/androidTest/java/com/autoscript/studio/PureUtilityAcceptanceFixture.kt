package com.autoscript.studio

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Nine real parameter dialogs and pure Rust results in an owned disposable project. */
internal object PureUtilityAcceptanceFixture {
    fun run(test:Instrumentation,activity:ComponentActivity,client:RuntimeClient,store:ProjectStore,initial:ProjectSnapshot,
        record:(String,()->Unit)->Unit):ProjectSnapshot {
        val id=initial.manifest.projectId;check(id.startsWith("project-qa-"))
        var snapshot=store.updateCapabilities(id,setOf("core.task","core.math"),initial.manifest.capabilities.toSet())
        val kinds=listOf("math.unary","math.binary","math.roundto","text.formatnumber","text.regexreplace","text.filter","text.reverse","data.encode","data.sort")
        val vars=kinds.mapIndexed {index,kind->ProjectVariable("out$index",ProjectVariableScope.GLOBAL,type=utilityResultType(kind))}
        snapshot=store.updateVariables(id,vars,snapshot.manifest.variables)
        val flow=snapshot.manifest.flows.single()
        var current by mutableStateOf<String?>(null)
        var arguments by mutableStateOf(JsonObject())
        val accepted=AtomicReference<JsonObject?>(null)
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            fun walk(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(n==null || !n.refresh()) return null
                if(predicate(n)) return n
                for(index in 0 until n.childCount) walk(n.getChild(index))?.let {return it}
                return null
            };return walk(test.uiAutomation.rootInActiveWindow)
        }
        fun waitFor(predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+12_000;while(!predicate() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(60);check(predicate()) {"PURE_UTILITY_UI_TIMEOUT"}}
        fun exists(label:String)=node {it.text?.toString()==label || it.contentDescription?.toString()==label}!=null
        fun click(label:String) {
            var target:AccessibilityNodeInfo?=null
            waitFor {var n=node {it.text?.toString()==label || it.contentDescription?.toString()==label};while(n!=null && !n.isClickable) n=n.parent;target=n;n?.isEnabled==true}
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {"PURE_UTILITY_CLICK"}
        }
        test.runOnMainSync {activity.setContent {AutoScriptTheme {current?.let {kind->
            VisualUtilityArguments(requireNotNull(BlockCatalog.find(kind)),arguments,snapshot,flow.flowId,null,
                onDismiss={current=null},onConfirm={accepted.set(it);current=null},confirmLabel="QA加入")
        }}}}
        val forest=mutableListOf<VisualBlockTree>()
        try {
            kinds.forEachIndexed {index,kind->
                record("pure-utility-dialog-$kind") {
                    val contract=requireNotNull(BlockCatalog.find(kind))
                    val inputs=blockPropertyTexts(contract,JsonObject())+("resultVariable" to "out$index")
                    val args=requireNotNull(parseBlockArguments(contract,inputs,null,emptyMap(),emptyList()).first)
                    accepted.set(null)
                    test.runOnMainSync {arguments=args;current=kind}
                    waitFor {exists(contract.title) && exists("QA加入")}
                    click("QA加入");waitFor {accepted.get()!=null && !exists("QA加入")}
                    check(accepted.get()==args) {"PURE_UTILITY_ARGUMENT_DRIFT"}
                    forest.add(VisualBlockTree(contract,args))
                }
            }
            record("pure-utility-source-roundtrip-and-backup") {
                val source=StudioWorkflowAcceptanceFixture.source(forest,flow.rootBlockId)
                val exported=pluginSourceToolchain.export(source,flow.rootBlockId)
                val imported=pluginSourceToolchain.parse(exported.text,source,flow.rootBlockId,vars.map {it.name}.toSet())
                check(source.lineSequence().filter(String::isNotBlank).map {JsonParser.parseString(it)}.toList()==
                    imported.text.lineSequence().filter(String::isNotBlank).map {JsonParser.parseString(it)}.toList()) {"PURE_UTILITY_ROUNDTRIP"}
                snapshot=store.saveFlows(snapshot,mapOf(flow.flowId to imported.text))
                val backup=java.io.ByteArrayOutputStream();store.exportProjectBackup(id,backup)
                val restored=store.importProjectBackup(backup.toByteArray().inputStream())
                try {check(restored.flowSources==snapshot.flowSources)} finally {store.deleteProject(restored.manifest.projectId)}
            }
            record("pure-utility-real-compiled-rust-results") {
                val tail=VisualBlockTree(requireNotNull(BlockCatalog.find("task.sleep")),JsonObject().apply {addProperty("milliseconds",60_000)})
                val source=StudioWorkflowAcceptanceFixture.source(forest+tail,flow.rootBlockId)
                snapshot=store.saveFlows(store.openProject(id),mapOf(flow.flowId to source))
                val compiled=client.compileVisualProject(id);check(compiled is VisualCompileResult.Success) {"PURE_UTILITY_COMPILE"}
                val frozen=store.openProject(id)
                val plan=RuntimeProjectPlan.fromVisualSnapshot(frozen,compiled.generationId).forEditorRun(frozen)
                check(runBlocking {withTimeout(30_000) {client.startProject(id,plan.luaSource,plan.resources,plan.capabilities,
                    plan.designWidth,plan.designHeight,plan.scaleMode,floatingControl=false,scriptUiJson=plan.scriptUiJson,
                    requiresPointerInput=plan.requiresPointerInput,requiredVisionComponents=plan.requiredVisionComponents)}}) {"PURE_UTILITY_START"}
                val tailId=JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
                runBlocking {withTimeout(15_000) {client.executionPosition.first {it?.projectId==id && it.nodeId==tailId}}}
                val reply=client.debugSnapshot(id)
                check(reply.status==RuntimeDebugReply.SUCCESS && reply.sessionGeneration==client.runtimeStates.value.sessionGeneration) {"PURE_UTILITY_DEBUG"}
                val values=reply.variables.associate {it.name to it.value}
                check(values["out0"]?.toDoubleOrNull()==3.0 && values["out1"]?.toDoubleOrNull()==2.0 && values["out2"]?.toDoubleOrNull()==3.14) {"PURE_UTILITY_MATH_RESULT"}
                check(values["out3"]=="0012.50" && values["out4"]=="a#b#" && values["out5"]=="12" && values["out6"]=="321构智") {"PURE_UTILITY_TEXT_RESULT"}
                check(values["out7"]=="\"智构\"" && values["out8"]=="[1,2,3]") {"PURE_UTILITY_DATA_RESULT"}
            }
        } finally {
            test.runOnMainSync {current=null}
            if(client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING,RuntimeEngineState.PAUSED,RuntimeEngineState.STOPPING)) {
                val proof=client.debugSnapshot(id)
                check(proof.status==RuntimeDebugReply.SUCCESS && proof.runRevision>0 && proof.sessionGeneration==client.runtimeStates.value.sessionGeneration) {"PURE_UTILITY_RUN_CHANGED"}
                check(client.requestStop()) {"PURE_UTILITY_STOP"}
                runBlocking {withTimeout(10_000) {client.runtimeStates.first {it.engineState==RuntimeEngineState.STOPPED}}}
            }
        }
        return snapshot
    }
}
