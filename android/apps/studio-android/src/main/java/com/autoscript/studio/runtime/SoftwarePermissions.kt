package com.autoscript.studio

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SoftwarePermissionStatus(val title: String, val status: String, val healthy: Boolean)

internal enum class InitialPermissionAction { OVERLAY, NOTIFICATIONS, NONE }

internal fun initialPermissionAction(overlayAllowed: Boolean, notificationsAllowed: Boolean, api: Int): InitialPermissionAction = when {
    !overlayAllowed -> InitialPermissionAction.OVERLAY
    api >= 33 && !notificationsAllowed -> InitialPermissionAction.NOTIFICATIONS
    else -> InitialPermissionAction.NONE
}

/** Binding and Root readiness do not imply that an input device supports every action. */
internal fun automationPermissionStatuses(state: RuntimeConnectionState, features: Int?): List<SoftwarePermissionStatus> {
    val connected = state.phase == RuntimeConnectionPhase.CONNECTED
    val root = connected && state.rootState == RuntimeRootState.READY
    val basic = root && features != null && features and RuntimeProtocol.INPUT_FEATURE_BASIC != 0
    val pointer = basic && features!! and RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER != 0
    return listOf(
        SoftwarePermissionStatus("Runner 连接", runtimeConnectionLabel(state.phase), connected),
        SoftwarePermissionStatus("Root 后端", when {
            !connected -> "等待 Runner 连接"
            root -> "已就绪"
            else -> runtimeRootLabel(state.rootState)
        }, root),
        SoftwarePermissionStatus("点击 / 滑动", when {
            !root -> "等待 Root 就绪"
            features == null -> "读取中"
            basic -> "后端支持"
            else -> "输入设备不可用"
        }, basic),
        SoftwarePermissionStatus("长按 / 拖动 / 按下弹起", when {
            !root -> "等待 Root 就绪"
            features == null -> "读取中"
            pointer -> "后端支持"
            else -> "不支持持续单指"
        }, pointer),
        SoftwarePermissionStatus("Root 截图", if (root) "后端就绪，未执行截图测试" else "等待 Root 就绪", root),
        SoftwarePermissionStatus("多指触摸（最多5指）",when {
            !root->"等待 Root 就绪"
            features==null->"读取中"
            features and RuntimeProtocol.INPUT_FEATURE_MULTI_POINTER!=0->"已协商支持，未代表目标App实测"
            else->"后端不支持，不降级为单指"
        },root && features!=null && features and RuntimeProtocol.INPUT_FEATURE_MULTI_POINTER!=0),
    )
}

/** Shared entry for profile, editor settings and input diagnostics. No permission is auto-granted. */
@Composable
internal fun rememberSoftwarePermissionsLauncher(runtimeClient: RuntimeClient, promptOnFirstLaunch: Boolean = false): () -> Unit {
    val context = LocalContext.current
    val notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    val shouldPrompt = remember {
        promptOnFirstLaunch && initialPermissionAction(Settings.canDrawOverlays(context), notificationsAllowed, Build.VERSION.SDK_INT) != InitialPermissionAction.NONE
    }
    var visible by remember { mutableStateOf(shouldPrompt) }
    if (visible) SoftwarePermissionsDialog(runtimeClient, startupPrompt = shouldPrompt) { visible = false }
    return { visible = true }
}

@Composable
private fun SoftwarePermissionsDialog(runtimeClient: RuntimeClient, startupPrompt: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by runtimeClient.runtimeStates.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var features by remember { mutableStateOf<Int?>(null) }
    var platform by remember { mutableStateOf(emptyList<SoftwarePermissionStatus>()) }
    var declared by remember { mutableStateOf(emptyList<SoftwarePermissionStatus>()) }
    var showDeclared by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notificationRequested by remember { mutableStateOf(false) }
    val overlayHost = LocalEditorOverlay.current
    val settingsRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        revision++
        if (!granted) message = "通知未允许；再次点击通知可进入系统设置手动开启"
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, state.phase, state.rootState, state.sessionGeneration) {
        features = null
        platform = listOf(
            SoftwarePermissionStatus("悬浮窗", if (Settings.canDrawOverlays(context)) "已允许" else "未允许", Settings.canDrawOverlays(context)),
            SoftwarePermissionStatus("通知", if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "已允许" else "已关闭", context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()),
            SoftwarePermissionStatus("电池优化", if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "不受优化限制" else "受系统优化管理", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)),
        )
        declared = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions.orEmpty().mapIndexed { index, permission ->
                val special = permission == Manifest.permission.SYSTEM_ALERT_WINDOW
                val granted = if (special) Settings.canDrawOverlays(context) else
                    ((info.requestedPermissionsFlags?.getOrNull(index) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                SoftwarePermissionStatus(permission.removePrefix("android.permission."), if (granted) "已允许" else "未允许 / 受系统控制", granted)
            }
        }
        if (state.phase == RuntimeConnectionPhase.CONNECTED && state.rootState == RuntimeRootState.READY) {
            features = withContext(Dispatchers.IO) { runtimeClient.inputFeatures() }
        }
    }
    fun open(intent: Intent, fallback: Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) {
        message = null
        if (overlayHost != null) { settingsRequest.launch(intent); return }
        if (runCatching { context.startActivity(intent) }.isFailure && runCatching { context.startActivity(fallback) }.isFailure) {
            message = "系统未提供此设置入口，请手动进入系统设置 → 智构"
        }
    }
    fun requestNextPermission() {
        when (initialPermissionAction(
            Settings.canDrawOverlays(context),
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            Build.VERSION.SDK_INT,
        )) {
            InitialPermissionAction.OVERLAY -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            InitialPermissionAction.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationRequested = true
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            InitialPermissionAction.NONE -> message = "基础权限已处理；Root 请在运行环境中由设备管理器授权"
        }
    }
    StudioPanelDialog("权限与运行环境", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (startupPrompt) {
                Column(Modifier.fillMaxWidth().background(AutoScriptPalette.AccentSoft, RoundedCornerShape(6.dp)).padding(10.dp)) {
                    Text("首次使用前需要准备权限", color = AutoScriptPalette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("悬浮窗用于在其他应用上编辑，通知用于恢复编辑和显示运行状态。Root 不在这里自动授予。",
                        color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.fillMaxWidth().height(38.dp).padding(top = 6.dp).background(AutoScriptPalette.Accent, RoundedCornerShape(4.dp))
                        .clickable(onClick = ::requestNextPermission), contentAlignment = Alignment.Center) {
                        Text("开始授权", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("快速授权 / 设置", color = AutoScriptPalette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val actions: List<Pair<String, () -> Unit>> = listOf(
                "悬浮窗" to { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) },
                "通知" to {
                    if (Build.VERSION.SDK_INT >= 33 && !notificationRequested && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationRequested = true
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else if (Build.VERSION.SDK_INT >= 26) open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    else open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                },
                "应用权限" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
                "电池限制" to { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                "启用输入法" to { open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                "选择输入法" to { context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker();revision++ },
                "重新检查" to { scope.launch { withContext(Dispatchers.IO) { runtimeClient.refresh() }; revision++ } },
                "声明权限" to { showDeclared = !showDeclared },
            )
            actions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { (title, click) ->
                        Box(Modifier.weight(1f).height(38.dp).background(AutoScriptPalette.AccentSoft, RoundedCornerShape(4.dp)).clickable(onClick = click), contentAlignment = Alignment.Center) {
                            Text(title, fontSize = 11.sp, color = AutoScriptPalette.Accent, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                        }
                    }
                }
            }
            (platform + automationPermissionStatuses(state, features)).forEach { row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, Modifier.weight(1f), fontSize = 11.sp)
                    Text(row.status, Modifier.weight(1f), fontSize = 10.sp, color = if (row.healthy) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
            }
            Text("Root 由设备的 Root 管理器授权给智构，不属于 Android 普通权限；连接成功不等于授权成功。当前不提供无障碍或系统录屏后端。项目能力声明仍在项目设置维护。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            Text("智构输入法为可选文字提交工具，不读取或保存输入内容；请在系统中主动启用并选择，脚本不会自动切换。",fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
            if (showDeclared) declared.forEach { row ->
                Text("${row.title} · ${row.status}", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            }
            message?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
        }
    }
}
