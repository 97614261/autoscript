package com.autoscript.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun StudioColorInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    format: StudioColorFormat = StudioColorFormat.RGB_HEX, dense: Boolean = false, identity: Any? = null, onChange: (String) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (LocalImageWorkspaceStyling.current) ImageToolCompactInput(label, value, enabled, Modifier.weight(1f), dense = dense, onChange = onChange)
        else DesignerInput(label, value, enabled, Modifier.weight(1f), onChange = onChange)
        StudioColorButton(label, value, format, enabled, identity = identity, onChange = onChange)
    }
}

@Composable
internal fun StudioColorButton(label: String, value: String, format: StudioColorFormat, enabled: Boolean = true,
    modifier: Modifier = Modifier, identity: Any? = null, onChange: (String) -> Unit) {
    val owner = LocalDesignerInputOwner.current
    var open by remember(label, owner, identity, value, format, enabled) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val parsed = studioParseColor(value, format)
    val shape = RoundedCornerShape(5.dp)
    Box(modifier.size(32.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline, shape)
        .semantics { contentDescription = "$label · 点选颜色" }
        .clickable(enabled) { focus.clearFocus(); open = true }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            colorCheckerboard()
            if (parsed != null) drawRect(Color(parsed.toInt()))
            else drawLine(Color.Gray, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx())
        }
    }
    if (open) CompositionLocalProvider(LocalImageWorkspace provides null) {
        MaterialTheme(colorScheme = if (LocalImageWorkspaceStyling.current) MaterialTheme.colorScheme else lightColorScheme()) {
            StudioColorPicker(label, value, format, { open = false }) { selected -> open = false; onChange(selected) }
        }
    }
}

@Composable
private fun StudioColorPicker(label: String, initial: String, format: StudioColorFormat, onDismiss: () -> Unit,
    onConfirm: (String) -> Unit) {
    var color by remember(initial, format) { mutableStateOf(StudioColorSelection.from(studioParseColor(initial, format) ?: 0xFFFFFFFFL)) }
    var text by remember(initial, format) { mutableStateOf(initial) }
    fun select(next: StudioColorSelection) { color = next; text = studioFormatColor(next.argb(), format) }
    val parsed = studioParseColor(text, format)
    // This is an independent child dialog; the image workspace's canvas and draft remain registered.
    StudioColorPickerPanel("$label · 选颜色", onDismiss, parsed != null,
        onConfirm = { parsed?.let { onConfirm(studioFormatColor(it, format)) } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(5.dp))
                .semantics { contentDescription = "点选或拖动选择颜色的饱和度和明暗" }
                .colorPointer { x, y -> select(color.copy(saturation = x, brightness = 1f - y)) }) {
                val hue = Color(color.copy(saturation = 1f, brightness = 1f, alpha = 255).argb().toInt())
                drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val center = Offset(size.width * color.saturation, size.height * (1f - color.brightness))
                drawCircle(Color.Black, 6.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 6.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
            ColorPickerStrip("色相", color.hue / 360f, listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) {
                select(color.copy(hue = it * 359.999f))
            }
            if (format.alpha) {
                val opaque = Color((color.argb() or 0xFF000000L).toInt())
                ColorPickerStrip("不透明度 ${((color.alpha / 255f) * 100).roundToInt()}%", color.alpha / 255f,
                    listOf(opaque.copy(alpha = 0f), opaque), checkerboard = true) { select(color.copy(alpha = (it * 255).roundToInt())) }
            }
            val presets = listOf(0xFFFFFF, 0x000000, 0x808080, 0xFF4040, 0xFFB84D, 0xFFFF00, 0x21AF83, 0x00FFFF, 0x386AFF, 0x9933FF, 0xFF66CC, 0x27344A)
            presets.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { rgb ->
                        val argb = (color.alpha.toLong() shl 24) or rgb.toLong()
                        Canvas(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(4.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .semantics { contentDescription = "选择 #%06X".format(rgb) }
                            .clickable { select(StudioColorSelection.from(argb)) }) { drawRect(Color((0xFF000000L or rgb.toLong()).toInt())) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))) { colorCheckerboard(); drawRect(Color((parsed ?: color.argb()).toInt())) }
                ImageToolCompactInput(if (format == StudioColorFormat.ARGB_HEX || format == StudioColorFormat.RGB_OR_ARGB) "颜色 / #AARRGGBB" else "颜色 / RGB",
                    text, true, Modifier.weight(1f)) {
                    text = it
                    studioParseColor(it, format)?.let { value -> color = StudioColorSelection.from(value) }
                }
            }
            if (parsed == null) Text("点选颜色，或输入有效色值", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StudioColorPickerPanel(title: String, onDismiss: () -> Unit, enabled: Boolean, onConfirm: () -> Unit,
    content: @Composable () -> Unit) {
    if (LocalImageWorkspaceStyling.current) ImageWorkspacePanel(title, onDismiss, confirmLabel = "确定", showFooter = true,
        confirmEnabled = enabled, onConfirm = onConfirm, landscapeWidthDp = 360, content = content)
    else StudioPanelDialog(title, onDismiss, "确定", confirmEnabled = enabled, onConfirm = onConfirm,
        landscapeWidthDp = 360, content = content)
}

@Composable
private fun ColorPickerStrip(label: String, fraction: Float, colors: List<Color>, checkerboard: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, modifier = Modifier.width(74.dp))
        Canvas(Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = "$label · 点选或拖动" }.colorPointer { x, _ -> onChange(x) }) {
            if (checkerboard) colorCheckerboard()
            drawRect(Brush.horizontalGradient(colors))
            val x = size.width * fraction
            drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 4.dp.toPx())
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

@Composable
private fun Modifier.colorPointer(onChange: (Float, Float) -> Unit): Modifier {
    val change by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun select(position: Offset) = change((position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                (position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
            down.consume(); select(down.position)
            do {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (pointer.isConsumed) break
                select(pointer.position); pointer.consume()
            } while (pointer.pressed)
        }
    }
}

private fun DrawScope.colorCheckerboard() {
    drawRect(Color.White)
    val tile = 7.dp.toPx()
    for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt())
        if ((x + y) % 2 == 0) drawRect(Color(0xFFBBBBBB), Offset(x * tile, y * tile), Size(tile, tile))
}
