package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

@Composable
internal fun RuntimeDebugDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(message) { mutableStateOf(false) }
    val hint = remember(message) { runtimeFailureHint(message) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("调试信息", fontWeight = FontWeight.Bold)
                Text("脚本运行失败", color = AutoScriptPalette.Danger, fontSize = 12.sp)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = hint,
                    color = AutoScriptPalette.TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF2F2), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                )
                Text(
                    "原始错误",
                    color = AutoScriptPalette.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                SelectionContainer {
                    Text(
                        text = message,
                        color = AutoScriptPalette.Danger,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message))
                    copied = true
                },
            ) { Text(if (copied) "已复制" else "复制错误") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

internal fun runtimeFailureHint(message: String): String = when {
    "CAPABILITY_DENIED" in message && "input.basic" in message ->
        "项目没有声明输入能力。打开代码编辑器顶部的“设置 → 项目设置”，在“脚本能力”中勾选 input.basic 并保存。"

    "CAPABILITY_DENIED" in message ->
        "项目缺少脚本所需能力。请根据原始错误中的能力名称，在“项目设置 → 脚本能力”中勾选并保存。"

    "ROOT_INPUT_FAILED" in message ->
        "Root 输入服务执行失败。请确认虚拟机已开启 Root，随后到“我的 → 运行环境”重启服务，再重新运行脚本。"

    "LUA_RUNTIME_ERROR" in message ->
        "Lua 脚本运行时出错。请根据堆栈中的脚本行号检查对应代码。"

    else -> "运行服务返回失败。请查看并复制下面的完整错误信息。"
}
