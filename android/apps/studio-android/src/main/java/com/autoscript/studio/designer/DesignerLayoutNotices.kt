package com.autoscript.studio

import com.autoscript.script.ui.ScriptUiControlMetrics
import com.autoscript.script.ui.ScriptUiControlVisuals
import com.autoscript.script.ui.ScriptUiGeometry
import com.autoscript.script.ui.visibleBorderWidthPx

internal enum class DesignerNoticeKind { BOUNDS, CONTENT, SCROLL }
internal data class DesignerLayoutNotice(val controlId: String, val pageId: String, val kind: DesignerNoticeKind, val message: String)
internal interface DesignerTextMeasure {
    fun width(text: String): Float
    fun rowHeight(text: String, width: Int, reserved: Int): Int
}

/** Nonblocking display advice; scrolling choices are informational, never a validation error. */
internal fun RunnerUiDesignerDraft.layoutNotices(metrics: (RunnerUiFieldDraft) -> DesignerTextMeasure): List<DesignerLayoutNotice> = buildList {
    for (field in fields) {
        val p = field.ui
        if (pages.none { it.id == p.pageId }) continue
        val clip = runCatching { previewClip(field) }.getOrNull() ?: continue
        if (clip.left != 0 || clip.top != 0 || clip.right != p.width || clip.bottom != p.height)
            add(DesignerLayoutNotice(field.id, p.pageId, DesignerNoticeKind.BOUNDS, "超出页面或容器，部分内容会被裁掉"))
        if (!field.kind.hasTextStyle()) continue
        val measure = metrics(field)
        val content = runCatching {
            val border = p.visibleBorderWidthPx * 2
            val choices = field.kind.wireName in setOf("choice", "multiChoice")
            val texts = if (choices) designerOptions(field.optionsText) else listOf(
                if (field.kind.wireName == "boolean") field.label else ScriptUiControlVisuals.text(field.kind.control, field.initialText, field.label).text)
            if (texts.size > 64 || texts.any { it.length > 2048 }) return@runCatching null
            val widths = texts.map(measure::width)
            if (widths.any { !it.isFinite() || it < 0f }) return@runCatching null
            val reserved = border + when {
                field.kind == RunnerUiControlKind.SWITCH -> ScriptUiControlMetrics.SWITCH_RESERVED
                choices || field.kind == RunnerUiControlKind.BOOLEAN -> ScriptUiControlMetrics.CHOICE_RESERVED
                else -> 8
            }
            val heights = texts.map { measure.rowHeight(it, p.width, reserved) }
            val singleLine = field.kind in setOf(RunnerUiControlKind.TEXT, RunnerUiControlKind.PASSWORD, RunnerUiControlKind.INTEGER,
                RunnerUiControlKind.DECIMAL, RunnerUiControlKind.CHOICE)
            val scrolling = field.kind in setOf(RunnerUiControlKind.RADIO, RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT)
            val horizontal = field.kind == RunnerUiControlKind.RADIO && ScriptUiGeometry.radioHorizontal(p.width, p.height, p.fontPx, widths)
            val rowPadding = scrolling || field.kind == RunnerUiControlKind.CHOICE
            val neededHeight = if (scrolling && !horizontal) heights.sum() else
                ((heights.maxOrNull() ?: 0) - if (rowPadding) 0 else 8).coerceAtLeast(0)
            when {
                scrolling && neededHeight > p.height -> DesignerNoticeKind.SCROLL to "选项可在控件内滚动查看"
                singleLine && (widths.maxOrNull() ?: 0f) > (p.width - reserved).coerceAtLeast(1) -> DesignerNoticeKind.CONTENT to "文字过长，显示时会截断或省略"
                neededHeight + border > p.height -> DesignerNoticeKind.CONTENT to "高度不足，文字可能显示不全"
                else -> null
            }
        }.getOrNull()
        content?.let { (kind, text) -> add(DesignerLayoutNotice(field.id, p.pageId, kind, text)) }
    }
}
