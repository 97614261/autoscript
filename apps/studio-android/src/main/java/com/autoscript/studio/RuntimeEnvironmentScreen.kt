package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState

/**
 * `activity_runtime_environment.xml` + `item_runtime_status.xml`：64dp 蓝头、20dp 圆角运行模式卡
 * （42dp 图标框、88×38 “重启服务”、灰组白块的模式选项）、15sp 分组标题、58dp 状态行（8dp 圆点 + 13sp 标签 + 12sp 右值）。
 * R0 只实现 Root 后端：系统录屏 / 无障碍两项保留位置但明确禁用，不伪装成可切换。
 */
@Composable
internal fun RuntimeEnvironmentScreen(
    state: RuntimeConnectionState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val connected = state.phase == RuntimeConnectionPhase.CONNECTED
    val rootReady = state.rootState == RuntimeRootState.READY
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AutoScriptPalette.PageBackground),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Surface(color = AutoScriptPalette.Accent) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.visual_back_24),
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp).clickable(onClick = onBack).padding(12.dp),
                    )
                    Text(
                        "运行环境",
                        modifier = Modifier.weight(1f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, end = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                border = BorderStroke(1.dp, AutoScriptPalette.Border),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_memory_24), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            "运行模式",
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AutoScriptPalette.TextPrimary,
                        )
                        // bg_runtime_restart_button：88×38，浅蓝底 + 蓝描边 + 12sp 加粗。
                        // 描边不能省：参考截图里这颗按钮是有轮廓的，只填底色会显得比参考“平”。
                        Text(
                            "重启服务",
                            color = AutoScriptPalette.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .size(width = 88.dp, height = 38.dp)
                                .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(19.dp))
                                .border(1.dp, AutoScriptPalette.Accent, RoundedCornerShape(19.dp))
                                .clickable(onClick = onRefresh)
                                .padding(top = 11.dp),
                        )
                    }
                    RuntimeSectionLabel("截屏权限", "Root 截屏走 screencap 与 Root 守护进程；系统录屏后端未实现，当前不可选")
                    RuntimeModeChoice("系统录屏", "Root", rightSelected = true, leftEnabled = false)
                    // activity_runtime_environment：第二组标题 marginTop 是 20，第一组是 22。
                    RuntimeSectionLabel("按键权限", "Root 权限启动特权按键服务；无障碍后端未实现，当前不可选", topPadding = 20.dp)
                    RuntimeModeChoice("无障碍", "Root", rightSelected = true, leftEnabled = false)
                }
            }
        }
        item { RuntimeGroupTitle("服务与权限") }
        item {
            RuntimeCard {
                RuntimeStatusRow("设备 Root 状态", if (rootReady) "已开启" else rootStateLabel(state.rootState), rootReady)
                RuntimeStatusRow("截屏服务", if (rootReady) "Root screencap · 已连接" else "Root 未就绪，截屏未连接", rootReady)
                RuntimeStatusRow("按键服务", if (rootReady) "Root 输入 · 已连接" else "Root 未就绪，按键未连接", rootReady)
                RuntimeStatusRow("Runner 服务", phaseLabel(state.phase), connected)
                RuntimeStatusRow("执行引擎", engineLabel(state.engineState), state.message == null, last = true)
                state.message?.let {
                    Text(
                        it,
                        color = AutoScriptPalette.Danger,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
        item { RuntimeGroupTitle("设备信息") }
        item {
            RuntimeCard {
                RuntimeStatusRow("协议版本", state.protocolVersion?.let { "AIDL v$it" } ?: "未连接", state.protocolVersion != null)
                RuntimeStatusRow("执行后端", "Root（当前阶段唯一后端）", true)
                RuntimeStatusRow("免 Root 后端", "Shizuku / 无障碍预留，未开放", false, last = true)
            }
        }
    }
}

@Composable
private fun RuntimeSectionLabel(title: String, detail: String, topPadding: Dp = 22.dp) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.TextPrimary, modifier = Modifier.padding(top = topPadding))
    Text(detail, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(top = 3.dp))
}

/** 15sp 加粗，20dp 左右边距，24dp 上距。 */
@Composable
private fun RuntimeGroupTitle(title: String) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = AutoScriptPalette.TextPrimary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp),
    )
}

@Composable
private fun RuntimeCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, AutoScriptPalette.Border),
    ) {
        Column { content() }
    }
}

/** `bg_runtime_mode_group`：灰组 4dp 内边距，选中项白块带阴影。 */
@Composable
private fun RuntimeModeChoice(left: String, right: String, rightSelected: Boolean, leftEnabled: Boolean) {
    Surface(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFFF3F6FA)) {
        Row(modifier = Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RuntimeModeButton(left, selected = !rightSelected, enabled = leftEnabled, Modifier.weight(1f))
            RuntimeModeButton(right, selected = rightSelected, enabled = true, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RuntimeModeButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color.White else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Text(
            if (enabled) label else "$label（未开放）",
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
            color = when {
                selected -> AutoScriptPalette.Accent
                enabled -> Color(0xFF667085)
                else -> Color(0xFFA0A8B8)
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** item_runtime_status.xml：58dp，8dp 圆点，13sp 标签，12sp 右值最多两行。 */
@Composable
private fun RuntimeStatusRow(label: String, value: String, healthy: Boolean, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (healthy) AutoScriptPalette.Mint else AutoScriptPalette.Danger, CircleShape))
        Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp), fontSize = 13.sp, color = AutoScriptPalette.TextPrimary)
        Text(
            value,
            fontSize = 12.sp,
            color = AutoScriptPalette.TextSecondary,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 190.dp),
        )
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(1.dp).background(AutoScriptPalette.Border))
}

private fun rootStateLabel(state: RuntimeRootState): String = when (state) {
    RuntimeRootState.READY -> "已开启"
    RuntimeRootState.STOPPED -> "未开启"
    RuntimeRootState.STARTING -> "启动中"
    RuntimeRootState.FAILED -> "授权失败"
    RuntimeRootState.UNKNOWN -> "未知"
}

private fun phaseLabel(phase: RuntimeConnectionPhase): String = when (phase) {
    RuntimeConnectionPhase.CONNECTED -> "已连接"
    RuntimeConnectionPhase.CONNECTING -> "连接中"
    RuntimeConnectionPhase.DISCONNECTED -> "未连接"
    RuntimeConnectionPhase.ERROR -> "连接失败"
}

private fun engineLabel(engine: RuntimeEngineState): String = when (engine) {
    RuntimeEngineState.IDLE, RuntimeEngineState.STOPPED -> "空闲"
    RuntimeEngineState.RUNNING -> "运行中"
    RuntimeEngineState.PAUSED -> "已暂停"
    RuntimeEngineState.STOPPING -> "停止中"
    RuntimeEngineState.FAILED -> "失败"
    RuntimeEngineState.UNKNOWN -> "未知"
}
