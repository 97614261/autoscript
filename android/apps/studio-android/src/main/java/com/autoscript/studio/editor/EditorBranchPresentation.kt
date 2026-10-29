package com.autoscript.studio

/** Branch headings are view items, never executable nodes or selection/clipboard identities. */
internal sealed interface EditorTreeDisplayRow {
    val key: String
    val nodeId: String?
    val depth: Int

    data class Node(val node: EditorTreeNode, override val key: String = "node:${node.id}",
        override val depth: Int = node.depth) : EditorTreeDisplayRow {
        override val nodeId: String get() = node.id
    }

    data class Branch(
        val ownerId: String,
        val slot: String,
        override val depth: Int,
        val empty: Boolean,
        val disabled: Boolean,
        val expanded: Boolean = true,
        val descendantCount: Int = 0,
    ) : EditorTreeDisplayRow {
        override val key: String get() = editorBranchKey(ownerId, slot)
        override val nodeId: String? get() = null
    }
}

/** One linear hierarchy pass, then success/failure presentation order without mutating source. */
internal fun editorTreeDisplayRows(nodes: List<EditorTreeNode>, collapsedBranches: Collection<String> = emptySet()): List<EditorTreeDisplayRow> {
    val visible = editorVisibleTreeNodes(nodes)
    // Invalid read-only files can repeat identities; never turn those into cyclic view ownership.
    if (nodes.map { it.id }.distinct().size != nodes.size) {
        return visible.mapIndexed { index, node -> EditorTreeDisplayRow.Node(node, "invalid:$index:${node.id}") }
    }
    val children = mutableMapOf<String, MutableList<EditorTreeNode>>()
    val foldedBranches = collapsedBranches.toHashSet()
    val descendantCounts = editorDescendantCounts(nodes)
    val roots = mutableListOf<EditorTreeNode>()
    val ancestors = ArrayDeque<EditorTreeNode>()
    visible.forEach { node ->
        while (ancestors.isNotEmpty() && ancestors.last().depth >= node.depth) ancestors.removeLast()
        val parent = ancestors.lastOrNull()
        if (parent == null) roots += node else children.getOrPut(parent.id) { mutableListOf() } += node
        ancestors.addLast(node)
    }
    return buildList {
        fun append(node: EditorTreeNode, displayDepth: Int = node.depth) {
            add(EditorTreeDisplayRow.Node(node, depth = displayDepth))
            if (!node.expanded) return
            val members = children[node.id].orEmpty()
            val slots = editorBranchSlots(node.code, node.childSlots)
            if (slots.isEmpty()) {
                members.forEach { append(it, displayDepth + it.depth - node.depth) }
                return
            }
            slots.forEach { slot ->
                val branch = members.filter { it.childSlot == slot }
                val parts = node.displayRow ?: compactProgramRowText(node.label)
                val expanded = editorBranchKey(node.id, slot) !in foldedBranches
                add(EditorTreeDisplayRow.Branch(node.id, slot, displayDepth + 1, branch.isEmpty(), parts.disabled,
                    expanded, branch.sumOf { 1 + (descendantCounts[it.id] ?: 0) }))
                // Headings occupy a visible level; executable depth and identity stay untouched.
                if (expanded) branch.forEach { append(it, displayDepth + it.depth - node.depth + 1) }
            }
            // Unknown/legacy metadata must not silently hide actual nodes.
            members.filter { it.childSlot !in slots }.forEach { append(it, displayDepth + it.depth - node.depth) }
        }
        roots.forEach { append(it) }
    }
}

internal fun editorBranchKey(ownerId: String, slot: String): String = "branch:$ownerId:$slot"

internal fun editorTreeBranchKeys(nodes: List<EditorTreeNode>): Set<String> = buildSet {
    nodes.forEach { node -> editorBranchSlots(node.code, node.childSlots).forEach { add(editorBranchKey(node.id, it)) } }
}

/** Locate only branches on the real ancestor chain, including children hidden by node folds. */
internal fun editorBranchAncestorKeys(nodes: List<EditorTreeNode>, targetId: String?): Set<String> {
    val index = nodes.indexOfFirst { it.id == targetId }
    if (index < 0) return emptySet()
    var child = nodes[index]
    return buildSet {
        for (previous in index - 1 downTo 0) {
            val parent = nodes[previous]
            if (parent.depth < child.depth) {
                if (child.childSlot in editorBranchSlots(parent.code, parent.childSlots))
                    add(editorBranchKey(parent.id, requireNotNull(child.childSlot)))
                child = parent
            }
            if (child.depth <= 0) break
        }
    }
}

internal fun editorBranchSlots(kind: String, slots: List<String>): List<String> = when (kind) {
    "flow.call.branch", "vision.findallimages", "vision.findimage", "vision.findgray" ->
        listOf("success", "failure").filter { it in slots }
    "control.if" -> listOf("then").filter { it in slots } +
        slots.filter { it.startsWith("elseIf") && it.removePrefix("elseIf").toIntOrNull() != null }
            .distinct().sortedBy { it.removePrefix("elseIf").toInt() } + listOf("else").filter { it in slots }
    else -> emptyList()
}

/** Counts all real descendants in one depth-stack pass, including folded children. */
internal fun editorDescendantCounts(nodes: List<EditorTreeNode>): Map<String, Int> {
    val ancestors = ArrayDeque<Int>()
    val counts = mutableMapOf<String, Int>()
    nodes.forEachIndexed { index, node ->
        while (ancestors.isNotEmpty() && nodes[ancestors.last()].depth >= node.depth) {
            val start = ancestors.removeLast()
            counts[nodes[start].id] = index - start - 1
        }
        ancestors.addLast(index)
    }
    while (ancestors.isNotEmpty()) {
        val start = ancestors.removeLast()
        counts[nodes[start].id] = nodes.size - start - 1
    }
    return counts
}

internal fun editorRangeSelection(visibleIds: List<String>, start: String, end: String): Set<String>? {
    val first = visibleIds.indexOf(start)
    val last = visibleIds.indexOf(end)
    if (first < 0 || last < 0 || kotlin.math.abs(last - first) + 1 > 256) return null
    val range = visibleIds.subList(minOf(first, last), maxOf(first, last) + 1)
    return range.toSet().takeIf { it.size == range.size }
}

internal fun List<EditorProgramNode>.toEditorTreeNodes(collapsed: Collection<String> = emptySet()): List<EditorTreeNode> {
    val folded = collapsed.toHashSet()
    return mapIndexed { index, node -> EditorTreeNode(
        node.nodeId, (if (node.disabled) "[已禁用] " else if (node.inheritedDisabled) "[随父级禁用] " else "") + node.label,
        node.kind, node.depth, node.childSlots.isNotEmpty() || getOrNull(index + 1)?.depth?.let { it > node.depth } == true,
        node.nodeId !in folded, node.displayRow, node.childSlots, node.childSlot, node.fullDisplayRow,
    ) }
}
