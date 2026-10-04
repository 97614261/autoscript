package com.autoscript.studio

internal enum class EditorToolbarAction(val label: String) {
    RUN("运行"),
    PAUSE("暂停"),
    RESUME("继续"),
    STOP("停止"),
    STEP("单步"),
    EDIT("编辑"),
    LOG("日志"),
    UNDO("撤销"),
    REDO("重做"),
    MORE("更多"),
    COPY("复制"),
    PASTE("粘贴"),
    DELETE("删除"),
    MOVE_UP("上移"),
    MOVE_DOWN("下移"),
    ORGANIZE("多选"),
}

internal data class EditorToolbarEntry(val action: EditorToolbarAction, val enabled: Boolean)

/** Editing commands have one entry; logs never replace or squeeze the program tree. */
internal fun editorToolbarEntries(
    running: Boolean,
    paused: Boolean,
    runEnabled: Boolean,
    resumeEnabled: Boolean,
    stepEnabled: Boolean,
    hasSource: Boolean,
    editingEnabled: Boolean,
): List<EditorToolbarEntry> {
    val active = running || paused
    val editable = editingEnabled && !active
    return buildList {
        add(EditorToolbarEntry(
            when {
                paused -> EditorToolbarAction.RESUME
                running -> EditorToolbarAction.PAUSE
                else -> EditorToolbarAction.RUN
            },
            if (paused) resumeEnabled else runEnabled,
        ))
        add(EditorToolbarEntry(EditorToolbarAction.STEP, hasSource && stepEnabled))
        if (active) add(EditorToolbarEntry(EditorToolbarAction.STOP, runEnabled))
        else add(EditorToolbarEntry(EditorToolbarAction.EDIT, editable))
        add(EditorToolbarEntry(EditorToolbarAction.LOG, true))
        add(EditorToolbarEntry(EditorToolbarAction.MORE, true))
    }
}

internal fun editorToolbarColumns(widthDp: Float): Int =
    if (widthDp.isFinite()) ((widthDp - 24f) / 40f).toInt().coerceIn(3, 5) else 3

internal fun editorEditingEntries(
    selectionCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    commandsAvailable: Boolean,
): List<EditorToolbarEntry> = listOf(
    EditorToolbarEntry(EditorToolbarAction.UNDO, commandsAvailable && canUndo),
    EditorToolbarEntry(EditorToolbarAction.REDO, commandsAvailable && canRedo),
    EditorToolbarEntry(EditorToolbarAction.COPY, commandsAvailable && selectionCount > 0),
    EditorToolbarEntry(EditorToolbarAction.PASTE, commandsAvailable && canPaste),
    EditorToolbarEntry(EditorToolbarAction.DELETE, selectionCount > 0),
    EditorToolbarEntry(EditorToolbarAction.MOVE_UP, commandsAvailable && selectionCount > 0),
    EditorToolbarEntry(EditorToolbarAction.MOVE_DOWN, commandsAvailable && selectionCount > 0),
    EditorToolbarEntry(EditorToolbarAction.ORGANIZE, commandsAvailable),
)

internal fun editorConsoleLineIsFailure(line: String): Boolean =
    line.contains("诊断：") || line.endsWith("失败")

internal fun editorSelectionToggleIds(ids: List<String>, selected: Set<String>, selectAll: Boolean): List<String> {
    val valid = ids.distinct()
    return if (selectAll) valid.filterNot { it in selected }.take((256 - valid.count { it in selected }).coerceAtLeast(0))
    else valid.filter { it in selected }
}
