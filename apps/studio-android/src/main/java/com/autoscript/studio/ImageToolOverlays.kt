package com.autoscript.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 十字准星，对应参考的 `#crosshair`。中空以免挡住目标像素。 */
@Composable
internal fun ImageToolCrosshair(position: Offset) {
    Canvas(Modifier.fillMaxSize()) {
        val arm = 26f
        val gap = 7f
        val colour = Color(0xFF00E5FF)
        // 四条臂中间留空，正中心那个像素必须看得见。
        drawLine(colour, Offset(position.x - arm, position.y), Offset(position.x - gap, position.y), 3f)
        drawLine(colour, Offset(position.x + gap, position.y), Offset(position.x + arm, position.y), 3f)
        drawLine(colour, Offset(position.x, position.y - arm), Offset(position.x, position.y - gap), 3f)
        drawLine(colour, Offset(position.x, position.y + gap), Offset(position.x, position.y + arm), 3f)
        drawCircle(colour, 22f, position, style = Stroke(width = 2f))
    }
}

/**
 * 11×11 像素放大镜，对应参考的 `#pixel-preview-box` + `GetPreviewImage(x, y, 11, 11, 1)`。
 *
 * 参考靠 WebView 注入的原生接口把区域渲染成图再回读；Compose 直接读 Bitmap 画格子即可。
 * 位置避让手指的算法在 [magnifierPlacement]，有单测。
 */
@Composable
internal fun BoxScope.ImageToolMagnifier(
    bitmap: Bitmap,
    viewport: ImageToolViewport,
    anchor: Offset,
) {
    val density = LocalDensity.current
    val boxPx = with(density) { MAGNIFIER_SIZE.toPx() }
    val centre = viewport.toImage(anchor.x, anchor.y) ?: return
    val samples = magnifierSamples(
        pixelAt = { x, y ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null
        },
        centerX = centre.first,
        centerY = centre.second,
    )
    val placement = magnifierPlacement(
        anchorX = anchor.x,
        anchorY = anchor.y,
        boxSize = boxPx,
        viewWidth = viewport.viewWidth,
        viewHeight = viewport.viewHeight,
        offset = with(density) { 40.dp.toPx() },
    )
    Box(
        Modifier
            .offset { IntOffset(placement.first.roundToInt(), placement.second.roundToInt()) }
            .size(MAGNIFIER_SIZE)
            .background(ToolChrome, RoundedCornerShape(8.dp))
            .padding(4.dp),
    ) {
        Canvas(Modifier.size(MAGNIFIER_SIZE - 8.dp)) {
            val cell = size.width / MAGNIFIER_GRID
            samples.forEachIndexed { index, argb ->
                val column = index % MAGNIFIER_GRID
                val row = index / MAGNIFIER_GRID
                val topLeft = Offset(column * cell, row * cell)
                if (argb == null) {
                    // 越界格留空，让用户看出自己正贴着边缘。
                    drawRect(Color(0xFF2A3040), topLeft, Size(cell, cell))
                } else {
                    drawRect(
                        Color(
                            red = (argb shr 16) and 0xFF,
                            green = (argb shr 8) and 0xFF,
                            blue = argb and 0xFF,
                        ),
                        topLeft,
                        Size(cell, cell),
                    )
                }
            }
            // 中心格描边：这一格才是准星取到的像素。
            val mid = MAGNIFIER_GRID / 2
            drawRect(
                Color(0xFF00E5FF),
                Offset(mid * cell, mid * cell),
                Size(cell, cell),
                style = Stroke(width = 2f),
            )
        }
    }
}

/** 顶部模式提示横幅，对应参考的 `#mode-hint-banner` + `showHint()`。 */
@Composable
internal fun BoxScope.ImageToolHintBanner(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.TopCenter)
            .padding(top = 56.dp)
            .background(Color(0xE6000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 缩放模式控制条，对应参考 `#controls` 的「退出缩放 / 原图」。 */
@Composable
internal fun ImageToolZoomControls(
    scale: Float,
    onReset: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("缩放 ${(scale * 100).roundToInt()}%", color = Color(0xE6FFFFFF), fontSize = 11.sp)
        ImageToolButton("原图", ButtonBlue, enabled = true, onClick = onReset)
        ImageToolButton("退出缩放", ButtonGrey, enabled = true, onClick = onExit)
    }
}

/** 底部状态条：坐标 + 颜色 + 选框尺寸，对应参考 `#tool-tooltip` 的实时信息。 */
@Composable
internal fun BoxScope.ImageToolStatusStrip(
    mode: ImageToolMode,
    zoomMode: Boolean,
    viewport: ImageToolViewport?,
    selection: ImageToolSelection,
    crosshair: Offset,
    mapping: ImageToolCodeGen.DesignMapping?,
) {
    if (viewport == null) return
    val text = when {
        zoomMode -> "缩放模式：单指拖动，双指捏合；完成后点「退出缩放」"
        mode.usesDragBox -> selection.box?.let { box ->
            val design = mapping?.toDesignRoi(box.left, box.top, box.right, box.bottom)
            if (design == null) {
                "X: ${box.left}, Y: ${box.top}, 宽: ${box.width}, 高: ${box.height}"
            } else {
                "X: ${design.left}, Y: ${design.top}, 宽: ${design.width}, 高: ${design.height}"
            }
        } ?: mode.hint
        // 取色/多点：显示最后一次抬手取到的点；还没取过就给操作提示。
        mode.usesPressPick -> selection.points.lastOrNull()?.let { point ->
            val design = mapping?.toDesign(point.x, point.y) ?: (point.x to point.y)
            "X: ${design.first}, Y: ${design.second}  ${ImageToolCodeGen.formatRgb(point.rgb)}" +
                (if (selection.points.size > 1) "   已选 ${selection.points.size} 点" else "")
        } ?: "按住图片拖动，抬手取点"
        else -> {
            val point = viewport.toImage(crosshair.x, crosshair.y)
            if (point == null) mode.hint else {
                val design = mapping?.toDesign(point.first, point.second) ?: point
                "X: ${design.first}, Y: ${design.second}"
            }
        }
    }
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.TopStart)
            .padding(start = 12.dp, top = 12.dp)
            .background(Color(0xCC000000), RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** 浮动工具条：可拖动的模式球 + 随模式变化的动作按钮，对应参考 `#mainToolContainer`。 */
@Composable
internal fun BoxScope.ImageToolBar(
    mode: ImageToolMode,
    zoomMode: Boolean,
    capturing: Boolean,
    hasImage: Boolean,
    ready: Boolean,
    selection: ImageToolSelection,
    swipeDuration: Int,
    tolerance: Int,
    offsetX: Float,
    offsetY: Float,
    onDrag: (Offset) -> Unit,
    onToggleMenu: () -> Unit,
    onZoom: () -> Unit,
    onCapture: () -> Unit,
    onClear: () -> Unit,
    onSwipeDuration: (Float) -> Unit,
    onTolerance: (Float) -> Unit,
    onConfirm: () -> Unit,
    onCropToTemplate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnToggleMenu by rememberUpdatedState(onToggleMenu)
    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .height(44.dp),
        shape = RoundedCornerShape(8.dp),
        color = ToolChrome,
        shadowElevation = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp)
                    .background(ButtonBlue)
                    .pointerInput(Unit) {
                        // 和易编精灵的悬浮条一致：点击/拖动由一套手势状态机判定。
                        // 否则 clickable 与 detectDragGestures 会抢事件，工具条会拖不稳或误开菜单。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touchSlop = viewConfiguration.touchSlop
                            var dragging = false
                            var previous = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val displacement = change.position - down.position
                                val distanceSquared = displacement.x * displacement.x + displacement.y * displacement.y
                                if (!dragging && distanceSquared > touchSlop * touchSlop) dragging = true
                                if (dragging) {
                                    val delta = change.position - previous
                                    if (delta != Offset.Zero) latestOnDrag(delta)
                                    previous = change.position
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    if (!dragging) latestOnToggleMenu()
                                    break
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(mode.icon), "切换模式", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Row(
                Modifier.padding(horizontal = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImageToolButton(if (capturing) "截图中" else "截图", ButtonBlue, !capturing, onCapture)
                if (hasImage && !zoomMode) {
                    ImageToolButton("缩放", ButtonGrey, true, onZoom)
                }
                when {
                    // 多点没有「取点」按钮：按住图片拖动、抬手就加一个点（参考 colorpicker 的取法）。
                    mode == ImageToolMode.MULTI_COLOR ->
                        ImageToolStepper("容差$tolerance") { onTolerance((tolerance + it).coerceIn(0, 255).toFloat()) }
                    mode == ImageToolMode.SWIPE ->
                        ImageToolStepper("${swipeDuration}ms") {
                            onSwipeDuration((swipeDuration + it * 100).coerceIn(100, 5_000).toFloat())
                        }
                    else -> Unit
                }
                if (selection.box != null || selection.points.isNotEmpty()) {
                    ImageToolButton("清除", ButtonGrey, true, onClear)
                }
                if (mode == ImageToolMode.CROP) {
                    ImageToolButton("存模板", ButtonGreen, ready, onCropToTemplate)
                } else {
                    ImageToolButton("确定", ButtonGreen, ready, onConfirm)
                }
            }
            Box(Modifier.size(38.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.editor_close_24),
                    "关闭图像工具",
                    tint = Color(0xFFB9C2D0),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** 模式菜单：两列网格，可用在前、预留在后并置灰。 */
@Composable
internal fun BoxScope.ImageToolMenu(
    current: ImageToolMode,
    onPick: (ImageToolMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = ToolChrome, shadowElevation = 12.dp) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ImageToolMode.menuOrder.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { entry -> ImageToolMenuItem(entry, entry == current, onPick) }
                    if (row.size == 1) Spacer(Modifier.size(56.dp))
                }
            }
        }
    }
}

@Composable
private fun ImageToolMenuItem(mode: ImageToolMode, selected: Boolean, onPick: (ImageToolMode) -> Unit) {
    val tint = when {
        !mode.enabled -> Color(0xFF5A6373)
        selected -> Color.White
        else -> Color(0xFFD5DCE8)
    }
    Column(
        Modifier.size(56.dp)
            .background(
                when {
                    !mode.enabled -> Color(0xFF262C38)
                    selected -> ButtonBlue
                    else -> Color(0xFF323A49)
                },
                RoundedCornerShape(6.dp),
            )
            .clickable { onPick(mode) }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(mode.icon), null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            if (mode.enabled) mode.label else "${mode.label}·预留",
            color = tint,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun ImageToolButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color(0xFF6B7484),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .height(28.dp)
            .background(if (enabled) color else Color(0xFF262C38), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp)
            // 固定高度配 padding(vertical) 会压扁内容框导致文字上下被裁，用 wrapContentHeight 居中。
            .wrapContentHeight(),
    )
}

@Composable
internal fun ImageToolStepper(label: String, onStep: (Int) -> Unit) {
    Row(
        Modifier.height(28.dp).background(Color(0xFF262C38), RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "−",
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp).clickable { onStep(-1) }.wrapContentHeight(),
        )
        Text(label, color = Color(0xFFD5DCE8), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 2.dp))
        Text(
            "+",
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp).clickable { onStep(1) }.wrapContentHeight(),
        )
    }
}

private val MAGNIFIER_SIZE = 132.dp
