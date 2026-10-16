package com.autoscript.studio

internal data class DesignerValidationIssue(val controlId: String?, val pageId: String, val tab: String, val message: String)

internal fun RunnerUiDesignerDraft.firstValidationIssue(): DesignerValidationIssue? {
    for (page in pages) {
        val failure = runCatching {
            com.autoscript.script.ui.ScriptUiDefinition(version = 2, pages = listOf(page), fields = listOf(
                com.autoscript.script.ui.UiField("validation_probe", "校验", "text", false, "",
                    ui = com.autoscript.script.ui.UiPresentation(control = "LABEL", pageId = page.id)))).validate()
        }.exceptionOrNull()
        if (failure != null) return DesignerValidationIssue(null, page.id, "布局", failure.message ?: "页面属性无效")
    }
    for (field in fields) {
        fun issue(text: String, tab: String = "布局") = DesignerValidationIssue(field.id, field.ui.pageId, tab, text)
        if (fields.count { it.id == field.id } != 1) return issue("控件ID重复")
        if (pages.none { it.id == field.ui.pageId }) return issue("控件所属页面不存在")
        val parent = field.ui.parentId
        if (parent != null && fields.none { it.id == parent && it.kind == RunnerUiControlKind.CONTAINER && it.ui.pageId == field.ui.pageId }) return issue("所属容器不存在或不在同一页面", "交互")
        val visited = mutableSetOf(field.id)
        var next = parent
        while (next != null) {
            if (!visited.add(next)) return issue("容器层级形成循环", "交互")
            next = fields.firstOrNull { it.id == next }?.ui?.parentId
        }
        if (visited.size > 9) return issue("容器层级超过8层", "交互")
        val isolated = copy(fields = listOf(field.copy(ui = field.ui.copy(parentId = null))))
        val failure = runCatching { isolated.toRunnerUiJson() }.exceptionOrNull()
        if (failure != null) return issue(failure.message ?: "控件属性无效")
    }
    return runCatching { toRunnerUiJson() }.exceptionOrNull()?.let {
        DesignerValidationIssue(null, pages.firstOrNull()?.id ?: "main", "布局", it.message ?: "页面属性无效")
    }
}
