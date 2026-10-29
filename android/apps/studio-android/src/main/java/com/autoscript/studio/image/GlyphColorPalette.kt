package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectGlyphColor

internal data class GlyphColorDraft(val rgb: String, val tolerance: String) {
    fun parsed(): ProjectGlyphColor? = runCatching {
        require(rgb.removePrefix("#").matches(Regex("[0-9A-Fa-f]{6}")) && tolerance.matches(Regex("[0-9]{1,3}")))
        ProjectGlyphColor(rgb.removePrefix("#").toInt(16), tolerance.toInt())
    }.getOrNull()
}

internal data class GlyphPaletteDraft(val colors: List<GlyphColorDraft>, val selected: Int = 0, val replaceOnPick: Boolean = true) {
    val current get() = colors[selected]
    fun parsed(): List<ProjectGlyphColor>? {
        val result = colors.map { it.parsed() ?: return null }
        return result.takeIf { it.size in 1..ProjectGlyphColor.MAX_COLORS && it.map { color -> color.rgb }.distinct().size == it.size }
    }
    fun edit(rgb: String = current.rgb, tolerance: String = current.tolerance) = copy(colors = colors.toMutableList().apply {
        set(selected, GlyphColorDraft(rgb, tolerance))
    })
    fun pick(rgb: Int): GlyphPaletteDraft {
        val old = colors.indexOfFirst { it.parsed()?.rgb == rgb }
        if (old >= 0) return copy(selected = old, replaceOnPick = false)
        val color = GlyphColorDraft("%06X".format(java.util.Locale.ROOT, rgb), current.tolerance)
        if (replaceOnPick || current.parsed() == null) return copy(colors = colors.toMutableList().apply { set(selected, color) }, replaceOnPick = false)
        if (colors.size >= ProjectGlyphColor.MAX_COLORS) return this
        return copy(colors = colors + color, selected = colors.size, replaceOnPick = false)
    }
    fun add() = if (colors.size >= ProjectGlyphColor.MAX_COLORS) this else copy(
        colors = colors + GlyphColorDraft("", current.tolerance), selected = colors.size, replaceOnPick = true)
    fun remove() = if (colors.size == 1) this else copy(colors = colors.filterIndexed { index, _ -> index != selected },
        selected = (selected-1).coerceAtLeast(0), replaceOnPick = true)
    companion object {
        fun from(primary: Int, tolerance: Int, extra: String = "") = GlyphPaletteDraft(
            (listOf(ProjectGlyphColor(primary, tolerance)) + ProjectGlyphColor.decodeAdditional(extra, primary)).map {
                GlyphColorDraft("%06X".format(java.util.Locale.ROOT, it.rgb), it.tolerance.toString())
            })
    }
}

/** Shared compact palette; selecting a chip explicitly makes the next pick replace only that color. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GlyphColorPaletteEditor(draft: GlyphPaletteDraft, enabled: Boolean, onChange: (GlyphPaletteDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            draft.colors.forEachIndexed { index, color ->
                val active = index == draft.selected
                Row(Modifier.heightIn(min = 26.dp).border(1.dp, if (active) AutoScriptPalette.Accent else AutoScriptPalette.Border,
                    RoundedCornerShape(4.dp)).clickable(enabled = enabled) { onChange(draft.copy(selected = index, replaceOnPick = true)) }
                    .padding(horizontal = 5.dp, vertical = 3.dp).semantics { contentDescription = "前景色${index+1}"; selected = active },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(12.dp).background(color.parsed()?.let { Color(0xff000000 or it.rgb.toLong()) } ?: Color.Transparent)
                        .border(1.dp, AutoScriptPalette.Divider))
                    Text("${index+1}", fontSize = 10.sp, color = if (active) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                }
            }
            ImageToolCompactAction("+颜色", enabled && draft.colors.size < ProjectGlyphColor.MAX_COLORS) { onChange(draft.add()) }
            ImageToolCompactAction("删除颜色", enabled && draft.colors.size > 1) { onChange(draft.remove()) }
        }
        StudioCompactGrid(listOf("颜色 RGB", "容差"), cellDp = 148, maximum = 2) { label, cell ->
            if (label == "容差") ImageToolCompactInput(label, draft.current.tolerance, enabled, cell, numeric = true) { onChange(draft.edit(tolerance = it.take(3))) }
            else ImageToolCompactInput(label, draft.current.rgb, enabled, cell) { onChange(draft.edit(rgb = it.take(7))) }
        }
        if (draft.parsed() == null) Text("每色需6位RGB、容差0–255，颜色不能重复；请补全或删除未完成项。", fontSize = 10.sp, color = AutoScriptPalette.Danger)
    }
}

internal fun glyphPaletteFromInputs(primary: String, tolerance: String, additional: String): GlyphPaletteDraft {
    val rgb = when {
        primary.startsWith("#") -> primary.drop(1).toInt(16)
        primary.startsWith("0x", true) -> primary.drop(2).toInt(16)
        else -> primary.toInt()
    }
    return GlyphPaletteDraft.from(rgb, tolerance.toInt(), additional)
}

@Composable
internal fun GlyphColorPaletteDialog(
    initial: GlyphPaletteDraft,
    onDismiss: () -> Unit,
    title: String = "字库前景色组",
    description: String = "最多16色，各自容差，命中任意颜色即为文字笔画。保存后才回填，不修改字库文件。",
    currentDraft: GlyphPaletteDraft? = null,
    onDraftChange: ((GlyphPaletteDraft) -> Unit)? = null,
    onPickFromScreen: (() -> Unit)? = null,
    onApply: (List<ProjectGlyphColor>) -> Unit,
) {
    var localDraft by remember { mutableStateOf(initial) }
    val draft = currentDraft ?: localDraft
    var discard by remember { mutableStateOf(false) }
    fun close() { if (draft.colors != initial.colors) discard = true else onDismiss() }
    StudioPanelDialog(title, ::close, confirmLabel = "保存色组", confirmEnabled = draft.parsed() != null,
        onConfirm = { draft.parsed()?.let(onApply) }, compactHeader = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphColorPaletteEditor(draft, true) { localDraft = it; onDraftChange?.invoke(it) }
            onPickFromScreen?.let { pick -> ImageToolCompactAction("屏幕取色 · 替换第${draft.selected + 1}色", true, onClick = pick) }
            Text(description, fontSize = 10.sp,
                color = AutoScriptPalette.TextSecondary)
        }
    }
    if (discard) EditorPromptDialog("放弃色组修改？", "尚未保存的颜色与容差将丢弃。", { discard = false },
        { discard = false; onDismiss() }, "继续编辑", "放弃")
}
