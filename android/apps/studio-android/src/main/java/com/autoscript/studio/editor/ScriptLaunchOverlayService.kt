package com.autoscript.studio

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.ScriptUiDialog
import com.autoscript.script.ui.ScriptUiWindowView
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File

/** Studio configuration entry, retained across execution. Never part of the independent Runner. */
class ScriptLaunchOverlayService : Service() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: RuntimeClient
    private lateinit var store: ProjectStore
    private var state = RuntimeConnectionState()
    private val returnGate = EditorRunReturnGate()
    private var projectId: String? = null
    private var definition: String? = null
    private var model: ScriptUiDefinition? = null
    private var view: ScriptUiWindowView? = null
    private var dialog: ScriptUiDialog? = null
    private var ball: StudioOverlayBall? = null
    private var loadJob: Job? = null
    private var startJob: Job? = null
    private var running = false
    private var returnedDuringStart = false
    private var destroyed = false
    private var permissionListener: AppOpsManager.OnOpChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        store = ProjectStore(File(filesDir, "projects"))
        client = RuntimeClient(this).also { runtime ->
            runtime.onStateChanged = { value ->
                if (!destroyed) {
                    state = value
                    if (returnGate.observe(value)) {
                        returnedDuringStart = true
                        running = false
                        restore(if (value.phase != RuntimeConnectionPhase.CONNECTED) "Runner连接中断，请检查后重试"
                            else if (value.engineState == RuntimeEngineState.FAILED) "运行失败，请查看运行日志" else null)
                    }
                }
            }
            runtime.bind()
        }
        startForeground(NOTIFICATION_ID, notification())
        permissionListener = AppOpsManager.OnOpChangedListener { _, name ->
            if (name == packageName) work.launch {
                if (!Settings.canDrawOverlays(this@ScriptLaunchOverlayService)) {
                    message("悬浮授权已撤销，已关闭配置窗口；不会隐式停止正在运行的脚本")
                    stopSelf()
                }
            }
        }.also { getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> stopSelf()
            ACTION_CANCEL -> if (startJob?.isActive == true) startJob?.cancel() else restore()
            ACTION_RESTORE -> if (running) message("脚本运行中，请用运行球暂停或停止") else if (startJob?.isActive == true)
                message("正在准备运行，可从通知取消启动") else if (projectId != null) restore() else stopSelf()
            ACTION_OPEN -> {
                val id = intent.getStringExtra(EXTRA_PROJECT)
                if (!validEditorProjectId(id) || !Settings.canDrawOverlays(this)) {
                    message("脚本界面需要允许显示在其他应用上层"); if (projectId == null) stopSelf()
                } else if (!canOpenScriptLaunch(EditorOverlaySessions.status.value?.projectId, projectId, id!!)) {
                    message("请先关闭已有的编辑或配置窗口"); if (projectId == null) stopSelf()
                } else if (projectId == id) {
                    if (!running && startJob?.isActive != true) restore()
                    else message("项目已在运行或启动中")
                } else open(id)
            }
            else -> if (projectId == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publish(phase: ScriptLaunchPhase) {
        projectId?.let { ScriptLaunchSessions.publish(ScriptLaunchStatus(it, phase)) }
    }

    private fun open(id: String) {
        projectId = id; publish(ScriptLaunchPhase.LOADING)
        loadJob = work.launch {
            try {
                val snapshot = withTimeout(15_000) { withContext(Dispatchers.IO) { store.openProject(id) } }
                require(StudioProjectPolicy.canOperate(snapshot.manifest.sourceMode)) { StudioProjectPolicy.historicalMessage }
                val source = requireNotNull(snapshot.manifest.runnerUi) { "项目没有脚本界面" }.toString()
                val parsed = ScriptUiDefinition.parse(source)
                ensureActive()
                check(Settings.canDrawOverlays(this@ScriptLaunchOverlayService)) { "悬浮权限已撤销" }
                definition = source; model = parsed
                val prefs = getSharedPreferences("script-ui-config", MODE_PRIVATE)
                val initial = parsed.fields.associate { it.id to prefs.getString("$id:${it.id}", it.initialValue).orEmpty() }
                    .let { runCatching { parsed.validateValues(it) }.getOrDefault(parsed.initialValues()) }
                val content = ScriptUiWindowView(this@ScriptLaunchOverlayService, parsed, initial,
                    { scriptUiImage(snapshot.directory, it) }, "脚本界面 · ${snapshot.manifest.name}") { _, _, _, action ->
                    when (action) {
                        "run" -> runProject()
                        "close" -> if (startJob?.isActive != true) stopSelf()
                        "minimize" -> if (!running && startJob?.isActive != true) {
                            dialog?.hide(); ball?.show(true)
                        }
                    }
                }
                view = content
                dialog = ScriptUiDialog(this@ScriptLaunchOverlayService, content, systemOverlay = true) { stopSelf() }
                ball = StudioOverlayBall(this@ScriptLaunchOverlayService, { restore() }, { message("配置窗口无法恢复，请检查悬浮授权"); stopSelf() })
                restore()
            } catch (_: TimeoutCancellationException) { message("配置读取超时，请重试"); stopSelf() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message(failure.message?.take(200) ?: "配置界面无法打开"); stopSelf() }
        }
    }

    private fun runProject() {
        if (running || startJob?.isActive == true || destroyed) return
        val id = projectId ?: return
        val content = view ?: return
        val parsed = model ?: return
        val values = runCatching { parsed.validateValues(content.values()) }.getOrElse {
            content.error(it.message ?: "参数无效"); return
        }
        if (state.phase != RuntimeConnectionPhase.CONNECTED) { content.error("Runner尚未连接，请稍后重试"); return }
        if (state.engineState in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) {
            content.error("已有脚本正在运行，请先停止"); return
        }
        getSharedPreferences("script-ui-config", MODE_PRIVATE).edit {
            values.forEach { (key, value) -> putString("$id:$key", value) }
        }
        // Hide before any compile/Root wait. Keep this exact View tree and its entered parameters.
        dialog?.setBusy(true); dialog?.hide(); ball?.show(false)
        returnedDuringStart = false
        publish(ScriptLaunchPhase.STARTING)
        startJob = work.launch {
            var accepted = false
            try {
                withTimeout(120_000) {
                    val snapshot = withContext(Dispatchers.IO) { store.openProject(id) }
                    require(snapshot.manifest.runnerUi?.toString() == definition) { "脚本界面已变化，请关闭窗口并重新启动" }
                    val compiled = withContext(Dispatchers.IO) { client.compileVisualProject(id) }
                    val generation = when (compiled) {
                        is VisualCompileResult.Success -> compiled.generationId
                        is VisualCompileResult.Invalid -> throw IllegalArgumentException(compiled.diagnostic.message)
                        is VisualCompileResult.Unavailable -> throw IllegalStateException(compiled.message)
                    }
                    val refreshed = withContext(Dispatchers.IO) { store.openProject(id) }
                    require(refreshed.manifest.runnerUi?.toString() == definition) { "脚本界面已变化，请关闭窗口并重新启动" }
                    val plan = withContext(Dispatchers.IO) { RuntimeProjectPlan.fromVisualSnapshot(refreshed, generation).withInterface(refreshed, values) }
                    ensureActive()
                    returnGate.arm(state)
                    accepted = withContext(Dispatchers.IO) { client.startProject(id, plan.luaSource, plan.resources,
                        requiresPointerInput = plan.requiresPointerInput, capabilities = plan.capabilities,
                        requiredVisionComponents = plan.requiredVisionComponents,
                        designWidth = plan.designWidth, designHeight = plan.designHeight, scaleMode = plan.scaleMode,
                        scriptUiJson = definition, scriptUiValuesJson = Gson().toJson(values), floatingControl = true) }
                    check(accepted) { "Runner拒绝启动，请检查Root和权限后重试" }
                    if (!returnedDuringStart) { running = true; publish(ScriptLaunchPhase.RUNNING) }
                }
            } catch (_: TimeoutCancellationException) {
                if (!destroyed) restore("启动超时，请检查Root和Runner后重试")
            } catch (cancelled: CancellationException) {
                if (!destroyed) restore("已取消启动")
                throw cancelled
            } catch (failure: Exception) {
                if (!destroyed) restore(failure.message?.take(300) ?: "启动失败")
            } finally {
                if (!accepted) returnGate.cancel()
                startJob = null
                if (!destroyed && !running) dialog?.setBusy(false)
            }
        }
    }

    private fun restore(error: String? = null) {
        if (destroyed || dialog == null || running) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        ball?.show(false)
        dialog?.setBusy(false)
        view?.error(error.orEmpty())
        runCatching { dialog?.show() }.onSuccess { publish(ScriptLaunchPhase.CONFIGURING) }
            .onFailure { message("配置窗口无法显示，请检查悬浮授权"); stopSelf() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); dialog?.resize(); ball?.resize()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true; returnGate.cancel()
        loadJob?.cancel(); startJob?.cancel(); work.cancel()
        dialog?.close(); ball?.close(); dialog = null; ball = null; view = null
        permissionListener?.let { getSystemService(AppOpsManager::class.java).stopWatchingMode(it) }
        client.onStateChanged = null; client.unbind()
        if (ScriptLaunchSessions.status.value?.projectId == projectId) ScriptLaunchSessions.publish(null)
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "脚本配置界面", NotificationManager.IMPORTANCE_LOW))
        fun intent(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ScriptLaunchOverlayService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val restore = intent(ACTION_RESTORE, 41); val cancel = intent(ACTION_CANCEL, 42); val close = intent(ACTION_CLOSE, 43)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else { @Suppress("DEPRECATION") Notification.Builder(this) }
        fun action(label: String, pending: PendingIntent) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.nav_workspace_24), label, pending).build()
        return builder.setSmallIcon(R.drawable.nav_workspace_24).setContentTitle("智构 · 脚本界面")
            .setContentText("停止后返回配置；关闭窗口不会隐式停止脚本").setContentIntent(restore).setOngoing(true)
            .addAction(action("恢复界面", restore)).addAction(action("取消启动", cancel)).addAction(action("关闭窗口", close)).build()
    }
    companion object {
        internal const val ACTION_OPEN = "com.autoscript.studio.launch.OPEN"
        internal const val ACTION_RESTORE = "com.autoscript.studio.launch.RESTORE"
        internal const val ACTION_CANCEL = "com.autoscript.studio.launch.CANCEL"
        internal const val ACTION_CLOSE = "com.autoscript.studio.launch.CLOSE"
        internal const val EXTRA_PROJECT = "projectId"
        private const val CHANNEL = "studio-script-configuration"
        private const val NOTIFICATION_ID = 4302
    }
}
