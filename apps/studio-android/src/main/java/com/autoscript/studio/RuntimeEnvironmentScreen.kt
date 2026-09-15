package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptDimens
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState

@Composable
internal fun RuntimeEnvironmentScreen(
    state: RuntimeConnectionState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var captureRoot by remember { mutableStateOf(true) }
    var inputRoot by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Surface(color = Color(0xFF3A6EFF)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack, modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("‹", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
                    Text(
                        "运行环境",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFEBEFF5)),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("◉", modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.primary) }
                            Text("运行模式", modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = onRefresh) { Text("重启服务") }
                        }
                        Text("截屏权限", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text("Root/Shizuku 高速截屏依赖配套 SO：Android 14 兼容性不完整，部分设备和模拟器不支持；Android 15 及以上当前不支持。不可用时请选择系统录屏", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A8499))
                        RuntimeModeChoice("系统录屏", "Root", captureRoot) { captureRoot = it }
                        Text("按键权限", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text("无障碍权限执行系统手势，Root 权限启动特权按键服务", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7A8499))
                        RuntimeModeChoice("无障碍", "Root", inputRoot) { inputRoot = it }
                    }
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("服务与权限", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AutoScriptDimens.CardRadius),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFEBEFF5)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EnvironmentStatusRow(
                            "Runner 服务",
                            state.phase.name,
                            state.phase == RuntimeConnectionPhase.CONNECTED,
                        )
                        EnvironmentStatusRow(
                            "设备 Root 状态",
                            state.rootState.name,
                            state.rootState == RuntimeRootState.READY,
                        )
                        EnvironmentStatusRow(
                            "执行引擎",
                            state.engineState.name,
                            state.message == null,
                        )
                        EnvironmentStatusRow(
                            "协议版本",
                            state.protocolVersion?.toString() ?: "未连接",
                            state.protocolVersion != null,
                        )
                        state.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                            Text("重新读取服务状态")
                        }
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = AutoScriptDimens.PageHorizontal, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备信息", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AutoScriptDimens.CardRadius), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFEBEFF5))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnvironmentStatusRow("协议版本", state.protocolVersion?.toString() ?: "未连接", state.protocolVersion != null)
                        EnvironmentStatusRow("当前后端", if (inputRoot) "Root" else "无障碍（预留）", inputRoot)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeModeChoice(left: String, right: String, rightSelected: Boolean, onSelected: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFFF3F6FA)) {
      Row(modifier = Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          RuntimeModeButton(left, !rightSelected, Modifier.weight(1f)) { onSelected(false) }
          RuntimeModeButton(right, rightSelected, Modifier.weight(1f)) { onSelected(true) }
      }
    }
}

@Composable
private fun RuntimeModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.height(40.dp), shape = RoundedCornerShape(10.dp), color = if (selected) Color.White else Color(0xFFF0F3F8), shadowElevation = if (selected) 1.dp else 0.dp, onClick = onClick) {
        Text(label, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = if (selected) Color(0xFF3A6EFF) else Color(0xFF667085), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EnvironmentStatusRow(label: String, value: String, healthy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.padding(2.dp),
            shape = CircleShape,
            color = if (healthy) Color(0xFF24A865) else MaterialTheme.colorScheme.error,
        ) { Text(" ", style = MaterialTheme.typography.labelSmall) }
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
