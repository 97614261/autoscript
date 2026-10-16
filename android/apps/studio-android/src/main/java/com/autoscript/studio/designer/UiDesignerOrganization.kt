package com.autoscript.studio

import com.autoscript.script.ui.UiPresentation
import kotlin.math.roundToInt

internal enum class DesignerUniformSize { WIDTH, HEIGHT, BOTH }

/** Validate a complete candidate before committing: geometry only, no partial edits or neighbor moves. */
internal fun RunnerUiDesignerDraft.checkedGeometryChange(next: RunnerUiDesignerDraft, ids: Set<String>, operation: String): RunnerUiDesignerDraft {
    fun overlaps(a: UiPresentation, b: UiPresentation) = a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
    val before = fields.associateBy { it.id }
    val after = next.fields.associateBy { it.id }
    require(ids.all { it in before && it in after }) { "选择的控件不存在" }
    next.fields.filter { it.id in ids }.forEach { field ->
        val p = field.ui
        val page = pages.singleOrNull { it.id == p.pageId } ?: error("控件所在页面不存在")
        val owner = p.parentId?.let { after[it]?.ui ?: error("控件所在容器不存在") }
        require(p.x >= 0 && p.y >= 0 && p.x + p.width <= (owner?.width ?: page.width) && p.y + p.height <= (owner?.height ?: page.height)) {
            "${operation}超出页面或容器，请先移动控件或增大可用空间；布局未改变"
        }
        require(next.fields.filter { it.ui.parentId == field.id }.all { child ->
            child.ui.x >= 0 && child.ui.y >= 0 && child.ui.x + child.ui.width <= p.width && child.ui.y + child.ui.height <= p.height
        }) { "${operation}放不下容器内控件，布局未改变" }
        require(next.fields.none { sibling ->
            sibling.id != field.id && sibling.ui.pageId == p.pageId && sibling.ui.parentId == p.parentId &&
                overlaps(p, sibling.ui) && !overlaps(before.getValue(field.id).ui, before.getValue(sibling.id).ui)
        }) { "${operation}会与相邻控件重叠，请先移动控件；布局未改变" }
    }
    return next
}

internal fun RunnerUiDesignerDraft.arrangeSelection(ids: Set<String>, horizontal: Boolean): RunnerUiDesignerDraft {
    require(ids.all { id -> fields.any { it.id == id } }) { "选择的控件不存在" }
    val roots = selectionRoots(ids)
    require(roots.size >= 2 && roots.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1) { "请选择同一页面、同一容器内至少两个控件" }
    val left = roots.minOf { it.ui.x }
    val top = roots.minOf { it.ui.y }
    var cursor = if (horizontal) left else top
    val changes = roots.sortedBy { if (horizontal) it.ui.x else it.ui.y }.associate { field ->
        val p = field.ui
        val position = if (horizontal) p.copy(x = cursor, y = top) else p.copy(x = left, y = cursor)
        cursor += (if (horizontal) p.width else p.height) + 8
        field.id to position
    }
    val next = copy(fields = fields.map { field -> field.copy(ui = changes[field.id] ?: field.ui) })
    return checkedGeometryChange(next, changes.keys, if (horizontal) "横向排列" else "纵向排列")
}

internal fun RunnerUiDesignerDraft.uniformSize(ids: Set<String>, dimension: DesignerUniformSize): RunnerUiDesignerDraft {
    require(ids.size >= 2 && ids.all { id -> fields.any { it.id == id } }) { "请至少选择两个控件" }
    val selected = fields.filter { it.id in ids }
    require(selected.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1) { "请选择同一页面、同一容器内的控件" }
    val reference = fields.single { it.id == ids.first() }.ui
    val next = copy(fields = fields.map { field -> if (field.id !in ids) field else field.copy(ui = field.ui.copy(
        width = if (dimension == DesignerUniformSize.HEIGHT) field.ui.width else reference.width,
        height = if (dimension == DesignerUniformSize.WIDTH) field.ui.height else reference.height,
    )) })
    return checkedGeometryChange(next, ids, "统一尺寸")
}

internal fun RunnerUiDesignerDraft.snapSelection(ids: Set<String>): RunnerUiDesignerDraft {
    require(ids.isNotEmpty() && ids.all { id -> fields.any { it.id == id } }) { "请先选择控件" }
    val roots = selectionRoots(ids).map { it.id }.toSet()
    val next = copy(fields = fields.map { field -> if (field.id !in roots) field else field.copy(ui = field.ui.copy(
        x = (field.ui.x / 8f).roundToInt() * 8, y = (field.ui.y / 8f).roundToInt() * 8,
    )) })
    return checkedGeometryChange(next, roots, "吸附8px网格")
}
