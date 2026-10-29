package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Manual edits reject invalid original-image coordinates instead of silently clamping them. */
internal fun imageToolPixelPoints(values: List<Pair<String, String>>, width: Int, height: Int): List<Pair<Int, Int>>? {
    if (width <= 0 || height <= 0 || values.isEmpty() || values.size > ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 1) return null
    return values.map { (x, y) ->
        val px = x.toIntOrNull() ?: return null
        val py = y.toIntOrNull() ?: return null
        if (px !in 0 until width || py !in 0 until height) return null
        px to py
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageToolPixelAdjustDialog(mode: ImageToolMode, width: Int, height: Int,
    box: ImageToolCodeGen.Roi?, points: List<Pair<Int, Int>>, onDismiss: () -> Unit,
    onApply: (ImageToolCodeGen.Roi?, List<Pair<Int, Int>>) -> Unit) {
    val originalFields = remember { listOf((box?.left ?: 0).toString(), (box?.top ?: 0).toString(),
        (box?.width ?: width).toString(), (box?.height ?: height).toString()) }
    val originalValues = remember { points.ifEmpty { listOf(width / 2 to height / 2) }.map { it.first.toString() to it.second.toString() } }
    var fields by remember { mutableStateOf(originalFields) }
    var values by remember { mutableStateOf(originalValues) }
    var discard by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    val rectangle = if (mode.usesDragBox) glyphMakerRegion(fields[0], fields[1], fields[2], fields[3], width, height) else null
    val parsed = if (mode.usesDragBox) emptyList() else imageToolPixelPoints(values, width, height)
    val valid = if (mode.usesDragBox) rectangle != null else parsed != null
    fun close() { if (if (mode.usesDragBox) fields != originalFields else values != originalValues) discard = true else onDismiss() }
    StudioPanelDialog("${mode.label} · 像素微调", ::close, confirmLabel = "应用", confirmEnabled = valid,
        onConfirm = { if (valid) onApply(rectangle, parsed.orEmpty()) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("原图 ${width}×${height}px · 修改后点击应用", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            if (mode.usesDragBox) {
                StudioCompactGrid(listOf("X", "Y", "宽", "高").withIndex().toList(), 130, 2, Modifier.fillMaxWidth()) { field, cell ->
                    ImageToolCompactInput(field.value, fields[field.index], true, cell, numeric = true) { text ->
                        fields = fields.toMutableList().also { it[field.index] = text.take(10) }
                    }
                }
            } else {
                if (values.size > 1) ImageToolChoice("点位", index.toString(), values.indices.map { i ->
                    i.toString() to if (mode.usesSlideMarkers) if (i == 0) "起点" else "终点" else "第 ${i + 1} 点"
                }, true) { index = it.toInt() }
                StudioCompactGrid(listOf(0, 1), 130, 2, Modifier.fillMaxWidth()) { axis, cell ->
                    ImageToolCompactInput(if (axis == 0) "X" else "Y", if (axis == 0) values[index].first else values[index].second,
                        true, cell, numeric = true) { text -> values = values.toMutableList().also {
                            it[index] = if (axis == 0) text.take(10) to it[index].second else it[index].first to text.take(10)
                        } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("左1px" to (-1 to 0), "右1px" to (1 to 0), "上1px" to (0 to -1), "下1px" to (0 to 1)).forEach { (label, delta) ->
                        ImageToolCompactAction(label, parsed != null) {
                            val point = parsed?.get(index)
                            if (point != null) values = values.toMutableList().also {
                                it[index] = (point.first + delta.first).coerceIn(0, width - 1).toString() to
                                    (point.second + delta.second).coerceIn(0, height - 1).toString()
                            }
                        }
                    }
                    if (mode == ImageToolMode.MULTI_COLOR && values.size > 1) ImageToolCompactAction("删除此点", true) {
                        values = values.filterIndexed { i, _ -> i != index }; index = index.coerceAtMost(values.lastIndex)
                    }
                }
            }
            if (!valid) Text("请输入原图范围内的整数坐标，宽高至少 1px。", fontSize = 10.sp, color = AutoScriptPalette.Danger)
            if (mode.usesPressPick) Text("应用时重新读取这些坐标的原图颜色。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
    if (discard) EditorPromptDialog("放弃像素微调？", "未应用的坐标修改会丢失，原选取保留。",
        { discard = false }, onDismiss, "继续编辑", "放弃修改")
}
