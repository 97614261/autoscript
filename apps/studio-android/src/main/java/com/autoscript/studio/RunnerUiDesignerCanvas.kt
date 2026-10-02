package com.autoscript.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.ScriptUiGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun DesignerCanvas(draft: RunnerUiDesignerDraft, page: UiPage, selection: Set<String>, zoom: Float, snap: Boolean, enabled: Boolean, modifier: Modifier,
    onSelect: (String) -> Unit, onClear: () -> Unit, onGestureStart: (String) -> Unit, onGestureChange: (RunnerUiDesignerDraft) -> Unit,
    onGestureEnd: (RunnerUiDesignerDraft) -> Unit, directory: File? = null,
) {
    val currentDraft by rememberUpdatedState(draft)
    val currentSelection by rememberUpdatedState(selection)
    val density = LocalDensity.current.density
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    LaunchedEffect(page.id, zoom) { if (zoom == 1f) { horizontal.scrollTo(0); vertical.scrollTo(0) } }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFE8EDF5))) {
        val fit = ScriptUiGeometry.viewport(page.width, page.height, maxWidth.value - 16, maxHeight.value - 16).scale
        val scale = (fit * zoom).coerceAtLeast(.01f)
        val canvasWidth = page.width * scale
        val canvasHeight = page.height * scale
        val bodyWidth = maxOf(maxWidth.value, canvasWidth + 16)
        val bodyHeight = maxOf(maxHeight.value, canvasHeight + 16)
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical)) {
            Box(Modifier.requiredSize(bodyWidth.dp, bodyHeight.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(canvasWidth.dp, canvasHeight.dp).shadow(3.dp, RoundedCornerShape(3.dp)).background(designerColor(page.background))
                    .clickable(enabled = enabled, onClick = onClear).clipToBounds()) {
                    if (snap) Canvas(Modifier.fillMaxSize()) {
                        val step = (if (scale < .5f) 32 else 16) * scale * density
                        var x = step
                        while (x < size.width) { drawLine(DesignerColors.Line.copy(alpha = .5f), Offset(x,0f), Offset(x,size.height), strokeWidth = 1f); x += step }
                        var y = step
                        while (y < size.height) { drawLine(DesignerColors.Line.copy(alpha = .5f), Offset(0f,y), Offset(size.width,y), strokeWidth = 1f); y += step }
                    }
                    draft.paintOrder(page.id).forEach { f -> key(f.id) {
                        val (x, y) = draft.absolutePosition(f)
                        val active = f.id in selection
                        val previewWidth = (f.ui.width * scale).coerceAtLeast(1f)
                        val previewHeight = (f.ui.height * scale).coerceAtLeast(1f)
                        Box(Modifier.offset((x * scale).dp, (y * scale).dp).requiredSize(previewWidth.dp, previewHeight.dp)
                            .clickable(enabled) { onSelect(f.id) }
                            .pointerInput(f.id, scale, enabled, snap) {
                                var before: RunnerUiDesignerDraft? = null
                                var ids = emptySet<String>(); var dx = 0f; var dy = 0f
                                detectDragGestures(
                                    onDragStart = { if (enabled) { before = currentDraft; ids = if (f.id in currentSelection) currentSelection else setOf(f.id); dx = 0f; dy = 0f; onGestureStart(f.id) } },
                                    onDragEnd = { before?.let(onGestureEnd); before = null },
                                    onDragCancel = { before?.let(onGestureChange); before = null },
                                ) { event, delta ->
                                    before?.let { base -> event.consume(); dx += delta.x / density / scale; dy += delta.y / density / scale; onGestureChange(base.translate(ids, dx.roundToInt(), dy.roundToInt(), snap)) }
                                }
                            }) {
                            DesignerControlPreview(f, scale, directory)
                            if (active) Box(Modifier.matchParentSize().border(1.dp, DesignerColors.Accent))
                            if (!f.ui.visible) Text("隐", Modifier.align(Alignment.TopEnd).background(Color.White), color = DesignerColors.Muted, fontSize = 8.sp)
                            if (active && enabled) Box(Modifier.align(Alignment.BottomEnd).offset(5.dp,5.dp).size(20.dp)
                                .pointerInput(f.id, scale, snap) {
                                    var before: RunnerUiDesignerDraft? = null
                                    var dx = 0f; var dy = 0f
                                    detectDragGestures(onDragStart = { before = currentDraft; dx = 0f; dy = 0f }, onDragEnd = { before?.let(onGestureEnd); before = null }, onDragCancel = { before?.let(onGestureChange); before = null }) { event, delta ->
                                        before?.let { base ->
                                            event.consume(); dx += delta.x / density / scale; dy += delta.y / density / scale
                                            val index = base.fields.indexOfFirst { it.id == f.id }
                                            if (index >= 0) { val field = base.fields[index]
                                                fun size(value: Float, max: Int) = (if (snap) (value / 8).roundToInt() * 8 else value.roundToInt()).coerceIn(20, max)
                                                onGestureChange(base.update(index, field.copy(ui = field.ui.copy(width = size(field.ui.width + dx,4096), height = size(field.ui.height + dy,8192)))))
                                            }
                                        }
                                    }
                                }, contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(DesignerColors.Accent, RoundedCornerShape(2.dp)).border(1.dp, Color.White, RoundedCornerShape(2.dp))) }
                        }
                    } }
                    if (draft.fields.none { it.ui.pageId == page.id }) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("＋", color = DesignerColors.Accent, fontSize = 28.sp)
                        Text("从左侧添加控件", color = DesignerColors.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignerControlPreview(field: RunnerUiFieldDraft, scale: Float, directory: File?) {
    val p = field.ui
    val textColor = designerColor(p.textColor, DesignerColors.Ink).let { if (p.enabled && p.visible) it else it.copy(alpha = .45f) }
    // Geometry is in design pixels: do not inherit theme line height or enlarge with system font scale.
    val font = with(LocalDensity.current) { (p.fontPx * scale).dp.toSp() }
    val alignment = when (p.alignment) { "left" -> Alignment.CenterStart; "right" -> Alignment.CenterEnd; else -> Alignment.Center }
    val textAlign = when (p.alignment) { "left" -> TextAlign.Start; "right" -> TextAlign.End; else -> TextAlign.Center }
    val options = field.optionsText.split(',').map(String::trim).filter(String::isNotEmpty)
    val shape = RoundedCornerShape((6 * scale).dp)
    val framed = field.kind !in setOf(RunnerUiControlKind.LABEL, RunnerUiControlKind.IMAGE, RunnerUiControlKind.CONTAINER)
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = font, lineHeight = (font.value * 1.2f).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false))) {
        Box(Modifier.fillMaxSize().clip(shape).background(designerColor(p.background))
            .then(if (framed) Modifier.border(.6.dp, DesignerColors.Line, shape) else Modifier)
            .padding(horizontal = (4 * scale).dp), contentAlignment = alignment) {
            when (field.kind) {
                RunnerUiControlKind.CONTAINER -> Box(Modifier.fillMaxSize().border(.5.dp, DesignerColors.Muted.copy(alpha = .45f))) {
                    Text(field.label, fontSize = 8.sp, color = DesignerColors.Muted, modifier = Modifier.align(Alignment.TopStart))
                }
                RunnerUiControlKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size((18 * scale).dp).background(if (field.initialText == "true") DesignerColors.Accent else Color.Transparent, RoundedCornerShape(2.dp)).border(.6.dp, DesignerColors.Accent, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) { if (field.initialText == "true") Text("✓", fontSize = font, color = Color.White) }
                    Spacer(Modifier.width((4 * scale).dp)); Text(field.label, fontSize = font, color = textColor, maxLines = 1)
                }
                RunnerUiControlKind.CHOICE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.initialText.ifEmpty { field.label }, Modifier.weight(1f), fontSize = font, color = textColor, textAlign = textAlign, maxLines = 1)
                    Text("⌄", fontSize = font, color = DesignerColors.Accent)
                }
                RunnerUiControlKind.RADIO -> if (ScriptUiGeometry.radioHorizontal(p.height, p.fontPx, options.size)) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    options.forEach { option -> Text((if (option == field.initialText) "● " else "○ ") + option, Modifier.weight(1f), fontSize = font, color = textColor, maxLines = 1, textAlign = textAlign, overflow = TextOverflow.Ellipsis) }
                } else Column(Modifier.fillMaxWidth()) {
                    options.forEach { option -> Text((if (option == field.initialText) "● " else "○ ") + option, fontSize = font, color = textColor, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().height((maxOf(20f, p.fontPx * 1.8f) * scale).dp).wrapContentHeight(Alignment.CenterVertically), textAlign = textAlign) }
                }
                RunnerUiControlKind.LIST -> Column(Modifier.fillMaxWidth()) {
                    options.take(4).forEach { option -> Text((if (option == field.initialText) "● " else "○ ") + option, fontSize = font, color = textColor, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = textAlign) }
                }
                RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS -> Box(Modifier.fillMaxWidth().height((8 * scale).dp).background(DesignerColors.Line, RoundedCornerShape(8.dp))) {
                    val low = field.minimum.toFloatOrNull() ?: 0f; val high = field.maximum.toFloatOrNull() ?: 100f
                    val percent = ((field.initialText.toFloatOrNull() ?: low) - low) / (high - low).coerceAtLeast(1f)
                    Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(.01f,1f)).background(DesignerColors.Accent, RoundedCornerShape(8.dp)))
                }
                RunnerUiControlKind.IMAGE -> DesignerImage(directory, field.initialText, field.label, font, textColor)
                else -> Text(field.initialText.ifEmpty { field.label }, Modifier.fillMaxWidth(), fontSize = font, color = textColor, textAlign = textAlign, maxLines = if (field.kind == RunnerUiControlKind.TEXTAREA) 5 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DesignerImage(directory: File?, path: String, label: String, font: androidx.compose.ui.unit.TextUnit, color: Color) {
    val bitmap by produceState<ImageBitmap?>(null, directory, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = scriptUiImage(directory, path) ?: return@withContext null
                if (!file.isFile || file.length() > 16 * 1024 * 1024) return@withContext null
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, info)
                if (info.outWidth !in 1..4096 || info.outHeight !in 1..4096) return@withContext null
                var sample = 1
                while (maxOf(info.outWidth,info.outHeight) / sample > 128) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, label, Modifier.fillMaxSize()) } ?: Text("▧ ${path.substringAfterLast('/').ifEmpty { label }}", fontSize = font, color = color, maxLines = 1)
}
