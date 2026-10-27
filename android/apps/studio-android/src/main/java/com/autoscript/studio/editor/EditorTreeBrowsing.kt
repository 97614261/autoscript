package com.autoscript.studio

internal data class EditorFocusCrumb(val id: String, val label: String)

internal fun editorFocusPath(nodes: List<EditorTreeNode>, focusId: String?): List<EditorFocusCrumb> {
    val index = nodes.indexOfFirst { it.id == focusId && it.container }
    if (index < 0) return emptyList()
    val path = ArrayDeque<EditorTreeNode>()
    var depth = nodes[index].depth
    path.addFirst(nodes[index])
    for (previous in index - 1 downTo 0) {
        if (nodes[previous].depth < depth) {
            path.addFirst(nodes[previous])
            depth = nodes[previous].depth
        }
        if (depth == 0) break
    }
    return path.map { EditorFocusCrumb(it.id, it.displayRow?.title ?: it.label.substringBefore(" · ")) }
}

internal fun editorFocusedTreeNodes(nodes: List<EditorTreeNode>, focusId: String?): List<EditorTreeNode> {
    val index = nodes.indexOfFirst { it.id == focusId && it.container }
    if (index < 0) return nodes
    val depth = nodes[index].depth
    var end = index + 1
    while (end < nodes.size && nodes[end].depth > depth) end++
    return nodes.subList(index, end).map { it.copy(depth = it.depth - depth) }
}

/** A leaf focuses its nearest real container; an insertion header uses its owner. */
internal fun editorFocusTarget(nodes: List<EditorTreeNode>, selectedId: String?): String? {
    val index = nodes.indexOfFirst { it.id == selectedId }
    if (index < 0) return null
    if (nodes[index].container) return selectedId
    val depth = nodes[index].depth
    return (index - 1 downTo 0).firstOrNull { nodes[it].container && nodes[it].depth < depth }?.let { nodes[it].id }
}

internal data class EditorSelectionImpact(val selected: Int, val affected: Int) {
    val label: String get() = "选中 $selected 行 · 含子积木共 $affected 行"
}

/** Preorder union: selecting an ancestor never double-counts selected descendants. */
internal fun editorSelectionImpact(nodes: List<EditorTreeNode>, selectedIds: Set<String>): EditorSelectionImpact {
    var affected = 0
    var selected = 0
    var selectedDepth: Int? = null
    nodes.forEach { node ->
        if (selectedDepth?.let { node.depth <= it } == true) selectedDepth = null
        if (node.id in selectedIds) {
            selected++
            if (selectedDepth == null) selectedDepth = node.depth
        }
        if (selectedDepth != null) affected++
    }
    return EditorSelectionImpact(selected, affected)
}

internal data class EditorListScrollRequest(val index: Int, val offset: Int)

internal fun editorMinimumScrollRequest(target: Int, visible: List<Int>, viewportHeight: Int, rowHeight: Int): EditorListScrollRequest? {
    if (target < 0 || target in visible) return null
    val below = visible.isNotEmpty() && target > visible.last()
    val offset = if (below) -(viewportHeight - rowHeight.coerceAtLeast(1)).coerceAtLeast(0) else 0
    return EditorListScrollRequest(target, offset)
}

internal fun editorSelectionOwnDisabled(nodes: List<EditorProgramNode>, selectedIds: Set<String>): Boolean {
    var ancestorDepth: Int? = null
    var found = false
    for (node in nodes) {
        if (ancestorDepth?.let { node.depth <= it } == true) ancestorDepth = null
        if (node.nodeId in selectedIds && ancestorDepth == null) {
            if (!node.disabled) return false
            found = true
            ancestorDepth = node.depth
        }
    }
    return found
}

internal fun editorFollowFlow(following: Boolean, position: VisualDebugPosition?, available: Set<String>): String? =
    position?.flowId?.takeIf { following && it in available }
