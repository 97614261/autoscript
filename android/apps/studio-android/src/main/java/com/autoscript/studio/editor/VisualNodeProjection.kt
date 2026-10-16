package com.autoscript.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser

internal data class VisualNodeRow(
    val nodeId: String,
    val blockId: String,
    val orderKey: String,
    val kind: String,
    val depth: Int,
    val childSlot: String?,
    val sourceLine: Int,
)

internal fun parseVisualNodes(source: String, rootBlockId: String): List<VisualNodeRow> {
    val drafts = source.lineSequence().filter(String::isNotBlank).mapIndexed { index, line ->
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrElse {
            return@mapIndexed VisualNodeDraft(
                nodeId = "invalid-line-${index + 1}",
                blockId = "?",
                orderKey = "?",
                kind = "损坏的JSON行 ${index + 1}",
                childBlocks = emptyList(),
                sourceLine = index + 1,
            )
        }
        val childBlocks = runCatching {
            json.getAsJsonObject("childBlocks")?.entrySet().orEmpty()
                .mapNotNull { (name, value) ->
                    value.takeIf { it.isJsonPrimitive }?.asString?.let { name to it }
                }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
        VisualNodeDraft(
            nodeId = json.stringOr("nodeId", "missing-node-${index + 1}"),
            blockId = json.stringOr("blockId", "?"),
            orderKey = json.stringOr("orderKey", "?"),
            kind = json.stringOr("kind", "未知节点"),
            childBlocks = childBlocks,
            sourceLine = index + 1,
        )
    }.toList()
    val members = drafts.withIndex().groupBy { it.value.blockId }
    val childSlots = drafts.flatMap { owner ->
        owner.childBlocks.map { (name, blockId) -> blockId to name }
    }.toMap()
    val visited = mutableSetOf<Int>()
    val projected = mutableListOf<VisualNodeRow>()
    fun visitBlock(blockId: String, depth: Int) {
        members[blockId].orEmpty().sortedBy { it.value.orderKey }.forEach { indexed ->
            if (!visited.add(indexed.index)) return@forEach
            val node = indexed.value
            projected += node.toRow(depth, childSlots[node.blockId])
            node.childBlocks.forEach { (_, childBlockId) ->
                visitBlock(childBlockId, depth + 1)
            }
        }
    }
    visitBlock(rootBlockId, 0)
    drafts.forEachIndexed { index, node ->
        if (visited.add(index)) projected += node.toRow(0, childSlots[node.blockId])
    }
    return projected
}

private data class VisualNodeDraft(
    val nodeId: String,
    val blockId: String,
    val orderKey: String,
    val kind: String,
    val childBlocks: List<Pair<String, String>>,
    val sourceLine: Int,
)

private fun VisualNodeDraft.toRow(depth: Int, childSlot: String?) = VisualNodeRow(
    nodeId = nodeId,
    blockId = blockId,
    orderKey = orderKey,
    kind = kind,
    depth = depth,
    childSlot = childSlot,
    sourceLine = sourceLine,
)

internal fun childBlockLabel(name: String): String = when (name) {
    "then" -> "满足"
    "else" -> "否则"
    "body" -> "循环体"
    else -> if (name.startsWith("elseIf")) {
        "否则如果 ${name.removePrefix("elseIf").toIntOrNull()?.plus(1) ?: ""}"
    } else name
}
