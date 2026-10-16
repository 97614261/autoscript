package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Only use a digest-verified source map; absent entries retain a conservative historical fallback. */
internal fun visualPointerInputRequired(sources: Map<String, String>, sourceMap: JsonObject): Boolean {
    val nodesByFlow = sources.mapValues { (_, source) ->
        source.lineSequence().filter(String::isNotBlank).map { JsonParser.parseString(it).asJsonObject }
            .associateBy { it.requiredInputString("nodeId") }
    }
    val entries = sourceMap.get("entries")
    if (entries != null) {
        require(entries.isJsonArray) { "积木位置记录无效，请重新编译" }
        return entries.asJsonArray.map { entry ->
            require(entry.isJsonObject) { "积木位置记录无效，请重新编译" }
            val position = entry.asJsonObject
            val node = nodesByFlow[position.requiredInputString("flowId")]
                ?.get(position.requiredInputString("nodeId"))
            requireNotNull(node) { "积木位置与项目不匹配，请重新编译" }
            node
        }.any(::needsContinuousPointer)
    }
    // Old source maps did not always expose entries. Do not interpret unknown as no input demand.
    return nodesByFlow.values.any { nodes ->
        nodes.values.any { node -> needsContinuousPointer(node) && !disabledByAncestor(node, nodes) }
    }
}

private fun needsContinuousPointer(node: JsonObject): Boolean = when (node.requiredInputString("kind")) {
    "input.pointerdown", "input.pointermove", "input.pointerup" -> true
    "vision.findimage", "vision.findallimages" -> node.getAsJsonObject("args")?.get("successAction")?.asString in setOf("hold", "pressRelease")
    "vision.resultaction" -> node.getAsJsonObject("args")?.get("action")?.asString in setOf("hold", "pressRelease")
    else -> false
}

private fun disabledByAncestor(node: JsonObject, nodes: Map<String, JsonObject>): Boolean {
    var current = node
    val visited = mutableSetOf<String>()
    while (true) {
        require(visited.add(current.requiredInputString("nodeId")) && visited.size <= 33) {
            "积木层级无效，请重新编译"
        }
        current.get("disabled")?.let { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "积木禁用标记无效，请重新编译" }
            if (value.asBoolean) return true
        }
        val parent = current.get("parentId")?.takeUnless { it.isJsonNull } ?: return false
        require(parent.isJsonPrimitive && parent.asJsonPrimitive.isString) { "积木层级无效，请重新编译" }
        current = requireNotNull(nodes[parent.asString]) { "积木父节点不存在，请重新编译" }
    }
}

private fun JsonObject.requiredInputString(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
        "积木身份无效，请重新编译"
    }
    return value.asString
}
