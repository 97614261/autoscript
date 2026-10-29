package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline

/*
 * 悬浮编辑器里通用的两种小弹窗，替代 Material 默认的 AlertDialog：
 *
 * - [EditorPromptDialog]   `service_tk_prompt_dialog.xml`：标题 + 正文 + 右对齐“取消 / 确定”；
 * - [EditorOptionDialog]   与悬浮面板“加入位置”同款：蓝标题 + 关闭图标、43dp 单选行、底部“取消 | 确定”。
 *
 * 参考的这类弹窗都是 `match_parent` 宽、根布局左右 10dp、2dp 圆角带阴影，不是居中的小卡片。
 */

/** Confirmation prompts share the current Studio shell and preserve caller actions. */
@Composable
internal fun EditorPromptDialog(
    title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit,
    cancelLabel: String = "取消", confirmLabel: String = "确定", confirmDanger: Boolean = false,
) {
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(cancelLabel) } },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) {
            Text(confirmLabel, color = if (confirmDanger) AutoScriptPalette.Danger else AutoScriptPalette.Accent)
        } })
}


/**
 * 单选弹窗：先选后确认，与悬浮面板“加入位置”同款（42dp 蓝标题 + 关闭图标、43dp 行、42dp 底栏）。
 * 默认选中第一项；`options` 为空时只显示“取消”。
 */
@Composable
internal fun <T> EditorOptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    onDismiss: () -> Unit,
    preview: (@Composable (T) -> Unit)? = null,
    onConfirm: (T) -> Unit,
) {
    var selectedIndex by remember(options) { mutableStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val availableHeight = maxHeight
            val controlHeight = studioCompactControlHeight(32, LocalDensity.current.fontScale).dp
            Surface(
                modifier = Modifier.width(studioDialogWidth(maxWidth.value, 360).dp).heightIn(max = availableHeight),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().height(controlHeight).padding(start = 14.dp, end = 5.dp),
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
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        options.getOrNull(selectedIndex)?.let { preview?.invoke(it.first) }
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
                                Modifier.fillMaxWidth().heightIn(min = controlHeight).padding(vertical = 5.dp).clickable { selectedIndex = index }.padding(horizontal = 15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, color = Color(0xFF20242C), fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (selectedIndex == index) "◉" else "○",
                                    color = AutoScriptPalette.DialogTitle,
                                    fontSize = 20.sp,
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(hairline()).background(AutoScriptPalette.Divider))
                    Row(Modifier.fillMaxWidth().height(controlHeight)) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                            Text("取消", color = Color(0xFF20242C), fontSize = 14.sp)
                        }
                        if (options.isNotEmpty()) {
                            Box(Modifier.width(hairline()).fillMaxHeight().background(AutoScriptPalette.Divider))
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .clickable { options.getOrNull(selectedIndex)?.let { onConfirm(it.first) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("确定", color = AutoScriptPalette.DialogTitle, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
