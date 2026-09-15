package com.autoscript.studio

import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.view.View
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.auth.local.LocalAuthProvider
import com.autoscript.core.designsystem.AutoScriptDimens
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.project.store.ProjectStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(245, 247, 251)
        window.navigationBarColor = AndroidColor.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { AutoScriptTheme { StudioApp() } }
    }
}

@Composable
private fun StudioApp() {
    var navigation by remember { mutableStateOf(StudioNavigationState()) }
    var runtimeEnvironmentVisible by remember { mutableStateOf(false) }
    var profileSubpage by remember { mutableStateOf<ProfileSubpage?>(null) }
    var workspaceFullScreen by remember { mutableStateOf(false) }
    var runtimeState by remember { mutableStateOf(RuntimeConnectionState()) }
    val runtimeClient = rememberRuntimeClient { runtimeState = it }
    val context = androidx.compose.ui.platform.LocalContext.current
    val projectStore = remember(context) {
        ProjectStore(File(context.applicationContext.filesDir, "projects"))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!runtimeEnvironmentVisible && !workspaceFullScreen) {
                StudioBottomNavigation(
                    selected = navigation.destination,
                    onSelected = { destination ->
                        navigation = navigation.navigateTo(destination)
                        if (destination != StudioDestination.PROFILE) profileSubpage = null
                    },
                )
            }
        },
    ) { contentPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (runtimeEnvironmentVisible) {
                RuntimeEnvironmentScreen(
                    state = runtimeState,
                    onBack = { runtimeEnvironmentVisible = false },
                    onRefresh = runtimeClient::refresh,
                    modifier = Modifier.fillMaxSize(),
                )
            } else when (navigation.destination) {
                StudioDestination.HOME -> HomePage(
                    state = runtimeState,
                    onOpenRuntimeEnvironment = { runtimeEnvironmentVisible = true },
                    onOpenWorkspace = {
                        navigation = navigation.navigateTo(StudioDestination.WORKSPACE)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                StudioDestination.WORKSPACE -> DeveloperPage(
                    state = runtimeState,
                    refresh = runtimeClient::refresh,
                    runtimeClient = runtimeClient,
                    projectStore = projectStore,
                    active = true,
                    onFullScreenChanged = { workspaceFullScreen = it },
                    modifier = Modifier.fillMaxSize(),
                )
                StudioDestination.PROFILE -> profileSubpage?.let { page ->
                    ProfileSubpageScreen(
                        page = page,
                        onBack = { profileSubpage = null },
                        onOpenSubpage = { profileSubpage = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: ProfilePage(
                        state = runtimeState,
                        onOpenRuntimeEnvironment = { runtimeEnvironmentVisible = true },
                        onOpenSubpage = { profileSubpage = it },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
private fun StudioBottomNavigation(
    selected: StudioDestination,
    onSelected: (StudioDestination) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudioBottomItem(StudioDestination.HOME, selected == StudioDestination.HOME, Modifier.weight(1f), onSelected)
        StudioBottomItem(StudioDestination.WORKSPACE, selected == StudioDestination.WORKSPACE, Modifier.weight(1f), onSelected)
        StudioBottomItem(StudioDestination.PROFILE, selected == StudioDestination.PROFILE, Modifier.weight(1f), onSelected)
    }
}

@Composable
private fun StudioBottomItem(
    destination: StudioDestination,
    selected: Boolean,
    modifier: Modifier,
    onSelected: (StudioDestination) -> Unit,
) {
    val color = if (selected) Color(0xFF3A6EFF) else Color(0xFF7A8499)
    Column(
        modifier = modifier.fillMaxSize().clickable { onSelected(destination) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StudioNavIcon(destination, Modifier.size(22.dp), color)
        Spacer(Modifier.height(4.dp))
        Text(destination.label, color = color, fontSize = 13.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun StudioNavIcon(destination: StudioDestination, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * .10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (destination) {
            StudioDestination.HOME -> {
                val roof = Path().apply { moveTo(.08f*w,.48f*h); lineTo(.50f*w,.10f*h); lineTo(.92f*w,.48f*h) }
                drawPath(roof, tint, style = stroke)
                drawRect(tint, Offset(.19f*w,.46f*h), Size(.62f*w,.43f*h))
                drawRect(Color.White, Offset(.45f*w,.62f*h), Size(.12f*w,.27f*h))
            }
            StudioDestination.WORKSPACE -> {
                val gap = .10f * w
                val box = .36f * w
                drawRect(tint, Offset(.06f*w,.06f*h), Size(box, box))
                drawRect(tint, Offset(.58f*w,.06f*h), Size(box, box))
                drawRect(tint, Offset(.06f*w,.58f*h), Size(box, box))
                drawRect(tint, Offset(.58f*w,.58f*h), Size(box, box))
            }
            StudioDestination.PROFILE -> {
                drawCircle(tint, .21f*w, Offset(.50f*w,.28f*h))
                drawRoundRect(tint, Offset(.18f*w,.55f*h), Size(.64f*w,.36f*h), androidx.compose.ui.geometry.CornerRadius(.26f*w))
            }
        }
    }
}

@Composable
private fun HomePage(
    state: RuntimeConnectionState,
    onOpenRuntimeEnvironment: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4FF)),
            ) {
                Column(
                    modifier = Modifier.height(420.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Text("●  从本地项目开始", color = Color(0xFF4D9D82), fontSize = 14.sp, modifier = Modifier.background(Color.White.copy(alpha = .7f), RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                    Text("可视化操作\n轻松开发脚本", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1E344A), modifier = Modifier.padding(top = 20.dp))
                    Text("常用功能清晰呈现，无需先掌握复杂代码\n从配置到运行，快速完成自动化流程", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF657D97), lineHeight = 27.sp)
                    Spacer(Modifier.weight(1f))
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .78f))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            HomeHeroValue("易", "简单直观", "零基础易上手", Modifier.weight(1f))
                            HomeHeroValue("Lua", "灵活扩展", "可编写 Lua", Modifier.weight(1f))
                        }
                    }
                    Text("━━━━     ●     ●     ●                         01 / 04", color = Color(0xFF79A18F), fontSize = 10.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F6FF))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("零基础快速上手 开发更高效", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeFeature("易", "简单易用", Modifier.weight(1f))
                        HomeFeature("Lua", "Lua 引擎", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeFeature("界", "界面开发", Modifier.weight(1f))
                        HomeFeature("Root", "本机运行", Modifier.weight(1f))
                    }
                    TextButton(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) { Text("进入工作台  →") }
                    TextButton(onClick = onOpenRuntimeEnvironment, modifier = Modifier.fillMaxWidth()) { Text("运行环境：${state.phase.name}") }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroValue(symbol: String, title: String, detail: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(13.dp), color = Color(0xFFE7F3EF)) { Box(contentAlignment = Alignment.Center) { Text(symbol, color = Color(0xFF4D9D82), fontWeight = FontWeight.Bold) } }
        Column(Modifier.padding(start = 10.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, fontSize = 11.sp, color = Color(0xFF758997)) }
    }
}

@Composable
private fun HomeFeature(symbol: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(AutoScriptDimens.ControlRadius)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun CompactStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onFullScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProjectPage(
        store = projectStore,
        runtimeClient = runtimeClient,
        runtimeState = state,
        active = active,
        onFullScreenChanged = onFullScreenChanged,
        modifier = modifier,
        footer = {
            RunnerCard(state = state, refresh = refresh)
        },
    )
}

@Composable
private fun RunnerCard(state: RuntimeConnectionState, refresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Runner 引擎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.phase} · Root ${state.rootState} · ${state.engineState} · " +
                        "协议 ${state.protocolVersion ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = refresh) { Text("刷新") }
        }
    }
}

@Composable
private fun ProfilePage(
    state: RuntimeConnectionState,
    onOpenRuntimeEnvironment: () -> Unit,
    onOpenSubpage: (ProfileSubpage) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            Surface(color = Color(0xFF3A6EFF), modifier = Modifier.fillMaxWidth().height(250.dp)) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = .96f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("本", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3A6EFF))
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.clickable { onOpenSubpage(ProfileSubpage.LOGIN) }) {
                        Text("登录 / 注册", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("登录后可创建项目并管理个人资料", color = Color.White.copy(alpha = .87f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item { Text("功能服务", modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium) }
        item {
            Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column {
                    ProfileFeatureRow("◉", "运行环境", null, onOpenRuntimeEnvironment)
                    ProfileDivider()
                    ProfileFeatureRow("群", "社区与反馈", "本地版暂未配置外部群组", {})
                    ProfileDivider()
                    ProfileFeatureRow("▤", "开发文档", "离线文档与接口说明", { onOpenSubpage(ProfileSubpage.DOCS) })
                    ProfileDivider()
                    ProfileFeatureRow("↑", "检查更新", "当前版本 0.1.0", { onOpenSubpage(ProfileSubpage.UPDATE) })
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenSubpage(ProfileSubpage.LEGAL) }) { Text("用户协议", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenSubpage(ProfileSubpage.LEGAL) }) { Text("隐私政策", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ProfileFeatureRow(symbol: String, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFFE8F0FF)) {
            Box(contentAlignment = Alignment.Center) { Text(symbol, color = Color(0xFF4D7DF4), fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
        }
        Text("›", fontSize = 28.sp, color = Color(0xFF9AA4B5))
    }
}

@Composable
private fun ProfileDivider() = Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 72.dp).background(Color(0xFFE7EAF0)))
