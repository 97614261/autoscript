package com.autoscript.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun glyphMakerRegion(x: String, y: String, w: String, h: String, width: Int, height: Int): ImageToolCodeGen.Roi? {
    val left = x.toIntOrNull() ?: return null
    val top = y.toIntOrNull() ?: return null
    val rw = w.toIntOrNull() ?: return null
    val rh = h.toIntOrNull() ?: return null
    if (left < 0 || top < 0 || rw <= 0 || rh <= 0 || left.toLong() + rw > width || top.toLong() + rh > height) return null
    return ImageToolCodeGen.Roi(left, top, left + rw, top + rh)
}

internal fun glyphMakerMoveRegion(roi: ImageToolCodeGen.Roi, dx: Int, dy: Int, width: Int, height: Int): ImageToolCodeGen.Roi {
    val x = (roi.left.toLong() + dx).coerceIn(0, (width - roi.width).toLong()).toInt()
    val y = (roi.top.toLong() + dy).coerceIn(0, (height - roi.height).toLong()).toInt()
    return ImageToolCodeGen.Roi(x, y, x + roi.width, y + roi.height)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlyphMakerSelectionEditor(roi: ImageToolCodeGen.Roi, width: Int, height: Int, enabled: Boolean,
    onRegion: (ImageToolCodeGen.Roi) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var edge by remember { mutableIntStateOf(-1) }
    var x by remember(roi) { mutableStateOf(roi.left.toString()) }
    var y by remember(roi) { mutableStateOf(roi.top.toString()) }
    var w by remember(roi) { mutableStateOf(roi.width.toString()) }
    var h by remember(roi) { mutableStateOf(roi.height.toString()) }
    val draft = glyphMakerRegion(x, y, w, h, width, height)
    ImageToolCompactAction(if (expanded) "收起像素微调" else "像素微调", enabled) { expanded = !expanded }
    if (!expanded) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf("整框", "左边", "上边", "右边", "下边").forEachIndexed { i, title ->
            ImageToolCompactAction(if (edge == i - 1) "✓ $title" else title, enabled) { edge = i - 1 }
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val actions = if (edge < 0) listOf("左1px", "右1px", "上1px", "下1px") else listOf("边缘−1px", "边缘＋1px")
        actions.forEachIndexed { index, title ->
            ImageToolCompactAction(title, enabled) {
                if (edge < 0) onRegion(glyphMakerMoveRegion(roi, if (index < 2) if (index == 0) -1 else 1 else 0,
                    if (index >= 2) if (index == 2) -1 else 1 else 0, width, height))
                else {
                    val values = intArrayOf(roi.left, roi.top, roi.right, roi.bottom)
                    values[edge] += if (index == 0) -1 else 1
                    glyphMakerRegion(values[0].toString(), values[1].toString(), (values[2] - values[0]).toString(),
                        (values[3] - values[1]).toString(), width, height)?.let(onRegion)
                }
            }
        }
    }
    ImageToolNumberFields(listOf(Triple("x", "X", x), Triple("y", "Y", y), Triple("w", "宽", w), Triple("h", "高", h)), enabled) { key, value ->
        when (key) { "x" -> x = value.take(10); "y" -> y = value.take(10); "w" -> w = value.take(10); else -> h = value.take(10) }
    }
    if (draft == null) Text("范围需在原图内，宽高至少1px；原选区保留。", fontSize = 10.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ImageToolCompactAction("应用选区", enabled && draft != null) { draft?.let(onRegion) }
        Text("X${roi.left} Y${roi.top} · ${roi.width}×${roi.height}px", fontSize = 10.sp, modifier = Modifier.weight(1f))
    }
}
