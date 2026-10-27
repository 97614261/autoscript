package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Owned above the tabs so unfinished typing and removal recovery survive page switches. */
internal class GlyphMakerPendingEditing(batch: GlyphMakerPendingBatch) {
    val selected = mutableIntStateOf(if (batch.samples.isEmpty()) -1 else 0)
    val character = mutableStateOf(batch.samples.firstOrNull()?.label.orEmpty())
    val bulk = mutableStateOf(if (batch.ready) batch.text else "")
    val removed = mutableStateOf<Pair<GlyphMakerPendingBatch, Int>?>(null)
    val shortcutSet = mutableStateOf(GlyphMakerCharacterSet.DIGITS)
}

@Composable
internal fun GlyphMakerPendingEditor(batch: GlyphMakerPendingBatch, target: String, existing: List<MakerGlyph>,
    editing: GlyphMakerPendingEditing, enabled: Boolean, onBatch: (GlyphMakerPendingBatch) -> Unit, onAppend: (Boolean) -> Unit,
    onDiscard: () -> Unit, onMessage: (String) -> Unit) {
    var selected by editing.selected
    var character by editing.character
    var bulk by editing.bulk
    var removed by editing.removed
    var shortcutSet by editing.shortcutSet
    val strip = rememberLazyListState()
    val bulkBaseline = if (batch.ready) batch.text else ""
    val bulkDirty = bulk != bulkBaseline
    val characterDirty = selected in batch.samples.indices && character != batch.samples[selected].label
    val canSwitch = enabled && !bulkDirty && !characterDirty
    LaunchedEffect(selected, batch.samples.size) {
        if (selected in batch.samples.indices) strip.animateScrollToItem(selected)
    }

    fun applyCharacter(value: String, advance: Boolean) {
        if (!enabled || bulkDirty) return
        try {
            val next = batch.label(selected, value)
            removed = null; bulk = if (next.ready) next.text else ""
            selected = if (advance) next.nextUnlabelled(selected) else -1
            character = next.samples.getOrNull(selected)?.label.orEmpty()
            onBatch(next)
            onMessage(if (next.ready) "本批已标注完成，核对字形后可追加" else "已标${next.labelledCount}/${next.samples.size}，继续标注下一个")
        } catch (invalid: IllegalArgumentException) { onMessage(invalid.message.orEmpty()) }
    }

    GlyphMakerSection("标注并追加", "已标${batch.labelledCount}/${batch.samples.size}") {
        if (batch.samples.isEmpty()) Text("本批字形已移除，可撤销移除或重新取样。", fontSize = 10.sp)
        else {
            LazyRow(Modifier.fillMaxWidth(), state = strip, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(batch.samples.size) { index ->
                    val glyph = batch.samples[index]
                    Column(Modifier.border(1.dp, if (selected == index) AutoScriptPalette.Accent else AutoScriptPalette.Border,
                        RoundedCornerShape(4.dp)).clickable(enabled = enabled) {
                        if (bulkDirty || characterDirty) onMessage("请先应用或恢复正在填写的标注")
                        else { selected = index; character = glyph.label }
                    }.padding(3.dp).semantics { contentDescription = "待标注字形${index + 1}" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        GlyphMask(glyph, Modifier.size(42.dp))
                        Text("${index + 1} · ${glyph.label.ifEmpty { "?" }}", fontSize = 10.sp)
                    }
                }
            }
            ImageToolCompactInput("顺序字符", bulk, enabled && !characterDirty, Modifier.fillMaxWidth()) { text ->
                bulk = text.take(512)
                if (glyphMakerLabelsReady(batch.samples.size, bulk)) {
                    val next = batch.labelAll(bulk)
                    removed = null; selected = -1; character = ""; onBatch(next)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (bulkDirty) "整行${glyphMakerLabelUnits(bulk).size}/${batch.samples.size}字 · 须逐字对应、不含空白" else "可粘贴整行，或点字形逐个标注",
                    fontSize = 10.sp, color = if (bulkDirty) AutoScriptPalette.Danger else AutoScriptPalette.TextSecondary,
                    modifier = Modifier.weight(1f))
                if (bulkDirty) ImageToolCompactAction("恢复输入", enabled) { bulk = bulkBaseline }
            }
            if (selected in batch.samples.indices) {
                ImageToolCompactInput("第${selected + 1}个", character, enabled && !bulkDirty, Modifier.fillMaxWidth()) {
                    character = it.take(32)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    GlyphMakerPrimaryAction("应用并下一个", enabled && !bulkDirty) { applyCharacter(character, true) }
                    ImageToolCompactAction("替换单字", enabled && !bulkDirty) { applyCharacter(character, false) }
                    ImageToolCompactAction("取消单字", enabled) { selected = -1; character = "" }
                    ImageToolCompactAction("移除此字形", canSwitch) {
                        val next = batch.remove(selected)
                        removed = batch to selected
                        selected = if (next.samples.isEmpty()) -1 else selected.coerceAtMost(next.samples.lastIndex)
                        character = next.samples.getOrNull(selected)?.label.orEmpty()
                        bulk = if (next.ready) next.text else ""
                        onBatch(next); onMessage("已移除误切字形，其他标注保留；可撤销移除")
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    listOf(GlyphMakerCharacterSet.DIGITS, GlyphMakerCharacterSet.SYMBOLS).forEach { group ->
                        ImageToolCompactAction(if (shortcutSet == group) "✓ ${group.title}" else group.title, enabled) { shortcutSet = group }
                    }
                    glyphMakerLabelUnits(shortcutSet.characters).forEach { value ->
                        val description = when (value) { "." -> "小数点"; "-" -> "负号"; "+" -> "正号"; "%" -> "百分号"; "(" -> "左括号"; ")" -> "右括号"; else -> "标注为$value" }
                        ImageToolCompactAction(value, enabled && !bulkDirty && !characterDirty,
                            Modifier.semantics { contentDescription = description }) { applyCharacter(value, true) }
                    }
                }
                Text("快捷字符点一下即标注并前进；先核对当前字形。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            } else if (!batch.ready) ImageToolCompactAction("继续逐字标注", canSwitch) {
                selected = batch.nextUnlabelled(-1); character = batch.samples.getOrNull(selected)?.label.orEmpty()
            }
            val shortcuts = glyphMakerLabelShortcuts(batch.samples.size, target, existing)
            if (shortcuts.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                shortcuts.forEach { (title, text) -> ImageToolCompactAction(title, canSwitch) {
                    val next = batch.labelAll(text)
                    removed = null; bulk = next.text; selected = -1; character = ""; onBatch(next)
                    onMessage("已按所选顺序填写，请核对字形后追加")
                } }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            GlyphMakerPrimaryAction("追加样本", canSwitch && batch.ready) { onAppend(false) }
            ImageToolCompactAction("追加并测试", canSwitch && batch.ready) { onAppend(true) }
            if (removed != null) ImageToolCompactAction("撤销移除", canSwitch) {
                val (restored, index) = requireNotNull(removed)
                removed = null; selected = index; character = restored.samples[index].label
                bulk = if (restored.ready) restored.text else ""; onBatch(restored)
                onMessage("已恢复误移除的字形和原标注")
            }
            ImageToolCompactAction("放弃待加入", enabled, onClick = onDiscard)
        }
    }
}
