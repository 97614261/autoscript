package com.autoscript.studio

import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.auth.local.LocalAuthProvider
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.project.store.ProjectStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoScriptTheme { StudioApp() } }
    }
}

@Composable
private fun StudioApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var runtimeState by remember { mutableStateOf(RuntimeConnectionState()) }
    val runtimeClient = rememberRuntimeClient { runtimeState = it }
    val context = androidx.compose.ui.platform.LocalContext.current
    val projectStore = remember(context) {
        ProjectStore(File(context.applicationContext.filesDir, "projects"))
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Text(
                text = "自动化工作室",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
            TabRow(selectedTabIndex = selectedTab) {
                listOf("开发者", "我的").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                DeveloperPage(
                    state = runtimeState,
                    refresh = runtimeClient::refresh,
                    runtimeClient = runtimeClient,
                    projectStore = projectStore,
                    active = selectedTab == 0,
                    modifier = if (selectedTab == 0) Modifier.fillMaxSize() else Modifier.size(0.dp),
                )
                if (selectedTab == 1) ProfilePage(runtimeState)
            }
        }
    }
}

@Composable
private fun rememberRuntimeClient(onState: (RuntimeConnectionState) -> Unit): RuntimeClient {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { RuntimeClient(context) }
    DisposableEffect(client) {
        client.onStateChanged = onState
        client.bind()
        onDispose {
            client.onStateChanged = null
            client.unbind()
        }
    }
    return client
}

@Composable
private fun DeveloperPage(
    state: RuntimeConnectionState,
    refresh: () -> Unit,
    runtimeClient: RuntimeClient,
    projectStore: ProjectStore,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    ProjectPage(
        store = projectStore,
        runtimeClient = runtimeClient,
        runtimeState = state,
        active = active,
        modifier = modifier,
        footer = {
            RunnerCard(state = state, refresh = refresh)
        },
    )
}

@Composable
private fun RunnerCard(state: RuntimeConnectionState, refresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Runner", style = MaterialTheme.typography.titleMedium)
            Text("连接：${state.phase} · 状态：${state.engineState}")
            Text("协议：${state.protocolVersion ?: "-"} · 会话：${state.sessionGeneration ?: "-"}")
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = refresh) { Text("刷新状态") }
        }
    }
}

@Composable
private fun ProfilePage(state: RuntimeConnectionState) {
    val profile = remember { LocalAuthProvider().currentProfile() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("本地模式 · 未启用远程登录")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("引擎连接")
                Text(state.phase.name)
            }
        }
    }
}
