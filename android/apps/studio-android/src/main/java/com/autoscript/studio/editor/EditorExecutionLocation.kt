package com.autoscript.studio

internal data class EditorExecutionBranch(val key: String, val line: Int, val label: String)

internal data class EditorExecutionLocation(
    val nodeId: String,
    val line: Int,
    val title: String,
    val branches: List<EditorExecutionBranch>,
) {
    val summary: String get() = buildString {
        append("第${line}行 · $title")
        if (branches.isNotEmpty()) {
            append("  /  ")
            append(branches.joinToString(" › ") { "第${it.line}行 ${it.label}" })
        }
    }
}

/** Use the full source order: folding and focus must not renumber the paused checkpoint. */
internal fun editorExecutionLocation(nodes: List<EditorTreeNode>, currentNodeId: String?): EditorExecutionLocation? {
    val index = nodes.indexOfFirst { it.id == currentNodeId }
    if (index < 0 || nodes.map { it.id }.distinct().size != nodes.size) return null
    val branchKeys = editorBranchAncestorKeys(nodes, currentNodeId)
    val branches = buildList {
        nodes.forEachIndexed { position, node ->
            editorBranchSlots(node.code, node.childSlots).forEach { slot ->
                val key = editorBranchKey(node.id, slot)
                if (key in branchKeys) {
                    val label = childBlockLabel(slot)
                    add(EditorExecutionBranch(key, position + 1, compactProgramBranchLabel(label) ?: label))
                }
            }
        }
    }
    val node = nodes[index]
    return EditorExecutionLocation(node.id, index + 1,
        (node.displayRow ?: compactProgramRowText(node.label)).title, branches)
}

internal fun editorExecutionScrollOffset(offset: Int, viewportWidth: Int, rowLeft: Int, readableWidth: Int): Int? {
    val width = viewportWidth.coerceAtLeast(1)
    val left = rowLeft.coerceAtLeast(0)
    val required = readableWidth.coerceIn(1, width)
    return when {
        left < offset -> left
        left + required > offset + width -> (left + required - width).coerceAtLeast(0)
        else -> null
    }
}
