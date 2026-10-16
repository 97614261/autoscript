package com.autoscript.studio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class ShowcaseBlock(val title: String, val parameter: String, val depth: Int)
private val showcaseBlocks = listOf(
    ShowcaseBlock("循环", "场景检测", 0),
    ShowcaseBlock("查找图像", "目标.png", 1),
    ShowcaseBlock("判断找到", "匹配成功", 1),
    ShowcaseBlock("点击目标", "中心坐标", 2),
    ShowcaseBlock("等待", "200 ms", 1),
)

/** Fixed public illustration, not a project or an execution trace. */
@Composable
internal fun HomeBlockPlayback(frame: HomeDemoFrame, primary: Color, secondary: Color, compact: Boolean, motion: Boolean) {
    val first = homeDemoFirstVisibleLine(frame.activeLine, compact)
    val count = if (compact) 3 else showcaseBlocks.size
    val fontScale = LocalDensity.current.fontScale
    val rowHeight = homeBlockRowHeight(fontScale, compact).dp
    val activeOffset by animateFloatAsState((frame.activeLine - first).coerceAtLeast(0).toFloat(),
        if (motion) tween(190) else snap(), label = "home-demo-row")
    Box(Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(16.dp))
        .background(Color(0xA00C1021), RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 7.dp)
        .semantics { contentDescription = "可视化积木演示" }) {
        Canvas(Modifier.matchParentSize()) {
            val pitch = rowHeight.toPx()
            val rounded = CornerRadius(5.dp.toPx())
            for (local in 0 until count) {
                val index = first + local
                if (index < frame.activeLine || frame.completed) drawRoundRect(primary.copy(alpha = .035f),
                    Offset(0f, local * pitch), Size(size.width, pitch - 1.dp.toPx()), rounded)
            }
            if (frame.activeLine >= 0) {
                val top = activeOffset * pitch
                drawRoundRect(Brush.horizontalGradient(listOf(primary.copy(alpha = .25f), secondary.copy(alpha = .08f))),
                    Offset.Zero.copy(y = top), Size(size.width, pitch), rounded)
                drawRoundRect(secondary, Offset(0f, top + 4.dp.toPx()), Size(2.dp.toPx(), pitch - 8.dp.toPx()), rounded)
                val head = size.width * frame.lineProgress
                drawLine(secondary.copy(alpha = .6f), Offset(0f, top + pitch - 1.dp.toPx()),
                    Offset(head, top + pitch - 1.dp.toPx()), 1.dp.toPx())
                val cursor = Path().apply {
                    moveTo(6.dp.toPx(), top + pitch * .35f)
                    lineTo(10.dp.toPx(), top + pitch * .5f)
                    lineTo(6.dp.toPx(), top + pitch * .65f)
                    close()
                }
                drawPath(cursor, secondary)
            }
        }
        Column {
            for (index in first until first + count) {
                val block = showcaseBlocks[index]
                val current = index == frame.activeLine
                val passed = index < frame.activeLine || frame.completed
                Row(Modifier.fillMaxWidth().height(rowHeight).padding(start = 13.dp, end = 5.dp).semantics {
                    contentDescription = "演示积木第${index + 1}行"
                    stateDescription = if (current) "演示当前行" else if (passed) "演示已经过" else "演示未经过"
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text("0${index + 1}", color = if (current) secondary else Color(0xFF666A89), fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.width(23.dp * fontScale.coerceAtMost(2f)))
                    Canvas(Modifier.width((block.depth * 7 + 5).dp).height(rowHeight)) {
                        if (block.depth > 0) {
                            val x = (block.depth * 7 - 3).dp.toPx()
                            drawLine(primary.copy(alpha = .28f), Offset(x, 0f), Offset(x, size.height / 2), .7.dp.toPx())
                            drawLine(primary.copy(alpha = .28f), Offset(x, size.height / 2), Offset(size.width, size.height / 2), .7.dp.toPx())
                        }
                    }
                    Text(block.title, color = if (current) Color(0xFFF5F1FF) else Color(0xFFB3AEC8), fontSize = 10.sp, lineHeight = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (!compact) Text(block.parameter, color = if (current) secondary else Color(0xFF706E8C),
                        fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
