package com.autoscript.studio

internal enum class EditorToolbarAction(val label: String) {
    RUN("运行"),
    PAUSE("暂停"),
    RESUME("继续"),
    STOP("停止"),
    STEP("单步"),
    EDIT("编辑"),
    LOG("日志"),
    MORE("更多"),
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
    stopEnabled: Boolean = runEnabled,
): List<EditorToolbarEntry> {
    val active = running || paused
    val editable = editingEnabled && !active
    return buildList {
        add(EditorToolbarEntry(
            when {
                active -> EditorToolbarAction.STOP
                else -> EditorToolbarAction.RUN
            },
            if (active) stopEnabled else runEnabled,
        ))
        add(EditorToolbarEntry(EditorToolbarAction.STEP, hasSource && stepEnabled))
        if (active) add(EditorToolbarEntry(if (paused) EditorToolbarAction.RESUME else EditorToolbarAction.PAUSE,
            if (paused) resumeEnabled else runEnabled))
        else add(EditorToolbarEntry(EditorToolbarAction.EDIT, editable))
        add(EditorToolbarEntry(EditorToolbarAction.LOG, true))
        add(EditorToolbarEntry(EditorToolbarAction.MORE, true))
    }
}

internal fun editorToolbarColumns(widthDp: Float): Int =
    if (widthDp.isFinite()) ((widthDp - 24f) / 40f).toInt().coerceIn(3, 5) else 3

private val failureStatusPattern = Regex("\\\"status\\\"\\s*:\\s*\\\"(failed|error|timeout|budget_exceeded)\\\"")
private val failureMetricPattern = Regex(" · (截图|找图|单图|多图|灰度找图|文字识别) (失败|超时|预算超限) · ")
internal fun editorConsoleLineIsFailure(line: String): Boolean =
    runtimeConsoleScriptIsError(line) || line.contains("诊断：") || line.endsWith("失败") || line.contains("Runner错误码 · ") ||
        failureStatusPattern.containsMatchIn(line) || failureMetricPattern.containsMatchIn(line)

internal fun editorSelectionToggleIds(ids: List<String>, selected: Set<String>, selectAll: Boolean): List<String> {
    val valid = ids.distinct()
    return if (selectAll) valid.filterNot { it in selected }.take((256 - valid.count { it in selected }).coerceAtLeast(0))
    else valid.filter { it in selected }
}
