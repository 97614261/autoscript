package com.autoscript.studio

/** The owner checks session/busy/stale state; this layer only applies a synchronous draft command. */
internal sealed interface EditorCommandResult {
    data class Mutation(val changed: Boolean, val message: String) : EditorCommandResult
    data class Clipboard(val nodes: List<VisualSubtreeClipboard>?, val cut: Boolean, val changed: Boolean = false) : EditorCommandResult
    data class Indent(val slots: List<String>) : EditorCommandResult
    data object Paste : EditorCommandResult
}

internal fun executeEditorCommand(editor: VisualEditorState, command: EditorProgramCommand): EditorCommandResult = when (command) {
    EditorProgramCommand.MOVE_UP -> EditorCommandResult.Mutation(editor.moveSelection(-1), "节点已上移")
    EditorProgramCommand.MOVE_DOWN -> EditorCommandResult.Mutation(editor.moveSelection(1), "节点已下移")
    EditorProgramCommand.OUTDENT -> EditorCommandResult.Mutation(editor.outdentSelection(), "节点已减少一级缩进")
    EditorProgramCommand.INDENT -> editor.indentSlotsForSelection().let { slots ->
        if (slots.size == 1) EditorCommandResult.Mutation(editor.indentSelection(slots.single()), "节点已缩进")
        else EditorCommandResult.Indent(slots)
    }
    EditorProgramCommand.COPY -> EditorCommandResult.Clipboard(editor.copySelection().takeIf { it.isNotEmpty() }, false)
    EditorProgramCommand.CUT -> {
        val copied = editor.copySelection().takeIf { it.isNotEmpty() }
        EditorCommandResult.Clipboard(copied, true, copied != null && editor.deleteSelection())
    }
    EditorProgramCommand.PASTE -> EditorCommandResult.Paste
    EditorProgramCommand.UNDO -> EditorCommandResult.Mutation(editor.undo(), "已撤销")
    EditorProgramCommand.REDO -> EditorCommandResult.Mutation(editor.redo(), "已重做")
    EditorProgramCommand.TOGGLE_DISABLED -> EditorCommandResult.Mutation(editor.toggleDisabledSelection(), "已更新积木执行状态")
}
