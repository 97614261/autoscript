package com.autoscript.studio

import com.autoscript.project.store.*

internal data class DesignerBindingOption(val key: String, val name: String, val owner: String,
    val scope: String, val type: ProjectVariableType?, val typeLabel: String) {
    val summary: String get() = "$name · $typeLabel · $owner"
}

internal fun designerParameterCompatible(kind: RunnerUiControlKind, type: String) = when (kind.wireName) {
    "number" -> type == "number"
    "integer" -> type in setOf("integer", "number")
    "boolean" -> type == "boolean"
    else -> type == "string"
}

internal fun designerPluginCompatible(kind: RunnerUiControlKind, flow: ProjectFlow) = flow.params.all { parameter ->
    val name = parameter.get("name")?.asString
    val type = parameter.get("type")?.asString.orEmpty()
    when (name) {
        "controlId", "event" -> type == "string"
        "value" -> designerParameterCompatible(kind, type)
        else -> parameter.get("required")?.asBoolean != true
    }
}

/** Match ProjectStore's binding contract, including boolean values written as numeric 0/1. */
internal fun RunnerUiDesignerDraft.bindingOptions(field: RunnerUiFieldDraft, manifest: ProjectManifestDocument): List<DesignerBindingOption> {
    val used = fields.filter { it.id != field.id }.mapNotNull { it.ui.binding }.toSet()
    val variables = manifest.variables.filter { variable -> when (field.kind.wireName) {
        "number" -> variable.type == ProjectVariableType.NUMBER
        "integer", "boolean" -> variable.type in setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
        else -> variable.type == ProjectVariableType.STRING
    } }.map { variable ->
        val global = variable.scope == ProjectVariableScope.GLOBAL
        val owner = if (global) "全局" else manifest.flows.find { it.flowId == variable.flowId }?.displayName() ?: variable.flowId.orEmpty()
        val typeLabel = calculationTypeLabel(variable.type) + if (field.kind.wireName == "boolean") " 0/1" else ""
        DesignerBindingOption(if (global) "global:${variable.name}" else "flow:${variable.flowId}:${variable.name}",
            variable.name, owner, if (global) "全局" else "局部", variable.type, typeLabel)
    }
    val parameters = manifest.flows.find { it.flowId == manifest.entryFlowId }?.params.orEmpty()
        .filter { designerParameterCompatible(field.kind, it.get("type")?.asString.orEmpty()) }.map { parameter ->
            val type = parameter.get("type").asString
            val name = parameter.get("name").asString
            val variableType = when (type) { "integer" -> ProjectVariableType.INTEGER; "number" -> ProjectVariableType.NUMBER; "string" -> ProjectVariableType.STRING; else -> null }
            DesignerBindingOption("param:$name", name, "入口参数", "参数", variableType,
                variableType?.let(::calculationTypeLabel) ?: "布尔")
        }
    return (variables + parameters).filterNot { it.key in used }.distinctBy { it.key }
}
