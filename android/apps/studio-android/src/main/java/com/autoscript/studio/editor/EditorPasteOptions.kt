package com.autoscript.studio

internal data class EditorPasteDestination(val before: Boolean = false, val childSlot: String? = null)

internal fun editorPasteOptions(slots: List<String>): List<Pair<EditorPasteDestination, String>> =
    listOf(EditorPasteDestination(before = true) to "本行之前", EditorPasteDestination() to "本行之后") +
        slots.distinct().map { EditorPasteDestination(childSlot = it) to "${childBlockLabel(it)}内" }

internal data class EditorPasteRequest(val projectId: String, val flowId: String, val source: String,
    val primary: String?, val selection: Set<String>, val branch: EditorBranchSelection? = null) {
    fun matches(project: String, flow: String, editor: VisualEditorState, selectedBranch: EditorBranchSelection? = null): Boolean =
        projectId == project && flowId == flow && source == editor.currentSource &&
            primary == editor.selectedNodeId && selection == editor.selectedNodeIds &&
            branch == selectedBranch && (branch == null || branch.matches(editor))

    fun accepts(destination: EditorPasteDestination): Boolean = branch == null ||
        (!destination.before && destination.childSlot == branch.slot)
}

/** Freeze the same structural context as paste; a late branch choice must not move another row. */
internal data class EditorIndentRequest(val context: EditorPasteRequest, val slots: List<String>) {
    fun matches(project: String, flow: String, editor: VisualEditorState): Boolean =
        context.matches(project, flow, editor) && slots == editor.indentSlotsForSelection()
}
