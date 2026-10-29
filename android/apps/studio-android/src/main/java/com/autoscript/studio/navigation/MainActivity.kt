package com.autoscript.studio

import android.content.Intent
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var captureHandoff by mutableStateOf<StudioCaptureHandoff?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureHandoff = intent.toCaptureHandoffOrNull()
        window.statusBarColor = AndroidColor.rgb(252, 252, 255)
        window.navigationBarColor = AndroidColor.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            AutoScriptTheme {
                val updateScope = rememberCoroutineScope()
                val updater = remember { StudioUpdateController(applicationContext, updateScope) }
                CompositionLocalProvider(LocalStudioUpdater provides updater) {
                    StudioApp(captureHandoff, onCaptureHandoffConsumed = { captureHandoff = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureHandoff = intent.toCaptureHandoffOrNull()
    }
}

@Composable
private fun StudioApp(captureHandoff: StudioCaptureHandoff?, onCaptureHandoffConsumed: () -> Unit) {
    var firstContentLaidOut by rememberSaveable { mutableStateOf(false) }
    val homeSpaceState = rememberHomeSpaceState()
    var navigation by remember { mutableStateOf(StudioNavigationState()) }
    var runtimeEnvironmentVisible by remember { mutableStateOf(false) }
    var aboutVisible by remember { mutableStateOf(false) }
    var functionDocsVisible by remember { mutableStateOf(false) }
    var workspaceFullScreen by remember { mutableStateOf(false) }
    var workspaceRequest by remember { mutableStateOf<WorkspaceEntryRequest?>(null) }
    var requestToken by remember { mutableLongStateOf(0) }
    var runtimeState by remember { mutableStateOf(RuntimeConnectionState()) }
    var runtimeDebugMessage by remember { mutableStateOf<String?>(null) }
    var runtimeFailureObserved by remember { mutableStateOf(false) }
    val runtimeClient = rememberRuntimeClient {
        runtimeState = it
    }
    val openSoftwarePermissions = rememberSoftwarePermissionsLauncher(runtimeClient, promptOnFirstLaunch = true)
    var environmentInputFeatures by remember { mutableStateOf<Int?>(null) }
    var visionComponentsVisible by remember { mutableStateOf(false) }
    var environmentRefreshRevision by remember { mutableIntStateOf(0) }
    val environmentScope = rememberCoroutineScope()
    LaunchedEffect(runtimeEnvironmentVisible, runtimeState.phase, runtimeState.rootState, runtimeState.sessionGeneration, environmentRefreshRevision) {
        environmentInputFeatures = null
        if (runtimeEnvironmentVisible) environmentInputFeatures = withContext(Dispatchers.IO) { runtimeClient.inputFeatures() }
    }
    val context = LocalContext.current
    val projectStore = remember(context) { ProjectStore(File(context.applicationContext.filesDir, "projects")) }
    StudioUpdateHost(LocalStudioUpdater.current, installAllowed = runtimeState.phase == RuntimeConnectionPhase.CONNECTED &&
        runtimeState.engineState in setOf(RuntimeEngineState.IDLE, RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED))

    fun openWorkspace(action: WorkspaceEntryAction? = null, projectId: String? = null) {
        runtimeEnvironmentVisible = false
        visionComponentsVisible = false
        aboutVisible = false
        workspaceRequest = action?.let { WorkspaceEntryRequest(++requestToken, it, projectId) }
        navigation = navigation.navigateTo(StudioDestination.WORKSPACE)
    }

    fun openEntry(entry: StudioEntry) {
        when (entry) {
            StudioEntry.PROJECTS -> openWorkspace()
            StudioEntry.CREATE -> openWorkspace(WorkspaceEntryAction.CREATE)
            StudioEntry.IMPORT -> openWorkspace(WorkspaceEntryAction.IMPORT)
            StudioEntry.BACKUPS -> openWorkspace(WorkspaceEntryAction.BACKUPS)
            StudioEntry.PERMISSIONS -> openSoftwarePermissions()
            StudioEntry.ENVIRONMENT -> runtimeEnvironmentVisible = true
            StudioEntry.DOCS -> functionDocsVisible = true
            StudioEntry.ABOUT -> {
                navigation = navigation.navigateTo(StudioDestination.PROFILE)
                aboutVisible = true
            }
        }
    }

    LaunchedEffect(captureHandoff?.token) {
        if (captureHandoff != null) {
            openWorkspace()
            functionDocsVisible = false
        }
    }
    LaunchedEffect(runtimeState.engineState, runtimeState.message) {
        if (runtimeState.engineState == RuntimeEngineState.FAILED) {
            if (!runtimeFailureObserved && !runtimeState.message.isNullOrBlank()) runtimeDebugMessage = runtimeState.message
            runtimeFailureObserved = true
        } else runtimeFailureObserved = false
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val navigationVisible = !runtimeEnvironmentVisible && !workspaceFullScreen && !aboutVisible
        val navigationRail = configuration.screenWidthDp > configuration.screenHeightDp &&
            studioWideShortLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
        val selectDestination: (StudioDestination) -> Unit = { destination ->
            navigation = navigation.navigateTo(destination)
            aboutVisible = false
            workspaceRequest = null
        }
        Scaffold(modifier = Modifier.fillMaxSize().onGloballyPositioned { firstContentLaidOut = true }, containerColor = StudioPaper, bottomBar = {
            if (navigationVisible && !navigationRail) {
                StudioBottomNavigation(navigation.destination, selectDestination)
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (navigationVisible && navigationRail) StudioSideNavigation(navigation.destination, selectDestination)
                Surface(Modifier.weight(1f).fillMaxHeight(), color = StudioPaper) {
                    if (runtimeEnvironmentVisible) RuntimeEnvironmentScreen(
                        state = runtimeState, onBack = { visionComponentsVisible = false; runtimeEnvironmentVisible = false }, onRefresh = {
                            environmentScope.launch {
                                withContext(Dispatchers.IO) { runtimeClient.refresh() }
                                environmentRefreshRevision++
                            }
                        },
                        onConnectRoot = { environmentScope.launch { runtimeClient.connectRoot() } },
                        onShowDebug = { heading -> environmentScope.launch {
                            val diagnostics = withContext(Dispatchers.IO) { runtimeClient.recentRuntimeLogs() }
                                .filter { "App诊断" in it }
                            runtimeDebugMessage = heading + "\n\n" + diagnostics.joinToString("\n").ifBlank { "暂无 App 诊断，请刷新连接后重试截图" }
                        } }, onOpenPermissions = openSoftwarePermissions,
                        onOpenVisionComponents = { visionComponentsVisible = true },
                        inputFeatures = environmentInputFeatures, modifier = Modifier.fillMaxSize(),
                    ) else when (navigation.destination) {
                        StudioDestination.HOME -> StudioHomePage(modifier = Modifier.fillMaxSize(), spaceState = homeSpaceState)
                        StudioDestination.WORKSPACE -> ProjectPage(
                            store = projectStore, runtimeClient = runtimeClient, runtimeState = runtimeState,
                            onFullScreenChanged = { workspaceFullScreen = it },
                            captureHandoff = captureHandoff, onCaptureHandoffConsumed = onCaptureHandoffConsumed,
                            entryRequest = workspaceRequest, onEntryRequestConsumed = { workspaceRequest = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                        StudioDestination.PROFILE -> if (aboutVisible) {
                            StudioAboutScreen(onBack = { aboutVisible = false }, modifier = Modifier.fillMaxSize())
                        } else StudioProfilePage(runtimeState, ::openEntry, Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (captureHandoff == null) StudioStartupOverlay(firstContentLaidOut)
    }
    if (runtimeEnvironmentVisible && visionComponentsVisible) VisionProviderManagerDialog(runtimeClient) { visionComponentsVisible = false }
    if (functionDocsVisible) FunctionLibraryDialog(
        groups = FunctionCatalog.visualGroups(), capabilities = emptySet(), onDismiss = { functionDocsVisible = false },
        onInsert = {}, documentationOnly = true,
    )
    runtimeDebugMessage?.let { RuntimeDebugDialog(it, onDismiss = { runtimeDebugMessage = null }) }
}

@Composable
private fun StudioSideNavigation(selected: StudioDestination, onSelected: (StudioDestination) -> Unit) {
    Surface(Modifier.width(64.dp).fillMaxHeight(), color = Color.White.copy(alpha = .98f), shadowElevation = 2.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioDestination.entries.forEach { destination ->
                val active = destination == selected
                val color = if (active) StudioViolet else StudioMuted
                val icon = when (destination) {
                    StudioDestination.HOME -> R.drawable.nav_home_24
                    StudioDestination.WORKSPACE -> R.drawable.nav_workspace_24
                    StudioDestination.PROFILE -> R.drawable.nav_profile_24
                }
                Column(Modifier.fillMaxWidth().clickable { onSelected(destination) }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(44.dp, 27.dp).background(if (active) StudioLilac else Color.Transparent, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), destination.label, tint = color, modifier = Modifier.size(19.dp))
                    }
                    Text(destination.label, color = color, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A bounded one-shot cache reference, never a filesystem path supplied by an intent. */
internal data class StudioCaptureHandoff(val projectId: String, val token: String, val width: Int, val height: Int)

private fun Intent?.toCaptureHandoffOrNull(): StudioCaptureHandoff? {
    if (this?.action != RuntimeProtocol.ACTION_OPEN_CAPTURE_EDITOR) return null
    val projectId = getStringExtra(RuntimeProtocol.EXTRA_CAPTURE_PROJECT_ID) ?: return null
    val token = getStringExtra(RuntimeProtocol.EXTRA_CAPTURE_TOKEN) ?: return null
    val width = getIntExtra(RuntimeProtocol.EXTRA_CAPTURE_WIDTH, 0)
    val height = getIntExtra(RuntimeProtocol.EXTRA_CAPTURE_HEIGHT, 0)
    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(projectId) || !Regex("[a-f0-9]{32}").matches(token)) return null
    if (width !in 1..8_192 || height !in 1..8_192) return null
    return StudioCaptureHandoff(projectId, token, width, height)
}

@Composable
internal fun StudioBottomNavigation(selected: StudioDestination, onSelected: (StudioDestination) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White.copy(alpha = .98f), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioDestination.entries.forEach { destination ->
                val active = selected == destination
                val color = if (active) StudioViolet else StudioMuted
                val icon = when (destination) {
                    StudioDestination.HOME -> R.drawable.nav_home_24
                    StudioDestination.WORKSPACE -> R.drawable.nav_workspace_24
                    StudioDestination.PROFILE -> R.drawable.nav_profile_24
                }
                Column(Modifier.weight(1f).clickable { onSelected(destination) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(width = 54.dp, height = 27.dp)
                        .background(if (active) StudioLilac else Color.Transparent, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), destination.label, tint = color, modifier = Modifier.size(20.dp))
                    }
                    Text(destination.label, color = color, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun rememberRuntimeClient(onState: (RuntimeConnectionState) -> Unit): RuntimeClient {
    val context = LocalContext.current
    val latestCallback by rememberUpdatedState(onState)
    val client = remember { RuntimeClient(context) }
    DisposableEffect(client) {
        client.onStateChanged = { latestCallback(it) }
        client.bind()
        onDispose { client.onStateChanged = null; client.unbind() }
    }
    return client
}
