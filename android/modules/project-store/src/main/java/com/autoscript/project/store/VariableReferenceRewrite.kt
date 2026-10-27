package com.autoscript.project.store

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Explicit origin is essential: declarations have no persistent ID, so never guess a rename. */
data class ProjectVariableRename(val original: ProjectVariable, val newName: String)

internal fun variableRenameMap(
    manifest: ProjectManifestDocument,
    flow: ProjectFlow,
    renames: List<ProjectVariableRename>,
): Map<String, String> {
    val parameters = flow.params.mapNotNull { it.get("name")?.asString }.toSet()
    val locals = manifest.variables.filter { it.scope == ProjectVariableScope.FLOW && it.flowId == flow.flowId }.map { it.name }.toSet()
    return renames.filter {
        if (it.original.scope == ProjectVariableScope.FLOW) it.original.flowId == flow.flowId && it.original.name !in parameters
        else it.original.name !in parameters && it.original.name !in locals
    }.associate { rename ->
        require(rename.newName !in parameters && (rename.original.scope == ProjectVariableScope.FLOW || rename.newName !in locals)) {
            "改名会被插件 ${flow.flowId} 的同名局部变量或参数遮蔽：${rename.newName}"
        }
        // A local rename could also capture pre-existing references to a global variable.
        require(rename.original.scope != ProjectVariableScope.FLOW || manifest.variables.none {
            it.scope == ProjectVariableScope.GLOBAL && it.name == rename.newName
        }) { "局部改名会遮蔽全局变量：${rename.newName}" }
        rename.original.name to rename.newName
    }
}

/** Token lexer preserves JSON strings and function names; never a whole-text replacement. */
internal fun rewriteCalculationNames(source: String, names: Map<String, String>): String {
    require(source.toByteArray(Charsets.UTF_8).size <= 1024) { "计算表达式超过预算" }
    val result = StringBuilder()
    var i = 0
    while (i < source.length) {
        val ch = source[i]
        if (ch == '"') {
            val start = i++
            var closed = false
            while (i < source.length) {
                if (source[i] == '\\') { i += 2; continue }
                if (source[i++] == '"') { closed = true; break }
            }
            require(closed && i <= source.length) { "计算表达式包含未闭合字符串" }
            result.append(source, start, i)
        } else if (ProjectNames.isStart(ch)) {
            val start = i++
            while (i < source.length && ProjectNames.isPart(source[i])) i++
            val name = source.substring(start, i)
            var next = i
            while (next < source.length && source[next].isWhitespace()) next++
            result.append(if (next < source.length && source[next] == '(') name else names[name] ?: name)
        } else { result.append(ch); i++ }
    }
    require(result.toString().toByteArray(Charsets.UTF_8).size <= 1024) { "改名后表达式超过预算" }
    return result.toString()
}

internal fun rewriteFlowVariableReferences(source: String, names: Map<String, String>): String {
    if (names.isEmpty()) return source
    var nodes = 0
    return source.lineSequence().joinToString("\n") { line ->
        if (line.isBlank()) line else {
            require(++nodes <= 100_000) { "变量改名超过节点预算" }
            val node = JsonParser.parseString(line).asJsonObject
            val kind = node.get("kind")?.asString.orEmpty()
            var changed = false
            fun visit(value: JsonElement, depth: Int) {
                require(depth <= 32) { "变量引用嵌套超过预算" }
                when {
                    value.isJsonObject -> {
                        val obj = value.asJsonObject
                        obj.entrySet().toList().forEach { (key, child) ->
                            val reference = key == "variable" || key.endsWith("Variable") ||
                                (key == "name" && kind in setOf("variable.set", "variable.copy", "variable.calculate", "control.loopmetric")) ||
                                (key == "sourceName" && kind == "variable.copy")
                            if (reference && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                                names[child.asString]?.let { obj.addProperty(key, it); changed = true }
                            } else if (key == "expression" && kind in setOf("variable.calculate", "task.message") && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                                val rewritten = rewriteCalculationNames(child.asString, names)
                                if (rewritten != child.asString) { obj.addProperty(key, rewritten); changed = true }
                            } else if (child.isJsonObject || child.isJsonArray) visit(child, depth + 1)
                        }
                    }
                    value.isJsonArray -> value.asJsonArray.forEach { visit(it, depth + 1) }
                }
            }
            node.get("args")?.let { visit(it, 0) }
            if (changed) node.toString() else line
        }
    }
}

internal fun rewriteVariableBindings(ui: JsonObject?, renames: List<ProjectVariableRename>): JsonObject? = ui?.deepCopy()?.apply {
    val bindings = renames.associate { rename ->
        val prefix = if (rename.original.scope == ProjectVariableScope.GLOBAL) "global:" else "flow:${rename.original.flowId}:"
        prefix + rename.original.name to prefix + rename.newName
    }
    fun visit(value: JsonElement, depth: Int) {
        require(depth <= 32) { "界面定义嵌套超过预算" }
        if (value.isJsonObject) {
            value.asJsonObject.entrySet().toList().forEach { (key, child) ->
                if (key == "binding" && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                    bindings[child.asString]?.let { value.asJsonObject.addProperty(key, it) }
                } else if (child.isJsonObject || child.isJsonArray) visit(child, depth + 1)
            }
        } else if (value.isJsonArray) value.asJsonArray.forEach { visit(it, depth + 1) }
    }
    visit(this, 0)
}
