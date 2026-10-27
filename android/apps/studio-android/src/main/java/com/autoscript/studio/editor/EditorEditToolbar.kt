package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

internal enum class EditorEditTool(val title: String) {
    PARAMETERS("参数"), BATCH_PARAMETERS("批量参数"), ALIAS("别名"), RANDOMIZATION("随机"), COPY("复制"), PASTE("粘贴"),
    UNDO("撤销"), REDO("重做"), CUT("剪切"), DELETE("删除"),
    ANNOTATE("添加说明"), TOGGLE_DISABLED("注释执行"), REUSE("以此为模板"),
    MOVE_UP("上移"), MOVE_DOWN("下移"), OUTDENT("减少缩进"), INDENT("增加缩进"),
    NODE_SOURCE("本行源码"), PLUGIN_SOURCE("插件源码")
}

internal data class EditorEditButton(
    val tool: EditorEditTool,
    val enabled: Boolean,
    val title: String = tool.title,
    val onClick: () -> Unit,
)

internal fun editorEditColumns(widthDp: Float, fontScale: Float): Int =
    ((widthDp + 3f) / (60f * fontScale.coerceIn(1f, 2f) + 3f)).toInt().coerceIn(2, 9)

@Composable
internal fun EditorEditToolbar(buttons: List<EditorEditButton>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground)) {
        val columns = editorEditColumns(maxWidth.value - 12f, LocalConfiguration.current.fontScale)
        // All operations stay visible in one grid; only narrow/large-font windows need scrolling.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            buttons.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { button -> EditorEditActionButton(button, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun EditorEditSelectionButton(title: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.clip(shape).background(AutoScriptPalette.AccentSoft)
        .clickable(enabled = enabled, onClick = onClick).widthIn(min = 36.dp).heightIn(min = 24.dp)
        .padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(title, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary.copy(alpha = .5f),
            fontSize = 10.sp, maxLines = 1, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
private fun EditorEditActionButton(button: EditorEditButton, modifier: Modifier = Modifier) {
    val primary = button.tool == EditorEditTool.PARAMETERS && button.enabled
    val color = when {
        !button.enabled -> AutoScriptPalette.TextSecondary.copy(alpha = .5f)
        primary -> Color.White
        button.tool == EditorEditTool.DELETE -> AutoScriptPalette.Danger
        else -> AutoScriptPalette.Accent
    }
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.clip(shape).background(if (primary) AutoScriptPalette.Accent else Color.White)
        .border(1.dp, if (primary) AutoScriptPalette.Accent else AutoScriptPalette.Divider, shape)
        .clickable(enabled = button.enabled, onClick = button.onClick).heightIn(min = 26.dp)
        .padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(button.title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
