package com.autoscript.studio

import com.autoscript.studio.generated.BlockPropertyEditor
import com.autoscript.studio.generated.BlockResourceKind
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

internal data class EditorReplacementChange(val flowId: String, val flowName: String, val nodeId: String,
    val line: Int, val title: String, val path: String, val label: String, val before: JsonElement, val after: JsonElement) {
    val key: String get() = "$flowId:$nodeId:$path"
}
internal data class EditorReplacementPreview(val changes: List<EditorReplacementChange>, val skipped: Int)
internal data class EditorReplacementCandidate(val sources: Map<String, String>, val nodes: Int, val fields: Int)

private val replacementTextFields = setOf("message", "text", "title")

/** Replacements operate on catalog-typed literals, never raw JSON or identity-like strings. */
internal fun editorReplacementPreview(flows: List<EditorSearchFlow>, mode: String, find: String, replacement: String,
    images: Set<String>, flowFilter: String? = null, enabled: String = "all", checkCancelled: () -> Unit = {}): EditorReplacementPreview {
    require(mode in setOf("text", "image") && find.isNotEmpty() && find != replacement) { "原值和新值不能相同，原值也不能为空" }
    require(find.length <= 256 && replacement.length <= 256) { "一次替换的文字最多256字" }
    if (mode == "image") require(find in images && replacement in images) { "请从本项目已声明的图片中选择原图和新图" }
    var skipped = 0
    var bytes = 0L
    var previewBytes = 0L
    val changes = mutableListOf<EditorReplacementChange>()
    val nodes = mutableSetOf<Pair<String, String>>()
    for (flow in flows) {
        checkCancelled()
        if (flowFilter != null && flow.id != flowFilter) continue
        val size = flow.source.toByteArray(Charsets.UTF_8).size
        if (flow.readOnly || size > 1_048_576) { skipped++; continue }
        bytes += size
        require(bytes <= 16L * 1_048_576) { "预览超过16MiB，请缩小到当前插件" }
        val projection = variableCheckNodes(VisualVariableCheckInput(flow.source, flow.root, false))
        if (projection.isEmpty() && flow.source.isNotBlank()) { skipped++; continue }
        for (node in projection) {
            checkCancelled()
            if (enabled != "all" && node.disabled != (enabled == "disabled")) continue
            val args = node.args ?: continue
            val contract = BlockCatalog.find(node.row.kind) ?: continue
            for (field in contract.properties) {
                if ('.' in field.path || field.dependsOn != null) continue
                val previous = args[field.path] ?: continue
                val binding = args["${field.path}Variable"]
                if (binding != null && !binding.isJsonNull && !(binding.isJsonPrimitive && binding.asJsonPrimitive.isString && binding.asString.isBlank())) continue
                val after: JsonElement = when {
                    mode == "text" && ((field.editor == BlockPropertyEditor.STRING && field.path in replacementTextFields) ||
                        (node.row.kind == "variable.set" && field.path == "value" && field.editor == BlockPropertyEditor.SCALAR)) -> {
                        if (!previous.isJsonPrimitive || !previous.asJsonPrimitive.isString || !previous.asString.contains(find)) continue
                        val text = previous.asString
                        var at = 0
                        var count = 0L
                        while (true) {
                            checkCancelled()
                            val match = text.indexOf(find, at)
                            if (match < 0) break
                            count++; at = match + find.length
                        }
                        require(text.length.toLong() + count * (replacement.length - find.length) <= 1_048_576) {
                            "替换后的单项文字超过1Mi字符，请缩小替换范围"
                        }
                        JsonPrimitive(text.replace(find, replacement))
                    }
                    mode == "image" && field.resourceKind == BlockResourceKind.IMAGE && field.editor == BlockPropertyEditor.RESOURCE -> {
                        if (!previous.isJsonPrimitive || !previous.asJsonPrimitive.isString || previous.asString != find) continue
                        JsonPrimitive(replacement)
                    }
                    mode == "image" && field.resourceKind == BlockResourceKind.IMAGE && field.editor == BlockPropertyEditor.RESOURCE_LIST -> {
                        if (!previous.isJsonArray || previous.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString } ||
                            previous.asJsonArray.none { it.asString == find }) continue
                        require(previous.asJsonArray.size() <= 1024) { "单项图片列表超过1024项，请单独调整后重试" }
                        JsonArray().apply { previous.asJsonArray.forEach { add(if (it.asString == find) JsonPrimitive(replacement) else it.deepCopy()) } }
                    }
                    else -> continue
                }
                previewBytes += previous.toString().toByteArray(Charsets.UTF_8).size.toLong() + after.toString().toByteArray(Charsets.UTF_8).size
                require(previewBytes <= 32L * 1_048_576) { "预览新旧参数超过32MiB，请缩小替换范围" }
                changes += EditorReplacementChange(flow.id, flow.name, node.row.nodeId, node.row.sourceLine, contract.title,
                    field.path, field.label, previous.deepCopy(), after)
                nodes += flow.id to node.row.nodeId
                require(changes.size <= 512 && nodes.size <= 256 && changes.map { it.flowId }.distinct().size <= 32) {
                    "匹配超过256行、512项参数或32个插件，请缩小范围；没有应用任何修改"
                }
            }
        }
    }
    return EditorReplacementPreview(changes, skipped)
}

internal fun editorReplacementCandidate(flows: List<EditorSearchFlow>, preview: EditorReplacementPreview,
    selected: Set<String>, checkCancelled: () -> Unit = {}): EditorReplacementCandidate {
    val changes = preview.changes.filter { it.key in selected }
    require(changes.isNotEmpty() && changes.size == selected.size) { "请选择实际需要修改的参数" }
    val targets = changes.groupBy { it.flowId }
    val sources = targets.mapValues { (flowId, fields) ->
        val flow = flows.single { it.id == flowId }
        val byNode = fields.groupBy { it.nodeId }
        val visited = mutableSetOf<String>()
        val source = flow.source.lineSequence().map { line ->
            checkCancelled()
            if (line.isBlank()) line else {
                val node = JsonParser.parseString(line).asJsonObject
                val id = node["nodeId"]?.asString
                val edits = byNode[id]
                if (edits == null) line else {
                    require(visited.add(requireNotNull(id))) { "积木身份重复，未应用" }
                    val args = node.getAsJsonObject("args")
                    edits.forEach {
                        require(args[it.path] == it.before) { "参数已变化，请重新预览" }
                        args.add(it.path, it.after.deepCopy())
                    }
                    node.toString()
                }
            }
        }.joinToString("\n")
        require(visited == byNode.keys && source.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "插件结构已变化或修改后超过1MiB" }
        source
    }
    require(sources.entries.sumOf { (id, source) -> source.toByteArray(Charsets.UTF_8).size.toLong() +
        flows.single { it.id == id }.source.toByteArray(Charsets.UTF_8).size } <= 32L * 1_048_576) { "整批修改超过32MiB，请缩小范围" }
    return EditorReplacementCandidate(sources, changes.map { it.flowId to it.nodeId }.distinct().size, changes.size)
}
