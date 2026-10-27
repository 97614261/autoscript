package com.autoscript.studio

import java.util.Locale

internal data class EditorSearchFlow(val id: String, val name: String, val source: String, val root: String, val readOnly: Boolean = false)
internal data class EditorProjectSearchHit(val flowId: String, val flowName: String, val nodeId: String, val line: Int,
    val title: String, val kind: String, val arguments: String, val variables: String, val images: String,
    val parameters: List<Pair<String, String>> = emptyList(), val disabled: Boolean = false)
internal data class EditorProjectSearchIndex(val hits: List<EditorProjectSearchHit>, val skipped: Int, val limited: Boolean)

internal fun editorProjectSearchIndex(flows: List<EditorSearchFlow>, checkCancelled: () -> Unit = {}): EditorProjectSearchIndex {
    val hits = mutableListOf<EditorProjectSearchHit>()
    var bytes = 0L
    var skipped = 0
    var limited = false
    for (flow in flows) {
        checkCancelled()
        val size = flow.source.toByteArray(Charsets.UTF_8).size
        if (flow.readOnly || size > 1_048_576) { skipped++; continue }
        if (bytes + size > 16L * 1_048_576 || hits.size >= 20_000) { limited = true; skipped++; continue }
        bytes += size
        val nodes = variableCheckNodes(VisualVariableCheckInput(flow.source, flow.root, false))
        if (nodes.isEmpty() && flow.source.isNotBlank()) { skipped++; continue }
        for (node in nodes) {
            checkCancelled()
            if (hits.size >= 20_000) { limited = true; skipped++; break }
            val args = node.args
            val variables = mutableListOf<String>()
            val images = mutableListOf<String>()
            fun scan(value: com.google.gson.JsonElement, key: String, depth: Int) {
                if (depth > 32) return
                when {
                    value.isJsonObject -> value.asJsonObject.entrySet().forEach { scan(it.value, it.key, depth + 1) }
                    value.isJsonArray -> value.asJsonArray.forEach { scan(it, key, depth + 1) }
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                        val text = value.asString
                        if (key == "variable" || key.endsWith("Variable") ||
                            (node.row.kind.startsWith("variable.") && key in setOf("name", "sourceName"))) variables += text
                        if (text.startsWith("assets/images/")) images += text
                    }
                }
            }
            args?.let { scan(it, "", 0) }
            val contract = BlockCatalog.find(node.row.kind)
            val labels = contract?.properties?.associate { it.path to it.label }.orEmpty()
            val parameters = args?.entrySet()?.map { (path, value) ->
                (labels[path] ?: path) to (if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString())
            }.orEmpty()
            hits += EditorProjectSearchHit(flow.id, flow.name, node.row.nodeId, node.row.sourceLine,
                applyProgramDisplayLabel(compactProgramLabel(BlockCatalog.find(node.row.kind), args, node.row.kind), node.display), node.row.kind,
                args?.toString().orEmpty(), variables.joinToString(" "), images.joinToString(" "), parameters, node.disabled)
        }
    }
    return EditorProjectSearchIndex(hits, skipped, limited)
}

internal fun editorProjectSearch(index: EditorProjectSearchIndex, query: String, scope: String,
    checkCancelled: () -> Unit = {}): List<EditorProjectSearchHit> {
    val terms = query.trim().take(80).lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return emptyList()
    return index.hits.filter { hit ->
        checkCancelled()
        val text = when (scope) {
            "variable" -> hit.variables
            "image" -> hit.images
            "kind" -> "${BlockCatalog.find(hit.kind)?.title.orEmpty()} ${hit.kind}"
            else -> "${hit.flowName} ${hit.title} ${hit.kind} ${hit.arguments} ${hit.parameters.joinToString(" ") { it.first }}"
        }.lowercase(Locale.ROOT)
        terms.all(text::contains)
    }
}

internal fun editorSearchTarget(hit: EditorProjectSearchHit, flows: List<EditorSearchFlow>, editor: VisualEditorState?): String? {
    val flow = flows.firstOrNull { it.id == hit.flowId } ?: return "插件已变化，请重新查找"
    if (editor == null || editor.currentSource != flow.source) return "积木内容已变化，请重新查找，未跳到其他行"
    if (editor.rows.none { it.nodeId == hit.nodeId }) return "这行积木已不存在，请重新查找"
    return null
}
