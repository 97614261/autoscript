package com.autoscript.studio

import com.autoscript.script.ui.MAX_SCRIPT_UI_CONTROLS
import com.autoscript.script.ui.ScriptUiControlMetrics
import com.autoscript.script.ui.UiPresentation

internal fun newDesignerControl(kind: RunnerUiControlKind, id: String, pageId: String, x: Int, y: Int, width: Int, height: Int): RunnerUiFieldDraft {
    val value = when (kind.wireName) {
        "multiChoice" -> "[]"; "number" -> "0.0"; "integer" -> "0"; "boolean" -> "false"; "choice" -> "选项1"
        else -> if (kind == RunnerUiControlKind.LABEL) "标签" else ""
    }
    return RunnerUiFieldDraft(id, kind.label, kind, false, value,
        minimum = if (kind in setOf(RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS)) "0" else "",
        maximum = if (kind in setOf(RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS)) "100" else "",
        optionsText = if (kind.wireName in setOf("choice", "multiChoice")) designerOptionsText(listOf("选项1", "选项2")) else "",
        ui = UiPresentation(control = kind.control.name, pageId = pageId, x = x, y = y, width = width, height = height,
            fontPx = ScriptUiControlMetrics.DEFAULT_FONT).withDesignerAppearance(kind))
}

internal enum class DesignerCombination(val label: String, val width: Int) {
    TEXT_INPUT("文字＋输入框", 312), LABEL_SWITCH("文字＋开关", 248), CONFIRM_CANCEL("确定＋取消", 216),
}

/** Reserve one complete slot before adding either control. The caller commits once. */
internal fun RunnerUiDesignerDraft.addCombination(combination: DesignerCombination, pageId: String): RunnerUiDesignerDraft {
    require(fields.size + 2 <= MAX_SCRIPT_UI_CONTROLS) { "控件数量不足以加入组合" }
    val page = pages.singleOrNull { it.id == pageId } ?: error("当前页面已不存在")
    val width = minOf(combination.width, page.width - 16)
    val height = ScriptUiControlMetrics.DEFAULT_HEIGHT
    require(width >= 48 && page.height >= height + 16) { "页面放不下组合，请增大画布" }
    val occupied = fields.filter { it.ui.pageId == pageId && it.ui.parentId == null }.map { it.ui }
    val ys = (listOf(8) + occupied.map { it.y + it.height + 8 }).distinct().sorted()
    var position: Pair<Int, Int>? = null
    for (y in ys) {
        if (y + height > page.height - 8) continue
        var x = 8
        while (x + width <= page.width - 8) {
            val blocking = occupied.firstOrNull { p -> x < p.x + p.width + 4 && x + width + 4 > p.x && y < p.y + p.height + 4 && y + height + 4 > p.y }
            if (blocking == null) { position = x to y; break }
            x = maxOf(x + 4, blocking.x + blocking.width + 8)
        }
        if (position != null) break
    }
    val (x, y) = position ?: error("当前页没有完整空位，请整理布局或添加页面")
    val leftWidth = if (combination == DesignerCombination.CONFIRM_CANCEL) (width - 8) / 2 else ((width - 8) * .32f).toInt().coerceAtLeast(20)
    val rightWidth = width - 8 - leftWidth
    val used = fields.map { it.id }.toMutableSet()
    fun id(): String { var n = used.size + 1; while ("control$n" in used) n++; return "control$n".also(used::add) }
    val leftKind = if (combination == DesignerCombination.CONFIRM_CANCEL) RunnerUiControlKind.BUTTON else RunnerUiControlKind.LABEL
    val rightKind = when (combination) { DesignerCombination.TEXT_INPUT -> RunnerUiControlKind.TEXT; DesignerCombination.LABEL_SWITCH -> RunnerUiControlKind.SWITCH; else -> RunnerUiControlKind.BUTTON }
    var left = newDesignerControl(leftKind, id(), pageId, x, y, leftWidth, height)
    var right = newDesignerControl(rightKind, id(), pageId, x + leftWidth + 8, y, rightWidth, height)
    when (combination) {
        DesignerCombination.TEXT_INPUT -> { left = left.copy(label = "名称", initialText = "名称"); right = right.copy(label = "请输入名称") }
        DesignerCombination.LABEL_SWITCH -> { left = left.copy(label = "功能", initialText = "功能"); right = right.copy(label = "启用") }
        DesignerCombination.CONFIRM_CANCEL -> {
            left = left.copy(label = "确定", initialText = "确定", ui = left.ui.copy(events = mapOf("click" to "run")))
            right = right.copy(label = "取消", initialText = "取消", ui = right.ui.withDesignerAppearance(rightKind, DesignerAppearance.OUTLINE).copy(events = mapOf("click" to "close")))
        }
    }
    return copy(fields = fields + left + right)
}
