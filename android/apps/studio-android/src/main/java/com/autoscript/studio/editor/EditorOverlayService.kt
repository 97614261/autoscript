package com.autoscript.studio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.*
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/** Studio-owned editor only. The independent Runner never links or launches this service. */
class EditorOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    private val ownerLifecycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = ownerLifecycle
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private lateinit var manager: WindowManager
    private lateinit var client: RuntimeClient
    private lateinit var session: EditorOverlaySession
    private lateinit var results: EditorActivityResults
    private var root: EditorViewport? = null
    private var compose: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var statusBar: EditorStatusBarObserver? = null
    private var ball: StudioOverlayBall? = null
    private var projectId: String? = null
    private var snapshot by mutableStateOf<ProjectSnapshot?>(null)
    private var captureBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    private var captureToken by mutableStateOf<String?>(null)
    private var pendingCapture: StudioCaptureHandoff? = null
    private var captureJob: Job? = null
    private var runtime by mutableStateOf(RuntimeConnectionState())
    private val console = RuntimeConsoleLog()
    private var destroyed = false
    private var windowUnavailable = false
    private var permissionListener: AppOpsManager.OnOpChangedListener? = null
    private val runtimeControl = EditorRuntimeControlSession()

    override fun onCreate() {
        super.onCreate()
        saved.performAttach(); saved.performRestore(null)
        ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        manager = getSystemService(WindowManager::class.java)
        session = EditorOverlaySession(::updateWindow) { work.launch {
            message("子窗口无法打开，请检查悬浮授权；草稿不会自动丢弃")
        } }
        results = EditorActivityResults(this, session) {
            if (!destroyed) { ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        }
        client = RuntimeClient(this).also {
            it.onStateChanged = { state ->
                if (!destroyed) {
                    runtime = state
                    ball?.setBrandState(studioBrandState(state))
                    console.record(state)
                    runtimeControl.observe(state, ::setRuntimeControlEnabled)
                }
            }
            it.bind()
        }
        startForeground(NOTIFICATION_ID, notification())
        permissionListener = AppOpsManager.OnOpChangedListener { _, packageName ->
            if (packageName == this.packageName) work.launch {
                if (!Settings.canDrawOverlays(this@EditorOverlayService)) {
                    session.closeRequest?.invoke() ?: stopSelf()
                }
            }
        }.also { getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in setOf(ACTION_OPEN, ACTION_CAPTURE) && ScriptLaunchSessions.status.value != null) {
            message("请先关闭脚本配置窗口，再编辑")
            if (projectId == null) stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CLOSE -> { session.restore(); session.closeRequest?.invoke() ?: stopSelf() }
            ACTION_RESTORE -> if (projectId == null) stopSelf() else {
                session.restore(); getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            ACTION_OPEN -> {
                val id = intent.getStringExtra(EXTRA_PROJECT)
                if (!validEditorProjectId(id)) {
                    message("项目标识无效，已有编辑会话保持不变")
                    if (projectId == null) stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    message("编辑需要允许显示在其他应用上层")
                    session.closeRequest?.invoke() ?: stopSelf()
                    return START_NOT_STICKY
                }
                when (editorSessionDecision(projectId, id!!)) {
                    EditorSessionDecision.RESTORE -> session.restore()
                    EditorSessionDecision.REJECT -> message("请先关闭当前项目的悬浮编辑，再切换项目")
                    EditorSessionDecision.OPEN -> open(id)
                }
            }
            ACTION_CAPTURE -> {
                val id = intent.getStringExtra(EXTRA_PROJECT)
                val token = intent.getStringExtra("token")
                val width = intent.getIntExtra("width", 0)
                val height = intent.getIntExtra("height", 0)
                if (validEditorProjectId(id) && token != null && Regex("[a-f0-9]{32}").matches(token) &&
                    width in 1..8192 && height in 1..8192 && Settings.canDrawOverlays(this)) {
                    if (editorSessionDecision(projectId, id!!) == EditorSessionDecision.REJECT) message("请先关闭当前项目的编辑窗口")
                    else {
                        pendingCapture = StudioCaptureHandoff(id, token, width, height)
                        if (projectId == null) open(id) else if (snapshot != null) readCapture()
                    }
                } else if (projectId == null) stopSelf()
            }
            else -> if (projectId == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun open(id: String) {
        runtimeControl.open(runtime, ::setRuntimeControlEnabled)
        projectId = id
        EditorOverlaySessions.publish(EditorOverlayStatus(id))
        loadJob = work.launch {
            try {
                val store = ProjectStore(File(filesDir, "projects"))
                val loaded = withTimeout(15_000) { withContext(Dispatchers.IO) { store.openProject(id) } }
                check(StudioProjectPolicy.canOperate(loaded.manifest.sourceMode)) { "只支持可视化项目的悬浮编辑" }
                ensureActive()
                check(Settings.canDrawOverlays(this@EditorOverlayService)) { "悬浮授权已撤销" }
                snapshot = loaded
                session.restore()
                attach(store)
                readCapture()
            } catch (_: TimeoutCancellationException) { message("项目读取超时，请重试"); stopSelf() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("无法打开悬浮编辑，请检查项目和悬浮授权"); stopSelf() }
        }
    }

    private fun setRuntimeControlEnabled(enabled: Boolean): Boolean = client.setFloatingControlEnabled(enabled)

    private fun readCapture() {
        val handoff = pendingCapture ?: return
        captureJob?.cancel()
        captureJob = work.launch {
            var decoded: android.graphics.Bitmap? = null
            try {
                withContext(Dispatchers.IO) { decoded = decodeCaptureHandoff(this@EditorOverlayService, handoff) }
                ensureActive()
                if (pendingCapture?.token != handoff.token || projectId != handoff.projectId) return@launch
                captureBitmap?.recycle()
                captureBitmap = decoded; decoded = null; captureToken = handoff.token
                session.restore()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("截图无法读取，请重新截屏") }
            finally { decoded?.recycle() }
        }
    }

    private fun attach(store: ProjectStore) {
        val container = EditorViewport(this)
        root = container
        container.setViewTreeLifecycleOwner(this)
        container.setViewTreeSavedStateRegistryOwner(this)
        container.setViewTreeViewModelStoreOwner(this)
        container.setViewTreeOnBackPressedDispatcherOwner(this)
        compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CompositionLocalProvider(LocalEditorOverlay provides session,
                    LocalActivityResultRegistryOwner provides results,
                    LocalOnBackPressedDispatcherOwner provides this@EditorOverlayService) {
                    AutoScriptTheme {
                        snapshot?.let { current -> VisualProjectScreen(current, store, client, runtime, console.lines,
                            active = true, modifier = Modifier.fillMaxSize(),
                            initialImageToolBitmap = captureBitmap, initialImageToolToken = captureToken,
                            onInitialImageToolConsumed = { captureBitmap = null; captureToken = null; pendingCapture = null },
                            onSnapshotChanged = { snapshot = it }, onExit = { stopSelf() }) }
                    }
                }
            }
        }.also { container.addView(it) }
        params = WindowManager.LayoutParams(1, 1, editorOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) { setFitInsetsTypes(0); setFitInsetsSides(0) }
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Seed a real first layout before the 1x1 bootstrap view can report Compose bounds.
        // Without this, the native window and its child can remain 1x1 forever on some devices.
        val displaySize = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(displaySize)
        // Seed the same physical screen area Compose uses. A position-only correction immediately
        // after attach can leave legacy ViewRoot screen coordinates at the old origin.
        statusBar = EditorStatusBarObserver(container, manager, ::updateWindow)
        val workArea = editorWorkArea(displaySize.x, displaySize.y, statusBar?.top ?: 0)
        session.workArea(workArea)
        session.bounds(defaultEditorDockBounds(displaySize.x, displaySize.y, resources.displayMetrics.density, workArea,
            androidx.compose.ui.geometry.Size(displaySize.x.toFloat(), displaySize.y.toFloat())))
        ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Seed the full virtual canvas before its first measure, even though the native window starts at 1×1.
        updateWindow()
        container.doOnAttach { updateWindow() }
        manager.addView(container, params)
        ball = StudioOverlayBall(this, { session.restore() }, {
            if (!destroyed) { message("吸附球窗口不可用"); session.closeRequest?.invoke() ?: stopSelf() }
        }).also { it.setBrandState(studioBrandState(runtime)) }
        updateWindow()
        work.launch {
            snapshotFlow { session.shouldSampleRealtimeLog && runtime.phase == com.autoscript.core.model.RuntimeConnectionPhase.CONNECTED }
                .collectLatest { visible ->
                    if (visible) while (isActive) {
                        console.recordRemote(withContext(Dispatchers.IO) { client.recentRuntimeLogs(MAX_CONSOLE_LINES) })
                        delay(500)
                    }
                }
        }
    }

    private fun updateWindow() {
        val container = root ?: return
        val layout = params ?: return
        if (destroyed || windowUnavailable) return
        val point = Point()
        @Suppress("DEPRECATION") manager.defaultDisplay.getRealSize(point)
        session.workArea(editorWorkArea(point.x, point.y, statusBar?.top ?: 0))
        val bounds = session.dockBounds
        val viewport = editorWindowBounds(bounds.left, bounds.top, bounds.right, bounds.bottom,
            point.x, point.y, session.dockModal || session.canvasVisible)
        container.configure(point.x, point.y, viewport.left, viewport.top)
        if (!container.isAttachedToWindow) return
        layout.width = viewport.width; layout.height = viewport.height; layout.x = viewport.left; layout.y = viewport.top
        val visibility = editorWindowVisibility(session.hidden, session.panelExpanded, session.canvasVisible, session.dockModal)
        // Keep the native View's visibility in step with the window. Alpha/flags alone do not
        // notify Compose's window lifecycle or remove invisible controls from accessibility.
        // INVISIBLE preserves the existing composition, measured canvas, drafts and geometry.
        container.visibility = if (visibility.panel) View.VISIBLE else View.INVISIBLE
        layout.alpha = if (visibility.panel) 1f else 0f
        layout.flags = if (!visibility.panel) layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { manager.updateViewLayout(container, layout) }.onSuccess {
            if (!session.hidden && bounds.width > 1f && bounds.height > 1f) {
                projectId?.let { EditorOverlaySessions.publish(EditorOverlayStatus(it, ready = true)) }
            }
        }.onFailure {
            windowUnavailable = true
            message("悬浮窗口不可用，正在保存并关闭")
            session.closeRequest?.invoke() ?: stopSelf()
        }
        ball?.show(visibility.ball)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        statusBar?.configurationChanged()
        updateWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        statusBar?.close(); statusBar = null
        session.suppress("disposed")
        session.closeDialogs()
        EditorSystemRequests.cancel(session)
        loadJob?.cancel()
        compose?.disposeComposition()
        ball?.close(); ball = null
        captureBitmap?.recycle(); captureBitmap = null
        root?.let { runCatching { manager.removeViewImmediate(it) } }
        root = null; compose = null
        permissionListener?.let { getSystemService(AppOpsManager::class.java).stopWatchingMode(it) }
        client.cancelRecordingOnClose()
        client.onStateChanged = null
        runtimeControl.close(client.runtimeStates.value, ::setRuntimeControlEnabled)
        client.unbind()
        work.cancel()
        if (ownerLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (ownerLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        ownerLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        EditorOverlaySessions.publish(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "悬浮编辑", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(this, 31, Intent(this, EditorOverlayService::class.java).setAction(ACTION_RESTORE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 32, Intent(this, EditorOverlayService::class.java).setAction(ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setSmallIcon(R.drawable.nav_workspace_24).setContentTitle("智构 · 悬浮编辑")
            .setContentText("可在其他App上编写；点击恢复，关闭前保存草稿")
            .setContentIntent(restore).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.nav_workspace_24), "恢复编辑", restore).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.editor_close_24), "保存关闭", close).build()).build()
    }

    companion object {
        internal const val ACTION_OPEN = "com.autoscript.studio.editor.OPEN"
        internal const val ACTION_RESTORE = "com.autoscript.studio.editor.RESTORE"
        internal const val ACTION_CLOSE = "com.autoscript.studio.editor.CLOSE"
        internal const val ACTION_CAPTURE = "com.autoscript.studio.editor.CAPTURE"
        internal const val EXTRA_PROJECT = "projectId"
        private const val CHANNEL = "studio-editor"
        private const val NOTIFICATION_ID = 4301
        internal fun openCapture(context: Context, handoff: StudioCaptureHandoff) {
            ContextCompat.startForegroundService(context, Intent(context, EditorOverlayService::class.java)
                .setAction(ACTION_CAPTURE).putExtra(EXTRA_PROJECT, handoff.projectId).putExtra("token", handoff.token)
                .putExtra("width", handoff.width).putExtra("height", handoff.height))
        }
    }
}

/** Child is always a full-screen virtual canvas; the outer native window clips to the visible panel. */
private class EditorViewport(context: Context) : FrameLayout(context) {
    private var virtualWidth = 1
    private var virtualHeight = 1
    private var originX = 0
    private var originY = 0
    fun configure(width: Int, height: Int, x: Int, y: Int) {
        if (virtualWidth == width && virtualHeight == height && originX == x && originY == y) return
        virtualWidth = width.coerceAtLeast(1); virtualHeight = height.coerceAtLeast(1); originX = x; originY = y
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0)?.layout(-originX, -originY, virtualWidth - originX, virtualHeight - originY)
    }
}
