package com.autoscript.studio

import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.autoscript.script.ui.ScriptUiGeometry
import com.autoscript.script.ui.ScriptUiControlMetrics
import kotlin.math.ceil

internal fun RunnerUiFieldDraft.supportsContentFit() = kind in setOf(RunnerUiControlKind.RADIO, RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT,
    RunnerUiControlKind.CHOICE, RunnerUiControlKind.LABEL, RunnerUiControlKind.BUTTON, RunnerUiControlKind.NAVIGATION,
    RunnerUiControlKind.BOOLEAN, RunnerUiControlKind.SWITCH, RunnerUiControlKind.TEXTAREA)

internal class DesignerTextMetrics(field: RunnerUiFieldDraft) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = field.ui.fontPx.toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, if (field.ui.fontBold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun width(text: String) = paint.measureText(text.take(2048))
    fun rowHeight(text: String, width: Int, reserved: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - reserved).coerceAtLeast(1))
        .setIncludePad(false).build().height + 8
}

internal fun RunnerUiFieldDraft.contentSizeChanged(before: RunnerUiFieldDraft): Boolean {
    if (!supportsContentFit()) return false
    if (ui.fontPx != before.ui.fontPx || ui.fontBold != before.ui.fontBold || ui.borderWidthPx != before.ui.borderWidthPx) return true
    if (kind.wireName in setOf("choice", "multiChoice")) return optionsText != before.optionsText
    if (kind in setOf(RunnerUiControlKind.BOOLEAN, RunnerUiControlKind.SWITCH)) return label != before.label
    return initialText.ifEmpty { label } != before.initialText.ifEmpty { before.label }
}

internal data class DesignerContentFit(val draft: RunnerUiDesignerDraft, val limited: Boolean)

/** Grow in place without moving neighbors; measurements are injectable for host-independent tests. */
internal fun RunnerUiDesignerDraft.fitContent(id: String, measure: (String) -> Float, rowHeight: ((String, Int, Int) -> Int)? = null): DesignerContentFit {
    val index = fields.indexOfFirst { it.id == id }
    if (index < 0) return DesignerContentFit(this, false)
    val f = fields[index]; val p = f.ui
    if (!f.supportsContentFit()) return DesignerContentFit(this, false)
    val page = pages.singleOrNull { it.id == p.pageId } ?: return DesignerContentFit(this, true)
    val owner = p.parentId?.let { parent -> fields.singleOrNull { it.id == parent }?.ui }
    if (p.parentId != null && owner == null) return DesignerContentFit(this, true)
    val maxWidth = owner?.width ?: page.width; val maxHeight = owner?.height ?: page.height
    val siblings = fields.filter { it.id != id && it.ui.pageId == p.pageId && it.ui.parentId == p.parentId }.map { it.ui }
    if (p.x < 0 || p.y < 0 || p.x + p.width > maxWidth || p.y + p.height > maxHeight || siblings.any {
        it.x < p.x + p.width && it.x + it.width > p.x && it.y < p.y + p.height && it.y + it.height > p.y
    }) return DesignerContentFit(this, true)
    val choices = f.kind.wireName in setOf("choice", "multiChoice")
    val texts = if (choices) designerOptions(f.optionsText) else
        (if (f.kind in setOf(RunnerUiControlKind.BOOLEAN, RunnerUiControlKind.SWITCH)) f.label else f.initialText.ifEmpty { f.label }).split('\n')
    require(if (choices) texts.size <= 64 && texts.all { it.length <= 128 } else texts.sumOf { it.length.toLong() + 1 } <= 2049)
    val widths = texts.map(measure); require(widths.all { it.isFinite() && it >= 0 })
    val reserved = (when { f.kind == RunnerUiControlKind.SWITCH -> ScriptUiControlMetrics.SWITCH_RESERVED; choices || f.kind == RunnerUiControlKind.BOOLEAN -> ScriptUiControlMetrics.CHOICE_RESERVED; else -> 8 }) + 2 * p.borderWidthPx
    val freeWidth = minOf(maxWidth - p.x, siblings.filter { it.x >= p.x + p.width && it.y < p.y + p.height && it.y + it.height > p.y }
        .minOfOrNull { maxOf(p.width, it.x - p.x - 4) } ?: maxWidth).coerceAtMost(4096)
    val wantsRow = f.kind == RunnerUiControlKind.RADIO && ScriptUiGeometry.radioHorizontal(p.height, p.fontPx, widths.size) && widths.sumOf { (it + reserved).toDouble() } <= freeWidth
    val desiredWidth = ceil(if (wantsRow) widths.sumOf { (it + reserved).toDouble() } else (widths.maxOrNull() ?: 0f) + reserved.toDouble()).toInt()
    val width = maxOf(p.width, minOf(desiredWidth, freeWidth))
    val horizontal = f.kind == RunnerUiControlKind.RADIO && ScriptUiGeometry.radioHorizontal(width, p.height, p.fontPx, widths)
    val heights = widths.mapIndexed { i, measured -> maxOf(ScriptUiGeometry.contentRowHeight(p.fontPx, measured, width, reserved), rowHeight?.invoke(texts[i], width, reserved) ?: 20) }
    val desiredHeight = if (f.kind == RunnerUiControlKind.CHOICE || horizontal) heights.maxOrNull() ?: 20 else heights.sum().coerceAtLeast(20)
    val freeHeight = minOf(maxHeight - p.y, siblings.filter { it.y >= p.y + p.height && it.x < p.x + width && it.x + it.width > p.x }
        .minOfOrNull { maxOf(p.height, it.y - p.y - 4) } ?: maxHeight).coerceAtMost(8192)
    val height = maxOf(p.height, minOf(desiredHeight, freeHeight))
    return DesignerContentFit(update(index, f.copy(ui = p.copy(width = width, height = height))), desiredWidth > width || desiredHeight > height)
}
