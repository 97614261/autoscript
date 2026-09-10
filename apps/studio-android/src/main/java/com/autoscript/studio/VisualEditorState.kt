package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

internal class VisualEditorState private constructor(
    initialSource: String,
    migratedSource: String,
    private val rootBlockId: String,
    private val idFactory: () -> String,
    forceReadOnly: Boolean,
) {
    var savedSource: String = initialSource
        private set
    var currentSource: String = migratedSource
        private set
    var selectedNodeId: String? = null
    val isReadOnly: Boolean = forceReadOnly || parseDocument(migratedSource, rootBlockId) == null
    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()

    val isDirty: Boolean get() = currentSource != savedSource
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val rows: List<VisualNodeRow>
        get() = parseDocument(currentSource, rootBlockId)?.rows
            ?: parseVisualNodes(currentSource, rootBlockId)

    fun insertNoop(afterNodeId: String? = selectedNodeId): String? = insertNode(
        kind = "task.noop",
        nodeVersion = 1,
        args = JsonObject(),
        childBlockNames = emptyList(),
        afterNodeId = afterNodeId,
    )

    fun insertBlock(
        contract: BlockContract,
        args: JsonObject,
        afterNodeId: String? = selectedNodeId,
        intoChildBlockName: String? = null,
    ): String? = insertNode(
        kind = contract.kind,
        nodeVersion = contract.nodeVersion,
        args = args,
        childBlockNames = contract.childBlocks,
        afterNodeId = afterNodeId,
        intoChildBlockName = intoChildBlockName,
    )

    fun insertFlowCall(
        targetFlowId: String,
        arguments: JsonObject = JsonObject(),
        afterNodeId: String? = selectedNodeId,
    ): String? {
        val args = JsonObject().apply {
            addProperty("targetFlowId", targetFlowId)
            add("arguments", arguments.deepCopy())
        }
        return insertNode("flow.call", 1, args, emptyList(), afterNodeId)
    }

    fun deleteSelected(): Boolean {
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val root = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        val ownedBlocks = mutableSetOf<String>()
        val removedNodes = mutableSetOf(root.nodeId)
        fun collect(node: EditableNode) {
            node.childBlocks.values.forEach { blockId ->
                if (!ownedBlocks.add(blockId)) return@forEach
                document.nodes.filter { it.blockId == blockId }.forEach { child ->
                    if (removedNodes.add(child.nodeId)) collect(child)
                }
            }
        }
        collect(root)
        document.nodes.removeAll { it.nodeId in removedNodes }
        selectedNodeId = null
        return commit(document)
    }

    fun moveSelected(offset: Int): Boolean {
        require(offset == -1 || offset == 1)
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        val siblings = document.nodes.filter { it.blockId == node.blockId }.sortedBy { it.orderKey }
        val from = siblings.indexOfFirst { it.nodeId == selected }
        val to = from + offset
        if (from < 0 || to !in siblings.indices) return false
        val reordered = siblings.toMutableList().apply {
            val moving = removeAt(from)
            add(to, moving)
        }
        rebalance(reordered)
        return commit(document)
    }

    fun updateArguments(nodeId: String, arguments: JsonObject): Boolean {
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == nodeId } ?: return false
        node.json.add("args", arguments.deepCopy())
        return commit(document)
    }

    fun nodeArguments(nodeId: String): JsonObject? = editableDocument()?.nodes
        ?.firstOrNull { it.nodeId == nodeId }
        ?.json?.getAsJsonObject("args")?.deepCopy()

    fun flowCallTarget(nodeId: String): String? = editableDocument()?.nodes
        ?.firstOrNull { it.nodeId == nodeId && it.kind == "flow.call" }
        ?.json?.getAsJsonObject("args")?.get("targetFlowId")
        ?.takeIf { it.isJsonPrimitive }?.asString

    fun flowCallArguments(nodeId: String): JsonObject? = editableDocument()?.nodes
        ?.firstOrNull { it.nodeId == nodeId && it.kind == "flow.call" }
        ?.json?.getAsJsonObject("args")?.getAsJsonObject("arguments")?.deepCopy()

    fun updateFlowCall(nodeId: String, targetFlowId: String, arguments: JsonObject): Boolean =
        updateArguments(
            nodeId,
            JsonObject().apply {
                addProperty("targetFlowId", targetFlowId)
                add("arguments", arguments.deepCopy())
            },
        )

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack += currentSource
        currentSource = undoStack.removeAt(undoStack.lastIndex)
        if (selectedNodeId !in rows.map(VisualNodeRow::nodeId)) selectedNodeId = null
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack += currentSource
        currentSource = redoStack.removeAt(redoStack.lastIndex)
        if (selectedNodeId !in rows.map(VisualNodeRow::nodeId)) selectedNodeId = null
        return true
    }

    /** Marks exactly the submitted snapshot saved; later edits remain dirty. */
    fun markSaved(submittedSource: String) {
        savedSource = submittedSource
    }

    private fun insertNode(
        kind: String,
        nodeVersion: Int,
        args: JsonObject,
        childBlockNames: List<String>,
        afterNodeId: String?,
        intoChildBlockName: String? = null,
    ): String? {
        val document = editableDocument() ?: return null
        val selected = afterNodeId?.let { id -> document.nodes.firstOrNull { it.nodeId == id } }
        val childBlockId = intoChildBlockName?.let { name -> selected?.childBlocks?.get(name) }
            ?: if (intoChildBlockName == null) null else return null
        val after = if (childBlockId == null) selected else null
        val blockId = childBlockId ?: after?.blockId ?: rootBlockId
        val parentId = if (childBlockId != null) selected?.nodeId else after?.parentId
        val siblings = document.nodes.filter { it.blockId == blockId }.sortedBy { it.orderKey }
        val insertionIndex = after?.let { selected ->
            siblings.indexOfFirst { it.nodeId == selected.nodeId }.takeIf { it >= 0 }?.plus(1)
        } ?: siblings.size
        var previous = siblings.getOrNull(insertionIndex - 1)?.orderKey
        var next = siblings.getOrNull(insertionIndex)?.orderKey
        var key = between(previous, next)
        if (key == null) {
            rebalance(siblings)
            previous = siblings.getOrNull(insertionIndex - 1)?.orderKey
            next = siblings.getOrNull(insertionIndex)?.orderKey
            key = requireNotNull(between(previous, next))
        }
        val nodeId = uniqueId("node", document.nodes.mapTo(mutableSetOf()) { it.nodeId })
        val blockIds = document.nodes.flatMapTo(mutableSetOf()) { node ->
            listOf(node.blockId) + node.childBlocks.values
        }.apply { add(rootBlockId) }
        val childBlocks = childBlockNames.associateWith {
            uniqueId("block", blockIds).also(blockIds::add)
        }
        val json = JsonObject().apply {
            addProperty("flowSchemaVersion", 1)
            addProperty("nodeId", nodeId)
            addProperty("blockId", blockId)
            add("parentId", parentId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            addProperty("orderKey", key)
            addProperty("kind", kind)
            addProperty("nodeVersion", nodeVersion)
            if (childBlocks.isNotEmpty()) {
                add("childBlocks", JsonObject().apply {
                    childBlocks.forEach(::addProperty)
                })
            }
            addProperty("depth", 0)
            add("args", args.deepCopy())
        }
        document.nodes += EditableNode(json)
        if (!commit(document)) return null
        selectedNodeId = nodeId
        return nodeId
    }

    private fun uniqueId(prefix: String, existing: Set<String>): String {
        repeat(32) {
            val candidate = "$prefix-${idFactory()}"
            if (candidate !in existing) return candidate
        }
        error("无法生成唯一节点 ID")
    }

    private fun editableDocument(): EditableDocument? =
        if (isReadOnly) null else parseDocument(currentSource, rootBlockId)?.editable

    private fun commit(document: EditableDocument): Boolean {
        val next = document.serialize(rootBlockId) ?: return false
        if (next == currentSource) return false
        undoStack += currentSource
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        currentSource = next
        return true
    }

    companion object {
        fun create(
            source: String,
            rootBlockId: String,
            idFactory: () -> String = { UUID.randomUUID().toString() },
        ): VisualEditorState = when (val migration = BlockMigrationEngine.migrateFlowSource(source)) {
            is FlowMigrationResult.Success -> VisualEditorState(
                source,
                migration.source,
                rootBlockId,
                idFactory,
                forceReadOnly = false,
            )
            is FlowMigrationResult.Failure -> VisualEditorState(
                source,
                source,
                rootBlockId,
                idFactory,
                forceReadOnly = true,
            )
        }

        private const val MAX_HISTORY = 100
    }
}

private class EditableNode(val json: JsonObject) {
    val nodeId: String get() = json.requiredString("nodeId")
    val blockId: String get() = json.requiredString("blockId")
    val parentId: String? get() = json.get("parentId")?.takeUnless { it.isJsonNull }?.asString
    var orderKey: String
        get() = json.requiredString("orderKey")
        set(value) { json.addProperty("orderKey", value) }
    val kind: String get() = json.requiredString("kind")
    val childBlocks: Map<String, String>
        get() = json.getAsJsonObject("childBlocks")?.entrySet().orEmpty()
            .associate { (name, value) -> name to value.asString }
}

private class EditableDocument(val nodes: MutableList<EditableNode>) {
    fun serialize(rootBlockId: String): String? {
        val projection = project(nodes, rootBlockId) ?: return null
        return buildString {
            projection.forEach { (node, depth) ->
                node.json.addProperty("depth", depth)
                append(node.json).append('\n')
            }
        }
    }
}

private data class ParsedDocument(
    val editable: EditableDocument,
    val rows: List<VisualNodeRow>,
)

private fun parseDocument(source: String, rootBlockId: String): ParsedDocument? {
    val nodes = mutableListOf<EditableNode>()
    val sourceLines = mutableMapOf<String, Int>()
    val lines = source.split('\n').toMutableList().apply {
        if (lastOrNull()?.isEmpty() == true) removeAt(lastIndex)
        if (size == 1 && single().isEmpty()) clear()
    }
    lines.forEachIndexed { index, line ->
        if (line.isBlank()) return null
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        val node = runCatching { EditableNode(json) }.getOrNull() ?: return null
        if (runCatching {
                node.nodeId.isNotBlank() && node.blockId.isNotBlank() && node.kind.isNotBlank() &&
                    node.orderKey.matches(Regex("[0-9A-Za-z]{1,64}"))
            }.getOrDefault(false).not()
        ) return null
        if (sourceLines.put(node.nodeId, index + 1) != null) return null
        nodes += node
    }
    val projection = project(nodes, rootBlockId) ?: return null
    val childSlots = nodes.flatMap { owner ->
        owner.childBlocks.map { (name, blockId) -> blockId to name }
    }.toMap()
    projection.forEach { (node, expectedDepth) ->
        val storedDepth = node.json.get("depth") ?: return@forEach
        if (!storedDepth.isJsonPrimitive || runCatching { storedDepth.asInt }.getOrNull() != expectedDepth) {
            return null
        }
    }
    return ParsedDocument(
        editable = EditableDocument(nodes),
        rows = projection.map { (node, depth) ->
            VisualNodeRow(
                nodeId = node.nodeId,
                blockId = node.blockId,
                orderKey = node.orderKey,
                kind = node.kind,
                depth = depth,
                childSlot = childSlots[node.blockId],
                sourceLine = sourceLines.getValue(node.nodeId),
            )
        },
    )
}

private fun project(nodes: List<EditableNode>, rootBlockId: String): List<Pair<EditableNode, Int>>? {
    if (nodes.map { it.nodeId }.distinct().size != nodes.size) return null
    if (nodes.groupBy { it.blockId }.values.any { block ->
            block.map { it.orderKey }.distinct().size != block.size
        }
    ) return null
    val byId = nodes.associateBy { it.nodeId }
    val owners = mutableMapOf<String, EditableNode>()
    for (node in nodes) {
        for (blockId in runCatching { node.childBlocks.values }.getOrElse { return null }) {
            if (blockId == rootBlockId || owners.put(blockId, node) != null) return null
        }
    }
    for (node in nodes) {
        if (node.blockId == rootBlockId) {
            if (node.parentId != null) return null
        } else {
            val owner = owners[node.blockId] ?: return null
            if (node.parentId != owner.nodeId || byId[node.parentId] == null) return null
        }
    }
    val members = nodes.groupBy { it.blockId }.mapValues { (_, value) -> value.sortedBy { it.orderKey } }
    val visited = mutableSetOf<String>()
    val output = mutableListOf<Pair<EditableNode, Int>>()
    fun visit(blockId: String, depth: Int): Boolean {
        for (node in members[blockId].orEmpty()) {
            if (!visited.add(node.nodeId)) return false
            output += node to depth
            for (child in node.childBlocks.toSortedMap().values) {
                if (!visit(child, depth + 1)) return false
            }
        }
        return true
    }
    if (!visit(rootBlockId, 0) || visited.size != nodes.size) return null
    return output
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: error("missing $name")

private const val ORDER_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

private fun between(previous: String?, next: String?): String? {
    if (previous != null && !previous.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
    if (next != null && !next.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
    if (previous != null && next != null && previous >= next) return null
    val output = StringBuilder()
    var lowerIndex = 0
    var upperIndex = 0
    var upperBounded = next != null
    while (output.length < 64) {
        val low = previous?.getOrNull(lowerIndex)?.let(ORDER_ALPHABET::indexOf) ?: -1
        val high = if (upperBounded) next?.getOrNull(upperIndex)?.let(ORDER_ALPHABET::indexOf) ?: -1
        else ORDER_ALPHABET.length
        when {
            low == high && low >= 0 -> {
                output.append(ORDER_ALPHABET[low]); lowerIndex++; upperIndex++
            }
            high - low > 1 -> {
                val middle = Math.floorDiv(low + high, 2)
                output.append(ORDER_ALPHABET[middle])
                if (middle == 0) output.append(ORDER_ALPHABET[ORDER_ALPHABET.length / 2])
                break
            }
            low == -1 && high == 0 -> {
                output.append(ORDER_ALPHABET[0]); upperIndex++
            }
            low >= 0 -> {
                output.append(ORDER_ALPHABET[low]); lowerIndex++; upperBounded = false
            }
            else -> return null
        }
    }
    val result = output.toString()
    return result.takeIf {
        it.length <= 64 && previous?.let { bound -> bound < it } != false &&
            next?.let { bound -> it < bound } != false
    }
}

private fun rebalance(nodes: List<EditableNode>) {
    val step = 62L * 62 * 62 * 62 / (nodes.size + 1)
    require(step >= 16) { "单个 block 节点过多" }
    nodes.forEachIndexed { index, node ->
        var value = step * (index + 1)
        val output = CharArray(4) { '0' }
        for (position in output.indices.reversed()) {
            output[position] = ORDER_ALPHABET[(value % 62).toInt()]
            value /= 62
        }
        node.orderKey = String(output)
    }
}
