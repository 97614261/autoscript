package com.autoscript.runner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.client.RuntimeClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoScriptTheme { RunnerApp() } }
    }
}

@Composable
private fun RunnerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { RuntimeClient(context) }
    var state by remember { mutableStateOf(RuntimeConnectionState()) }

    DisposableEffect(client) {
        client.onStateChanged = { state = it }
        client.bind()
        onDispose {
            client.onStateChanged = null
            client.unbind()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("自动化Runner", style = MaterialTheme.typography.titleLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("运行状态", style = MaterialTheme.typography.titleMedium)
                    Text("连接：${state.phase}")
                    Text("协议：${state.protocolVersion ?: "-"}")
                    Text("会话：${state.sessionGeneration ?: "-"}")
                    Text("引擎：${state.engineState}")
                }
            }
            Button(onClick = { client.requestStop() }) { Text("停止") }
            Text("当前为R0模板：不包含编辑器、项目列表或在线登录。")
        }
    }
}
