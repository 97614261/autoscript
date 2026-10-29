package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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

    StudioConfirmDialog(
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
                        .padding(8.dp),
                )
                Text(
                    "原始错误",
                    color = AutoScriptPalette.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 7.dp, bottom = 4.dp),
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
                            .background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp))
                            .padding(8.dp),
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
    "ROOT_STOP_CLEANUP_FAILED" in message ->
        "停止时未收到认证的触点清理确认，已禁止继续输入。这不是正常停止；请重新连接 RootDaemon 后再运行，不要连续点击重试。"

    "SCREEN_CAPTURE_FAILED" in message -> when {
        "CAPTURE_CONTROL_UNAVAILABLE" in message -> "Root停止通道失效或安全清理失败，已取消执行并禁止继续截图；不是模板未找到。请重新运行以建立新会话，并查看运行诊断。"
        "CAPTURE_DEADLINE" in message -> "截图采集超过5秒上限，尚未进入找图。请检查模拟器是否卡顿、显示是否正在切换；重新运行后查看截图诊断。缩小找图区域不会解决截图采集超时。"
        "CAPTURE_CANCELLED" in message -> "截图请求已被停止或取消，不是未找到图片，也不是找图计算超时。"
        "CAPTURE_PROTOCOL" in message || "CAPTURE_CORRELATION" in message -> "截图传输的认证、数据格式或请求关联检查失败，已丢弃不完整画面。请重新运行以重建 Root 连接；若重复出现，请保留完整错误码。"
        "CAPTURE_TRANSPORT" in message || "CAPTURE_CONNECTION_CLOSED" in message -> "截图传输中断或 Root 连接已关闭，尚未进入找图。请复查 Root 就绪状态并重新运行。"
        "CAPTURE_INVALID_FRAME" in message || "CAPTURE_BUDGET" in message -> "截图数据尺寸、格式或资源上限检查失败，未使用不完整画面。请保留完整错误码和设备分辨率。"
        "CAPTURE_PROCESS_START" in message -> "快速截图不可用，且系统截图进程启动失败。请复查 Root 状态与系统截图支持。"
        "CAPTURE_PIPE_READ" in message || "CAPTURE_PROCESS_EXIT" in message -> "快速截图不可用，系统截图回退也读取失败或异常退出。请保留完整错误码，复查 Root 和设备显示状态。"
        else -> "截图阶段失败，尚未进入找图，不能判定为找图超时或未找到。请重新运行并保留完整错误码；旧日志不包含具体截图失败原因。"
    }

    "VISION_SEARCH_TIMEOUT" in message ->
        "找图超过5秒计算上限，搜索尚未完成，不等于未找到。请缩小搜索区域、减少一次搜索的模板数量，或裁剪更小且有辨识度的模板；调整后重新运行。"

    "VISION_COMPARISON_BUDGET_EXCEEDED" in message || "ComparisonBudgetExceeded" in message ->
        "找图超过像素比较预算，搜索尚未完成。请缩小搜索区域或减少模板数量；不会把超预算伪装成未找到。"

    "VISION_SEARCH_CANCELLED" in message ->
        "找图任务已被停止或取消，这与找图超时、未找到图片不同。"

    "CAPABILITY_DENIED" in message && "input.basic" in message ->
        "项目没有声明输入能力。打开可视化编辑器的“设置 → 项目设置”，在“脚本能力”中勾选 input.basic 并保存。"

    "CAPABILITY_DENIED" in message ->
        "项目缺少脚本所需能力。请根据原始错误中的能力名称，在“项目设置 → 脚本能力”中勾选并保存。"

    "ROOT_INPUT_FAILED" in message || "ROOT_NOT_READY" in message || "ROOT_DISCONNECTED" in message ->
        "Root 输入服务未就绪或执行失败。请在软件权限页复查 Root 就绪和实际输入特性，必要时重启运行服务；Runner 连接成功不代表 Root 已就绪。"

    "LUA_RUNTIME_ERROR" in message ->
        "脚本运行时出错。优先根据错误中的插件和积木定位修改；生成的 Lua 行号只用于诊断，不要直接修改构建产物。"

    else -> "运行服务返回失败。请查看并复制下面的完整错误信息。"
}
