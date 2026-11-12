package com.autoscript.studio

/** Freeze editable content and identities, not fold state or other presentation details. */
internal data class EditorDeleteRequest(
    val source: String?,
    val flowId: String?,
    val selection: Set<String>,
    val affected: Int,
) {
    fun matches(source: String?, flowId: String?, selection: Set<String>): Boolean =
        this.source == source && this.flowId == flowId && this.selection == selection
}

internal fun editorDeleteRequest(source: String?, flowId: String?, nodes: List<EditorTreeNode>,
    selection: Set<String>): EditorDeleteRequest? {
    if (selection.isEmpty() || !nodes.map { it.id }.toSet().containsAll(selection)) return null
    return EditorDeleteRequest(source, flowId, selection.toSet(), editorSelectionImpact(nodes, selection).affected)
}

internal fun editorDeleteAction(enabled: Boolean, source: String?, flowId: String?, nodes: List<EditorTreeNode>,
    selection: Set<String>, onRequest: (EditorDeleteRequest) -> Unit): () -> Unit = {
    if (enabled) editorDeleteRequest(source, flowId, nodes, selection)?.let(onRequest)
}
