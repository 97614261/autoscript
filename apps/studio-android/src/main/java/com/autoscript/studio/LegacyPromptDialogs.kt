package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline

/*
 * 悬浮编辑器里通用的三种小弹窗，替代 Material 默认的 AlertDialog：
 *
 * - [LegacyPromptDialog]   `service_tk_prompt_dialog.xml`：标题 + 正文 + 右对齐“取消 / 确定”；
 * - [LegacyInputDialog]    `service_tk_new_file.xml`：标题 + 单行输入 + 左“取消”右“确定”；
 * - [LegacyOptionDialog]   与悬浮面板“加入位置”同款：蓝标题 + 关闭图标、43dp 单选行、底部“取消 | 确定”。
 *
 * 参考的这类弹窗都是 `match_parent` 宽、根布局左右 10dp、2dp 圆角带阴影，不是居中的小卡片。
 */

private val PromptTitle = Color(0xFF304FFE)
private val PromptAction = Color(0xFF2962FF)

/** `service_tk_prompt_dialog.xml`：20dp 上内边距、18sp 标题（左 25）、正文 25 边距、按钮 15/8 内边距。 */
@Composable
internal fun LegacyPromptDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelLabel: String = "取消",
    confirmLabel: String = "确定",
    confirmDanger: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(title, color = PromptTitle, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 25.dp))
                    Text(
                        text,
                        color = Color.Black,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 25.dp, top = 10.dp, end = 25.dp, bottom = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                        PromptTextButton(cancelLabel, onClick = onDismiss)
                        PromptTextButton(
                            confirmLabel,
                            color = if (confirmDanger) AutoScriptPalette.Danger else PromptAction,
                            modifier = Modifier.padding(end = 15.dp),
                            onClick = onConfirm,
                        )
                    }
                }
            }
        }
    }
}

/** `service_tk_new_file.xml`：18sp 黑标题（左 25）、15sp 输入框（30/10/30/5）、左“取消”、右“确定”。 */
@Composable
internal fun LegacyInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    hint: String = "",
    confirmLabel: String = "确定",
    confirmEnabled: Boolean = true,
    maxLength: Int = 30,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(title, color = Color.Black, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 25.dp))
                    BasicTextField(
                        value = value,
                        onValueChange = { onValueChange(it.take(maxLength)) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = AutoScriptPalette.TextPrimary),
                        cursorBrush = SolidColor(AutoScriptPalette.Accent),
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 30.dp, top = 10.dp, end = 30.dp, bottom = 5.dp)
                            .background(AutoScriptPalette.PageBackground, RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFFC8CED8), RoundedCornerShape(2.dp))
                            .padding(horizontal = 9.dp, vertical = 10.dp),
                        decorationBox = { field ->
                            Box {
                                if (value.isEmpty() && hint.isNotEmpty()) {
                                    Text(hint, color = AutoScriptPalette.TextSecondary, fontSize = 15.sp)
                                }
                                field()
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 15.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PromptTextButton("取消", color = AutoScriptPalette.DialogAction, onClick = onDismiss)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                            PromptTextButton(
                                confirmLabel,
                                color = AutoScriptPalette.DialogAction.copy(alpha = if (confirmEnabled) 1f else .4f),
                                modifier = Modifier.padding(end = 15.dp),
                                enabled = confirmEnabled,
                                onClick = onConfirm,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单选弹窗：先选后确认，与悬浮面板“加入位置”同款（42dp 蓝标题 + 关闭图标、43dp 行、42dp 底栏）。
 * 默认选中第一项；`options` 为空时只显示“取消”。
 */
@Composable
internal fun <T> LegacyOptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    onDismiss: () -> Unit,
    onConfirm: (T) -> Unit,
) {
    var selectedIndex by remember(options) { mutableStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(42.dp).padding(start = 14.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, color = AutoScriptPalette.DialogTitle, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(
                            painterResource(R.drawable.editor_close_24),
                            contentDescription = "关闭",
                            tint = AutoScriptPalette.DialogTitle,
                            modifier = Modifier.size(34.dp).clickable(onClick = onDismiss).padding(6.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                    if (options.isEmpty()) {
                        Text(
                            "没有可选项",
                            color = AutoScriptPalette.Muted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    }
                    options.forEachIndexed { index, (_, label) ->
                        Row(
                            Modifier.fillMaxWidth().height(43.dp).clickable { selectedIndex = index }.padding(horizontal = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, color = Color(0xFF20242C), fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (selectedIndex == index) "◉" else "○",
                                color = AutoScriptPalette.DialogTitle,
                                fontSize = 28.sp,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                    Row(Modifier.fillMaxWidth().height(42.dp)) {
                        Text(
                            "取消",
                            color = Color(0xFF20242C),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss).padding(top = 11.dp),
                        )
                        if (options.isNotEmpty()) {
                            Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.Divider))
                            Text(
                                "确定",
                                color = AutoScriptPalette.DialogTitle,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .clickable { options.getOrNull(selectedIndex)?.let { onConfirm(it.first) } }
                                    .padding(top = 11.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptTextButton(
    label: String,
    color: Color = PromptAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color,
        fontSize = 14.sp,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}
