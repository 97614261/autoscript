package com.autoscript.studio

import com.autoscript.project.store.ProjectSnapshot

/** Conservative maintenance warnings; compiler validation remains authoritative. */
internal fun visualVariableWarnings(editor: VisualEditorState, snapshot: ProjectSnapshot, flowId: String): List<String> {
    return visualVariableWarnings(variableCheckNodes(editor.variableCheckInput()), snapshot, flowId)
}

internal fun visualVariableWarnings(nodes: List<VisualVariableCheckNode>, snapshot: ProjectSnapshot, flowId: String,
    checkpoint: () -> Unit = {}): List<String> {
    val declared = snapshot.manifest.variables.filter { it.flowId == null || it.flowId == flowId }.map { it.name }.toSet() +
        snapshot.manifest.flows.firstOrNull { it.flowId == flowId }?.params.orEmpty().mapNotNull { it.get("name")?.asString }
    val warnings = linkedSetOf<String>()
    val writes = setOf("resultVariable", "foundVariable", "xVariable", "yVariable", "indexVariable", "elapsedVariable", "countVariable")
    val assignments = mutableMapOf<String, MutableSet<String>>()
    val initial = snapshot.manifest.variables.filter { (it.flowId == null || it.flowId == flowId) && it.type in setOf(
        com.autoscript.project.store.ProjectVariableType.INTEGER, com.autoscript.project.store.ProjectVariableType.NUMBER,
        com.autoscript.project.store.ProjectVariableType.STRING) }.map { it.name }.toSet() +
        snapshot.manifest.flows.firstOrNull { it.flowId == flowId }?.params.orEmpty().filter { it.get("required")?.asBoolean == true }.mapNotNull { it.get("name")?.asString }
    val rows = nodes.map { it.row }
    val rowsById = rows.associateBy { it.nodeId }
    nodes.forEach { node ->
        checkpoint()
        if (node.disabled || warnings.size >= 128) return@forEach
        val row = node.row
        val args = node.args ?: return@forEach
        val assigned = assignments.getOrPut(row.blockId) {
            val parent = node.parentId
            val parentBlock = rowsById[parent]?.blockId
            (assignments[parentBlock] ?: initial).toMutableSet()
        }
        val nodeWrites = mutableSetOf<String>()
        if (row.kind == "variable.calculate") {
            calculationReferencedNames(args["expression"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()).forEach { name ->
                if (name !in declared && warnings.size < 128) warnings += "${row.nodeId} · $name：计算来源未声明"
                else if (name !in assigned && warnings.size < 128) warnings += "${row.nodeId} · $name：计算前可能未赋值（保守检查）"
            }
        }
        fun visit(json: com.google.gson.JsonElement, depth: Int = 0) {
            if (depth > 32 || warnings.size >= 128) return
            if (json.isJsonObject) json.asJsonObject.entrySet().forEach { (key, value) ->
                if (warnings.size >= 128) return@forEach
                if ((key == "variable" || key.endsWith("Variable") || (key == "name" && row.kind in setOf("variable.set", "variable.calculate", "control.loopmetric")) ||
                        (key in setOf("sourceName", "name") && row.kind == "variable.copy")) && value.isJsonPrimitive && value.asJsonPrimitive.isString
                ) {
                    val name = value.asString
                    val output = key in writes || (key == "name" && row.kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.loopmetric"))
                    if (name.isNotBlank() && name !in declared) warnings += "${row.nodeId} · $name：未维护到变量表（${if (output) "输出" else "引用/赋值"}）"
                    if (output) {
                        if (key !in setOf("xVariable", "yVariable")) nodeWrites += name
                    } else if (name.isNotBlank() && name !in assigned && warnings.size < 128) warnings += "${row.nodeId} · $name：使用前可能未赋值（保守检查，分支/调用需人工确认）"
                } else if (value.isJsonObject || value.isJsonArray) visit(value, depth + 1)
            } else if (json.isJsonArray) json.asJsonArray.forEach { visit(it, depth + 1) }
        }
        visit(args)
        assigned += nodeWrites
    }
    return warnings.toList()
}
