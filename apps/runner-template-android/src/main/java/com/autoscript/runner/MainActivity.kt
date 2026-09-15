package com.autoscript.runner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var permissionEpoch by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoScriptTheme { RunnerApp(permissionEpoch) } }
    }

    override fun onResume() {
        super.onResume()
        permissionEpoch += 1
    }
}

@Composable
private fun RunnerApp(permissionEpoch: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { RuntimeClient(context) }
    var state by remember { mutableStateOf(RuntimeConnectionState()) }
    var releaseState by remember { mutableStateOf<ReleaseLoadState>(ReleaseLoadState.Loading) }
    var starting by remember { mutableStateOf(false) }
    var controlling by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var notificationAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    var overlayAllowed by remember { mutableStateOf(hasOverlayPermission(context)) }
    var overlayEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences("runner-ui", Context.MODE_PRIVATE)
                .getBoolean("floating-control", true),
        )
    }
    var configValues by remember { mutableStateOf<Map<String, RunnerConfigDraftValue>>(emptyMap()) }
    var runtimeLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed -> notificationAllowed = allowed }

    DisposableEffect(client) {
        client.onStateChanged = { state = it }
        client.bind()
        onDispose {
            client.onStateChanged = null
            client.unbind()
        }
    }

    LaunchedEffect(Unit) {
        releaseState = withContext(Dispatchers.IO) {
            runCatching {
                EmbeddedReleaseLoader.load(
                    context = context,
                    expectedApplicationId = BuildConfig.APPLICATION_ID,
                    expectedVersionCode = BuildConfig.VERSION_CODE,
                    expectedVersionName = BuildConfig.VERSION_NAME,
                )
            }.fold(
                onSuccess = { release ->
                    if (release == null) ReleaseLoadState.Template
                    else ReleaseLoadState.Ready(release)
                },
                onFailure = { error ->
                    ReleaseLoadState.Invalid(error.message ?: "内置发布包校验失败")
                },
            )
        }
    }

    LaunchedEffect(permissionEpoch) {
        notificationAllowed = hasNotificationPermission(context)
        overlayAllowed = hasOverlayPermission(context)
        client.refresh()
    }

    LaunchedEffect(releaseState) {
        val release = (releaseState as? ReleaseLoadState.Ready)?.release ?: return@LaunchedEffect
        configValues = initialRunnerConfig(context, release)
    }

    LaunchedEffect(
        state.engineState,
        state.rootState,
        state.sessionGeneration,
        overlayEnabled,
        overlayAllowed,
    ) {
        if (state.phase == RuntimeConnectionPhase.CONNECTED) {
            runtimeLogs = withContext(Dispatchers.IO) {
                client.setFloatingControlEnabled(overlayEnabled && overlayAllowed)
                client.recentRuntimeLogs()
            }
        }
    }

    fun startRelease(release: EmbeddedRelease) {
        if (starting || state.phase != RuntimeConnectionPhase.CONNECTED) return
        if (state.rootState != RuntimeRootState.READY) return
        if (state.engineState in setOf(
                RuntimeEngineState.RUNNING,
                RuntimeEngineState.PAUSED,
                RuntimeEngineState.STOPPING,
            )
        ) return
        val runtimeLua = runCatching { buildRuntimeLua(release, configValues) }.getOrElse { error ->
            operationMessage = error.message ?: "脚本配置无效"
            return
        }
        persistRunnerConfig(context, release, configValues)
        starting = true
        operationMessage = null
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                client.startProject(
                    generatedLuaModule = runtimeLua,
                    resources = release.resources,
                    capabilities = release.capabilities,
                    designWidth = release.designWidth,
                    designHeight = release.designHeight,
                    scaleMode = release.scaleMode,
                )
            }
            operationMessage = if (started) "内置项目已提交运行" else "内置项目启动失败"
            starting = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val readyRelease = (releaseState as? ReleaseLoadState.Ready)?.release
        val canStart = readyRelease != null && !starting &&
            state.phase == RuntimeConnectionPhase.CONNECTED &&
            state.rootState == RuntimeRootState.READY &&
            state.engineState !in setOf(
                RuntimeEngineState.RUNNING,
                RuntimeEngineState.PAUSED,
                RuntimeEngineState.STOPPING,
            )
        val errorMessage = when (val release = releaseState) {
            is ReleaseLoadState.Invalid -> "发布包无效：${release.message}"
            else -> state.message
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                readyRelease?.displayName ?: "自动化Runner",
                style = MaterialTheme.typography.titleLarge,
            )
            when (val release = releaseState) {
                ReleaseLoadState.Loading -> Text("正在校验内置发布包…")
                ReleaseLoadState.Template -> Text("模板模式 · 尚未注入发布项目")
                is ReleaseLoadState.Invalid -> Unit
                is ReleaseLoadState.Ready -> {
                    Text("版本 ${release.release.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (diagnosticsExpanded) {
                        Text(
                            "API ${release.release.runtimeApi} · ${release.release.sourceMode} · " +
                                "能力 ${release.release.capabilities.size} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Release ${release.release.releaseId.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("运行条件", style = MaterialTheme.typography.titleMedium)
                    StatusLine("Runner服务", connectionText(state.phase), state.phase == RuntimeConnectionPhase.CONNECTED)
                    StatusLine("Root后端", rootText(state.rootState), state.rootState == RuntimeRootState.READY)
                    StatusLine(
                        "运行通知",
                        if (notificationAllowed) "已允许" else "未允许",
                        notificationAllowed,
                    )
                    StatusLine(
                        "悬浮窗",
                        if (overlayAllowed) "已允许" else "未允许",
                        overlayAllowed,
                    )
                    if (!notificationAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(
                            "通知被拒绝不会阻止脚本运行，但通知栏可能看不到停止入口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("允许运行通知") }
                    }
                    if (!overlayAllowed) {
                        OutlinedButton(
                            onClick = { openOverlaySettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("允许悬浮控制") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("运行时显示悬浮控制")
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enabled ->
                                overlayEnabled = enabled
                                context.getSharedPreferences("runner-ui", Context.MODE_PRIVATE)
                                    .edit().putBoolean("floating-control", enabled).apply()
                                if (enabled && !overlayAllowed) openOverlaySettings(context)
                                else scope.launch {
                                    withContext(Dispatchers.IO) {
                                        client.setFloatingControlEnabled(enabled)
                                    }
                                }
                            },
                        )
                    }
                    Text(
                        "R0仅支持Root自动化；Root未就绪时不会提交脚本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("脚本状态", style = MaterialTheme.typography.titleMedium)
                    StatusLine("引擎", engineText(state.engineState), state.engineState == RuntimeEngineState.RUNNING)
                    Text(
                        "协议 ${state.protocolVersion ?: "-"} · 会话 ${state.sessionGeneration ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            readyRelease?.runnerUi?.let { definition ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("脚本配置", style = MaterialTheme.typography.titleMedium)
                        if (definition.fields.all { configValues.containsKey(it.id) }) {
                            ScriptUiHost(
                                releaseId = readyRelease.releaseId,
                                definition = definition,
                                values = configValues,
                                onValueChanged = { id, value ->
                                    configValues = configValues + (id to value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("正在读取本地配置…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            errorMessage?.let { ErrorCard(runtimeErrorPresentation(readyRelease, it)) }
            operationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { readyRelease?.let { startRelease(it) } },
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) { Text(if (starting) "启动中…" else "运行") }
                OutlinedButton(
                    onClick = {
                        val resume = state.engineState == RuntimeEngineState.PAUSED
                        controlling = true
                        scope.launch {
                            val accepted = withContext(Dispatchers.IO) {
                                if (resume) client.requestResume() else client.requestPause()
                            }
                            if (!accepted) {
                                operationMessage = if (resume) "继续请求被拒绝" else "暂停请求被拒绝"
                            }
                            controlling = false
                        }
                    },
                    enabled = state.phase == RuntimeConnectionPhase.CONNECTED &&
                        state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED) &&
                        !controlling,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.engineState == RuntimeEngineState.PAUSED) "继续" else "暂停") }
                OutlinedButton(
                    onClick = {
                        controlling = true
                        scope.launch {
                            val accepted = withContext(Dispatchers.IO) { client.requestStop() }
                            if (!accepted) operationMessage = "停止请求被拒绝"
                            controlling = false
                        }
                    },
                    enabled = state.phase == RuntimeConnectionPhase.CONNECTED &&
                        state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED) &&
                        !controlling,
                    modifier = Modifier.weight(1f),
                ) { Text("停止") }
                OutlinedButton(onClick = client::refresh, modifier = Modifier.weight(1f)) {
                    Text("刷新")
                }
            }
            TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                Text(if (diagnosticsExpanded) "收起高级诊断" else "高级诊断")
            }
            if (diagnosticsExpanded && runtimeLogs.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("运行日志", style = MaterialTheme.typography.titleMedium)
                        runtimeLogs.takeLast(12).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (diagnosticsExpanded) {
                Text(
                    "独立 Runner 不包含编辑器、项目列表或在线登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            value,
            color = if (positive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(error: RuntimeErrorPresentation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("错误信息", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            error.location?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(error.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun connectionText(phase: RuntimeConnectionPhase): String = when (phase) {
    RuntimeConnectionPhase.DISCONNECTED -> "未连接"
    RuntimeConnectionPhase.CONNECTING -> "连接中"
    RuntimeConnectionPhase.CONNECTED -> "已连接"
    RuntimeConnectionPhase.ERROR -> "连接失败"
}

private fun rootText(state: RuntimeRootState): String = when (state) {
    RuntimeRootState.UNKNOWN -> "未知"
    RuntimeRootState.STOPPED -> "未启动"
    RuntimeRootState.STARTING -> "等待Root授权"
    RuntimeRootState.READY -> "已认证"
    RuntimeRootState.FAILED -> "授权或连接失败"
}

private fun engineText(state: RuntimeEngineState): String = when (state) {
    RuntimeEngineState.UNKNOWN -> "未知"
    RuntimeEngineState.IDLE -> "空闲"
    RuntimeEngineState.RUNNING -> "运行中"
    RuntimeEngineState.PAUSED -> "已暂停"
    RuntimeEngineState.STOPPING -> "停止中"
    RuntimeEngineState.STOPPED -> "已停止"
    RuntimeEngineState.FAILED -> "执行失败"
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

private fun hasOverlayPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal data class RuntimeErrorPresentation(val message: String, val location: String?)

internal fun runtimeErrorPresentation(
    release: EmbeddedRelease?,
    diagnostic: String,
): RuntimeErrorPresentation {
    val runtimeLine = Regex("(?:android-runner|main\\.lua)[^:]*:(\\d+):")
        .find(diagnostic)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val sourceLine = runtimeLine?.minus(RUNTIME_CONFIG_PREFIX_LINES)?.takeIf { it > 0 }
    val mapped = sourceLine?.let { line ->
        release?.sourceMap?.entries?.firstOrNull { line in it.luaStartLine..it.luaEndLine }
    }
    val location = when {
        mapped != null -> "Flow ${mapped.flowId} · 节点 ${mapped.nodeId} · Lua第${sourceLine}行"
        sourceLine != null -> "Lua第${sourceLine}行"
        else -> null
    }
    return RuntimeErrorPresentation(diagnostic.take(4_096), location)
}

private sealed interface ReleaseLoadState {
    data object Loading : ReleaseLoadState
    data object Template : ReleaseLoadState
    data class Ready(val release: EmbeddedRelease) : ReleaseLoadState
    data class Invalid(val message: String) : ReleaseLoadState
}
