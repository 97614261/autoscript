package com.autoscript.studio

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.graphics.Point
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.InputDevice
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in Android test APK. Only a freshly owned QA project is edited or executed. */
class StudioAcceptanceInstrumentation : Instrumentation() {
    private val cases = JSONArray()
    private var deadline = 0L
    private lateinit var client: RuntimeClient
    private var owned: ProjectSnapshot? = null
    private lateinit var store: ProjectStore
    private var activity: Activity? = null
    private var waitingFor: String? = null
    private var lastClick: JSONObject? = null
    private var finalClosure = false
    private var keyboardOnly = false
    private var dialogsOnly = false
    private var workflowsOnly = false
    private var brandOnly = false
    private var workspaceOnly = false
    private var homeOnly = false
    private var experienceOnly = false
    private var stabilityOnly = false
    private var boundariesOnly = false
    private var performanceOnly = false
    private var soakMillis = 0L
    private var retentionMode: String? = null
    private var skipRotation = false
    private val active = setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)

    override fun onCreate(arguments: Bundle?) {
        finalClosure = arguments?.getString("finalClosure") == "true"
        keyboardOnly = arguments?.getString("keyboardOnly") == "true"
        dialogsOnly = arguments?.getString("dialogsOnly") == "true"
        workflowsOnly = arguments?.getString("workflowsOnly") == "true"
        brandOnly = arguments?.getString("brandOnly") == "true"
        workspaceOnly = arguments?.getString("workspaceOnly") == "true"
        homeOnly = arguments?.getString("homeOnly") == "true"
        experienceOnly = arguments?.getString("experienceOnly") == "true"
        stabilityOnly = arguments?.getString("stabilityOnly") == "true"
        boundariesOnly = arguments?.getString("boundariesOnly") == "true"
        performanceOnly = arguments?.getString("performanceOnly") == "true"
        soakMillis = arguments?.getString("soakSeconds")?.toLongOrNull()?.takeIf { it in 60..900 }?.times(1000) ?: 0L
        retentionMode = arguments?.getString("retentionMode")?.takeIf { it in setOf("prepare", "verify") }
        skipRotation = arguments?.getString("skipRotation") == "true"
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        retentionMode?.let { mode ->
            val report = runCatching {
                client = RuntimeClient(targetContext)
                main { client.bind() }
                awaitState { it.phase == RuntimeConnectionPhase.CONNECTED }
                check(client.runtimeStates.value.engineState !in active && EditorOverlaySessions.status.value == null &&
                    ScriptLaunchSessions.status.value == null) { "USER_RUN_OR_WINDOW_ACTIVE" }
                StudioDataRetentionFixture.run(this, mode == "prepare")
            }.getOrElse { failure ->
                JSONObject().put("pass", false).put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "QA_RETENTION_FAILED")
            }
            if (::client.isInitialized) main { client.unbind() }
            finish(if (report.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", report.toString(2) + "\n") })
            return
        }
        deadline = SystemClock.elapsedRealtime() + if (stabilityOnly) soakMillis + 180_000 else if (finalClosure || boundariesOnly || performanceOnly) 360_000 else 150_000
        val report = JSONObject().put("scope", "real Studio overlay and own synthetic core.task project")
            .put("sdk", Build.VERSION.SDK_INT).put("cases", cases)
        try {
            check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "USER_WINDOW_ACTIVE" }
            client = RuntimeClient(targetContext)
            main { client.bind() }
            awaitState { it.phase == RuntimeConnectionPhase.CONNECTED }
            check(client.runtimeStates.value.engineState !in active) { "USER_RUN_ACTIVE" }
            check(Settings.canDrawOverlays(targetContext)) { "OVERLAY_PERMISSION_REQUIRED" }
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            // singleTop can reuse the existing Activity, leaving startActivitySync waiting
            // forever for a creation event. Start an explicitly new, owned QA task instead.
            val activityMonitor = addMonitor(MainActivity::class.java.name, null, false)
            try {
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity = activityMonitor.waitForActivityWithTimeout(8_000) ?: error("QA_ACTIVITY_START_TIMEOUT")
            } finally { removeMonitor(activityMonitor) }
            check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "USER_WINDOW_ACTIVE" }
            check(client.runtimeStates.value.engineState !in active) { "USER_RUN_ACTIVE" }
            store = ProjectStore(File(targetContext.filesDir, "projects"), idFactory = { "project-qa-${UUID.randomUUID()}" })
            val size = Point()
            @Suppress("DEPRECATION") targetContext.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
            val initial = store.createProject("公开合成验收", ProjectSourceMode.VISUAL,
                designWidth = size.x, designHeight = size.y, initialCapabilities = setOf("core.task"))
            owned = initial
            sendStatus(1, Bundle().apply { putString("stream", "QA_OWNED_PROJECT:${initial.manifest.projectId}\n") })
            val flow = initial.manifest.flows.single()
            owned = store.saveFlows(initial, mapOf(flow.flowId to source(flow.rootBlockId)))
            if (performanceOnly) {
                report.put("scope", "fixed public synthesis; real Root capture and Rust matching; stage percentiles not additive")
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioPerformanceAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case)
            } else if (boundariesOnly) {
                StudioDataSafetyAcceptanceFixture.run(this, ::case)
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case, extended = true)
            } else if (stabilityOnly) {
                check(soakMillis > 0L) { "QA_SOAK_DURATION_REQUIRED" }
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case, soakMillis)
            } else if (experienceOnly) {
                HomeSpaceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
                ProjectWorkspaceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
                StudioExperienceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if (homeOnly) {
                HomeSpaceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if (workspaceOnly) {
                ProjectWorkspaceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if (brandOnly) {
                BrandUiAcceptanceFixture.run(this, requireNotNull(activity), ::case)
            } else if (dialogsOnly) {
                dialogWindows()
                if (!skipRotation) editorRotation()
            } else if (workflowsOnly) {
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case)
            } else if (keyboardOnly) {
                dialogAndControlComposition()
            } else {
            main {
                ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
                    .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, initial.manifest.projectId))
            }
            runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == initial.manifest.projectId && it.ready } } }
            case("open-expanded-no-ball") {
                waitNode("最小化为智构悬浮球")
                val expected = defaultEditorDockBounds(size.x, size.y, targetContext.resources.displayMetrics.density)
                waitFor { windowBoundsOrNull("打开项目设置")?.let { it.width() == expected.width.toInt() && it.height() == expected.height.toInt() } == true }
                check(!exists("智构 · 恢复窗口")) { "BALL_WITH_EDITOR" }
            }
            case("maximize-restore") {
                val before = bounds("打开项目设置")
                val window = windowBounds("打开项目设置")
                click("最大化窗口"); waitNode("还原窗口")
                waitFor { windowBoundsOrNull("还原窗口")?.let { it.width() > window.width() } == true }
                click("还原窗口"); waitNode("最大化窗口")
                waitFor { windowBoundsOrNull("打开项目设置") == window }
                check(bounds("打开项目设置") == before) { "RESTORED_GEOMETRY_CHANGED" }
            }
            case("more-menu-stable") {
                val before = bounds("打开项目设置")
                val window = windowBounds("打开项目设置")
                val attempts = JSONArray()
                cases.getJSONObject(cases.length() - 1).put("attempts", attempts)
                repeat(3) { attempt ->
                    cases.getJSONObject(cases.length() - 1).put("iteration", attempt)
                    waitNode("更多")
                    val moreBounds = bounds("更多")
                    attempts.put(JSONObject().put("more", moreBounds.flattenToString()).put("header", bounds("打开项目设置").flattenToString()))
                    cases.getJSONObject(cases.length() - 1).put("lastMoreBounds", moreBounds.flattenToString())
                        .put("lastWindowBounds", windowBounds("更多").flattenToString())
                    tap(moreBounds); waitNode("运行变量")
                    // The menu backdrop intentionally occludes underlying accessibility nodes.
                    check(windowBounds("运行变量") == window) { "MORE_MOVED_EDITOR" }
                    // The non-focusable cross-app editor deliberately does not own system BACK.
                    // Dismiss through the observed menu backdrop, not a guessed screen coordinate.
                    tap(before)
                    waitAbsent("运行变量")
                    try { uiAutomation.waitForIdle(200, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
                }
            }
            case("minimize-ball-restore") {
                click("最小化为智构悬浮球"); waitNode("智构 · 恢复窗口"); waitAbsent("打开项目设置")
                click("智构 · 恢复窗口"); waitNode("打开项目设置"); waitAbsent("智构 · 恢复窗口")
            }
            check(client.runtimeStates.value.rootState == RuntimeRootState.READY) { "ROOT_NOT_READY" }
            case("idle-single-step-own-node") {
                click("QA_START_ROW") // Idle single-step deliberately requires an explicitly selected node.
                val previous = client.runtimeStates.value.stateRevision
                click("单步")
                // A quick node can complete before the StateFlow collector observes RUNNING.
                awaitState { it.stateRevision > previous && it.engineState in setOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED) }
                check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "NODE_TEST_FAILED" }
                waitNode("PUBLIC_QA_PROMPT") // Prove the selected prompt really executed, not just an idle status.
                waitNode("打开项目设置")
            }
            case("run-highlight-pause-step-resume") {
                click("运行"); awaitState { it.engineState == RuntimeEngineState.RUNNING }
                runBlocking { withTimeout(8_000) { client.executionPosition.first { p ->
                    p?.projectId == initial.manifest.projectId && (p.nodeId == "qa-loop-prompt" || p.recent.any { it.nodeId == "qa-loop-prompt" })
                } } }
                ownPosition()
                waitNode("停止"); check(exists("打开项目设置")) { "RUN_HID_EDITOR" }
                check(!exists("智构 · 恢复窗口")) { "RUN_CONTROL_BALL_VISIBLE" }
                click("暂停"); awaitState { it.engineState == RuntimeEngineState.PAUSED }
                val previous = client.runtimeStates.value.stateRevision
                click("单步")
                awaitState { it.engineState == RuntimeEngineState.PAUSED && it.stateRevision > previous }
                click("继续"); awaitState { it.engineState == RuntimeEngineState.RUNNING }
            }
            case("stop-preserves-minimized-restore") {
                ownPosition()
                click("最小化为智构悬浮球"); waitNode("智构 · 恢复窗口"); waitAbsent("打开项目设置")
                check(client.requestStop()) { "STOP_REJECTED" }
                awaitState { it.engineState !in active }
                check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "STOP_FAILED" }
                waitNode("智构 · 恢复窗口"); check(!exists("打开项目设置")) { "STOP_EXPANDED_EDITOR" }
                click("智构 · 恢复窗口"); waitNode("运行"); waitAbsent("智构 · 恢复窗口")
            }
            case("close-editor-and-ball") {
                click("保存并关闭编辑窗")
                runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
                waitAbsent("打开项目设置"); waitAbsent("智构 · 恢复窗口")
            }
            case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
            if (finalClosure) {
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                dialogAndControlComposition()
                imagePipeline()
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case)
                designerWindow()
                configurationReturn()
                dialogWindows()
                clientReconnect()
                editorRotation()
            }
            }
            report.put("pass", (0 until cases.length()).all { cases.getJSONObject(it).optBoolean("pass") })
        } catch (failure: Throwable) {
            report.put("pass", false).put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "STUDIO_ACCEPTANCE_FAILED")
                .put("waitingFor", waitingFor ?: JSONObject.NULL)
                .put("lastClick", lastClick ?: JSONObject.NULL)
                .put("knownQaControls", JSONObject().apply {
                    for (label in listOf("更多", "运行变量", "变量检查", "录制动作", "打开项目设置", "智构 · 恢复窗口", "项目设置", "运行设置", "取消", "保存")) put(label, exists(label))
                })
            if (waitingFor == "运行变量") {
                var more = find("更多")
                repeat(8) {
                    if (more != null && more?.isClickable != true) {
                        val parent = more?.parent
                        more?.recycle(); more = parent
                    }
                }
                report.put("diagnosticSemanticMoreAccepted", try { more?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } finally { more?.recycle() })
                try { uiAutomation.waitForIdle(200, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
                report.put("diagnosticSemanticMoreVisible", exists("运行变量"))
                // Diagnostic action never converts a failed real-touch case into a passing case.
            }
            if (waitingFor == "打开项目设置" && exists("智构 · 恢复窗口")) {
                val ball = find("智构 · 恢复窗口")
                report.put("diagnosticSemanticBallAccepted", try { ball?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } finally { ball?.recycle() })
                try { uiAutomation.waitForIdle(200, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
                report.put("diagnosticSemanticBallRestored", exists("打开项目设置"))
            }
        } finally {
            val cleanup = runCatching { cleanup() }.isSuccess
            report.put("cleanup", cleanup)
            if (!cleanup) report.put("pass", false)
            if (::client.isInitialized) main { client.unbind() }
            activity?.let { main { it.finish() } }
        }
        finish(if (report.optBoolean("pass")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2) + "\n") })
    }

    private fun rootSurface() {
        check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null)
        check(client.runtimeStates.value.engineState !in active)
        val touches = AtomicInteger()
        lateinit var surface: View
        main {
            surface = object : View(targetContext) {
                override fun onDraw(canvas: Canvas) { canvas.drawColor(if (touches.get() == 0) Color.rgb(18, 52, 86) else Color.rgb(32, 160, 96)) }
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_UP) { touches.incrementAndGet(); invalidate() }
                    return true
                }
            }
            requireNotNull(activity).setContentView(surface)
        }
        waitFor { surface.width > 0 && surface.height > 0 && surface.isShown }
        val location = IntArray(2)
        main { surface.getLocationOnScreen(location) }
        val x = location[0] + surface.width / 2; val y = location[1] + surface.height / 2
        var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
        snapshot = store.updateCapabilities(snapshot.manifest.projectId, setOf("core.task", "input.basic", "screen.capture", "vision.pixel"), snapshot.manifest.capabilities.toSet())
        snapshot = store.updateVariables(snapshot.manifest.projectId, listOf(
            ProjectVariable("qaFrame", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE),
            ProjectVariable("qaColor", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
        ), snapshot.manifest.variables)
        val flow = snapshot.manifest.flows.single()
        fun node(index: Int, kind: String, args: JSONObject) = JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", 1)
            .put("nodeId", "qa-io-$index").put("blockId", flow.rootBlockId).put("parentId", JSONObject.NULL)
            .put("orderKey", "a$index").put("kind", kind).put("args", args).toString()
        val source = listOf(
            node(0, "input.tap", JSONObject().put("x", x).put("y", y)),
            node(1, "task.sleep", JSONObject().put("milliseconds", 200)),
            node(2, "screen.capture", JSONObject().put("resultVariable", "qaFrame")),
            node(3, "vision.getcolor", JSONObject().put("frameVariable", "qaFrame").put("point", JSONObject().put("x", x).put("y", y)).put("resultVariable", "qaColor")),
            node(4, "screen.release", JSONObject().put("frameVariable", "qaFrame")),
            node(5, "task.sleep", JSONObject().put("milliseconds", 3000)),
        ).joinToString("\n", postfix = "\n")
        snapshot = store.saveFlows(snapshot, mapOf(flow.flowId to source)); owned = snapshot
        val compile = client.compileVisualProject(snapshot.manifest.projectId)
        check(compile is VisualCompileResult.Success) { "QA_IO_COMPILE_FAILED" }
        val frozen = store.openProject(snapshot.manifest.projectId)
        val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compile.generationId).forEditorRun(frozen)
        check(runBlocking { withTimeout(20_000) { client.startProject(frozen.manifest.projectId, plan.luaSource, plan.resources, plan.capabilities,
            plan.designWidth, plan.designHeight, plan.scaleMode, requiresPointerInput = plan.requiresPointerInput,
            floatingControl = false, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_IO_START_FAILED" }
        runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == frozen.manifest.projectId && it.nodeId == "qa-io-5" } } }
        ownPosition()
        val reply = client.debugSnapshot(frozen.manifest.projectId)
        check(reply.status == RuntimeDebugReply.SUCCESS && touches.get() == 1) { "ROOT_TAP_NOT_RECEIVED" }
        check(reply.variables.singleOrNull { it.name == "qaColor" }?.value?.toLongOrNull() == 0x20A060L) { "ROOT_CAPTURE_NOT_FRESH" }
        check(client.requestStop()) { "STOP_REJECTED" }
        awaitState { it.engineState !in active }
        check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED)
    }

    private fun ownPosition() {
        // Resume clears the paused position before the next real event arrives. A null
        // delivery is not a foreign identity; wait boundedly, then validate the first one.
        val position = runBlocking { withTimeout(8_000) { client.executionPosition.first { it != null } } }
        check(position?.projectId == owned?.manifest?.projectId &&
            position?.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "RUN_IDENTITY_CHANGED" }
        check(client.debugSnapshot(requireNotNull(owned).manifest.projectId).status == RuntimeDebugReply.SUCCESS) { "RUN_IDENTITY_CHANGED" }
    }
    private fun configurationReturn() {
        var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
        val definition = com.autoscript.script.ui.ScriptUiDefinition(fields = listOf(
            com.autoscript.script.ui.UiField("qaInput", "QA参数", "text", true, "PUBLIC_QA_CONFIG"),
        ))
        snapshot = store.updateRunnerUi(snapshot.manifest.projectId, definition.toJson(), snapshot.manifest.runnerUi)
        snapshot = store.updateVariables(snapshot.manifest.projectId, snapshot.manifest.variables +
            ProjectVariable("qaConfig", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING), snapshot.manifest.variables)
        val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
        val sleep = JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", 1).put("nodeId", "qa-config-wait")
            .put("blockId", flow.rootBlockId).put("parentId", JSONObject.NULL).put("orderKey", "b0")
            .put("kind", "task.sleep").put("args", JSONObject().put("milliseconds", 60_000)).toString() + "\n"
        val read = JSONObject(sleep).put("nodeId", "qa-config-read").put("orderKey", "a0").put("kind", "ui.get")
            .put("args", JSONObject().put("controlId", "qaInput").put("resultVariable", "qaConfig")).toString() + "\n"
        snapshot = store.saveFlows(snapshot, mapOf(flow.flowId to read + sleep)); owned = snapshot
        case("project-start-config-run-stop-returns-original-parameters") {
            main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, ScriptLaunchOverlayService::class.java)
                .setAction(ScriptLaunchOverlayService.ACTION_OPEN).putExtra(ScriptLaunchOverlayService.EXTRA_PROJECT, snapshot.manifest.projectId)) }
            runBlocking { withTimeout(10_000) { ScriptLaunchSessions.status.first { it?.phase == ScriptLaunchPhase.CONFIGURING } } }
            waitNode("PUBLIC_QA_CONFIG")
            check(client.runtimeStates.value.engineState !in active) { "QA_CONFIG_AUTO_RAN" }
            click("运行"); awaitState { it.engineState == RuntimeEngineState.RUNNING }
            runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == snapshot.manifest.projectId && it.nodeId == "qa-config-wait" } } }
            ownPosition()
            check(client.debugSnapshot(snapshot.manifest.projectId).variables.singleOrNull { it.name == "qaConfig" }?.value == "PUBLIC_QA_CONFIG") { "QA_CONFIG_READBACK_FAILED" }
            waitAbsent("PUBLIC_QA_CONFIG")
            check(client.requestStop()) { "QA_CONFIG_STOP_REJECTED" }
            awaitState { it.engineState == RuntimeEngineState.STOPPED }
            runBlocking { withTimeout(10_000) { ScriptLaunchSessions.status.first { it?.phase == ScriptLaunchPhase.CONFIGURING } } }
            waitNode("PUBLIC_QA_CONFIG")
            main { targetContext.startService(Intent(targetContext, ScriptLaunchOverlayService::class.java).setAction(ScriptLaunchOverlayService.ACTION_CLOSE)) }
            runBlocking { withTimeout(8_000) { ScriptLaunchSessions.status.first { it == null } } }
            waitAbsent("PUBLIC_QA_CONFIG")
        }
    }
    /** Runs one owned flow through calculation, nested control blocks, a jump and both dialog forms. */
    private fun dialogAndControlComposition() {
        case("runtime-confirm-layout-dialog-and-control-composition") {
            var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
            val id = snapshot.manifest.projectId
            val ui = com.autoscript.script.ui.ScriptUiDefinition(
                version = 2,
                pages = listOf(
                    com.autoscript.script.ui.UiPage(),
                    com.autoscript.script.ui.UiPage("qaDialog", "QA布局", 720, 960),
                ),
                fields = listOf(com.autoscript.script.ui.UiField(
                    id = "qaDialogInput", label = "布局输入", kind = "text", required = false, initialValue = "PUBLIC_QA_DIALOG",
                    ui = com.autoscript.script.ui.UiPresentation(
                        control = "INPUT", pageId = "qaDialog", x = 24, y = 120, width = 672, height = 84, fontPx = 28,
                    ),
                )),
            )
            snapshot = store.updateRunnerUi(id, ui.toJson(), snapshot.manifest.runnerUi)
            snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + "ui.control", snapshot.manifest.capabilities.toSet())
            snapshot = store.updateVariables(id, snapshot.manifest.variables + listOf(
                ProjectVariable("qaCount", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
                ProjectVariable("qaIndex", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
                ProjectVariable("qaConfirm", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
                ProjectVariable("qaDialogResult", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
                ProjectVariable("qaDialogValue", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
            ), snapshot.manifest.variables)
            val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
            fun node(idText: String, block: String, order: String, kind: String, args: JSONObject,
                parent: String? = null, depth: Int = 0, children: JSONObject? = null): String = JSONObject()
                .put("flowSchemaVersion", 1).put("nodeVersion", 1).put("nodeId", idText)
                .put("blockId", block).put("parentId", parent ?: JSONObject.NULL).put("orderKey", order)
                .put("depth", depth).put("kind", kind).put("args", args)
                .apply { children?.let { put("childBlocks", it) } }.toString()
            val source = listOf(
                node("qa-compose-init", flow.rootBlockId, "a0", "variable.calculate", JSONObject().put("name", "qaCount").put("expression", "0")),
                node("qa-compose-repeat", flow.rootBlockId, "b0", "control.repeat", JSONObject().put("times", 3).put("indexVariable", "qaIndex"),
                    children = JSONObject().put("body", "qa-compose-body")),
                node("qa-compose-inc", "qa-compose-body", "a0", "variable.calculate", JSONObject().put("name", "qaCount").put("expression", "qaCount + 1"),
                    parent = "qa-compose-repeat", depth = 1),
                node("qa-compose-if", flow.rootBlockId, "c0", "control.if", JSONObject().put("variable", "qaCount").put("operator", "equals").put("value", 3),
                    children = JSONObject().put("then", "qa-compose-then").put("else", "qa-compose-else")),
                node("qa-compose-confirm", "qa-compose-then", "a0", "ui.confirm", JSONObject()
                    .put("title", "QA确认").put("message", "确认后继续布局弹窗").put("confirmText", "确定")
                    .put("cancelText", "取消").put("timeoutMs", 30_000).put("resultVariable", "qaConfirm"),
                    parent = "qa-compose-if", depth = 1),
                node("qa-compose-unexpected-else", "qa-compose-else", "a0", "task.runprompt", JSONObject().put("message", "QA_UNEXPECTED_ELSE"),
                    parent = "qa-compose-if", depth = 1),
                node("qa-compose-goto", flow.rootBlockId, "d0", "control.goto", JSONObject().put("name", "qaAfterJump")),
                node("qa-compose-skipped", flow.rootBlockId, "d1", "variable.calculate", JSONObject().put("name", "qaCount").put("expression", "999")),
                node("qa-compose-label", flow.rootBlockId, "e0", "control.label", JSONObject().put("name", "qaAfterJump")),
                node("qa-compose-dialog", flow.rootBlockId, "f0", "ui.dialog", JSONObject()
                    .put("pageId", "qaDialog").put("blockOutside", true).put("timeoutMs", 60_000).put("resultVariable", "qaDialogResult")),
                node("qa-compose-read", flow.rootBlockId, "f1", "ui.get", JSONObject().put("controlId", "qaDialogInput").put("resultVariable", "qaDialogValue")),
                node("qa-compose-after-dialog", flow.rootBlockId, "g0", "task.sleep", JSONObject().put("milliseconds", 60_000)),
            ).joinToString("\n", postfix = "\n")
            snapshot = store.saveFlows(snapshot, mapOf(flow.flowId to source)); owned = snapshot
            val compile = client.compileVisualProject(id)
            check(compile is VisualCompileResult.Success) { "QA_COMPOSITION_COMPILE_FAILED" }
            val frozen = store.openProject(id)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compile.generationId).forEditorRun(frozen)
            check(runBlocking { withTimeout(20_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_COMPOSITION_START_FAILED" }
            runBlocking { withTimeout(12_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == "qa-compose-confirm" } } }
            waitNode("QA确认"); click("确定")
            runBlocking { withTimeout(12_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == "qa-compose-dialog" } } }
            waitNode("QA布局")
            // Use only a real user tap; pre-focusing through accessibility can change IME policy.
            click("PUBLIC_QA_DIALOG")
            sendStatus(1, Bundle().apply { putString("stream", "QA_LAYOUT_KEYBOARD_OBSERVATION\n") })
            // Inspect only platform window metadata, never the user's IME tree or content.
            fun imeBounds(description: String = "QA键盘面板"): Rect? {
                val window = uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
                // Only inspect our explicitly approved QA keyboard. IME window frames can
                // include a fullscreen transparent area above the actual input view.
                var budget = 128
                fun panel(node: AccessibilityNodeInfo): Rect? {
                    try {
                        if (--budget < 0) return null
                        if (node.packageName?.toString() == "com.autoscript.vision.acceptance" &&
                            node.contentDescription?.toString() == description) return Rect().also(node::getBoundsInScreen)
                        for (index in 0 until node.childCount.coerceAtMost(32)) node.getChild(index)?.let { child -> panel(child)?.let { return it } }
                        return null
                    } finally { node.recycle() }
                }
                val ownPanel = window.root?.let { root ->
                    if (root.packageName?.toString() == "com.autoscript.vision.acceptance") panel(root)
                    else { root.recycle(); null }
                }
                val qaExpected = Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ==
                    "com.autoscript.vision.acceptance/com.autoscript.runtime.service.QaInputMethodService"
                if (qaExpected) return ownPanel?.takeUnless(Rect::isEmpty)
                if (description != "QA键盘面板") return null
                return (ownPanel ?: Rect().also(window::getBoundsInScreen)).takeUnless(Rect::isEmpty)
            }
            val imeDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 2_000)
            while (imeBounds() == null && SystemClock.elapsedRealtime() < imeDeadline) {
                try { uiAutomation.executeAndWaitForEvent({}, { true }, 200) } catch (_: java.util.concurrent.TimeoutException) { }
            }
            val keyboard = imeBounds()
            cases.getJSONObject(cases.length() - 1).put("imeObserved", keyboard != null)
                .put("occlusionCoverage", if (keyboard == null) "not_observed" else "detected_pending_check")
            var inputLabel = "PUBLIC_QA_DIALOG"
            if (keyboard != null && imeBounds("QA键盘输入") != null) {
                try { uiAutomation.waitForIdle(300, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
                requireNotNull(find(inputLabel)).let { field ->
                    try { check(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, inputLabel.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, inputLabel.length)
                    })) { "QA_IME_SELECTION_REJECTED" } } finally { field.recycle() }
                }
                tap(requireNotNull(imeBounds("QA键盘输入")))
                inputLabel += "QA"
                waitNode(inputLabel)
                cases.getJSONObject(cases.length() - 1).put("qaKeyboardCommit", true)
            }
            val input = requireNotNull(find(inputLabel))
            try {
                cases.getJSONObject(cases.length() - 1).put("inputFocused", input.isFocused)
                    .put("dialogFocused", uiAutomation.windows.firstOrNull { it.id == input.windowId }?.isFocused == true)
                check(input.isEditable && input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "PUBLIC_QA_TYPED")
                })) { "QA_DIALOG_INPUT_REJECTED" }
            } finally { input.recycle() }
            val keyboardRecord = JSONObject().put("case", "runtime-layout-keyboard-visible-and-actions-uncovered").put("pass", false)
                .put("code", if (keyboard == null) "QA_LAYOUT_IME_NOT_VISIBLE" else "QA_OCCLUSION_NOT_CHECKED")
            cases.put(keyboardRecord)
            waitNode("PUBLIC_QA_TYPED"); waitNode("QA_DIALOG_CLOSE_ACTION")
            if (keyboard != null) {
                var latestKeyboard: Rect = keyboard
                var unobscured = false
                try {
                    waitFor {
                        imeBounds()?.let { latestKeyboard = it }
                        unobscured = exists("QA_DIALOG_CLOSE_ACTION") && bounds("QA_DIALOG_CLOSE_ACTION").bottom <= latestKeyboard.top
                        unobscured
                    }
                } catch (failure: IllegalStateException) { if (failure.message != "UI_WAIT_TIMEOUT") throw failure }
                val closeBounds = bounds("QA_DIALOG_CLOSE_ACTION")
                cases.getJSONObject(cases.length() - 1).put("keyboardBounds", latestKeyboard.flattenToString())
                    .put("closeBounds", closeBounds.flattenToString()).put("dialogBounds", windowBounds("QA_DIALOG_CLOSE_ACTION").flattenToString())
                keyboardRecord.put("code", "QA_DIALOG_CLOSE_BEHIND_IME")
                check(unobscured) { "QA_DIALOG_CLOSE_BEHIND_IME" }
                keyboardRecord.put("pass", true).put("code", "OK")
                cases.getJSONObject(cases.length() - 2).put("occlusionCoverage", "checked")
            }
            click("QA_DIALOG_CLOSE_ACTION")
            runBlocking { withTimeout(12_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == "qa-compose-after-dialog" } } }
            val variables = client.debugSnapshot(id).variables.associate { it.name to it.value }
            cases.getJSONObject(cases.length() - 2).put("resultChecks", JSONObject()
                .put("count", variables["qaCount"] == "3").put("index", variables["qaIndex"] == "3")
                .put("confirm", variables["qaConfirm"] == "confirm").put("close", variables["qaDialogResult"] == "close")
                .put("input", variables["qaDialogValue"] == "PUBLIC_QA_TYPED"))
            check(variables["qaCount"] == "3" && variables["qaIndex"] == "3" && variables["qaConfirm"] == "confirm" &&
                variables["qaDialogResult"] == "close" && variables["qaDialogValue"] == "PUBLIC_QA_TYPED") {
                "QA_COMPOSITION_RESULT_MISMATCH"
            }
            ownPosition()
            check(client.requestStop()) { "QA_COMPOSITION_STOP_REJECTED" }
            awaitState { it.engineState == RuntimeEngineState.STOPPED }
            case("runtime-dialog-cancel-then-stop-removes-layout") {
                check(runBlocking { withTimeout(20_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                    plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                    requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_COMPOSITION_RESTART_FAILED" }
                waitNode("QA确认"); click("取消")
                waitNode("QA布局")
                ownPosition()
                check(client.debugSnapshot(id).variables.single { it.name == "qaConfirm" }.value == "cancel") { "QA_DIALOG_CANCEL_LOST" }
                check(client.requestStop()) { "QA_DIALOG_STOP_REJECTED" }
                awaitState { it.engineState == RuntimeEngineState.STOPPED }
                waitAbsent("QA布局"); waitAbsent("PUBLIC_QA_DIALOG")
            }
            case("runtime-stop-removes-confirm-without-opening-followup") {
                check(runBlocking { withTimeout(20_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                    plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                    requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_COMPOSITION_RESTART_FAILED" }
                waitNode("QA确认"); ownPosition()
                check(client.requestStop()) { "QA_CONFIRM_STOP_REJECTED" }
                awaitState { it.engineState == RuntimeEngineState.STOPPED }
                waitAbsent("QA确认"); check(!exists("QA布局")) { "QA_CANCELLED_DIALOG_CONTINUED" }
            }
            case("runtime-confirm-and-layout-deadlines-return-timeout") {
                // Change structured QA nodes and recompile; never patch generated Lua.
                val timedSource = source.lineSequence().filter(String::isNotBlank).map { line ->
                    JSONObject(line).apply {
                        if (getString("kind") in setOf("ui.confirm", "ui.dialog")) getJSONObject("args").put("timeoutMs", 1_000)
                    }.toString()
                }.joinToString("\n", postfix = "\n")
                val current = store.openProject(id)
                snapshot = store.saveFlows(current, mapOf(flow.flowId to timedSource)); owned = snapshot
                val timedCompile = client.compileVisualProject(id)
                check(timedCompile is VisualCompileResult.Success) { "QA_TIMEOUT_COMPILE_FAILED" }
                val timedSnapshot = store.openProject(id)
                val timedPlan = RuntimeProjectPlan.fromVisualSnapshot(timedSnapshot, timedCompile.generationId).forEditorRun(timedSnapshot)
                check(runBlocking { withTimeout(20_000) { client.startProject(id, timedPlan.luaSource, timedPlan.resources, timedPlan.capabilities,
                    timedPlan.designWidth, timedPlan.designHeight, timedPlan.scaleMode, floatingControl = false, scriptUiJson = timedPlan.scriptUiJson,
                    requiredVisionComponents = timedPlan.requiredVisionComponents) } }) { "QA_TIMEOUT_START_FAILED" }
                runBlocking { withTimeout(12_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == "qa-compose-after-dialog" } } }
                ownPosition()
                val timed = client.debugSnapshot(id).variables.associate { it.name to it.value }
                check(timed["qaConfirm"] == "timeout" && timed["qaDialogResult"] == "timeout") { "QA_DIALOG_TIMEOUT_RESULT" }
                waitAbsent("QA确认"); waitAbsent("QA布局")
                check(client.requestStop()) { "QA_TIMEOUT_STOP_REJECTED" }
                awaitState { it.engineState == RuntimeEngineState.STOPPED }
            }
        }
    }

    private fun imagePipeline() {
        case("compiled-image-workbench-chain-recognition-tail-and-release") {
            var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
            val id = snapshot.manifest.projectId
            snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + setOf("vision.template", "vision.pixel", "screen.capture"), snapshot.manifest.capabilities.toSet())
            snapshot = store.updateVariables(id, snapshot.manifest.variables + listOf(
                ProjectVariable("qaScratch", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE),
                ProjectVariable("qaProcessed", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE),
                ProjectVariable("qaFound", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
                ProjectVariable("qaX", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
                ProjectVariable("qaY", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER)), snapshot.manifest.variables)
            val path = snapshot.manifest.resources.first { it.get("kind").asString == "image" }.get("path").asString
            val original = File(snapshot.directory, path).readBytes()
            val pipeline = ImageProcessingPipeline(24, 16).append(ImagePixelOperation("gray"))
                .append(ImagePixelOperation("rotate90")).append(ImagePixelOperation("crop", roi = ImageToolCodeGen.Roi(0, 0, 8, 12)))
                .append(ImagePixelOperation("resize", width = 16, height = 24)).append(ImagePixelOperation("flipX"))
            val blocks = imageProcessingBlocks(pipeline, path, "qaScratch", "qaProcessed",
                snapshot.manifest.variables.map { CalculationVariable(it.name, it.type, "global") }, snapshot.manifest.resources).toMutableList()
            fun add(kind: String, args: JSONObject) { blocks += requireNotNull(BlockCatalog.find(kind)) to com.google.gson.JsonParser.parseString(args.toString()).asJsonObject }
            fun match(frame: String, found: String, width: Int, height: Int) = JSONObject().put("frameVariable", frame).put("imagePath", path)
                .put("tolerance", 0).put("similarityPermille", 1000).put("region", JSONObject().put("left", 0).put("top", 0).put("right", width).put("bottom", height))
                .put("foundVariable", found).put("xVariable", "qaX").put("yVariable", "qaY").put("autoCapture", false)
            add("vision.getcolor", JSONObject().put("frameVariable", "qaProcessed").put("point", JSONObject().put("x", 0).put("y", 0)).put("resultVariable", "qaColor"))
            // Read the transformed output, then explicitly reload the original for a positive template match.
            add("screen.release", JSONObject().put("frameVariable", "qaProcessed"))
            add("screen.loadimage", JSONObject().put("imagePath", path).put("resultVariable", "qaScratch"))
            add("vision.findimage", match("qaScratch", "qaFound", 24, 16))
            add("screen.release", JSONObject().put("frameVariable", "qaScratch"))
            add("task.sleep", JSONObject().put("milliseconds", 3000))
            val flow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
            val source = blocks.mapIndexed { index, (contract, args) -> JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", contract.nodeVersion)
                .put("nodeId", "qa-image-$index").put("blockId", flow.rootBlockId).put("parentId", JSONObject.NULL)
                .put("orderKey", "a%03d".format(index)).put("kind", contract.kind).put("args", JSONObject(args.toString())).toString() }.joinToString("\n", postfix = "\n")
            snapshot = store.saveFlows(snapshot, mapOf(flow.flowId to source)); owned = snapshot
            val compile = client.compileVisualProject(id)
            check(compile is VisualCompileResult.Success) { "QA_IMAGE_COMPILE_FAILED" }
            val frozen = store.openProject(id)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compile.generationId).forEditorRun(frozen)
            check(runBlocking { withTimeout(20_000) { client.startProject(id, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_IMAGE_START_FAILED" }
            runBlocking { withTimeout(12_000) { client.executionPosition.first { it?.projectId == id && it.nodeId == "qa-image-${blocks.lastIndex}" } } }
            ownPosition()
            val reply = client.debugSnapshot(id)
            check(reply.status == RuntimeDebugReply.SUCCESS) { "QA_IMAGE_DEBUG_FAILED" }
            val variables = reply.variables.associate { it.name to it.value }
            val expected = processImagePixels(intArrayOf(Color.rgb(32, 160, 96)), 1, 1, ImagePixelOperation("gray")) {}.argb.single() and 0xffffff
            check(variables["qaColor"]?.toIntOrNull() == expected && variables["qaFound"] == "1" && variables["qaX"] == "0" && variables["qaY"] == "0") { "QA_IMAGE_RESULT_MISMATCH" }
            check(File(frozen.directory, path).readBytes().contentEquals(original)) { "QA_IMAGE_ORIGINAL_CHANGED" }
            check(client.requestStop()) { "QA_IMAGE_STOP_REJECTED" }; awaitState { it.engineState == RuntimeEngineState.STOPPED }
        }
    }
    private fun designerWindow() {
        case("real-designer-page-add-undo-and-orientation-return") {
            val owner = requireNotNull(activity) as ComponentActivity
            val originalOrientation = owner.requestedOrientation
            val originalScreen = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
            val returned = java.util.concurrent.atomic.AtomicBoolean()
            val project = store.openProject(requireNotNull(owned).manifest.projectId)
            try {
                main { owner.setContent { com.autoscript.core.designsystem.AutoScriptTheme {
                    RunnerUiDesignerScreen(project, store, { owned = it }, { returned.set(true) })
                } } }
                waitNode("UI 设计"); waitNode("+ 页")
                click("+ 页"); waitNode("新页面")
                click("撤销"); waitAbsent("新页面")
                click("‹ 返回"); waitFor { returned.get() }
            } finally {
                main { owner.setContentView(View(owner)) }
                // UNSPECIFIED intentionally restores to USER, so an old forced orientation is not inherited.
                waitFor { owner.requestedOrientation == restoredScreenOrientation(originalOrientation) }
                waitFor { Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) } == originalScreen }
            }
            check(store.openProject(project.manifest.projectId).manifest.runnerUi == project.manifest.runnerUi) { "QA_DESIGN_UNDO_SAVED" }
        }
    }
    private fun clientReconnect() {
        case("idle-client-disconnect-rebind-does-not-replay-old-run") {
            check(client.runtimeStates.value.engineState !in active && ScriptLaunchSessions.status.value == null)
            main { client.unbind() }
            main { client.bind() }
            awaitState { it.phase == RuntimeConnectionPhase.CONNECTED }
            check(client.runtimeStates.value.engineState !in active) { "QA_OLD_RUN_REPLAYED" }
            check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "QA_OLD_WINDOW_REOPENED" }
        }
        case("active-own-run-client-rebind-and-stop") {
            val snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
            val compile = client.compileVisualProject(snapshot.manifest.projectId)
            check(compile is VisualCompileResult.Success) { "QA_RECONNECT_COMPILE" }
            val frozen = store.openProject(snapshot.manifest.projectId)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compile.generationId).forEditorRun(frozen)
            repeat(3) { iteration ->
                val record = cases.getJSONObject(cases.length() - 1)
                record.put("iteration", iteration).put("phase", "start")
                check(client.runtimeStates.value.engineState !in active) { "USER_RUN_ACTIVE" }
                check(runBlocking { withTimeout(20_000) { client.startProject(frozen.manifest.projectId, plan.luaSource, plan.resources, plan.capabilities,
                    plan.designWidth, plan.designHeight, plan.scaleMode, floatingControl = false, scriptUiJson = plan.scriptUiJson,
                    requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_RECONNECT_START" }
                record.put("phase", "initial-position")
                runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == frozen.manifest.projectId && it.nodeId == "qa-config-wait" } } }
                ownPosition()
                check(client.debugSnapshot(frozen.manifest.projectId).variables.singleOrNull { it.name == "qaConfig" }?.value == "PUBLIC_QA_CONFIG") { "QA_EDITOR_DEFAULT_VALUE" }
                record.put("phase", "rebind")
                main { client.unbind(); client.bind() }
                awaitState { it.phase == RuntimeConnectionPhase.CONNECTED && it.engineState == RuntimeEngineState.RUNNING }
                runBlocking { withTimeout(8_000) { client.executionPosition.first { it?.projectId == frozen.manifest.projectId && it.nodeId == "qa-config-wait" } } }
                record.put("phase", "debug-read")
                check(client.debugSnapshot(frozen.manifest.projectId).status == RuntimeDebugReply.SUCCESS) { "QA_RECONNECT_WRONG_RUN" }
                check(client.requestStop()) { "QA_RECONNECT_STOP" }
                record.put("phase", "stop")
                awaitState { it.engineState == RuntimeEngineState.STOPPED }
                check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "QA_RECONNECT_REOPENED_WINDOW" }
            }
        }
    }
    private fun dialogWindows() {
        case("real-log-settings-and-function-library-fullscreen-restore") {
            var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
            snapshot = store.updateCapabilities(snapshot.manifest.projectId,
                snapshot.manifest.capabilities.toSet() + setOf("screen.capture", "vision.template"), snapshot.manifest.capabilities.toSet())
            owned = snapshot
            main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
                .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, snapshot.manifest.projectId)) }
            runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == snapshot.manifest.projectId && it.ready } } }
            waitNode("日志"); click("日志"); waitNode("运行日志")
            click("最大化全屏"); waitNode("还原窗口"); click("还原窗口"); waitNode("最大化全屏")
            click("记录设置"); waitNode("日志记录设置"); click("取消"); waitAbsent("日志记录设置")
            click("关闭"); waitAbsent("运行日志")
            click("函数"); waitNode("函数库")
            click("最大化全屏"); waitNode("还原窗口"); click("还原窗口"); waitNode("最大化全屏")
            click("关闭函数库"); waitAbsent("函数库")
            editorParameterDialogs(snapshot)
            click("保存并关闭编辑窗")
            runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
        }
    }
    // Run the system rotation boundary last so a platform freeze cannot hide preceding app results.
    private fun editorRotation() {
        case("editor-function-dialog-rotation-retains-window-and-project") {
                val snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
                main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
                    .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, snapshot.manifest.projectId)) }
                runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == snapshot.manifest.projectId && it.ready } } }
                val owner = requireNotNull(activity)
                val requested = owner.requestedOrientation
                val original = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
                try {
                    click("函数"); waitNode("函数库")
                    main { owner.requestedOrientation = if (original.x < original.y)
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitFor { Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }.let { (it.x > it.y) != (original.x > original.y) } }
                    waitNode("关闭函数库")
                    val display = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
                    val button = bounds("关闭函数库")
                    check(!button.isEmpty && button.left >= 0 && button.top >= 0 && button.right <= display.x && button.bottom <= display.y) { "QA_ROTATED_CLOSE_OUTSIDE_SCREEN" }
                    check(EditorOverlaySessions.status.value?.projectId == snapshot.manifest.projectId) { "QA_ROTATED_SESSION_LOST" }
                    click("关闭函数库"); waitNode("打开项目设置")
                } finally {
                    main { owner.requestedOrientation = restoredScreenOrientation(requested) }
                    waitFor { Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) } == original }
                }
                waitNode("打开项目设置"); check(!exists("智构 · 恢复窗口")) { "QA_ROTATION_MINIMIZED_EDITOR" }
                check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_ROTATION_MUTATED_SOURCE" }
                // Geometry alone cannot prove InputDispatcher recovered after rotation.
                click("函数"); waitNode("函数库"); click("关闭函数库"); waitAbsent("函数库")
                main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
                runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
        }
    }
    private fun editorParameterDialogs(snapshot: ProjectSnapshot) {
        case("editor-variable-nested-draft-fullscreen-and-discard") {
            click("变量"); waitNode("变量管理")
            click("＋新增"); waitNode("变量名称")
            setFirstEditable("qaUnsavedOnly")
            click("添加"); waitNode("qaUnsavedOnly")
            click("取消"); waitNode("放弃变量更改？")
            click("取消"); waitAbsent("放弃变量更改？")
            click("最大化全屏"); waitNode("还原窗口"); waitNode("qaUnsavedOnly")
            click("还原窗口"); click("取消"); waitNode("放弃变量更改？")
            click("放弃更改"); waitAbsent("变量管理")
            check(store.openProject(snapshot.manifest.projectId).manifest.variables == snapshot.manifest.variables) { "QA_VARIABLE_DISCARD_MUTATED_PROJECT" }
        }
        case("editor-loop-preset-nested-return-without-insertion") {
            click("循环"); waitNode("预设次数 ▾")
            click("预设次数 ▾"); waitNode("选择预设")
            click("3 次"); waitAbsent("选择预设")
            click("限次循环"); waitNode("3")
            val display = Point().also { @Suppress("DEPRECATION") requireNotNull(activity).windowManager.defaultDisplay.getRealSize(it) }
            check(bounds("取消").let { !it.isEmpty && it.bottom <= display.y && it.right <= display.x }) { "QA_LOOP_ACTION_OUTSIDE_SCREEN" }
            click("取消"); waitNode("打开项目设置")
            check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_LOOP_CANCEL_INSERTED" }
        }
        case("editor-image-config-fullscreen-and-cancel") {
            click("图像"); waitNode("图像 · 识别工具")
            click("区域找图"); waitNode("截图取材")
            click("最大化全屏"); waitNode("还原窗口")
            click("还原窗口"); click("取消"); waitNode("打开项目设置")
            check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_IMAGE_CANCEL_INSERTED" }
        }
        case("editor-source-draft-fullscreen-and-confirmed-discard") {
            click("编辑"); waitNode("编辑积木")
            click("插件源码"); waitNode("插件 · 积木 / 源码")
            waitNode("QA_FIRST_EDITABLE")
            val original = requireNotNull(find("QA_FIRST_EDITABLE")).let { node -> try { node.text.toString() } finally { node.recycle() } }
            val draft = "-- PUBLIC_QA_UNSAVED\n" + original
            setFirstEditable(draft)
            click("最大化全屏"); waitNode("还原窗口")
            check(requireNotNull(find("QA_FIRST_EDITABLE")).let { node -> try { node.text.toString() == draft } finally { node.recycle() } }) { "QA_SOURCE_FULLSCREEN_DRAFT_LOST" }
            click("还原窗口"); click("关闭"); waitNode("放弃源码修改？")
            click("继续编辑"); waitAbsent("放弃源码修改？")
            click("关闭"); click("放弃修改"); waitNode("编辑积木")
            click("关闭"); waitNode("打开项目设置")
            check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_SOURCE_DISCARD_SAVED" }
        }
    }
    private fun setFirstEditable(value: String) {
        val node = requireNotNull(find("QA_FIRST_EDITABLE"))
        try { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "QA_TEXT_EDIT_REJECTED" } } finally { node.recycle() }
    }
    private fun cleanup() {
        val project = owned ?: return
        if (client.runtimeStates.value.engineState in active) {
            // Highlight delivery is itself under test. Prove ownership through the
            // service's project/generation-bound reply, not the possibly missing UI sample.
            val proof = client.debugSnapshot(project.manifest.projectId)
            check(proof.status == RuntimeDebugReply.SUCCESS && proof.runRevision > 0 &&
                proof.sessionGeneration == client.runtimeStates.value.sessionGeneration) { "RUN_IDENTITY_CHANGED" }
            check(client.requestStop())
            runBlocking { withTimeout(8_000) { client.runtimeStates.first { it.engineState !in active } } }
        }
        val current = EditorOverlaySessions.status.value
        check(current == null || current.projectId == project.manifest.projectId) { "USER_WINDOW_ACTIVE" }
        if (current != null) {
            // These are test-opened, read-only windows. A normal save-close correctly refuses
            // to discard an open child dialog, so dismiss the owned browser before requesting it.
            if (exists("关闭函数库")) click("关闭函数库")
            main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
            runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
        }
        val launch = ScriptLaunchSessions.status.value
        check(launch == null || launch.projectId == project.manifest.projectId) { "USER_WINDOW_ACTIVE" }
        if (launch != null) {
            main { targetContext.startService(Intent(targetContext, ScriptLaunchOverlayService::class.java).setAction(ScriptLaunchOverlayService.ACTION_CLOSE)) }
            runBlocking { withTimeout(8_000) { ScriptLaunchSessions.status.first { it == null } } }
        }
        check(client.runtimeStates.value.engineState !in active)
        val root = File(targetContext.filesDir, "projects").canonicalFile
        check(project.manifest.projectId.startsWith("project-qa-") && project.directory.canonicalFile.parentFile == root)
        targetContext.getSharedPreferences("script-ui-config", android.content.Context.MODE_PRIVATE).edit()
            .remove("${project.manifest.projectId}:qaInput").commit()
        store.deleteProject(project.manifest.projectId)
    }
    private fun case(name: String, work: () -> Unit) {
        val record = JSONObject().put("case", name).put("pass", false)
        cases.put(record)
        sendStatus(1, Bundle().apply { putString("stream", "QA_CASE:$name\n") })
        try { work(); record.put("pass", true) }
        catch (failure: Throwable) {
            record.put("failureAt", JSONArray(failure.stackTrace.filter {
                it.fileName != null && it.lineNumber > 0 && (it.className.startsWith("com.autoscript.studio.") || it.className.startsWith("com.autoscript.script.ui.") || it.className.startsWith("com.autoscript.project.store."))
            }.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            if (name == "active-own-run-client-rebind-and-stop") {
                record.put("engineState", client.runtimeStates.value.engineState.name)
                val project = owned
                if (project != null) {
                    val reply = client.debugSnapshot(project.manifest.projectId)
                    record.put("debugStatus", reply.status)
                    record.put("debugAtWait", reply.status == RuntimeDebugReply.SUCCESS && reply.nodeId == "qa-config-wait")
                    record.put("debugAtRead", reply.status == RuntimeDebugReply.SUCCESS && reply.nodeId == "qa-config-read")
                    record.put("hasOwnPosition", client.executionPosition.value?.projectId == project.manifest.projectId)
                }
            }
            throw failure
        }
    }
    private fun awaitState(predicate: (com.autoscript.core.model.RuntimeConnectionState) -> Boolean) =
        runBlocking { withTimeout(15_000) { client.runtimeStates.first(predicate) } }
    private fun main(work: () -> Unit) = runOnMainSync(work)
    private fun waitNode(label: String) { waitingFor = label; waitFor { exists(label) }; waitingFor = null }
    private fun waitAbsent(label: String) { waitingFor = "absent:$label"; waitFor { !exists(label) }; waitingFor = null }
    private fun tap(bounds: Rect) {
        check(!bounds.isEmpty) { "EMPTY_CLICK_BOUNDS" }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = bounds.exactCenterX(); y = bounds.exactCenterY(); pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f; size = 1f
            }
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; check(uiAutomation.injectInputEvent(event, true)) { "TAP_REJECTED" } }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(50) // A real tap, not zero-duration DOWN/UP at one timestamp.
        }
        try { uiAutomation.waitForIdle(100, 1000) } catch (_: java.util.concurrent.TimeoutException) { }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val end = minOf(deadline, SystemClock.elapsedRealtime() + 8_000)
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < end) { "UI_WAIT_TIMEOUT" }
            // Event-driven wait; unrelated accessibility text is never retained or reported.
            try { uiAutomation.executeAndWaitForEvent({}, { true }, 150) } catch (_: java.util.concurrent.TimeoutException) { }
        }
    }
    private fun exists(label: String): Boolean = find(label)?.let { it.recycle(); true } ?: false
    private fun bounds(label: String): Rect = requireNotNull(find(label)).let { node ->
        Rect().also { node.refresh(); node.getBoundsInScreen(it); node.recycle() }
    }
    private fun windowBounds(label: String): Rect = requireNotNull(windowBoundsOrNull(label))
    private fun windowBoundsOrNull(label: String): Rect? = find(label)?.let { node ->
        // node.window can retain pre-resize metadata even after Compose semantics already changed.
        try { uiAutomation.windows.firstOrNull { it.id == node.windowId }?.let { window -> Rect().also { window.getBoundsInScreen(it) } } }
        finally { node.recycle() }
    }
    private fun click(label: String) {
        waitNode(label)
        try { uiAutomation.waitForIdle(200, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
        val point = bounds(label)
        lastClick = JSONObject().put("label", label).put("bounds", point.flattenToString()).put("window", windowBounds(label).flattenToString())
        tap(point)
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        var budget = 4096
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (--budget < 0) { node.recycle(); error("UI_TREE_BUDGET") }
            // Compose may replace virtual children when its backdrop disappears. Refreshing only
            // the root leaves detached child IDs with valid-looking old text/bounds in the cache.
            if (!node.refresh()) { node.recycle(); return null }
            val matches = if (label == "QA_FIRST_EDITABLE") node.isEditable
            else if (label == "QA_DIALOG_CLOSE_ACTION") node.text?.toString() == "关闭"
            else if (label == "QA_START_ROW") node.text?.toString()?.let {
                it.contains("运行提示") && it.contains("PUBLIC_QA_PROMPT")
            } == true else node.text?.toString() == label || node.contentDescription?.toString() == label ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == label }
            if (node.packageName?.toString() == targetContext.packageName && node.isVisibleToUser && matches) return node
            for (index in 0 until node.childCount.coerceAtMost(256)) {
                val found = node.getChild(index)?.let(::visit)
                if (found != null) { node.recycle(); return found }
            }
            node.recycle(); return null
        }
        for (window in uiAutomation.windows.take(32)) {
            val found = window.root?.let { it.refresh(); visit(it) }
            if (found != null) return found
        }
        return null
    }
    private fun source(block: String): String {
        fun node(id: String, kind: String, order: String, args: JSONObject, body: Boolean = false, children: Boolean = false) =
            JSONObject().put("flowSchemaVersion", 1).put("nodeVersion", 1).put("nodeId", id)
                .put("blockId", if (body) "qa-body" else block).put("parentId", if (body) "qa-loop" else JSONObject.NULL)
                .put("orderKey", order).put("depth", if (body) 1 else 0).put("kind", kind).put("args", args)
                .apply { if (children) put("childBlocks", JSONObject().put("body", "qa-body")) }.toString()
        return listOf(
            node("qa-start", "task.runprompt", "a0", JSONObject().put("message", "PUBLIC_QA_PROMPT")),
            node("qa-loop", "control.repeat", "b0", JSONObject().put("times", 300), children = true),
            node("qa-loop-prompt", "task.runprompt", "a0", JSONObject().put("message", "PUBLIC_QA_ITERATION"), body = true),
            node("qa-loop-wait", "task.sleep", "b0", JSONObject().put("milliseconds", 100), body = true),
        ).joinToString("\n", postfix = "\n")
    }
}
