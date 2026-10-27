package com.autoscript.studio

import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState

/** System authorization, backend readiness and actual capabilities are distinct. */
@Composable
internal fun RuntimeEnvironmentScreen(
    state: RuntimeConnectionState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShowDebug: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenVisionComponents: () -> Unit,
    inputFeatures: Int?,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val connected = state.phase == RuntimeConnectionPhase.CONNECTED
    val rootReady = connected && state.rootState == RuntimeRootState.READY
    val labels = studioRuntimeLabels(state)
    var permissionRevision by remember { mutableIntStateOf(0) }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AutoScriptPalette.PageBackground),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            Surface(color = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.visual_back_24),
                        contentDescription = "返回",
                        tint = AutoScriptPalette.TextPrimary,
                        modifier = Modifier.size(48.dp).clickable(onClick = onBack).padding(12.dp),
                    )
                    Text(
                        "权限与运行环境",
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AutoScriptPalette.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Box(Modifier.size(48.dp).clickable(onClick = onOpenPermissions), contentAlignment = Alignment.Center) {
                        Text("授权", color = AutoScriptPalette.Accent, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, AutoScriptPalette.Border),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_memory_24), contentDescription = null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            if (rootReady) "Root 后端已就绪" else "检查运行条件",
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AutoScriptPalette.TextPrimary,
                        )
                        Surface(
                            onClick = { permissionRevision++; onRefresh() },
                            shape = RoundedCornerShape(12.dp),
                            color = AutoScriptPalette.AccentSoft,
                            border = BorderStroke(1.dp, AutoScriptPalette.Accent),
                        ) {
                            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center) {
                                Text("刷新状态", color = AutoScriptPalette.Accent, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                            }
                        }
                    }
                    Text("当前仅支持 Root 自动化", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("连接" to labels.runner, "Root" to labels.root, "引擎" to labels.execution).forEach { (title, value) ->
                            Column(Modifier.weight(1f).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(8.dp)).padding(8.dp)) {
                                Text(title, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                                Text(value, fontSize = 12.sp, color = AutoScriptPalette.Accent, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        item { RuntimeGroupTitle("系统授权") }
        item { EnvironmentSystemPermissions(permissionRevision, onOpenPermissions) }
        item { RuntimeGroupTitle("自动化能力") }
        item {
            RuntimeCard {
                RuntimeStatusRow("截屏服务", if (rootReady) "Root 后端就绪，未测试截图" else "Root 未就绪", rootReady)
                automationPermissionStatuses(state, inputFeatures).filter { it.title in setOf("点击 / 滑动", "长按 / 拖动 / 按下弹起") }.forEachIndexed { index, row ->
                    RuntimeStatusRow(row.title, row.status, row.healthy, last = index == 1)
                }
                state.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowDebug(message) }
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        Text(
                            message,
                            color = AutoScriptPalette.Danger,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Text(
                            "查看调试详情",
                            color = AutoScriptPalette.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Text("Root / 截图诊断", color = AutoScriptPalette.Accent, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onShowDebug(state.message ?: "Root 与截图诊断 · 可复制后提供排查")
                    }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        item { RuntimeGroupTitle("可选视觉组件") }
        item {
            RuntimeCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpenVisionComponents).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenCV / ONNX 本地组件", fontSize = 12.sp, color = AutoScriptPalette.Accent)
                        Text("导入、选择与状态 · 不等于项目能力授权", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right_20), null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        item { RuntimeGroupTitle("服务信息") }
        item {
            RuntimeCard {
                RuntimeStatusRow("协议版本", state.protocolVersion?.let { "AIDL v$it" } ?: "未连接", state.protocolVersion != null)
                RuntimeStatusRow("执行后端", "Root", rootReady, last = true)
            }
        }
        item { Text("系统授权不等于 Root 授权；连接成功不代表按键或截图已实测。项目能力仍在项目设置中维护。",
            Modifier.padding(12.dp), fontSize = 10.sp, color = AutoScriptPalette.TextSecondary) }
    }
}

@Composable
private fun EnvironmentSystemPermissions(refreshRevision: Int, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val statuses = remember(context, revision, refreshRevision) {
        val overlay = Settings.canDrawOverlays(context)
        val notification = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        listOf(
            SoftwarePermissionStatus("悬浮窗", if (overlay) "已允许" else "未允许", overlay),
            SoftwarePermissionStatus("运行通知", if (notification) "已允许" else "已关闭", notification),
            SoftwarePermissionStatus("电池优化", if (battery) "不受优化限制" else "受系统优化管理", battery),
        )
    }
    RuntimeCard {
        statuses.forEach { row -> RuntimeStatusRow(row.title, row.status, row.healthy) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenPermissions() }
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("管理系统授权", Modifier.weight(1f), fontSize = 12.sp, color = AutoScriptPalette.Accent)
            Icon(painterResource(R.drawable.ic_chevron_right_20), null,
                tint = AutoScriptPalette.Accent, modifier = Modifier.size(18.dp))
        }
    }
}


/** Compact section heading. */
@Composable
private fun RuntimeGroupTitle(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = AutoScriptPalette.TextPrimary,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp),
    )
}

@Composable
private fun RuntimeCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, AutoScriptPalette.Border),
    ) {
        Column { content() }
    }
}


/** Rows grow with text, while retaining accessible touch targets. */
@Composable
private fun RuntimeStatusRow(label: String, value: String, healthy: Boolean, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (healthy) AutoScriptPalette.Mint else AutoScriptPalette.TextSecondary, CircleShape))
        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = AutoScriptPalette.TextPrimary)
        Text(
            value,
            fontSize = 11.sp,
            color = AutoScriptPalette.TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(1.dp).background(AutoScriptPalette.Border))
}

