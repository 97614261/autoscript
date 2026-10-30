package com.autoscript.studio

import android.os.SystemClock
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

/** Each invocation compiles a real owned project and checks a live Rust debug snapshot. */
internal class OwnedUsageRuntime(val client: RuntimeClient, val store: ProjectStore, initial: ProjectSnapshot) {
    val id = initial.manifest.projectId.also { check(it.startsWith("project-qa-")) }
    var snapshot = initial
        private set
    val main get() = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
    fun variables(vararg declarations: Pair<String, ProjectVariableType>) {
        snapshot = store.updateVariables(id, declarations.map { (name, type) ->
            ProjectVariable(name, ProjectVariableScope.GLOBAL, type = type)
        }, snapshot.manifest.variables)
    }
    fun block(kind: String, vararg inputs: Pair<String, Any>): VisualBlockTree {
        val contract = requireNotNull(BlockCatalog.find(kind))
        val args = initialBlockArguments(contract, snapshot.manifest.flows, snapshot.manifest.resources, main.flowId, true) ?: JsonObject()
        inputs.forEach { (key, value) -> when (value) {
            is JsonElement -> args.add(key, value)
            is Number -> args.addProperty(key, value)
            is Boolean -> args.addProperty(key, value)
            else -> args.addProperty(key, value.toString())
        } }
        if (kind in setOf("flow.argument.set", "flow.return.set") && args.get("valueVariable")?.asString?.isNotBlank() == true) args.remove("value")
        return VisualBlockTree(contract, args, contract.childBlocks.associateWith { emptyList() })
    }
    fun set(name: String, expression: String) = block("variable.calculate", "name" to name, "expression" to expression)
    fun run(nodes: List<VisualBlockTree>, during: () -> Unit = {}, completed: Boolean = false, inspect: (Map<String, String>) -> Unit) {
        fun caps(forest: List<VisualBlockTree>): Set<String> = forest.flatMap { node ->
            node.contract.requiredCapabilities + node.children.values.flatMap { caps(it) }
        }.toSet()
        snapshot = store.openProject(id)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + caps(nodes), snapshot.manifest.capabilities.toSet())
        val source = StudioWorkflowAcceptanceFixture.source(nodes + block("task.sleep", "milliseconds" to 60_000), main.rootBlockId)
        snapshot = store.saveFlows(snapshot, mapOf(main.flowId to source))
        val compiled = client.compileVisualProject(id)
        check(compiled is VisualCompileResult.Success) { "QA_USAGE_COMPILE:$compiled" }
        val frozen = store.openProject(id)
        val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
        try {
            check(runBlocking { withTimeout(25_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                requiresPointerInput = plan.requiresPointerInput, requiredVisionComponents = plan.requiredVisionComponents) } }) {
                "QA_USAGE_START:${client.runtimeStates.value.message?.take(800)}"
            }
            during()
            if (completed) {
                runBlocking { withTimeout(15_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.STOPPED } } }
                inspect(emptyMap())
                return
            }
            val tail = JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
            runBlocking { withTimeout(15_000) {
                client.executionPosition.combine(client.runtimeStates) { position, state ->
                    check(state.engineState != RuntimeEngineState.FAILED) { "QA_USAGE_RUNTIME:${state.message?.take(1200)}" }
                    position?.projectId == id && position.nodeId == tail
                }.first { it }
            } }
            val reply = client.debugSnapshot(id)
            check(reply.status == RuntimeDebugReply.SUCCESS && !reply.variablesTruncated && reply.sessionGeneration == client.runtimeStates.value.sessionGeneration)
            inspect(reply.variables.filter { it.scope == "global" }.associate { it.name to it.value })
        } finally { stop() }
    }
    fun stop() {
        if (client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) {
            val proof = client.debugSnapshot(id)
            check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 && proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "QA_USAGE_RUN_CHANGED" }
            check(client.requestStop())
            runBlocking { withTimeout(10_000) { client.runtimeStates.first { it.engineState == RuntimeEngineState.STOPPED } } }
        }
    }
}
