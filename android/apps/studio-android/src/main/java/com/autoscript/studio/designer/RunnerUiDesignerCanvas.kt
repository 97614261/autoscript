package com.autoscript.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.ScriptUiGeometry
import com.autoscript.script.ui.ScriptUiControlMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun DesignerCanvas(draft: RunnerUiDesignerDraft, page: UiPage, selection: Set<String>, zoom: Float, snap: Boolean, enabled: Boolean, modifier: Modifier,
    onSelect: (String) -> Unit, onClear: () -> Unit, onGestureStart: (String) -> Unit, onGestureChange: (RunnerUiDesignerDraft) -> Unit,
    onGestureEnd: (RunnerUiDesignerDraft) -> Unit, directory: File? = null,
    boxSelect: Boolean = false, onBoxSelect: (Set<String>) -> Unit = {},
) {
    val currentDraft by rememberUpdatedState(draft)
    val currentSelection by rememberUpdatedState(selection)
    val density = LocalDensity.current.density
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(page.id, zoom) { if (zoom == 1f) { horizontal.scrollTo(0); vertical.scrollTo(0) } }
    BoxWithConstraints(modifier.focusRequester(focus).onPreviewKeyEvent { event ->
        val delta = when (event.key) { Key.DirectionLeft -> -1 to 0; Key.DirectionRight -> 1 to 0; Key.DirectionUp -> 0 to -1; Key.DirectionDown -> 0 to 1; else -> null }
        if (enabled && event.type == KeyEventType.KeyDown && currentSelection.isNotEmpty() && delta != null) {
            val before = currentDraft; val step = if (event.isShiftPressed) 8 else 1
            onGestureChange(before.translate(currentSelection, delta.first * step, delta.second * step)); onGestureEnd(before); true
        } else false
    }.focusable().clip(RoundedCornerShape(8.dp)).background(Color(0xFFE8EDF5))) {
        val fit = ScriptUiGeometry.viewport(page.width, page.height, maxWidth.value - 16, maxHeight.value - 16).scale
        val scale = (fit * zoom).coerceAtLeast(.01f)
        val canvasWidth = page.width * scale
        val canvasHeight = page.height * scale
        val bodyWidth = maxOf(maxWidth.value, canvasWidth + 16)
        val bodyHeight = maxOf(maxHeight.value, canvasHeight + 16)
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical)) {
            Box(Modifier.requiredSize(bodyWidth.dp, bodyHeight.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(canvasWidth.dp, canvasHeight.dp).shadow(3.dp, RoundedCornerShape(3.dp)).background(designerColor(page.background))
                    .clickable(enabled = enabled) { focus.requestFocus(); onClear() }.clipToBounds()) {
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
                        val contentClip = draft.previewClip(f)
                        Box(Modifier.offset((x * scale).dp, (y * scale).dp).requiredSize(previewWidth.dp, previewHeight.dp)
                            .clickable(enabled) { focus.requestFocus(); onSelect(f.id) }
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
                            Box(Modifier.fillMaxSize().clip(GenericShape { size, _ ->
                                addRect(Rect(contentClip.left * size.width / f.ui.width, contentClip.top * size.height / f.ui.height,
                                    contentClip.right * size.width / f.ui.width, contentClip.bottom * size.height / f.ui.height))
                            })) { DesignerControlPreview(f, scale, directory) }
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
                    if (selection.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                        draft.fields.filter { it.id in selection && it.ui.pageId == page.id }.forEach { f ->
                            val (x, y) = draft.absolutePosition(f)
                            val cx = (x + f.ui.width / 2f) * scale * density
                            val cy = (y + f.ui.height / 2f) * scale * density
                            drawLine(DesignerColors.Accent.copy(alpha = .35f), Offset(cx, 0f), Offset(cx, size.height), 1f)
                            drawLine(DesignerColors.Accent.copy(alpha = .35f), Offset(0f, cy), Offset(size.width, cy), 1f)
                        }
                    }
                    if (boxSelect && enabled) {
                        var start by remember { mutableStateOf<Offset?>(null) }
                        var end by remember { mutableStateOf(Offset.Zero) }
                        Canvas(Modifier.fillMaxSize().pointerInput(page.id, scale, enabled) {
                            detectDragGestures(onDragStart = { focus.requestFocus(); start = it; end = it }, onDragCancel = { start = null }, onDragEnd = {
                                start?.let { a -> onBoxSelect(currentDraft.boxSelection(page.id, a.x / density / scale, a.y / density / scale, end.x / density / scale, end.y / density / scale)) }; start = null
                            }) { change, delta -> change.consume(); end += delta }
                        }) {
                            start?.let { a ->
                                val origin = Offset(minOf(a.x, end.x), minOf(a.y, end.y))
                                val box = androidx.compose.ui.geometry.Size(kotlin.math.abs(end.x-a.x), kotlin.math.abs(end.y-a.y))
                                drawRect(DesignerColors.Accent.copy(alpha = .12f), origin, box)
                                drawRect(DesignerColors.Accent, origin, box, style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
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
    val options = runCatching { designerOptions(field.optionsText) }.getOrDefault(emptyList())
    val metrics = remember(p.fontPx, p.fontBold) { DesignerTextMetrics(field) }
    val widths = remember(options, metrics) { options.map(metrics::width) }
    val rows = remember(options, metrics, p.width) { options.mapIndexed { i, text -> maxOf(ScriptUiGeometry.contentRowHeight(p.fontPx, widths[i], p.width), metrics.rowHeight(text.take(128), p.width, 30)) } }
    val horizontalRadio = ScriptUiGeometry.radioHorizontal(p.width, p.height, p.fontPx, widths)
    val shape = RoundedCornerShape((p.cornerPx * scale).dp)
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = font, lineHeight = (font.value * 1.2f).sp,
        fontWeight = if (p.fontBold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
        textDecoration = if (p.textUnderline) androidx.compose.ui.text.style.TextDecoration.Underline else androidx.compose.ui.text.style.TextDecoration.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false))) {
        Box(Modifier.fillMaxSize().clip(shape).background(designerColor(p.background))
            .then(if (p.borderWidthPx > 0) Modifier.border((p.borderWidthPx * scale).dp, designerColor(p.borderColor), shape) else Modifier)
            .padding(horizontal = (if (field.kind in setOf(RunnerUiControlKind.SLIDER, RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT)) 0 else ScriptUiControlMetrics.INSET).times(scale).dp), contentAlignment = alignment) {
            when (field.kind) {
                RunnerUiControlKind.CONTAINER -> Box(Modifier.fillMaxSize().border(.5.dp, DesignerColors.Muted.copy(alpha = .45f))) {
                    Text(field.label, fontSize = 8.sp, color = DesignerColors.Muted, modifier = Modifier.align(Alignment.TopStart))
                }
                RunnerUiControlKind.SWITCH -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, Modifier.weight(1f), fontSize = font, color = textColor, textAlign = textAlign)
                    Spacer(Modifier.width((ScriptUiControlMetrics.INSET * scale).dp))
                    Box(Modifier.width((ScriptUiControlMetrics.SWITCH_WIDTH * scale).dp).height((ScriptUiControlMetrics.SWITCH_HEIGHT * scale).dp).background(if (field.initialText == "true") DesignerColors.Accent else DesignerColors.Line, RoundedCornerShape(50))) {
                        Box(Modifier.size((ScriptUiControlMetrics.SWITCH_THUMB * scale).dp).align(if (field.initialText == "true") Alignment.CenterEnd else Alignment.CenterStart).background(Color.White, RoundedCornerShape(50)))
                    }
                }
                RunnerUiControlKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size((ScriptUiControlMetrics.INDICATOR * scale).dp).background(if (field.initialText == "true") DesignerColors.Accent else Color.Transparent, RoundedCornerShape(2.dp)).border(.6.dp, DesignerColors.Accent, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) { if (field.initialText == "true") Text("✓", fontSize = font, color = Color.White) }
                    Spacer(Modifier.width((4 * scale).dp)); Text(field.label, Modifier.weight(1f), fontSize = font, color = textColor, textAlign = textAlign)
                }
                RunnerUiControlKind.CHOICE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.initialText.ifEmpty { field.label }, Modifier.weight(1f), fontSize = font, color = textColor, textAlign = textAlign, maxLines = 1)
                    Text("⌄", fontSize = font, color = DesignerColors.Accent)
                }
                RunnerUiControlKind.RADIO -> if (horizontalRadio) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    options.forEachIndexed { i, option -> Text((if (option == field.initialText) "● " else "○ ") + option, Modifier.width(((widths[i] + 26) * scale).dp), fontSize = font, color = textColor, maxLines = 1, textAlign = textAlign) }
                } else Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)) {
                    options.forEachIndexed { i, option -> Text((if (option == field.initialText) "● " else "○ ") + option, fontSize = font, color = textColor,
                        modifier = Modifier.fillMaxWidth().heightIn(min = (rows[i] * scale).dp).padding(vertical = (4 * scale).dp), textAlign = textAlign) }
                }
                RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT -> Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)) {
                    val selected = if (field.kind == RunnerUiControlKind.MULTISELECT) runCatching { com.autoscript.script.ui.uiSelections(field.initialText, options) }.getOrDefault(emptyList()) else listOf(field.initialText)
                    options.forEachIndexed { i, option -> Row(Modifier.fillMaxWidth().heightIn(min = (rows[i] * scale).dp).padding((ScriptUiControlMetrics.INSET * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option, Modifier.weight(1f), fontSize = font, color = textColor, textAlign = textAlign)
                        Spacer(Modifier.width((ScriptUiControlMetrics.INSET * scale).dp))
                        val indicatorShape = if (field.kind == RunnerUiControlKind.MULTISELECT) RoundedCornerShape((3 * scale).dp) else RoundedCornerShape(50)
                        Box(Modifier.size((ScriptUiControlMetrics.INDICATOR * scale).dp).background(if (option in selected) DesignerColors.Accent else Color.Transparent, indicatorShape)
                            .border((1 * scale).dp, DesignerColors.Accent, indicatorShape))
                    } }
                }
                RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS -> BoxWithConstraints(Modifier.fillMaxWidth().height(((if (field.kind == RunnerUiControlKind.SLIDER) ScriptUiControlMetrics.SLIDER_THUMB else ScriptUiControlMetrics.TRACK_HEIGHT) * scale).dp)) {
                    val low = field.minimum.toFloatOrNull() ?: 0f; val high = field.maximum.toFloatOrNull() ?: 100f
                    val percent = (((field.initialText.toFloatOrNull() ?: low) - low) / (high - low).coerceAtLeast(1f)).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                    val slider = field.kind == RunnerUiControlKind.SLIDER
                    Box(Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = (if (slider) ScriptUiControlMetrics.SLIDER_THUMB * scale / 2 else 0f).dp)
                        .height((ScriptUiControlMetrics.TRACK_HEIGHT * scale).dp).background(DesignerColors.Line, RoundedCornerShape(50))) {
                        if (percent > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(percent).background(DesignerColors.Accent, RoundedCornerShape(50)))
                    }
                    if (slider) {
                        val size = (ScriptUiControlMetrics.SLIDER_THUMB * scale).dp
                        Box(Modifier.offset(x = (maxWidth - size).coerceAtLeast(0.dp) * percent).size(size).align(Alignment.CenterStart).background(DesignerColors.Accent, RoundedCornerShape(50)))
                    }
                }
                RunnerUiControlKind.IMAGE -> DesignerImage(directory, field.initialText, field.label, font, textColor)
                else -> Text(if (field.kind == RunnerUiControlKind.PASSWORD) "•".repeat(field.initialText.length.coerceAtMost(16)).ifEmpty { field.label } else field.initialText.ifEmpty { field.label }, Modifier.fillMaxWidth(), fontSize = font, color = textColor, textAlign = textAlign, maxLines = if (field.kind in setOf(RunnerUiControlKind.TEXTAREA, RunnerUiControlKind.LABEL, RunnerUiControlKind.BUTTON, RunnerUiControlKind.NAVIGATION)) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
            if (field.kind in setOf(RunnerUiControlKind.RADIO, RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT) && !horizontalRadio && rows.sum() > p.height) {
                Box(Modifier.align(Alignment.CenterEnd).width((3 * scale).dp).fillMaxHeight().background(DesignerColors.Line)) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((p.height.toFloat() / rows.sum()).coerceIn(.05f, 1f)).background(DesignerColors.Accent))
                }
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
