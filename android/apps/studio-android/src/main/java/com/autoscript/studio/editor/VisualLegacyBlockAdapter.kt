package com.autoscript.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonParser
import com.google.gson.JsonObject

internal fun legacyDockBlockQuery(snippet: String): String {
    val line = snippet.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        line.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:") -> "重复次数"
        line.startsWith(FunctionCatalog.LOOP_HINT_PREFIX) -> "条件循环"
        line.contains("Onnx", ignoreCase = true) -> "ONNX OCR"
        line.contains("findImage", ignoreCase = true) -> "区域找图"
        line.contains("findColor", ignoreCase = true) -> "区域找色"
        line.contains("findText", ignoreCase = true) -> "字库识字"
        line.startsWith("if ") -> "如果"
        line.startsWith("while ") || line.startsWith("repeat") -> "条件循环"
        line.startsWith("for ") -> "重复次数"
        line == "break" -> "跳出循环"
        line == "return" -> "返回上层"
        line.startsWith("::") && line.endsWith("::") -> "放置标记"
        line.startsWith("goto ") -> "跳转标记"
        line.contains("Input.tap", ignoreCase = true) -> "点击"
        line.contains("Input.swipe", ignoreCase = true) -> "滑动"
        line.contains("sleep", ignoreCase = true) -> "等待"
        // 运行提示、短时弹窗和开发日志分开落成对应的积木。
        line.contains("Prompt.show", ignoreCase = true) -> "运行提示"
        line.contains("Prompt.toast", ignoreCase = true) -> "弹出提示"
        line.contains("Log.", ignoreCase = true) -> "输出日志"
        line.startsWith("--") -> "注释"
        line.contains("Capture.", ignoreCase = true) -> "截图"
        line.contains("Runtime.setParameter", ignoreCase = true) -> "设置变量"
        line.contains("Runtime.getParameter", ignoreCase = true) -> "读取变量"
        else -> line.substringBefore('(').substringBefore('\n').trim()
    }
}

/** Shared pre-insert checks for the full and floating visual editors. */
internal fun visualJumpInsertionError(
    kind: String,
    arguments: JsonObject,
    editor: VisualEditorState,
    childSlot: String?,
    position: EditorInsertPosition,
): String? {
    val selected = editor.rows.firstOrNull { it.nodeId == editor.selectedNodeId }
    if (kind in setOf("control.break", "control.continue")) {
        val insideLoop = childSlot == "body" && selected?.kind in setOf("control.repeat", "control.while", "control.cycle") ||
            selected?.depth?.let { it > 0 } == true &&
            editor.nearestAncestorOfKind(kinds = setOf("control.repeat", "control.while", "control.cycle")) != null
        if (position == EditorInsertPosition.LIST_BOTTOM || !insideLoop) return "跳出/结束本次循环只能加入循环体"
    }
    if (kind !in setOf("control.label", "control.goto")) return null
    if (childSlot != null || (position != EditorInsertPosition.LIST_BOTTOM && selected?.depth?.let { it > 0 } == true)) {
        return "标记和跳转只能加入插件的根层级"
    }
    val name = arguments.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    if (!JumpPanelCode.validName(name)) return "标记名无效"
    val matchingLabels = editor.rows.filter { row ->
        row.kind == "control.label" && editor.nodeArguments(row.nodeId)?.get("name")?.asString == name &&
            !(position == EditorInsertPosition.REPLACE && row.nodeId == editor.selectedNodeId)
    }
    if (kind == "control.label" && matchingLabels.isNotEmpty()) return "当前插件已有同名标记：$name"
    if (kind == "control.goto" && matchingLabels.isEmpty()) return "请先在当前插件放置标记：$name"
    return null
}

internal fun legacyDockProgramLabel(
    contract: BlockContract?,
    arguments: JsonObject?,
    fallbackKind: String,
): String {
    return compactProgramLabel(contract, arguments, fallbackKind)
}

/**
 * Migrates the compact legacy editor's Lua-shaped command into the typed
 * arguments owned by the Flow node. Unknown or symbolic values deliberately
 * keep the catalog defaults so a dock action can never create an invalid node.
 */
internal fun legacyDockBlockArguments(
    contract: BlockContract,
    snippet: String,
    defaults: JsonObject,
): JsonObject = defaults.deepCopy().apply {
    if (contract.kind == "control.if") {
        configuredConditionInsertion(snippet)?.takeUnless { it.elseIf }?.let { insertion ->
            keySet().toList().forEach(::remove)
            insertion.arguments.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            return@apply
        }
    }
    if (contract.kind in setOf("task.runprompt", "task.prompt", "task.log", "task.message", "task.comment", "task.sleep",
            "flow.call", "flow.call.branch", "flow.status.return", "flow.argument.set", "flow.argument.get", "flow.return.set", "flow.return.get") &&
        snippet.startsWith(FunctionCatalog.BLOCK_HINT_PREFIX + contract.kind + "\n")) {
        val encoded = snippet.lineSequence().drop(1).firstOrNull().orEmpty()
        val payload = runCatching { com.google.gson.JsonParser.parseString(encoded).asJsonObject }.getOrNull()
        if (payload != null) {
            if (contract.kind in setOf("flow.argument.set", "flow.return.set")) {
                if (payload.has("valueVariable")) remove("value") else remove("valueVariable")
            }
            payload.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            return@apply
        }
    }
    loopInsertionFromHint(snippet)?.takeIf { it.kind == contract.kind }?.let { insertion ->
        keySet().toList().forEach(::remove)
        insertion.arguments.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
        return@apply
    }
    val callArguments = legacyLuaCallArguments(snippet)
    fun integer(index: Int): Long? = callArguments.getOrNull(index)?.trim()?.toLongOrNull()
    fun decimal(index: Int): Double? = callArguments.getOrNull(index)?.trim()?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    fun quoted(index: Int): String? = callArguments.getOrNull(index)?.trim()?.let(::legacyLuaString)

    when (contract.kind) {
        "variable.calculate" -> variableCalculationArguments(snippet)?.let {
            addProperty("name", it["name"].asString)
            addProperty("expression", it["expression"].asString)
        }
        "flow.argument.set", "flow.return.set" -> {
            val parts = snippet.lineSequence().drop(1).firstOrNull().orEmpty().split('|', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { addProperty("index", it) }
            val source = parts.getOrNull(1).orEmpty()
            if (source.startsWith("variable:")) {
                remove("value")
                addProperty("valueVariable", source.removePrefix("variable:"))
            } else if (source.startsWith("value:")) {
                remove("valueVariable")
                add("value", parseScalar(source.removePrefix("value:"))
                    ?: com.google.gson.JsonPrimitive(source.removePrefix("value:")))
            }
        }
        "flow.argument.get", "flow.return.get" -> {
            val parts = snippet.lineSequence().drop(1).firstOrNull().orEmpty().split('|', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { addProperty("index", it) }
            parts.getOrNull(1)?.let { addProperty("targetVariable", it) }
        }
        "input.tap" -> {
            integer(0)?.let { addProperty("x", it) }
            integer(1)?.let { addProperty("y", it) }
        }
        "input.swipe" -> {
            integer(0)?.let { addProperty("x1", it) }
            integer(1)?.let { addProperty("y1", it) }
            integer(2)?.let { addProperty("x2", it) }
            integer(3)?.let { addProperty("y2", it) }
            integer(4)?.takeIf { it in 1..5_000 }?.let { addProperty("durationMs", it) }
        }
        "task.sleep" -> {
            val marker = snippet.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
            if (marker.startsWith("variable:")) {
                addProperty("milliseconds", 0)
                addProperty("millisecondsVariable", marker.removePrefix("variable:"))
            } else {
                remove("millisecondsVariable")
                integer(0)?.takeIf { it >= 0 }?.let { addProperty("milliseconds", it) }
            }
        }
        "control.label" -> snippet.trim().removeSurrounding("::")
            .takeIf { it.matches(com.autoscript.project.store.ProjectNames.pattern) }
            ?.let { addProperty("name", it) }
        "control.goto" -> snippet.trim().removePrefix("goto ")
            .takeIf { it.matches(com.autoscript.project.store.ProjectNames.pattern) }
            ?.let { addProperty("name", it) }
        "task.log" -> {
            Regex("Log\\.(info|warn|error)", RegexOption.IGNORE_CASE)
                .find(snippet)?.groupValues?.getOrNull(1)?.lowercase()?.let { addProperty("level", it) }
            val variable = Regex("__vars\\s*\\[\\s*[\\\"']([${com.autoscript.project.store.ProjectNames.START}][${com.autoscript.project.store.ProjectNames.PART}]{0,63})[\\\"']\\s*]")
                .find(snippet)?.groupValues?.getOrNull(1)
            if (variable != null) {
                addProperty("valueVariable", variable)
                addProperty("message", variable)
            } else {
                quoted(0)?.let { addProperty("message", it) }
            }
        }
        "task.prompt", "task.runprompt" -> {
            val variable = Regex("__vars\\s*\\[\\s*[\\\"']([${com.autoscript.project.store.ProjectNames.START}][${com.autoscript.project.store.ProjectNames.PART}]{0,63})[\\\"']\\s*]")
                .find(snippet)?.groupValues?.getOrNull(1)
            if (variable != null) {
                addProperty("valueVariable", variable)
                addProperty("message", variable)
            } else {
                quoted(0)?.let { addProperty("message", it) }
            }
        }
        "task.comment" -> addProperty("message", snippet.trim().removePrefix("--").trim().take(2048))
        "control.repeat" -> {
            val marker = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
            if (marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:")) {
                val value = marker.removePrefix(FunctionCatalog.LOOP_HINT_PREFIX + "repeat:")
                when {
                    value.startsWith("fixed:") -> value.removePrefix("fixed:").toLongOrNull()
                        ?.takeIf { it in 0..1_000_000 }?.let { addProperty("times", it) }
                    value.startsWith("variable:") -> value.removePrefix("variable:")
                        .takeIf { it.matches(com.autoscript.project.store.ProjectNames.pattern) }
                        ?.let { addProperty("times", 0); addProperty("timesVariable", it) }
                }
                return@apply
            }
            Regex("for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*1\\s*,\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(snippet)?.let { match ->
                    match.groupValues[2].toLongOrNull()?.takeIf { it > 0 }?.let { addProperty("times", it) }
                    addProperty("indexVariable", match.groupValues[1])
                }
        }
        "control.if", "control.while" -> {
            val marker = snippet.lineSequence().firstOrNull()?.trim().orEmpty()
            if (contract.kind == "control.while" && marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX)) {
                addProperty("always", true)
                addProperty("variable", "loopEnabled")
                addProperty("operator", "equals")
                addProperty("value", true)
                when {
                    marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "timed:fixed:") -> marker
                        .substringAfterLast(':').toLongOrNull()?.takeIf { it in 1..86_400_000 }
                        ?.let { addProperty("durationMs", it) }
                    marker.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "timed:variable:") -> marker
                        .substringAfterLast(':').takeIf { it.matches(com.autoscript.project.store.ProjectNames.pattern) }
                        ?.let { addProperty("durationVariable", it) }
                }
                return@apply
            }
            Regex("(?:if|while)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(==|~=|!=|<=|>=|<|>)\\s*(.+?)\\s+(?:then|do)", RegexOption.IGNORE_CASE)
                .find(snippet)?.let { match ->
                    addProperty("variable", match.groupValues[1])
                    addProperty("operator", legacyComparisonOperator(match.groupValues[2]))
                    add("value", parseScalar(match.groupValues[3].trim())
                        ?: com.google.gson.JsonPrimitive(match.groupValues[3].trim()))
                }
            Regex("--\\s*maxIterations=(\\d+)").find(snippet)?.groupValues?.get(1)
                ?.toLongOrNull()?.takeIf { it > 0 }?.let { addProperty("maxIterations", it) }
        }
        "vision.findimage" -> {
            decimal(1)?.takeIf { it in 0.0..1.0 }
                ?.let { addProperty("similarityPermille", (it * 1_000).toInt()) }
            // Only replace a catalog-selected resource when the command names
            // that same project asset. Arbitrary picker examples must not leak
            // invalid paths into the persisted Flow.
            quoted(0)?.substringAfterLast('/')?.let { requestedName ->
                get("imagePath")?.asString?.takeIf { it.substringAfterLast('/') == requestedName }
                    ?.let { addProperty("imagePath", it) }
            }
        }
        "vision.findcolor" -> quoted(0)?.let(::parseColor)?.let { addProperty("rgb", it) }
        "ocr.glyph" -> {
            decimal(1)?.takeIf { it in 0.0..1.0 }
                ?.let { addProperty("similarityPermille", (it * 1_000).toInt()) }
        }
    }
}

private fun legacyLuaCallArguments(snippet: String): List<String> {
    val body = snippet.substringAfter('(', "").substringBeforeLast(')', "")
    if (body.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    body.forEach { character ->
        when {
            escaped -> { current.append(character); escaped = false }
            character == '\\' && quote != null -> { current.append(character); escaped = true }
            quote != null && character == quote -> { current.append(character); quote = null }
            quote != null -> current.append(character)
            character == '\'' || character == '"' -> { current.append(character); quote = character }
            character == ',' -> { result += current.toString().trim(); current.clear() }
            else -> current.append(character)
        }
    }
    result += current.toString().trim()
    return result
}

private fun legacyLuaString(value: String): String? {
    if (value.length < 2 || value.first() !in charArrayOf('\'', '"') || value.last() != value.first()) return null
    return value.substring(1, value.lastIndex)
        .replace("\\${value.first()}", value.first().toString())
        .replace("\\\\", "\\")
}

private fun legacyComparisonOperator(value: String): String = when (value) {
    "==" -> "equals"
    "!=", "~=" -> "notEquals"
    "<" -> "lessThan"
    "<=" -> "lessOrEqual"
    ">" -> "greaterThan"
    ">=" -> "greaterOrEqual"
    else -> "equals"
}

internal fun com.google.gson.JsonObject.stringOr(name: String, fallback: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
