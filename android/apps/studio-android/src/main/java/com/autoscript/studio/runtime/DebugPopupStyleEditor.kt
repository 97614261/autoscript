package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.PopupStyle

@Composable
internal fun DebugPopupStyleEditor(draft: PopupStyleDraft, initialStyle: PopupStyle, onChange: (PopupStyleDraft) -> Unit) {
    var helpVisible by remember { mutableStateOf(false) }
    val preview = draft.preview(initialStyle)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("样式预览 · px", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.weight(1f))
            Text(if (helpVisible) "收起说明" else "说明", fontSize = 10.sp, color = AutoScriptPalette.Accent,
                modifier = Modifier.fillMaxHeight().clickable { helpVisible = !helpVisible }
                    .padding(horizontal = 8.dp).wrapContentSize(Alignment.Center))
        }
        PopupStylePreview(preview)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (helpVisible) Text(
                "尺寸、坐标、字号和圆角为屏幕像素。X/Y=-1分轴居中，0为屏幕原点；预览只展示样式，超出时等比缩小。" +
                    "运行提示结束关闭，短时提示到时消失，停止均关闭。颜色格式为#AARRGGBB。",
                fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PopupStyleNumberInput("宽 px", "width", draft, Modifier.weight(1f), onChange = onChange)
                PopupStyleNumberInput("高 px", "height", draft, Modifier.weight(1f), onChange = onChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PopupStyleNumberInput("X px", "x", draft, Modifier.weight(1f), center = true, onChange = onChange)
                PopupStyleNumberInput("Y px", "y", draft, Modifier.weight(1f), center = true, onChange = onChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PopupStyleNumberInput("字号 px", "font", draft, Modifier.weight(1f), onChange = onChange)
                PopupStyleNumberInput("圆角 px", "corner", draft, Modifier.weight(1f), onChange = onChange)
            }
            PopupStyleNumberInput("时长 ms", "duration", draft, Modifier.fillMaxWidth(), onChange = onChange)
            PopupStyleColorInput("背景色", "background", draft, preview.backgroundColor, onChange)
            PopupStyleColorInput("文字色", "text", draft, preview.textColor, onChange)
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("对齐", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(40.dp))
                listOf("left" to "居左", "center" to "居中", "right" to "居右").forEach { (value, title) ->
                    Box(Modifier.weight(1f).height(28.dp)
                        .background(if (draft.textAlign == value) AutoScriptPalette.AccentSoft else AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp))
                        .clickable { onChange(draft.copy(textAlign = value)) }, contentAlignment = Alignment.Center) {
                        Text(title, fontSize = 11.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            color = if (draft.textAlign == value) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                    }
                }
            }
            Text("恢复默认样式", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                modifier = Modifier.fillMaxWidth().height(28.dp).clickable { onChange(PopupStyleDraft.from(PopupStyle())) }
                    .wrapContentSize(Alignment.Center), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PopupStylePreview(style: PopupStyle) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp)
        .background(AutoScriptPalette.PageBackground, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center) {
        val scale = popupPreviewScale(style.widthPx, style.heightPx,
            with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        val corner = RoundedCornerShape(with(density) { (style.cornerPx * scale).toDp() })
        Box(Modifier.width(with(density) { (style.widthPx * scale).toDp() })
            .height(with(density) { (style.heightPx * scale).toDp() })
            .clip(corner).background(Color(style.backgroundColor.toColorInt()))
            .padding(horizontal = with(density) { (minOf(12, style.widthPx / 12) * scale).toDp() }),
            contentAlignment = Alignment.Center) {
            Text("提示预览", color = Color(style.textColor.toColorInt()),
                fontSize = with(density) { (style.fontPx * scale).toSp() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                textAlign = when (style.textAlign) { "left" -> TextAlign.Start; "right" -> TextAlign.End; else -> TextAlign.Center },
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PopupStyleNumberInput(label: String, key: String, draft: PopupStyleDraft, modifier: Modifier,
    center: Boolean = false, onChange: (PopupStyleDraft) -> Unit) {
    Row(modifier.heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(40.dp), maxLines = 2)
        PopupStyleTextInput(draft.inputs[key].orEmpty(), draft.valid(key), Modifier.weight(1f), TextAlign.Center) {
            onChange(draft.edit(key, it.take(16)))
        }
        if (center) Text("居中", fontSize = 10.sp, color = AutoScriptPalette.Accent,
            modifier = Modifier.height(28.dp).clickable { onChange(draft.edit(key, "-1")) }
                .padding(start = 4.dp).wrapContentSize(Alignment.Center))
    }
}

@Composable
private fun PopupStyleColorInput(label: String, key: String, draft: PopupStyleDraft, previewColor: String,
    onChange: (PopupStyleDraft) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(40.dp))
        Box(Modifier.padding(end = 6.dp).size(20.dp).border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(3.dp))
            .background(Color(previewColor.toColorInt()), RoundedCornerShape(3.dp)))
        PopupStyleTextInput(draft.inputs[key].orEmpty(), draft.valid(key), Modifier.weight(1f), TextAlign.Start) {
            onChange(draft.edit(key, it.take(9)))
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("不透明度", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.width(52.dp))
        listOf(0, 25, 50, 75, 100).forEach { percent ->
            Box(Modifier.weight(1f).height(24.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(3.dp))
                .clickable(enabled = draft.valid(key)) { onChange(draft.withOpacity(key, percent)) }, contentAlignment = Alignment.Center) {
                Text("$percent%", fontSize = 10.sp, color = if (draft.valid(key)) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
            }
        }
    }
}

@Composable
private fun PopupStyleTextInput(value: String, valid: Boolean, modifier: Modifier, align: TextAlign, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, color = AutoScriptPalette.TextPrimary, textAlign = align,
            platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier.heightIn(min = 28.dp)
            .border(1.dp, if (valid) AutoScriptPalette.Border else AutoScriptPalette.Danger, RoundedCornerShape(4.dp))
            .background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 5.dp))
}
