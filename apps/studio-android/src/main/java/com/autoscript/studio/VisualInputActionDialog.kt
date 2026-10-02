package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.runtime.api.RuntimeProtocol

/** Only expose actions already connected to the screenshot picker and real runtime contracts. */
internal enum class VisualInputAction(val mode: ImageToolMode, val title: String, val detail: String, val needsPointer: Boolean) {
    TAP(ImageToolMode.TAP, "单击", "定位一个点击坐标", false),
    SWIPE(ImageToolMode.SWIPE, "滑动", "起点 → 终点", false),
    LONG_PRESS(ImageToolMode.LONG_PRESS, "长按", "按住后自动弹起", true),
    DRAG(ImageToolMode.DRAG, "拖动", "分段移动后弹起", true),
    DOWN(ImageToolMode.POINTER_DOWN, "按下", "保持单指，稍后弹起", true),
    MOVE(ImageToolMode.POINTER_MOVE, "移动", "移动已按下的单指", true),
    UP(ImageToolMode.POINTER_UP, "弹起", "释放本任务的单指", true),
}

internal fun inputActionUnavailableReason(action: VisualInputAction, canPickPoint: Boolean, features: Int?): String? = when {
    !canPickPoint -> "当前没有可用定位入口"
    features == null -> "正在读取 Root 输入能力"
    features and RuntimeProtocol.INPUT_FEATURE_BASIC == 0 -> "Root 输入未就绪"
    action.needsPointer && features and RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER == 0 -> "后端不支持持续触点"
    else -> null
}

@Composable
internal fun VisualInputActionDialog(
    canPickPoint: Boolean,
    backendFeatures: Int?,
    onDismiss: () -> Unit,
    onPick: (ImageToolMode) -> Unit,
    onFloatingPick: ((ImageToolMode) -> Unit)? = null,
) {
    var screenshot by remember { mutableStateOf(onFloatingPick == null) }
    val blue = AutoScriptPalette.Accent
    val muted = AutoScriptPalette.TextSecondary
    val basicReason = inputActionUnavailableReason(VisualInputAction.TAP, canPickPoint, backendFeatures)
    val pointerReason = inputActionUnavailableReason(VisualInputAction.LONG_PRESS, canPickPoint, backendFeatures)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight).align(Alignment.Center),
                color = Color.White, shape = RoundedCornerShape(5.dp), shadowElevation = 9.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("按键", color = blue, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (basicReason != null) "未就绪" else if (pointerReason == null) "Root · 单指" else "Root · 基础输入",
                            color = if (basicReason == null) blue else muted, fontSize = 10.sp,
                            modifier = Modifier.background(Color(0xFFF3F5F8), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                        InputPanelButton("×", onDismiss, Modifier.width(36.dp).fillMaxHeight())
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("定位方式", color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            if (onFloatingPick != null) InputPanelButton(if (!screenshot) "● 悬浮选点" else "悬浮选点", { screenshot = false }, Modifier.height(32.dp).padding(horizontal = 8.dp))
                            InputPanelButton(if (screenshot) "● 截图选点" else "截图选点", { screenshot = true }, Modifier.height(32.dp).padding(horizontal = 8.dp))
                        }
                        VisualInputAction.entries.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { action ->
                                    val reason = inputActionUnavailableReason(action, canPickPoint, backendFeatures)
                                    InputActionTile(action, reason, Modifier.weight(1f)) {
                                        // Recheck availability on activation as well as when drawing the card.
                                        if (inputActionUnavailableReason(action, canPickPoint, backendFeatures) == null) {
                                            if (!screenshot && onFloatingPick != null) onFloatingPick(action.mode) else onPick(action.mode)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(when {
                            basicReason != null -> "$basicReason，请确认 Runner 连接、Root 授权和输入后端。"
                            pointerReason != null -> "单击、滑动可用；其他动作需后端支持持续单指。"
                            else -> "定位不会触发目标触摸，加入插件并运行后才执行。"
                        }, color = muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    InputPanelButton("取消", onDismiss, Modifier.fillMaxWidth().height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun InputActionTile(action: VisualInputAction, reason: String?, modifier: Modifier, onClick: () -> Unit) {
    val enabled = reason == null
    val blue = AutoScriptPalette.Accent
    val shape = RoundedCornerShape(4.dp)
    Row(modifier.height(62.dp).background(if (enabled) Color(0xFFFAFBFD) else Color(0xFFF5F6F8), shape)
        .border(1.dp, AutoScriptPalette.Divider, shape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(if (enabled) blue.copy(alpha = .09f) else Color(0xFFE9ECF0), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(action.mode.icon), null, tint = if (enabled) blue else AutoScriptPalette.TextSecondary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(action.title, color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            Text(reason ?: action.detail, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
    }
}

@Composable
private fun InputPanelButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = AutoScriptPalette.TextPrimary, fontSize = 13.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
