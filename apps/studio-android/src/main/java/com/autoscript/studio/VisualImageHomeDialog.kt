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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

internal data class ImageEntryAction(val title: String, val kind: String)
internal val imageEntryGroups = listOf(
    "找图" to listOf(ImageEntryAction("区域找图", "vision.findimage"), ImageEntryAction("灰度找图", "vision.findgray"),
        ImageEntryAction("多结果找图", "vision.findallimages"), ImageEntryAction("读取结果", "vision.result")),
    "颜色" to listOf(ImageEntryAction("找色", "vision.findcolor"), ImageEntryAction("多点找色", "vision.findmulticolor"),
        ImageEntryAction("取色", "vision.getcolor"), ImageEntryAction("比色", "vision.comparecolor"),
        ImageEntryAction("颜色数量", "vision.countcolor"), ImageEntryAction("多结果找色", "vision.findallcolor")),
    "文字" to listOf(ImageEntryAction("字库识字", "ocr.glyph"), ImageEntryAction("字母 / 数字", "ocr.alphanumeric")),
)

@Composable
internal fun VisualImageHomeDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit,
    onCapture: (() -> Unit)?, onLibrary: (() -> Unit)?, onTest: (() -> Unit)?) {
    StudioPanelDialog("图像", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("截图 / 取图" to onCapture, "图片库" to onLibrary, "识别测试" to onTest).forEach { (label, callback) ->
                    ImageEntryButton(label, callback != null, Modifier.weight(1f), accent = true) { callback?.invoke() }
                }
            }
            imageEntryGroups.forEach { (title, actions) ->
                Text(title, color = AutoScriptPalette.Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                actions.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { action -> ImageEntryButton(action.title, true, Modifier.weight(1f)) { onCreate(action.kind) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageEntryButton(label: String, enabled: Boolean, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.height(36.dp).background(if (accent) AutoScriptPalette.AccentSoft else AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, AutoScriptPalette.Divider, shape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
