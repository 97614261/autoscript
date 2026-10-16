package com.autoscript.studio

internal data class EditorPasteDestination(val before: Boolean = false, val childSlot: String? = null)

internal fun editorPasteOptions(slots: List<String>): List<Pair<EditorPasteDestination, String>> =
    listOf(EditorPasteDestination(before = true) to "本行之前", EditorPasteDestination() to "本行之后") +
        slots.distinct().map { EditorPasteDestination(childSlot = it) to "${childBlockLabel(it)}内" }

internal data class EditorPasteRequest(val projectId: String, val flowId: String, val source: String,
    val primary: String?, val selection: Set<String>) {
    fun matches(project: String, flow: String, editor: VisualEditorState): Boolean =
        projectId == project && flowId == flow && source == editor.currentSource &&
            primary == editor.selectedNodeId && selection == editor.selectedNodeIds
}

/** Freeze the same structural context as paste; a late branch choice must not move another row. */
internal data class EditorIndentRequest(val context: EditorPasteRequest, val slots: List<String>) {
    fun matches(project: String, flow: String, editor: VisualEditorState): Boolean =
        context.matches(project, flow, editor) && slots == editor.indentSlotsForSelection()
}
