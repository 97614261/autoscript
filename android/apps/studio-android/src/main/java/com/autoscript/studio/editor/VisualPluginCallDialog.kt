package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectFlow
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal data class PluginCallDraft(
    val targetId: String?,
    val values: Map<String, Map<String, String>>,
) {
    val inputs: Map<String, String> get() = values[targetId].orEmpty()

    fun select(target: ProjectFlow): PluginCallDraft = copy(targetId = target.flowId,
        values = if (target.flowId in values) values else values +
            (target.flowId to flowCallArgumentTexts(target, defaultFlowCallArguments(target))))

    fun update(name: String, value: String): PluginCallDraft = targetId?.let {
        copy(values = values + (it to (inputs + (name to value))))
    } ?: this

    companion object {
        fun create(targets: List<ProjectFlow>, arguments: JsonObject?): PluginCallDraft {
            val originalId = arguments?.get("targetFlowId")?.asString
            val id = originalId ?: targets.firstOrNull()?.flowId
            val target = targets.firstOrNull { it.flowId == id }
            val inputs = target?.let { flowCallArgumentTexts(it,
                if (originalId == it.flowId) arguments?.getAsJsonObject("arguments")
                else defaultFlowCallArguments(it)) }.orEmpty()
            return PluginCallDraft(id, if (id != null) mapOf(id to inputs) else emptyMap())
        }
    }
}

@Composable
internal fun PluginCallFields(targets: List<ProjectFlow>, draft: PluginCallDraft, onChange: (PluginCallDraft) -> Unit) {
    ImageToolChoice("插件", draft.targetId.orEmpty(), targets.mapIndexed { index, target ->
        target.flowId to "${index + 1}. ${target.displayName()}"
    }, targets.isNotEmpty(), dense = true) { id ->
        targets.firstOrNull { it.flowId == id }?.let { onChange(draft.select(it)) }
    }
    if (targets.isEmpty() || targets.none { it.flowId == draft.targetId }) {
        Text(if (targets.isEmpty()) "请先创建另一个插件" else "原目标插件已不存在，请重新选择",
            color = AutoScriptPalette.Danger, fontSize = 11.sp)
    }
    targets.firstOrNull { it.flowId == draft.targetId }?.params.orEmpty().forEach { parameter ->
        val name = parameter.get("name")?.asString.orEmpty()
        val type = parameter.get("type")?.asString.orEmpty()
        val optional = parameter.get("required")?.asBoolean != true
        val label = name + if (optional) " (可选)" else ""
        if (type == "boolean") ImageToolChoice(label, draft.inputs[name].orEmpty(),
            (if (optional) listOf("" to "不传") else emptyList()) + listOf("true" to "true", "false" to "false"),
            true, dense = true) { onChange(draft.update(name, it)) }
        else ImageToolCompactInput(label, draft.inputs[name].orEmpty(), true, Modifier.fillMaxWidth(),
            numeric = type in setOf("integer", "number"), dense = true) { onChange(draft.update(name, it)) }
    }
}

@Composable
internal fun VisualPluginCallDialog(contract: BlockContract, arguments: JsonObject, flows: List<ProjectFlow>,
    currentFlowId: String, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String) {
    val targets = flows.filter { it.flowId != currentFlowId }
    val original = remember(arguments, targets) { PluginCallDraft.create(targets, arguments) }
    var draft by remember(original) { mutableStateOf(original) }
    var error by remember(original) { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    fun close() {
        if (draft.targetId != original.targetId || draft.inputs != original.inputs) discard = true else onDismiss()
    }
    StudioPanelDialog(contract.title, ::close, confirmLabel = confirmLabel, landscapeWidthDp = 440, onConfirm = {
        val target = targets.firstOrNull { it.flowId == draft.targetId }
        if (target == null) error = "请选择目标插件"
        else {
            val (values, problem) = parseFlowCallArguments(target, draft.inputs)
            error = problem
            if (values != null) onConfirm(arguments.deepCopy().apply {
                addProperty("targetFlowId", target.flowId)
                add("arguments", values)
            })
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PluginCallFields(targets, draft) { draft = it; error = null }
            error?.let { Text(it, color = AutoScriptPalette.Danger, fontSize = 11.sp) }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次修改？") },
        actions = listOf(StudioConfirmAction("继续编辑", { discard = false }),
            StudioConfirmAction("放弃修改", onDismiss, destructive = true)))
}
