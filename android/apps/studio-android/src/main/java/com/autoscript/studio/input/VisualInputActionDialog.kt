package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.runtime.api.RuntimeProtocol

/** Only expose actions already connected to the screenshot picker and real runtime contracts. */
internal enum class VisualInputAction(val mode: ImageToolMode, val title: String, val needsPointer: Boolean) {
    TAP(ImageToolMode.TAP, "单击", false),
    SWIPE(ImageToolMode.SWIPE, "滑动", false),
    LONG_PRESS(ImageToolMode.LONG_PRESS, "长按", true),
    DRAG(ImageToolMode.DRAG, "拖动", true),
    DOWN(ImageToolMode.POINTER_DOWN, "按下", true),
    MOVE(ImageToolMode.POINTER_MOVE, "移动", true),
    UP(ImageToolMode.POINTER_UP, "弹起", true),
}

internal fun inputActionUnavailableReason(action: VisualInputAction, canPickPoint: Boolean, features: Int?): String? = when {
    !canPickPoint -> "当前没有可用定位入口"
    features == null -> "正在读取 Root 输入能力"
    features and RuntimeProtocol.INPUT_FEATURE_BASIC == 0 -> "Root 输入未就绪"
    action.needsPointer && features and RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER == 0 -> "后端不支持持续触点"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VisualInputActionDialog(
    canPickPoint: Boolean,
    backendFeatures: Int?,
    onDismiss: () -> Unit,
    onPick: (ImageToolMode) -> Unit,
    onOpenPermissions: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onRecord: (() -> Unit)? = null,
) {
    val basicReason = inputActionUnavailableReason(VisualInputAction.TAP, canPickPoint, backendFeatures)
    val pointerReason = inputActionUnavailableReason(VisualInputAction.LONG_PRESS, canPickPoint, backendFeatures)
    StudioPanelDialog("触控", onDismiss, showFooter = false, landscapeWidthDp = 480) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val actions: List<VisualInputAction?> = VisualInputAction.entries + if (onRecord != null) listOf(null) else emptyList()
            StudioCompactGrid(actions, cellDp = 104, maximum = 4) { action, cell ->
                if (action == null) StudioToolAction("录制", R.drawable.ic_list_24, cell, dense = true) { onRecord?.invoke() }
                else {
                    val reason = inputActionUnavailableReason(action, canPickPoint, backendFeatures)
                    StudioToolAction(action.title, action.mode.icon, cell, enabled = reason == null, dense = true) {
                        if (inputActionUnavailableReason(action, canPickPoint, backendFeatures) == null) {
                            onPick(action.mode)
                        }
                    }
                }
            }
            if (basicReason != null || pointerReason != null) {
                Text(when {
                    backendFeatures == null && canPickPoint -> "正在读取输入能力…"
                    basicReason != null -> "$basicReason，请检查运行环境。"
                    else -> "持续触点暂不可用；单击和滑动仍可使用。"
                }, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onOpenPermissions?.let { ImageToolCompactAction("权限", true, onClick = it) }
                    onRefresh?.let { ImageToolCompactAction("重新连接", true, onClick = it) }
                }
            }
        }
    }
}
