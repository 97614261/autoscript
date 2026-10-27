package com.autoscript.studio

import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlin.math.floor
import kotlin.math.max

@Composable
internal fun GlyphMakerSection(title: String, detail: String = "", content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = AutoScriptPalette.Card, shape = RoundedCornerShape(7.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AutoScriptPalette.Border)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AutoScriptPalette.TextPrimary)
                if (detail.isNotEmpty()) Text(detail, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            }
            content()
        }
    }
}

/** Stable single-line tools: selected modes do not grow labels or push zoom onto another row. */
@Composable
internal fun GlyphMakerPreviewToolbar(mode: ImageToolMode, moving: Boolean, enabled: Boolean,
    onMode: (ImageToolMode) -> Unit, onMove: () -> Unit, onReset: () -> Unit, onFocus: () -> Unit,
    onFit: () -> Unit, onZoom: (Float) -> Unit, onActualSize: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .semantics { contentDescription = "取样工具栏" }, verticalAlignment = Alignment.CenterVertically) {
        GlyphMakerPreviewAction("框选", enabled, active = mode == ImageToolMode.CROP && !moving) { onMode(ImageToolMode.CROP) }
        GlyphMakerPreviewAction("取色", enabled, active = mode == ImageToolMode.COLOR && !moving) { onMode(ImageToolMode.COLOR) }
        GlyphMakerPreviewAction("移动", enabled, active = moving, onClick = onMove)
        GlyphMakerToolDivider()
        GlyphMakerPreviewAction("重选", enabled, onClick = onReset)
        GlyphMakerPreviewAction("选区", enabled, description = "看选区", onClick = onFocus)
        GlyphMakerPreviewAction("全图", enabled, onClick = onFit)
        GlyphMakerToolDivider()
        GlyphMakerPreviewAction("−", enabled, description = "缩小预览") { onZoom(.8f) }
        GlyphMakerPreviewAction("100%", enabled, onClick = onActualSize)
        GlyphMakerPreviewAction("＋", enabled, description = "放大预览") { onZoom(1.25f) }
    }
}

@Composable
private fun GlyphMakerToolDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(12.dp).background(AutoScriptPalette.Divider))
}

@Composable
private fun GlyphMakerPreviewAction(text: String, enabled: Boolean, active: Boolean = false,
    description: String = text, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TextButton(onClick, Modifier.defaultMinSize(minWidth = 26.dp).heightIn(min = 28.dp)
            .semantics { contentDescription = description; selected = active }, enabled = enabled,
            shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = if (active) AutoScriptPalette.Accent.copy(alpha = .10f) else Color.Transparent)) {
            Text(text, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
internal fun GlyphMakerPrimaryAction(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Button(onClick, modifier.heightIn(min = 28.dp), enabled = enabled, shape = RoundedCornerShape(5.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AutoScriptPalette.Accent)) {
            Text(text, fontSize = 11.sp)
        }
    }
}

/** Original-pixel brush. A drag commits only on pointer-up; cancellation restores the mask. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GlyphMakerPixelEditor(glyph: MakerGlyph, enabled: Boolean, modifier: Modifier = Modifier,
    onCommit: (MakerGlyph) -> Unit) {
    var brush by remember { mutableStateOf(GlyphMakerBrush.AUTO) }
    var magnification by remember { mutableFloatStateOf(1f) }
    var pan by remember(glyph.width, glyph.height, magnification) { mutableStateOf(Offset.Zero) }
    var painting by remember { mutableStateOf(false) }
    var redraw by remember { mutableIntStateOf(0) }
    val commit by rememberUpdatedState(onCommit)
    val bitmap = remember(glyph) {
        runCatching { createBitmap(glyph.width, glyph.height) }.getOrNull()
    }
    fun restore() {
        if (bitmap == null || bitmap.isRecycled) return
        val pixels = IntArray(glyph.width * glyph.height) { index ->
            if (glyph.foreground(index % glyph.width, index / glyph.width)) 0xff171923.toInt() else -1
        }
        bitmap.setPixels(pixels, 0, glyph.width, 0, 0, glyph.width, glyph.height)
        redraw++
    }
    DisposableEffect(bitmap) { restore(); onDispose { bitmap?.recycle() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(GlyphMakerBrush.AUTO to "修点", GlyphMakerBrush.INK to "画笔", GlyphMakerBrush.ERASE to "橡皮",
                GlyphMakerBrush.MOVE to "移动").forEach { (value, title) ->
                ImageToolCompactAction(if (brush == value) "✓ $title" else title, enabled && !painting) { brush = value }
            }
            ImageToolCompactAction("−", enabled && !painting && magnification > 1f,
                Modifier.semantics { contentDescription = "缩小字形" }) { magnification /= 2 }
            ImageToolCompactAction("＋", enabled && !painting && magnification < 8f,
                Modifier.semantics { contentDescription = "放大字形" }) { magnification *= 2 }
            ImageToolCompactAction("适应", enabled && !painting,
                Modifier.semantics { contentDescription = "适应字形" }) { magnification = 1f; pan = Offset.Zero }
        }
        if (bitmap == null) Text("修点预览内存不足，原样本保留", fontSize = 10.sp)
        else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Canvas(Modifier.fillMaxWidth().height(144.dp).clipToBounds().background(Color(0xffe8ecf3))
                .semantics { contentDescription = "修点字形" }.pointerInput(glyph, bitmap, enabled, brush, magnification) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabled) return@awaitEachGesture
                        val scale = glyphMakerMaskScale(glyph.width, glyph.height, size.width, size.height) * magnification
                        val base = Offset((size.width - glyph.width * scale) / 2, (size.height - glyph.height * scale) / 2)
                        fun pixel(point: Offset): Pair<Int, Int>? {
                            val x = floor((point.x - base.x - pan.x) / scale).toInt()
                            val y = floor((point.y - base.y - pan.y) / scale).toInt()
                            return (x to y).takeIf { x in 0 until glyph.width && y in 0 until glyph.height }
                        }
                        val first = pixel(down.position)
                        if (brush != GlyphMakerBrush.MOVE && first == null) return@awaitEachGesture
                        down.consume(); painting = true
                        val stroke = first?.takeIf { brush != GlyphMakerBrush.MOVE }?.let { GlyphMakerStroke(glyph, brush, it.first, it.second) }
                        fun paint(point: Pair<Int, Int>) {
                            stroke?.drawTo(point.first, point.second) { x, y, ink ->
                                if (!bitmap.isRecycled) bitmap[x, y] = if (ink) 0xff171923.toInt() else -1
                            }
                            redraw++
                        }
                        var accepted = false
                        try {
                            first?.let(::paint)
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size != 1) break // No accidental multi-touch edits.
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if (brush == GlyphMakerBrush.MOVE) {
                                    val delta = change.position - change.previousPosition
                                    val limitX = max(0f, (glyph.width * scale - size.width) / 2)
                                    val limitY = max(0f, (glyph.height * scale - size.height) / 2)
                                    pan = Offset((pan.x + delta.x).coerceIn(-limitX, limitX), (pan.y + delta.y).coerceIn(-limitY, limitY))
                                } else pixel(change.position)?.let(::paint)
                                change.consume()
                                if (!change.pressed) { accepted = true; break }
                            }
                            if (accepted && stroke != null) commit(stroke.result())
                        } finally {
                            // The parent validates the complete dictionary before accepting a stroke.
                            // Always redraw from its immutable source until the new glyph is published.
                            restore(); painting = false
                        }
                    }
                }) {
                @Suppress("UNUSED_VARIABLE") val invalidation = redraw
                val scale = glyphMakerMaskScale(glyph.width, glyph.height, size.width.toInt(), size.height.toInt()) * magnification
                val dx = (size.width - glyph.width * scale) / 2 + pan.x
                val dy = (size.height - glyph.height * scale) / 2 + pan.y
                drawImage(image, dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                    dstSize = IntSize(max(1, (glyph.width * scale).toInt()), max(1, (glyph.height * scale).toInt())), filterQuality = FilterQuality.None)
                if (scale >= 10) {
                    val grid = Color(0xff8b99b0).copy(alpha = .32f)
                    for (x in 0..glyph.width) drawLine(grid, Offset(dx + x * scale, dy), Offset(dx + x * scale, dy + glyph.height * scale))
                    for (y in 0..glyph.height) drawLine(grid, Offset(dx, dy + y * scale), Offset(dx + glyph.width * scale, dy + y * scale))
                }
            }
        }
        Text("${glyph.width}×${glyph.height}px · 放大${magnification.toInt()}倍 · 拖动修点，一笔一次撤销", fontSize = 10.sp,
            color = AutoScriptPalette.TextSecondary)
    }
}
