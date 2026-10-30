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
import java.security.MessageDigest
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
    private val qaDialogClickGeometry = JSONArray()
    private val qaInputDispatch = JSONArray()
    private val noAnimationInput by lazy {
        runCatching { android.app.UiAutomation::class.java.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) }.getOrNull()
    }
    private var finalClosure = false
    private var keyboardOnly = false
    private var dialogsOnly = false
    private var landscapeOnly = false
    private var landscapeCaptureCount = 0
    private var retiredQaProjects: List<String> = emptyList()
    private var retiredQaInsertedSourceSha256: String? = null
    private var workflowsOnly = false
    private var brandOnly = false
    private var workspaceOnly = false
    private var homeOnly = false
    private var experienceOnly = false
    private var pluginBranchesOnly = false
    private var promptLayersOnly = false
    private var promptReuseOnly = false
    private var insertionCompletionOnly = false
    private var insertionSafetyOnly = false
    private var stabilityOnly = false
    private var lifecycleOnly = false
    private var boundariesOnly = false
    private var performanceOnly = false
    private var performanceExpanded = false
    private var performanceMixed = false
    private var runtimePerformanceOnly = false
    private var captureGeometryOnly = false
    private var templateCaptureOnly = false
    private var captureDimOnly = false
    private var captureRegionOnly = false
    private var editorControlOnly = false
    private var glyphMakerOnly = false
    private var glyphPaletteOnly = false
    private var pluginManagerOnly = false
    private var pluginFilesOnly = false
    private var imageFilterOnly = false
    private var imageLibraryOnly = false
    private var editorCloseoutOnly = false
    private var imageEnhancementOnly = false
    private var imageSelectionOnly = false
    private var pureUtilityOnly = false
    private var variableUsageOnly = false
    private var touchUsageOnly = false
    private var controlUsageStage = ""
    private var capabilityExpansionOnly=false
    private var capabilityProductionIme=false
    private var soakMillis = 0L
    private var retentionMode: String? = null
    private var skipRotation = false
    private var expectedTargetApkSha256: String? = null
    private val active = setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)

    override fun onCreate(arguments: Bundle?) {
        finalClosure = arguments?.getString("finalClosure") == "true"
        keyboardOnly = arguments?.getString("keyboardOnly") == "true"
        dialogsOnly = arguments?.getString("dialogsOnly") == "true"
        landscapeOnly = arguments?.getString("landscapeOnly") == "true"
        retiredQaProjects = arguments?.getString("retiredQaProjects")?.split(',') ?: emptyList()
        retiredQaInsertedSourceSha256 = arguments?.getString("retiredQaInsertedSourceSha256")
        workflowsOnly = arguments?.getString("workflowsOnly") == "true"
        brandOnly = arguments?.getString("brandOnly") == "true"
        workspaceOnly = arguments?.getString("workspaceOnly") == "true"
        homeOnly = arguments?.getString("homeOnly") == "true"
        experienceOnly = arguments?.getString("experienceOnly") == "true"
        pluginBranchesOnly = arguments?.getString("pluginBranchesOnly") == "true"
        promptLayersOnly = arguments?.getString("promptLayersOnly") == "true"
        promptReuseOnly = arguments?.getString("promptReuseOnly") == "true"
        insertionCompletionOnly = arguments?.getString("insertionCompletionOnly") == "true"
        insertionSafetyOnly = arguments?.getString("insertionSafetyOnly") == "true"
        stabilityOnly = arguments?.getString("stabilityOnly") == "true"
        lifecycleOnly = arguments?.getString("lifecycleOnly") == "true"
        boundariesOnly = arguments?.getString("boundariesOnly") == "true"
        performanceOnly = arguments?.getString("performanceOnly") == "true"
        performanceExpanded = arguments?.getString("performanceExpanded") == "true"
        performanceMixed = arguments?.getString("performanceMixed") == "true"
        runtimePerformanceOnly = arguments?.getString("runtimePerformanceOnly") == "true"
        captureGeometryOnly = arguments?.getString("captureGeometryOnly") == "true"
        templateCaptureOnly = arguments?.getString("templateCaptureOnly") == "true"
        captureDimOnly = arguments?.getString("captureDimOnly") == "true"
        captureRegionOnly = arguments?.getString("captureRegionOnly") == "true"
        editorControlOnly = arguments?.getString("editorControlOnly") == "true"
        glyphMakerOnly = arguments?.getString("glyphMakerOnly") == "true"
        glyphPaletteOnly = arguments?.getString("glyphPaletteOnly") == "true"
        pluginManagerOnly = arguments?.getString("pluginManagerOnly") == "true"
        pluginFilesOnly = arguments?.getString("pluginFilesOnly") == "true"
        imageFilterOnly = arguments?.getString("imageFilterOnly") == "true"
        imageLibraryOnly = arguments?.getString("imageLibraryOnly") == "true"
        editorCloseoutOnly = arguments?.getString("editorCloseoutOnly") == "true"
        imageEnhancementOnly = arguments?.getString("imageEnhancementOnly") == "true"
        imageSelectionOnly = arguments?.getString("imageSelectionOnly") == "true"
        pureUtilityOnly = arguments?.getString("pureUtilityOnly") == "true"
        variableUsageOnly = arguments?.getString("variableUsageOnly") == "true"
        touchUsageOnly = arguments?.getString("touchUsageOnly") == "true"
        controlUsageStage = arguments?.getString("controlUsageStage").orEmpty()
        capabilityExpansionOnly=arguments?.getString("capabilityExpansionOnly")=="true"
        capabilityProductionIme=arguments?.getString("capabilityProductionIme")=="true"
        soakMillis = arguments?.getString("soakSeconds")?.toLongOrNull()?.takeIf { it in 60..1800 }?.times(1000) ?: 0L
        retentionMode = arguments?.getString("retentionMode")?.takeIf { it in setOf("prepare", "verify") }
        skipRotation = arguments?.getString("skipRotation") == "true"
        expectedTargetApkSha256 = arguments?.getString("expectedTargetApkSha256")
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
        deadline = SystemClock.elapsedRealtime() + if (stabilityOnly) soakMillis + 180_000 else if (performanceOnly || runtimePerformanceOnly || variableUsageOnly || controlUsageStage.startsWith("library")) 540_000 else if (finalClosure || landscapeOnly || boundariesOnly || imageEnhancementOnly || capabilityExpansionOnly || lifecycleOnly) 360_000 else 150_000
        val report = JSONObject().put("scope", "real Studio overlay and own synthetic core.task project")
            .put("sdk", Build.VERSION.SDK_INT).put("cases", cases).put("qaInputDispatch", qaInputDispatch)
        try {
            val targetDigest = installedApkSha256(targetContext)
            report.put("targetApkSha256", targetDigest).put("testApkSha256", installedApkSha256(context))
            expectedTargetApkSha256?.let { expected ->
                check(expected.matches(Regex("[0-9a-fA-F]{64}")) && targetDigest.equals(expected, ignoreCase = true)) {
                    "QA_INSTALLED_APK_DIGEST_DIFFERS"
                }
            }
            check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "USER_WINDOW_ACTIVE" }
            client = RuntimeClient(targetContext)
            main { client.bind() }
            awaitState { it.phase == RuntimeConnectionPhase.CONNECTED }
            check(client.runtimeStates.value.engineState !in active) { "USER_RUN_ACTIVE" }
            check(Settings.canDrawOverlays(targetContext)) { "OVERLAY_PERMISSION_REQUIRED" }
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            // singleTop can reuse the existing Activity, leaving startActivitySync waiting
            // forever for a creation event. Start an explicitly new, owned QA task instead.
            if (controlUsageStage != "libraryOcr") {
                val activityMonitor = addMonitor(MainActivity::class.java.name, null, false)
                try {
                    targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    activity = activityMonitor.waitForActivityWithTimeout(8_000) ?: error("QA_ACTIVITY_START_TIMEOUT")
                } finally { removeMonitor(activityMonitor) }
            }
            check(EditorOverlaySessions.status.value == null && ScriptLaunchSessions.status.value == null) { "USER_WINDOW_ACTIVE" }
            check(client.runtimeStates.value.engineState !in active) { "USER_RUN_ACTIVE" }
            store = ProjectStore(File(targetContext.filesDir, "projects"), idFactory = { "project-qa-${UUID.randomUUID()}" })
            if (retiredQaProjects.isNotEmpty()) {
                check(retiredQaInsertedSourceSha256?.matches(Regex("[0-9a-f]{64}")) != false) { "QA_CLEANUP_DIGEST_INVALID" }
                check(retiredQaProjects.size <= 16 && retiredQaProjects.distinct().size == retiredQaProjects.size &&
                    retiredQaProjects.all { it.matches(Regex("project-qa-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) }) { "QA_CLEANUP_IDS_INVALID" }
                var removed = 0
                for (id in retiredQaProjects) {
                    if (!File(targetContext.filesDir, "projects/$id").exists()) continue
                    val previous = store.openProject(id)
                    val manifest = previous.manifest
                    val flow = manifest.flows.singleOrNull()
                    val baseline = flow?.let { source(it.rootBlockId) }
                    val saved = flow?.let { previous.flowSources[it.flowId] }
                    val inspectedInsertion = retiredQaInsertedSourceSha256?.let { expected ->
                        saved != null && baseline != null && saved.startsWith(baseline) &&
                            saved.lineSequence().filter(String::isNotBlank).count() == 5 &&
                            JSONObject(saved.lineSequence().last(String::isNotBlank)).let {
                                it.optString("kind") == "task.runprompt" && it.optJSONObject("args")?.optString("message") == "PUBLIC_QA_INSERTION_COMPLETION"
                            } && MessageDigest.getInstance("SHA-256").digest(saved.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it.toInt() and 255) } == expected
                    } == true
                    check(manifest.name == "公开合成验收" && manifest.sourceMode == ProjectSourceMode.VISUAL &&
                        manifest.variables.isEmpty() && manifest.resources.isEmpty() && manifest.runnerUi == null &&
                        flow != null && previous.flowSources.keys == setOf(flow.flowId) &&
                        (saved == baseline || inspectedInsertion)) { "QA_CLEANUP_CONTENT_CHANGED" }
                    store.deleteProject(id)
                    removed++
                }
                report.put("retiredOwnedQaProjectsRemoved", removed)
            }
            val size = Point()
            @Suppress("DEPRECATION") targetContext.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
            val initial = store.createProject("公开合成验收", ProjectSourceMode.VISUAL,
                designWidth = size.x, designHeight = size.y, initialCapabilities = setOf("core.task"))
            owned = initial
            sendStatus(1, Bundle().apply { putString("stream", "QA_OWNED_PROJECT:${initial.manifest.projectId}\n") })
            val flow = initial.manifest.flows.single()
            owned = if (imageSelectionOnly) initial else store.saveFlows(initial, mapOf(flow.flowId to source(flow.rootBlockId)))
            if (variableUsageOnly) {
                report.put("scope", "owned service-hosted variable management, saved declarations, real compiled Rust assignments and live variable observer")
                owned = VariableUsageAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage in setOf("conditions", "loops")) {
                report.put("scope", "owned real $controlUsageStage parameter forms and compiled Rust execution")
                owned = ControlUsageAcceptanceFixture.run(controlUsageStage, this, requireNotNull(activity) as ComponentActivity,
                    client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage in setOf("flow", "output", "ai")) {
                report.put("scope", "owned real $controlUsageStage entry and compiled runtime where applicable")
                owned = EntryUsageAcceptanceFixture.run(controlUsageStage, this, requireNotNull(activity) as ComponentActivity,
                    client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "aiAssistant") {
                report.put("scope", "owned AI dialog, isolated settings and public loopback model, real full draft validation; no external model or script run")
                owned = AiAssistantAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryRecording") {
                report.put("scope", "owned public ARM view, actual evdev recording, floating stop/cancel, compiled playback and timeout recovery")
                owned = RecordingUsageAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryInput") {
                report.put("scope", "owned production IME, paste, replay and app launch requests")
                owned = LibraryInputUsageFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryOcr") {
                report.put("scope", "owned public OCR image, saved visual project and real compiled Rust execution; no display rendering")
                owned = OcrUsageAcceptanceFixture.run(this, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryVision") {
                report.put("scope", "owned exact pixels, compiled Rust color/template/frame functions and actions on public QA View")
                owned = LibraryVisionUsageFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryHost") {
                report.put("scope", "owned actual layout/floating windows, public confirmation and bounded compiled tasks/timers/restart")
                owned = LibraryHostUsageFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryDevice") {
                report.put("scope", "own QA clipboard text, device clock and audio; original clipboard and volume restored")
                owned = LibraryDeviceUsageFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage == "libraryFiles") {
                report.put("scope", "owned private project data sandbox, real visual file functions and checked persisted payloads")
                owned = LibraryFilesUsageFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (controlUsageStage in setOf("libraryUI", "libraryPure")) {
                report.put("scope", "owned actual library catalog or parameter forms and real compiled utility results")
                owned = LibraryUsageAcceptanceFixture.run(controlUsageStage, this, requireNotNull(activity) as ComponentActivity,
                    client, store, requireNotNull(owned), ::case)
            } else if (touchUsageOnly) {
                report.put("scope", "owned touch menu, production input generators and actual Root events on a public QA surface")
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                TouchUsageAcceptanceFixture.menu(this, requireNotNull(activity) as ComponentActivity, client, ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), { name, work ->
                    if (name.startsWith("workflow-generated-input-") || name.startsWith("workflow-root-") ) case(name, work)
                }, extended = true)
            } else if (editorCloseoutOnly) {
                report.put("scope", "owned public editor closeout panels, real draft compilation and image library; no script execution, IME or system changes")
                owned = EditorFinalAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
                imageLibraryOnly = true
                editorImageLibrary()
            } else if (landscapeOnly) {
                report.put("scope", "real landscape service-hosted editor, opt-in QA keyboard and own public project; orientation and IME restored")
                landscapeDialogs()
            } else if (insertionSafetyOnly) {
                report.put("scope", "public in-memory editor stale insertion and retained forms; no scripts or system changes")
                InsertionSafetyAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if (insertionCompletionOnly) {
                report.put("scope", "owned real editor insertion completion and cancelled form retention; no scripts or permission changes")
                editorInsertionCompletion()
            } else if (promptReuseOnly) {
                report.put("scope", "owned runtime prompt windows; no scripts, captures, Root input or system changes")
                RuntimePromptReuseAcceptanceFixture.run(this, requireNotNull(activity), ::case)
            } else if (promptLayersOnly) {
                report.put("scope", "owned shared prompt and parameter windows; no scripts, permission changes or private project writes")
                PromptLayerAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if (pluginFilesOnly) {
                report.put("scope", "compact plugin browser and real transactions within a newly owned QA project")
                owned = PluginFileManagerAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, store, requireNotNull(owned), ::case)
            } else if (pluginManagerOnly) {
                report.put("scope", "compact plugin management launcher; no scripts or private project writes")
                PluginManagerAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case)
            } else if(capabilityExpansionOnly) {
                report.put("scope","owned expansion project, loopback HTTP, two/five-finger Root and image events; production IME only with explicit opt-in")
                report.put("productionImeRequested",capabilityProductionIme)
                owned=CapabilityExpansionAcceptanceFixture.run(this,requireNotNull(activity) as ComponentActivity,client,store,requireNotNull(owned),::case,capabilityProductionIme)
            } else if(pureUtilityOnly) {
                report.put("scope","nine pure utility dialogs and owned synthetic visual runtime project")
                owned=PureUtilityAcceptanceFixture.run(this,requireNotNull(activity) as ComponentActivity,client,store,requireNotNull(owned),::case)
            } else if(imageSelectionOnly) {
                report.put("scope","owned public image selection, narrow toolbar and actual Activity landscape; restore orientation, no private scripts")
                ImageSelectionAcceptanceFixture.run(this,requireNotNull(activity) as ComponentActivity,client,store,requireNotNull(owned),::case)
            } else if(imageEnhancementOnly) {
                report.put("scope","real Studio workbench dialogs and owned synthetic image/runtime project")
                owned=ImageEnhancementAcceptanceFixture.run(this,requireNotNull(activity) as ComponentActivity,client,store,requireNotNull(owned),::case)
            } else if (imageLibraryOnly) {
                report.put("scope", "real service-hosted editor image library; owned project only; no screenshot or script execution")
                editorImageLibrary()
            } else if (imageFilterOnly) {
                owned = ImageFilterAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (glyphPaletteOnly) {
                owned = GlyphPaletteAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (glyphMakerOnly) {
                owned = GlyphMakerAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
                owned = GlyphPaletteAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, client, store, requireNotNull(owned), ::case)
            } else if (editorControlOnly) {
                editorRuntimeControlHandoff()
            } else if (captureRegionOnly) {
                report.put("scope","fresh region pixels and screen mapping on owned portrait/landscape surface; orientation restored")
                owned=StudioRegionCaptureFixture.run(this,requireNotNull(activity),client,store,requireNotNull(owned),::case)
            } else if (captureDimOnly) {
                report.put("scope", "owned flat public surface and native editor dim suppression; no scripts, rotation or keyboard changes")
                TemplateCaptureSuppressionFixture.run(this, requireNotNull(activity), client, ::case)
            } else if (templateCaptureOnly) {
                report.put("scope", "fresh screenshot templates, original ROI, normal/node-test matching on owned portrait/landscape surface; orientation restored")
                owned = ScreenshotTemplateAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case)
            } else if (captureGeometryOnly) {
                report.put("scope", "new Root capture pixels and geometry of an owned surface; actual orientation restored")
                RootCaptureGeometryFixture.run(this,requireNotNull(activity),client,::case)
            } else if (runtimePerformanceOnly) {
                report.put("scope", "fixed public compiled ordinary calls, real editor/log/watch and priority stop; no IME or system changes")
                owned = RuntimePerformanceAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case)
            } else if (performanceOnly) {
                report.put("scope", "fixed public synthesis; real Root capture and Rust matching; stage percentiles not additive")
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioPerformanceAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case,
                    expanded = performanceExpanded, mixed = performanceMixed)
            } else if (boundariesOnly) {
                StudioDataSafetyAcceptanceFixture.run(this, ::case)
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case, extended = true)
            } else if (lifecycleOnly) {
                report.put("scope", "owned editor, log and observer lifecycle pressure; no forced GC, script execution or settings changes")
                editorLifecyclePressure()
            } else if (stabilityOnly) {
                check(soakMillis > 0L) { "QA_SOAK_DURATION_REQUIRED" }
                case("root-tap-and-fresh-capture-owned-surface") { rootSurface() }
                owned = StudioProjectClosureFixture.run(this, client, store, requireNotNull(owned), ::case)
                owned = StudioWorkflowAcceptanceFixture.run(this, requireNotNull(activity), client, store, requireNotNull(owned), ::case, soakMillis)
            } else if (pluginBranchesOnly) {
                report.put("scope", "plugin branch headings on synthetic shared editor widgets; no script execution")
                StudioExperienceAcceptanceFixture.run(this, requireNotNull(activity) as ComponentActivity, ::case, branchesOnly = true)
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
                // Check before maximize/restore can accidentally repair a stale native child origin.
                for (label in listOf("日志", "更多", "单步")) {
                    waitNode(label)
                    check(windowBounds(label).contains(bounds(label))) { "QA_INITIAL_EDITOR_ACTION_CLIPPED" }
                }
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
                // Observe the short-lived preview before waiting for terminal
                // state delivery; that delivery is not the preview's lifetime.
                waitNode("PUBLIC_QA_PROMPT")
                // A quick node can complete before the StateFlow collector observes RUNNING.
                awaitState { it.stateRevision > previous && it.engineState in setOf(RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED) }
                check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED) { "NODE_TEST_FAILED" }
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
            if (landscapeOnly) captureLandscape("failure")
            report.put("pass", false).put("error", failure.javaClass.simpleName)
                .put("code", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "STUDIO_ACCEPTANCE_FAILED")
                .put("waitingFor", waitingFor ?: JSONObject.NULL)
                .put("lastClick", lastClick ?: JSONObject.NULL)
                .put("qaDialogClickGeometry", qaDialogClickGeometry)
                .put("knownQaControls", JSONObject().apply {
                    for (label in listOf("更多", "运行变量", "变量检查", "录制动作", "打开项目设置", "智构 · 恢复窗口", "项目设置", "运行设置", "取消", "保存")) put(label, exists(label))
                })
            if (waitingFor == "QA_DIALOG_CLOSE_ACTION" || waitingFor == "日志") {
                val geometry = JSONArray()
                val labels = if (waitingFor == "日志") setOf("打开项目设置", "保存并关闭编辑窗", "最小化为智构悬浮球",
                    "函数", "运行", "单步", "编辑", "日志", "更多", "变量", "AI") else setOf("关闭", "QA布局", "PUBLIC_QA_TYPED")
                var budget = 256
                fun inspect(node: AccessibilityNodeInfo, frame: Rect) {
                    try {
                        if (--budget < 0) return
                        val label = node.text?.toString()?.takeIf { it in labels }
                            ?: node.contentDescription?.toString()?.takeIf { it in labels }
                        if (label != null) geometry.put(JSONObject().put("label", label).put("visible", node.isVisibleToUser)
                            .put("bounds", Rect().also(node::getBoundsInScreen).flattenToString()).put("window", frame.flattenToString()))
                        for (index in 0 until node.childCount.coerceAtMost(32)) node.getChild(index)?.let { inspect(it, frame) }
                    } finally { node.recycle() }
                }
                for (window in uiAutomation.windows.take(16)) {
                    val frame = Rect().also(window::getBoundsInScreen)
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        geometry.put(JSONObject().put("imeWindow", frame.flattenToString()))
                    } else window.root?.let { root ->
                        if (root.packageName?.toString() == targetContext.packageName) inspect(root, frame) else root.recycle()
                    }
                }
                report.put(if (waitingFor == "日志") "qaEditorGeometry" else "qaDialogGeometry", geometry)
                if (waitingFor == "日志") {
                    val display = Point().also { @Suppress("DEPRECATION") requireNotNull(activity).windowManager.defaultDisplay.getRealSize(it) }
                    report.put("qaDisplay", JSONObject().put("width", display.x).put("height", display.y)
                        .put("density", targetContext.resources.displayMetrics.density)
                        .put("orientation", targetContext.resources.configuration.orientation))
                }
            }
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
            if (editorControlOnly && ::client.isInitialized) {
                report.put("engineState", client.runtimeStates.value.engineState.name)
                val fixedLabels = setOf("智构 · 展开运行控制", "智构 · 收起运行控制", "智构 · 恢复窗口",
                    "打开项目设置", "最小化为智构悬浮球", "运行提示：PUBLIC_QA_HANDOFF（点击设置，拖动移动）")
                report.put("windowDiagnostics", JSONArray().apply {
                    for (window in uiAutomation.windows.take(16)) {
                        val root = window.root
                        val record = JSONObject().put("type", window.type).put("hasRoot", root != null)
                            .put("windowBounds", Rect().also(window::getBoundsInScreen).flattenToString())
                        val matches = JSONArray()
                        var budget = 512
                        fun inspect(node: AccessibilityNodeInfo) {
                            if (--budget < 0) { node.recycle(); return }
                            val label = node.contentDescription?.toString()?.takeIf { it in fixedLabels }
                                ?: node.text?.toString()?.takeIf { it in fixedLabels }
                            if (label != null) matches.put(JSONObject().put("label", label)
                                .put("package", node.packageName).put("visible", node.isVisibleToUser)
                                .put("bounds", Rect().also(node::getBoundsInScreen).flattenToString())
                                .put("refresh", node.refresh()))
                            for (index in 0 until node.childCount.coerceAtMost(64)) node.getChild(index)?.let(::inspect)
                            node.recycle()
                        }
                        if (root != null) {
                            record.put("rootPackage", root.packageName)
                                .put("rootBounds", Rect().also(root::getBoundsInScreen).flattenToString())
                                .put("rootParentBounds", Rect().also(root::getBoundsInParent).flattenToString())
                            inspect(root)
                        }
                        put(record.put("fixedMatches", matches))
                    }
                })
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

    private fun installedApkSha256(owner: android.content.Context): String {
        val apk = File(owner.applicationInfo.sourceDir)
        val size = apk.length()
        check(apk.isFile && size in 1..(256L * 1024 * 1024)) { "QA_INSTALLED_APK_SIZE_INVALID" }
        val end = SystemClock.elapsedRealtime() + 15_000
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        // Hash the installed APK locally; old Android shells need no hash applet or ADB sync support.
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(SystemClock.elapsedRealtime() < end) { "QA_INSTALLED_APK_HASH_TIMEOUT" }
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= size) { "QA_INSTALLED_APK_CHANGED" }
                digest.update(buffer, 0, count)
            }
        }
        check(bytes == size && apk.length() == size) { "QA_INSTALLED_APK_CHANGED" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
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
        fun startStage(nodes: List<String>, tail: String) {
            snapshot = store.saveFlows(snapshot, mapOf(flow.flowId to nodes.joinToString("\n", postfix = "\n")))
            owned = snapshot
            val compile = client.compileVisualProject(snapshot.manifest.projectId)
            check(compile is VisualCompileResult.Success) { "QA_IO_COMPILE_FAILED" }
            val frozen = store.openProject(snapshot.manifest.projectId)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compile.generationId).forEditorRun(frozen)
            check(runBlocking { withTimeout(20_000) { client.startProject(frozen.manifest.projectId, plan.luaSource, plan.resources, plan.capabilities,
                plan.designWidth, plan.designHeight, plan.scaleMode, requiresPointerInput = plan.requiresPointerInput,
                floatingControl = false, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_IO_START_FAILED" }
            runBlocking { withTimeout(10_000) { client.executionPosition.first { it?.projectId == frozen.manifest.projectId && it.nodeId == tail } } }
            ownPosition()
        }
        fun stopStage() {
            check(client.requestStop()) { "STOP_REJECTED" }
            awaitState { it.engineState !in active }
            check(client.runtimeStates.value.engineState == RuntimeEngineState.STOPPED)
        }
        fun presentedColor(): Int? = uiAutomation.takeScreenshot()?.let { image ->
            try { if (x in 0 until image.width && y in 0 until image.height) image.getPixel(x,y) and 0xFFFFFF else null }
            finally { image.recycle() }
        }
        startStage(listOf(node(0, "input.tap", JSONObject().put("x", x).put("y", y)),
            node(1, "task.sleep", JSONObject().put("milliseconds", 60_000))), "qa-io-1")
        check(touches.get() == 1) { "ROOT_TAP_NOT_RECEIVED" }
        // Layout and a fixed delay do not prove compositor presentation. Establish the public
        // pixel oracle before a single fresh Root capture; never retry a failed Root assertion.
        waitFor { presentedColor() == 0x20A060 }
        stopStage()
        startStage(listOf(
            node(2, "screen.capture", JSONObject().put("resultVariable", "qaFrame")),
            node(3, "vision.getcolor", JSONObject().put("frameVariable", "qaFrame").put("point", JSONObject().put("x", x).put("y", y)).put("resultVariable", "qaColor")),
            node(4, "screen.release", JSONObject().put("frameVariable", "qaFrame")),
            node(5, "task.sleep", JSONObject().put("milliseconds", 3000)),
        ), "qa-io-5")
        val reply = client.debugSnapshot(snapshot.manifest.projectId)
        check(reply.status == RuntimeDebugReply.SUCCESS && touches.get() == 1) { "ROOT_TAP_NOT_RECEIVED" }
        val nativeColor = reply.variables.singleOrNull { it.name == "qaColor" }?.value?.toLongOrNull()
        sendStatus(1,Bundle().apply {putString("stream","QA_ROOT_SURFACE_COLOR:native=$nativeColor,presented=${presentedColor()},touches=${touches.get()}\n")})
        check(nativeColor == 0x20A060L) { "ROOT_CAPTURE_NOT_FRESH" }
        stopStage()
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
    private fun editorLifecyclePressure() {
        val id = requireNotNull(owned).manifest.projectId
        check(id.startsWith("project-qa-") && EditorOverlaySessions.status.value == null &&
            ScriptLaunchSessions.status.value == null && client.runtimeStates.value.engineState !in active)
        fun ownWindows(): Set<Int> = uiAutomation.windows.mapNotNull { window ->
            window.root?.let { root ->
                try { window.id.takeIf { root.packageName?.toString() == targetContext.packageName } }
                finally { root.recycle() }
            }
        }.toSet()
        waitFor { ownWindows().isNotEmpty() }
        val baselineWindows = ownWindows().size
        val samples = JSONArray()
        val manager = targetContext.getSystemService(android.app.ActivityManager::class.java)
        try {
            case("twenty-editor-log-observer-open-close-cycles") {
                repeat(20) { index ->
                    check(client.runtimeStates.value.engineState !in active) { "QA_LIFECYCLE_UNEXPECTED_RUN" }
                    main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
                        .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, id)) }
                    runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == id && it.ready } } }
                    waitNode("打开项目设置")
                    click("日志"); waitNode("运行日志"); click("关闭"); waitAbsent("运行日志")
                    click("更多"); click("运行变量"); waitNode("关闭运行变量")
                    click("关闭运行变量"); waitAbsent("关闭运行变量")
                    val closingWindows = ownWindows()
                    check(EditorOverlaySessions.status.value?.projectId == id) { "QA_LIFECYCLE_EDITOR_CHANGED" }
                    main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
                    runBlocking { withTimeout(10_000) { EditorOverlaySessions.status.first { it == null } } }
                    waitAbsent("打开项目设置")
                    try {
                        waitFor { ownWindows().intersect(closingWindows).size <= baselineWindows && ownWindows().size <= baselineWindows }
                    } catch (failure: IllegalStateException) {
                        sendStatus(1, Bundle().apply { putString("stream", "QA_WINDOW_COUNTS:" + JSONObject()
                            .put("baseline", baselineWindows).put("current", ownWindows().size)
                            .put("closingStillPresent", ownWindows().intersect(closingWindows).size) + "\n") })
                        throw failure
                    }
                    check(ScriptLaunchSessions.status.value == null) { "QA_LIFECYCLE_LAUNCH_WINDOW_RETAINED" }
                    if ((index + 1) % 5 == 0) {
                        val memory = manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).single()
                        samples.put(JSONObject().put("cycles", index + 1).put("pssKiB", memory.totalPss)
                            .put("nativeAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize().takeIf { it > 0 } ?: JSONObject.NULL)
                            .put("ownedWindows", ownWindows().size))
                        sendStatus(1, Bundle().apply { putString("stream", "QA_LIFECYCLE_PROGRESS:${index + 1}\n") })
                    }
                }
                sendStatus(1, Bundle().apply { putString("stream", "QA_LIFECYCLE_SUMMARY:$samples\n") })
            }
        } finally {
            if (EditorOverlaySessions.status.value?.projectId == id) {
                if (exists("运行日志")) { click("关闭"); waitAbsent("运行日志") }
                if (exists("关闭运行变量")) { click("关闭运行变量"); waitAbsent("关闭运行变量") }
                main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
                runBlocking { withTimeout(10_000) { EditorOverlaySessions.status.first { it == null } } }
            }
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

    private fun editorInsertionCompletion() {
        val snapshot = requireNotNull(owned)
        val id = snapshot.manifest.projectId
        val text = "PUBLIC_QA_INSERTION_COMPLETION"
        val flow = snapshot.manifest.flows.single().flowId
        fun addedCount(): Int = store.openProject(id).flowSources.getValue(flow).lineSequence().filter(String::isNotBlank)
            .map(::JSONObject).count { it.optString("kind") == "task.runprompt" && it.optJSONObject("args")?.optString("message") == text }
        fun capture() {
            SystemClock.sleep(350)
            val image = requireNotNull(uiAutomation.takeScreenshot())
            try { File(requireNotNull(activity?.externalCacheDir), "qa-insertion-completion.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { image.recycle() }
        }
        main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
            .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, id)) }
        runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == id && it.ready } } }
        waitNode("打开项目设置")
        try {
            case("cancelled-position-keeps-form-and-does-not-add-a-node") {
                click("输出"); waitNode("输入持续显示的运行提示")
                setFirstEditable(text)
                click("加入"); waitNode("加入位置")
                click("取消")
                waitNode(text)
                requireNotNull(find("QA_FIRST_EDITABLE")).let { node ->
                    try { check(node.isEditable) { "QA_INSERT_CANCEL_PANEL_CLOSED" } } finally { node.recycle() }
                }
                check(addedCount() == 0) { "QA_INSERT_CANCEL_ADDED_NODE" }
            }
            case("successful-commit-closes-parameter-form-and-selects-new-node") {
                click("加入"); waitNode("加入位置"); click("确定")
                waitAbsent("加入位置"); waitAbsent("弹窗样式")
                waitFor { addedCount() == 1 }
                check(find("QA_FIRST_EDITABLE") == null) { "QA_INSERT_FORM_STILL_OPEN" }
                waitNode("QA_INSERTED_ROW")
                capture()
                if (Build.VERSION.SDK_INT >= 30) {
                    var node: AccessibilityNodeInfo? = requireNotNull(find("QA_INSERTED_ROW"))
                    var selected = false
                    repeat(4) {
                        node?.let { current ->
                            selected = selected || current.stateDescription?.toString() == "已选中"
                            node = current.parent
                            current.recycle()
                        }
                    }
                    node?.recycle()
                    check(selected) { "QA_INSERT_NOT_SELECTED" }
                }
            }
            case("reopening-form-creates-a-new-completion-ticket") {
                click("输出"); waitNode("输入持续显示的运行提示"); setFirstEditable(text)
                click("加入"); waitNode("加入位置"); click("确定")
                waitAbsent("加入位置"); waitAbsent("弹窗样式")
                waitFor { addedCount() == 2 }
                check(find("QA_FIRST_EDITABLE") == null) { "QA_INSERT_REOPEN_FORM_STILL_OPEN" }
            }
            case("function-library-remains-after-insertion-with-search-intact") {
                click("函数"); waitNode("关闭函数库")
                setFirstEditable("Task.sleep")
                click("加入"); waitNode("加入位置"); click("确定")
                waitAbsent("加入位置")
                waitNode("关闭函数库"); waitNode("Task.sleep")
                click("关闭函数库"); waitAbsent("关闭函数库")
            }
        } catch (failure: Throwable) {
            if (EditorOverlaySessions.status.value?.projectId == id) capture()
            throw failure
        } finally {
            if (exists("关闭函数库")) click("关闭函数库")
            if (exists("加入位置")) click("取消")
            if (exists("弹窗样式")) {
                click("关闭")
                if (exists("放弃未保存内容？")) click("放弃并关闭")
            }
        }
        case("closing-editor-releases-owned-session") {
            main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
            runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
        }
    }

    private fun editorImageLibrary() {
        var snapshot = requireNotNull(owned)
        val pixel = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = try { java.io.ByteArrayOutputStream().use { output ->
            check(pixel.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        } } finally { pixel.recycle() }
        listOf("first/nested" to "qa-library-one.png", "second" to "qa-library-two.png",
            "second" to "qa-library-delete.png").forEach { (folder, name) ->
            snapshot = store.importResource(snapshot.manifest.projectId, com.autoscript.project.store.ProjectResourceKind.IMAGE,
                name, java.io.ByteArrayInputStream(bytes), snapshot.resourcePaths(), folder)
            owned = snapshot
        }
        val imagePath = snapshot.resourcePaths().single { it.endsWith("/qa-library-two.png") }
        snapshot = store.updateRunnerUi(snapshot.manifest.projectId, com.autoscript.script.ui.ScriptUiDefinition(version = 2,
            fields = listOf(com.autoscript.script.ui.UiField("qa_image", "QA图片", "text", false, imagePath,
                ui = com.autoscript.script.ui.UiPresentation(control = "IMAGE")))).toJson(), snapshot.manifest.runnerUi)
        owned = snapshot
        main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
            .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, snapshot.manifest.projectId)) }
        runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == snapshot.manifest.projectId && it.ready } } }
        waitNode("打开项目设置")
        val editorWindow = windowBounds("打开项目设置")
        val editorWindowId = requireNotNull(find("打开项目设置")).let { try { it.windowId } finally { it.recycle() } }
        repeat(3) { attempt ->
            case("editor-image-library-open-and-return-${attempt + 1}") {
                click("图像"); waitNode("截图取材")
                click("图片库"); waitNode("关闭图片库")
                waitAbsent("识别测试")
                waitNode("全部图片 · 包含子文件夹"); waitNode("qa-library-one.png"); waitNode("qa-library-two.png")
                check(!exists("图片与标注") && !exists("截取当前屏幕") && !exists("截图工作区")) { "QA_LIBRARY_OPENED_RETIRED_SCREEN" }
                val library = requireNotNull(find("关闭图片库"))
                try {
                    val window = uiAutomation.windows.single { it.id == library.windowId }
                    val legacyPhoneWindow = Build.VERSION.SDK_INT in 24..25 &&
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                    var activityWindowId = -1
                    main {
                        val nativeRoot = requireNotNull(activity).window.decorView.createAccessibilityNodeInfo()
                        try { activityWindowId = nativeRoot.windowId } finally { nativeRoot.recycle() }
                    }
                    check((window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM || legacyPhoneWindow) &&
                        window.id != editorWindowId && window.id != activityWindowId) { "QA_LIBRARY_NOT_OVERLAY_WINDOW" }
                    cases.getJSONObject(cases.length() - 1).put("observedAccessibilityWindowType", window.type)
                    check(windowBounds("关闭图片库").height() > editorWindow.height()) { "QA_LIBRARY_CLIPPED_TO_EDITOR" }
                } finally { library.recycle() }
                if (attempt == 0) {
                    val image = requireNotNull(uiAutomation.takeScreenshot())
                    try { File(targetContext.cacheDir, "qa-image-library.png").outputStream().use {
                        check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { image.recycle() }
                    case("image-library-select-folder-and-return-to-all") {
                        click("选择文件夹"); waitNode("选择图片文件夹")
                        click("second"); click("选择此文件夹"); waitAbsent("选择图片文件夹")
                        waitNode("qa-library-two.png"); waitAbsent("qa-library-one.png")
                        click("全部图片"); waitNode("qa-library-one.png")
                    }
                    case("image-library-opens-real-workbench-and-preserves-filter-on-return") {
                        setFirstEditable("qa-library-one")
                        waitAbsent("qa-library-two.png"); click("qa-library-one.png"); waitNode("图片工作台")
                        waitNode("保存当前图")
                        check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) { "QA_WORKBENCH_BACK_REJECTED" }
                        waitAbsent("图片工作台"); waitNode("qa-library-one.png"); waitAbsent("qa-library-two.png")
                        click("清空"); waitNode("qa-library-two.png")
                    }
                    case("image-library-import-opens-location-picker-not-project-settings") {
                        click("导入图片"); waitNode("选择图片文件夹")
                        check(!exists("项目设置")) { "QA_LIBRARY_IMPORT_OPENED_SETTINGS" }
                        click("取消"); waitAbsent("选择图片文件夹")
                    }
                    case("image-library-delete-cancel-and-confirm-preserve-other-images-and-filter") {
                        setFirstEditable("qa-library-delete")
                        waitNode("qa-library-delete.png"); waitAbsent("qa-library-one.png")
                        click("使用位置"); waitNode("图片使用位置"); waitNode("未发现使用位置，可以返回图片库删除。")
                        click("关闭"); waitAbsent("图片使用位置")
                        val before = store.openProject(snapshot.manifest.projectId)
                        click("删除"); waitNode("删除图片"); click("取消"); waitAbsent("删除图片")
                        check(store.openProject(snapshot.manifest.projectId).manifest.resources == before.manifest.resources)
                        click("删除"); waitNode("删除图片"); click("删除")
                        waitAbsent("删除图片"); waitAbsent("qa-library-delete.png"); waitNode("图片已删除")
                        snapshot = store.openProject(snapshot.manifest.projectId); owned = snapshot
                        check(snapshot.manifest.resources.size == before.manifest.resources.size - 1 && snapshot.flowSources == before.flowSources)
                        click("清空"); waitNode("qa-library-one.png"); waitNode("qa-library-two.png")
                    }
                    case("image-library-used-image-reports-owner-and-refuses-deletion-visibly") {
                        setFirstEditable("qa-library-two"); waitAbsent("qa-library-one.png")
                        val before = store.openProject(snapshot.manifest.projectId)
                        click("使用位置"); waitNode("图片使用位置"); waitNode("1. 脚本界面 · 主页面 / QA图片（qa_image）")
                        click("关闭"); waitAbsent("图片使用位置")
                        click("删除"); waitNode("删除图片"); click("删除")
                        waitNode("图片使用位置"); waitNode("无法删除：仍有 1 处引用，图片已保留。")
                        waitNode("1. 脚本界面 · 主页面 / QA图片（qa_image）")
                        check(store.openProject(snapshot.manifest.projectId).manifest.resources == before.manifest.resources)
                        check(File(before.directory, imagePath).isFile)
                        click("关闭"); waitAbsent("图片使用位置"); waitNode("qa-library-two.png")
                        click("清空"); waitNode("qa-library-one.png")
                    }
                    click("关闭图片库")
                } else {
                    check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) { "QA_LIBRARY_BACK_REJECTED" }
                }
                waitAbsent("关闭图片库"); waitNode("打开项目设置")
                check(windowBounds("打开项目设置") == editorWindow) { "QA_LIBRARY_CHANGED_EDITOR_GEOMETRY" }
                check(EditorOverlaySessions.status.value?.projectId == snapshot.manifest.projectId) { "QA_LIBRARY_LOST_EDITOR_SESSION" }
            }
        }
        case("image-library-return-keeps-editor-operable-and-project-unchanged") {
            click("函数"); waitNode("函数库"); click("关闭函数库"); waitAbsent("函数库")
            check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_LIBRARY_MUTATED_SOURCE" }
            check(store.openProject(snapshot.manifest.projectId).manifest.resources == snapshot.manifest.resources) { "QA_LIBRARY_MUTATED_RESOURCES" }
            click("保存并关闭编辑窗")
            runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
            waitAbsent("打开项目设置"); waitAbsent("关闭图片库")
        }
    }

    private fun editorRuntimeControlHandoff() {
        case("preexisting-own-run-editor-control-handoff-preserves-prompt") {
            val original = store.openProject(requireNotNull(owned).manifest.projectId)
            val flow = original.manifest.flows.single()
            fun node(id: String, kind: String, order: String, args: JSONObject) = JSONObject()
                .put("flowSchemaVersion", 1).put("nodeVersion", 1).put("nodeId", id)
                .put("blockId", flow.rootBlockId).put("parentId", JSONObject.NULL)
                .put("orderKey", order).put("kind", kind).put("args", args).toString()
            val source = listOf(
                node("qa-handoff-prompt", "task.runprompt", "a0", JSONObject().put("message", "PUBLIC_QA_HANDOFF")),
                node("qa-handoff-wait", "task.sleep", "b0", JSONObject().put("milliseconds", 60_000)),
            ).joinToString("\n", postfix = "\n")
            val saved = store.saveFlows(original, mapOf(flow.flowId to source)); owned = saved
            val compiled = client.compileVisualProject(saved.manifest.projectId)
            check(compiled is VisualCompileResult.Success) { "QA_HANDOFF_COMPILE" }
            val frozen = store.openProject(saved.manifest.projectId)
            val plan = RuntimeProjectPlan.fromVisualSnapshot(frozen, compiled.generationId).forEditorRun(frozen)
            check(runBlocking { withTimeout(20_000) { client.startProject(frozen.manifest.projectId, plan.luaSource,
                plan.resources, plan.capabilities, plan.designWidth, plan.designHeight, plan.scaleMode,
                floatingControl = true, requiredVisionComponents = plan.requiredVisionComponents) } }) { "QA_HANDOFF_START" }
            awaitState { it.engineState == RuntimeEngineState.RUNNING }
            ownPosition()
            val prompt = "运行提示：PUBLIC_QA_HANDOFF（点击设置，拖动移动）"
            waitNode("智构 · 展开运行控制"); waitNode(prompt)
            // A legacy custom View may have a false accessibility visibility bit.
            // Real touch must still expand/collapse the observed control, not just locate it.
            click("智构 · 展开运行控制"); waitNode("暂停")
            click("智构 · 收起运行控制"); waitNode("智构 · 展开运行控制")
            repeat(2) { iteration ->
                main { ContextCompat.startForegroundService(targetContext, Intent(targetContext, EditorOverlayService::class.java)
                    .setAction(EditorOverlayService.ACTION_OPEN).putExtra(EditorOverlayService.EXTRA_PROJECT, frozen.manifest.projectId)) }
                runBlocking { withTimeout(15_000) { EditorOverlaySessions.status.first { it?.projectId == frozen.manifest.projectId && it.ready } } }
                waitNode("打开项目设置"); waitAbsent("智构 · 展开运行控制"); waitNode(prompt)
                click("最小化为智构悬浮球")
                waitNode("智构 · 恢复窗口"); waitAbsent("打开项目设置")
                check(!exists("智构 · 展开运行控制")) { "QA_HANDOFF_TWO_BALLS" }
                waitNode(prompt)
                click("智构 · 恢复窗口"); waitNode("打开项目设置"); waitAbsent("智构 · 恢复窗口")
                if (iteration == 0) {
                    check(client.requestPause()) { "QA_HANDOFF_PAUSE" }
                    awaitState { it.engineState == RuntimeEngineState.PAUSED }
                }
                click("保存并关闭编辑窗")
                runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
                waitAbsent("打开项目设置"); waitAbsent("智构 · 恢复窗口")
                waitNode("智构 · 展开运行控制"); waitNode(prompt)
            }
            check(client.requestStop()) { "QA_HANDOFF_STOP" }
            awaitState { it.engineState == RuntimeEngineState.STOPPED }
            waitAbsent("智构 · 展开运行控制"); waitAbsent(prompt)
            owned = store.saveFlows(store.openProject(frozen.manifest.projectId), original.flowSources)
        }
    }
    private fun dialogWindows() {
        case("real-log-settings-and-function-library-fullscreen-restore") {
            var snapshot = store.openProject(requireNotNull(owned).manifest.projectId)
            snapshot = store.updateCapabilities(snapshot.manifest.projectId,
                snapshot.manifest.capabilities.toSet() + setOf("screen.capture", "vision.template") +
                    (if (landscapeOnly) setOf("input.basic") else emptySet()), snapshot.manifest.capabilities.toSet())
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
            if (landscapeOnly) editorLandscapeKeyboard(snapshot)
            editorParameterDialogs(snapshot)
            if (landscapeOnly) {
                case("landscape-find-replace-and-recording-actions-reachable") {
                    click("更多"); click("查找替换"); waitNode("查找替换")
                    setFirstEditable(""); click("QA_FIRST_EDITABLE")
                    waitFor { qaKeyboardBounds("QA键盘输入") != null }
                    clickQaKeyboard(); waitNode("QA")
                    click("替换")
                    val searchTitle = bounds("查找替换")
                    val searchKeyboard = requireNotNull(qaKeyboardBounds("QA键盘面板"))
                    repeat(6) {
                        if (!exists("确认范围并替换") || bounds("确认范围并替换").bottom > searchKeyboard.top - 60) {
                            dragQa(searchTitle.centerX().toFloat(), searchKeyboard.top - 84f, searchTitle.centerX().toFloat(), searchTitle.bottom + 24f)
                        }
                    }
                    waitNode("确认范围并替换")
                    checkOnScreen("确认范围并替换")
                    check(bounds("确认范围并替换").bottom <= requireNotNull(qaKeyboardBounds("QA键盘面板")).top) { "QA_REPLACE_ACTION_BEHIND_IME" }
                    click("最大化全屏"); waitNode("还原窗口"); click("还原窗口")
                    click("关闭"); waitAbsent("查找替换")
                    val recordingSupported = client.inputFeatures() and com.autoscript.runtime.api.RuntimeProtocol.INPUT_FEATURE_RECORDING != 0
                    cases.getJSONObject(cases.length() - 1).put("recordingBackendSupported", recordingSupported)
                    click("更多"); click("录制动作")
                    if (recordingSupported) {
                        waitNode("动作录制"); click("说明"); checkOnScreen("开始录制")
                        click("最大化全屏"); waitNode("还原窗口"); click("还原窗口")
                        click("关闭"); waitAbsent("动作录制")
                    } else {
                        waitNode("录制需要停止脚本、声明 input.basic，并确认Root录制能力")
                        click("知道了"); waitAbsent("编辑提示")
                    }
                    check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_LANDSCAPE_CANCEL_MUTATED_SOURCE" }
                }
                case("landscape-real-capture-cancel-restores-recognition-parameters") {
                    client.refresh()
                    check(client.runtimeStates.value.engineState !in active) { "QA_ROTATION_CREATED_PHANTOM_RUN" }
                    click("图像"); click("区域找图"); revealInspectorAction("截图取材", "区域找图", backward = true)
                    click("更多参数"); revealInspectorAction("QA_FIRST_EDITABLE", "区域找图"); setFirstEditable("2")
                    revealInspectorAction("截图取材", "区域找图", backward = true)
                    click("截图取材"); waitNode("图像工具画布")
                    checkOnScreen("关闭图像工具"); click("关闭图像工具"); revealInspectorAction("截图取材", "区域找图", backward = true)
                    revealInspectorAction("QA_FIRST_EDITABLE", "区域找图")
                    check(requireNotNull(find("QA_FIRST_EDITABLE")).let { node -> try { node.text.toString() == "2" } finally { node.recycle() } }) {
                        "QA_CAPTURE_RETURN_LOST_PARAMETERS"
                    }
                    click("取消"); waitNode("放弃识别参数？"); click("放弃"); waitNode("打开项目设置")
                    check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_CAPTURE_CANCEL_INSERTED_SOURCE" }
                }
                case("landscape-editor-minimize-restore-and-repeated-dialogs") {
                    // Keyboard-overlap assertions above stay keyboard-open. Window
                    // dragging/resizing is a separate, explicitly keyboard-closed case.
                    qaKeyboardBounds("QA键盘收起")?.let { tap(it); waitFor { qaKeyboardBounds("QA键盘面板") == null } }
                    val screen = Point().also { @Suppress("DEPRECATION") requireNotNull(activity).windowManager.defaultDisplay.getRealSize(it) }
                    click("最大化窗口"); waitNode("还原窗口")
                    waitFor { windowBoundsOrNull("还原窗口")?.width()?.let { it > screen.x * .8f } == true }
                    val maximizedFrame = windowBounds("还原窗口")
                    click("还原窗口")
                    waitNode("拖动调整窗口大小")
                    waitFor { windowBoundsOrNull("打开项目设置")?.let { frame ->
                        frame.width() < screen.x && frame.contains(bounds("拖动调整窗口大小"))
                    } == true }
                    val originalWindow = windowBounds("打开项目设置")
                    val originalHandle = bounds("拖动调整窗口大小")
                    val geometryReport = cases.getJSONObject(cases.length() - 1)
                    geometryReport.put("maximizedWindow", maximizedFrame.flattenToString())
                    geometryReport.put("originalWindow", originalWindow.flattenToString()).put("observedHandle", originalHandle.flattenToString())
                    fun renderedFrame(): Rect? {
                        val frame = uiAutomation.takeScreenshot() ?: return null
                        try {
                            fun white(pixel: Int) = Color.red(pixel) >= 245 && Color.green(pixel) >= 245 && Color.blue(pixel) >= 245
                            val y = frame.height / 2
                            // API25's white side navigation bar is outside the
                            // editor workspace and must not be counted as its edge.
                            val limit = minOf(frame.width, maximizedFrame.width())
                            val left = (0 until limit).firstOrNull { white(frame.getPixel(it, y)) } ?: return null
                            val right = (limit - 1 downTo left).first { white(frame.getPixel(it, y)) } + 1
                            val x = (left + right) / 2
                            val bottom = ((frame.height - 1 downTo y).firstOrNull { white(frame.getPixel(x, it)) } ?: return null) + 1
                            return Rect(left, originalWindow.top, right, bottom).also {
                                geometryReport.put("lastRenderedWindow", it.flattenToString())
                            }
                        } finally { frame.recycle() }
                    }
                    waitFor { renderedFrame()?.let { kotlin.math.abs(it.width() - originalWindow.width()) <= 2 &&
                        kotlin.math.abs(it.left - originalWindow.left) <= 16 } == true }
                    val originalRendered = requireNotNull(renderedFrame())
                    geometryReport.put("originalRenderedWindow", originalRendered.flattenToString())
                    fun observedHandle(frame: Rect) = Rect(originalHandle).apply {
                        offset(frame.right - originalWindow.right, frame.bottom - originalWindow.bottom)
                    }
                    val settings = bounds("打开项目设置")
                    val minimize = bounds("最小化为智构悬浮球")
                    val headerX = (settings.right + minimize.left) / 2f
                    dragQa(headerX, settings.exactCenterY(), headerX + 64f, settings.exactCenterY())
                    waitFor { renderedFrame()?.left?.let { it > originalRendered.left + 8 } == true }
                    val moved = requireNotNull(renderedFrame())
                    geometryReport.put("movedWindow", moved.flattenToString())
                    // MuMu can mark a moved Compose corner invisible in accessibility.
                    // Track its observed right/bottom inset; only real geometry changes pass.
                    val resize = observedHandle(moved)
                    geometryReport.put("resizeTouch", resize.flattenToString())
                    dragQa(resize.exactCenterX(), resize.exactCenterY(), resize.exactCenterX() + 80f, resize.exactCenterY() - 48f)
                    waitFor { renderedFrame()?.let { it.width() > moved.width() + 8 && it.height() < moved.height() - 8 } == true }
                    checkOnScreen("保存并关闭编辑窗")
                    geometryReport.put("resizedRenderedWindow", requireNotNull(renderedFrame()).flattenToString())
                    captureLandscape("resized-public-editor")
                    val resizedHandle = observedHandle(requireNotNull(renderedFrame()))
                    dragQa(resizedHandle.exactCenterX(), resizedHandle.exactCenterY(), resizedHandle.exactCenterX() - 80f, resizedHandle.exactCenterY() + 48f)
                    val movedSettings = bounds("打开项目设置")
                    val movedMinimize = bounds("最小化为智构悬浮球")
                    val movedHeaderX = (movedSettings.right + movedMinimize.left) / 2f
                    dragQa(movedHeaderX, movedSettings.exactCenterY(), movedHeaderX - 64f, movedSettings.exactCenterY())
                    waitFor { renderedFrame()?.left?.let { it <= originalRendered.left + 2 } == true }
                    click("最小化为智构悬浮球"); waitNode("智构 · 恢复窗口")
                    click("智构 · 恢复窗口"); waitNode("打开项目设置")
                    repeat(3) { click("函数"); waitNode("函数库"); click("关闭函数库"); waitAbsent("函数库") }
                    checkOnScreen("保存并关闭编辑窗")
                }
            }
            click("保存并关闭编辑窗")
            runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } }
        }
    }
    private fun checkOnScreen(label: String) {
        val display = Point().also { @Suppress("DEPRECATION") requireNotNull(activity).windowManager.defaultDisplay.getRealSize(it) }
        check(bounds(label).let { !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= display.x && it.bottom <= display.y }) {
            "QA_LANDSCAPE_ACTION_CLIPPED"
        }
    }
    private fun dragQa(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val fraction = step / 10f
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(uiAutomation.injectInputEvent(event, true)) { "QA_DRAG_REJECTED" } } finally { event.recycle() }
            SystemClock.sleep(30)
        }
        try { uiAutomation.waitForIdle(150, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
    }
    private fun landscapeDialogs() {
        val owner = requireNotNull(activity)
        val requested = owner.requestedOrientation
        val original = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
        val sentinel = Color.rgb(31, 52, 78)
        main {
            owner.setContentView(View(owner).apply { setBackgroundColor(sentinel) })
            owner.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        try {
            case("landscape-real-frame-presented-before-overlay") {
                waitFor {
                    val size = Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) }
                    if (size.x <= size.y) false else uiAutomation.takeScreenshot()?.let { frame ->
                        try { frame.width == size.x && frame.height == size.y && frame.getPixel(frame.width / 2, frame.height / 2) == sentinel }
                        finally { frame.recycle() }
                    } == true
                }
                client.refresh()
                check(client.runtimeStates.value.engineState !in active) { "QA_ROTATION_CREATED_PHANTOM_RUN" }
            }
            dialogWindows()
        } finally {
            val session = EditorOverlaySessions.status.value
            if (session?.projectId == owned?.manifest?.projectId) {
                runCatching { dismissOwnedQaVariables() }
                main { targetContext.startService(Intent(targetContext, EditorOverlayService::class.java).setAction(EditorOverlayService.ACTION_CLOSE)) }
                runCatching { runBlocking { withTimeout(8_000) { EditorOverlaySessions.status.first { it == null } } } }
            }
            main { owner.requestedOrientation = restoredScreenOrientation(requested) }
            waitFor { Point().also { @Suppress("DEPRECATION") owner.windowManager.defaultDisplay.getRealSize(it) } == original }
        }
    }
    private fun qaKeyboardBounds(description: String): Rect? {
        check(Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ==
            "com.autoscript.vision.acceptance/com.autoscript.runtime.service.QaInputMethodService") { "QA_KEYBOARD_NOT_SELECTED" }
        var budget = 128
        fun visit(node: AccessibilityNodeInfo): Rect? {
            try {
                if (--budget < 0 || node.packageName?.toString() != "com.autoscript.vision.acceptance") return null
                if (node.contentDescription?.toString() == description) return Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
                for (index in 0 until node.childCount.coerceAtMost(32)) node.getChild(index)?.let(::visit)?.let { return it }
                return null
            } finally { node.recycle() }
        }
        return uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root?.let(::visit)
    }
    private fun captureLandscape(tag: String) {
        if (landscapeCaptureCount >= 60 && (tag == "click" || landscapeCaptureCount >= 64)) return
        val frame = uiAutomation.takeScreenshot() ?: return
        try { File(requireNotNull(activity?.externalCacheDir), "qa-landscape-${landscapeCaptureCount++}-$tag.png")
            .outputStream().use { check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { frame.recycle() }
    }
    private fun clickQaKeyboard() {
        var previous: Rect? = null
        var stableSince = 0L
        waitFor {
            val current = qaKeyboardBounds("QA键盘输入")
            val now = SystemClock.uptimeMillis()
            if (current == null || current != previous) {
                previous = current?.let(::Rect); stableSince = now; false
            } else now - stableSince >= 400L
        }
        captureLandscape("keyboard-key")
        tap(requireNotNull(previous))
    }
    private fun editorLandscapeKeyboard(snapshot: ProjectSnapshot) {
        case("landscape-overlay-real-keyboard-commit-and-uncovered-variable-actions") {
            click("变量"); waitNode("变量管理"); click("＋新增"); waitNode("新增局部变量"); waitNode("QA_FIRST_EDITABLE")
            setFirstEditable("")
            click("QA_FIRST_EDITABLE")
            waitFor { qaKeyboardBounds("QA键盘输入") != null }
            clickQaKeyboard()
            waitNode("QA")
            waitFor { bounds("添加").bottom <= requireNotNull(qaKeyboardBounds("QA键盘面板")).top }
            checkOnScreen("添加")
            cases.getJSONObject(cases.length() - 1).put("keyboardBounds", requireNotNull(qaKeyboardBounds("QA键盘面板")).flattenToString())
                .put("actionBounds", bounds("添加").flattenToString()).put("realImeCommit", true)
            click("添加"); waitAbsent("新增局部变量"); revealInspectorAction("QA", "变量管理")
            click("取消"); waitNode("放弃变量更改？"); click("放弃更改"); waitAbsent("变量管理")
            check(store.openProject(snapshot.manifest.projectId).manifest.variables == snapshot.manifest.variables) { "QA_LANDSCAPE_IME_SAVED_DISCARDED_VARIABLE" }
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
            click("＋新增"); waitNode("新增局部变量"); waitNode("QA_FIRST_EDITABLE")
            setFirstEditable("qaUnsavedOnly")
            click("添加"); waitAbsent("新增局部变量"); revealInspectorAction("qaUnsavedOnly", "变量管理")
            click("取消"); waitNode("放弃变量更改？")
            click("取消"); waitAbsent("放弃变量更改？")
            click("最大化全屏"); waitNode("还原窗口"); revealInspectorAction("qaUnsavedOnly", "变量管理")
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
            click("图像"); waitNode("截图取材")
            click("区域找图"); revealInspectorAction("截图取材", "区域找图", backward = true)
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
        case("editor-variable-check-and-runtime-observer-use-live-windows") {
            click("更多"); click("变量检查"); waitNode("变量检查")
            revealInspectorAction("编译器参数 / 类型校验")
            click("编译器参数 / 类型校验")
            waitNode("编译器参数与类型校验通过。此检查不能证明所有执行路径都已赋值。")
            click("最大化全屏"); waitNode("还原窗口")
            click("还原窗口"); click("关闭"); waitAbsent("变量检查")
            click("更多"); click("运行变量"); waitNode("关闭运行变量")
            waitNode("当前值")
            click("关闭运行变量"); waitAbsent("关闭运行变量")
            waitNode("打开项目设置")
            check(store.openProject(snapshot.manifest.projectId).flowSources == snapshot.flowSources) { "QA_INSPECTOR_MUTATED_SOURCE" }
        }
    }
    private fun revealInspectorAction(label: String, dialogTitle: String = "变量检查", backward: Boolean = false) {
        waitNode(dialogTitle)
        val touchViewport = if (landscapeOnly && dialogTitle in setOf("变量检查", "区域找图")) {
            val title = bounds(dialogTitle)
            val footer = bounds(if (dialogTitle == "区域找图") "取消" else "QA_DIALOG_CLOSE_ACTION")
            val bottom = minOf(footer.top - 24f, (qaKeyboardBounds("QA键盘面板")?.top ?: footer.top) - 24f)
            val top = title.bottom + 24f
            Triple(title.exactCenterX(), top, bottom)
        } else null
        for (attempt in 0 until 6) {
            if (exists(label) && (!landscapeOnly || qaKeyboardBounds("QA键盘面板")?.let { bounds(label).bottom <= it.top } != false)) return
            val title = find(dialogTitle) ?: break
            val windowId = try { title.windowId } finally { title.recycle() }
            var budget = 256
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (--budget < 0 || node.packageName?.toString() != targetContext.packageName) { node.recycle(); return null }
                if (node.isScrollable && node.isVisibleToUser && node.actionList.any {
                    it.id == (if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }) return node
                for (index in 0 until node.childCount.coerceAtMost(32)) {
                    node.getChild(index)?.let(::scrollable)?.let { node.recycle(); return it }
                }
                node.recycle(); return null
            }
            val scroller = uiAutomation.windows.firstOrNull { it.id == windowId }?.root?.let(::scrollable) ?: break
            val accepted = try { scroller.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { scroller.recycle() }
            if (!accepted) break
            try { uiAutomation.waitForIdle(100, 1000) } catch (_: java.util.concurrent.TimeoutException) { }
        }
        if (touchViewport != null) repeat(4) {
            if (exists(label)) return
            // The fixed header can retain a stale invisible accessibility bit after
            // scrolling on API25. Use its observed pre-scroll bounds for real swipes.
            val (x, top, bottom) = touchViewport
            if (bottom > top + 24f) {
                if (backward) dragQa(x, top, x, bottom) else dragQa(x, bottom, x, top)
            }
        }
        waitNode(label)
    }
    private fun setFirstEditable(value: String) {
        val node = requireNotNull(find("QA_FIRST_EDITABLE"))
        try { check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "QA_TEXT_EDIT_REJECTED" } } finally { node.recycle() }
    }
    private fun dismissOwnedQaVariables() {
        if (exists("新增局部变量")) { click("取消"); waitAbsent("新增局部变量") }
        if (exists("变量管理") && exists("取消")) {
            click("取消")
            waitFor { !exists("变量管理") || exists("放弃变量更改？") }
            if (exists("放弃变量更改？")) click("放弃更改")
            waitAbsent("变量管理")
        }
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
            if (exists("变量检查") && exists("关闭")) { click("关闭"); waitAbsent("变量检查") }
            if (exists("关闭运行变量")) click("关闭运行变量")
            if (landscapeOnly) dismissOwnedQaVariables()
            if (landscapeOnly && exists("区域找图") && exists("取消")) {
                click("取消")
                if (exists("放弃识别参数？")) click("放弃")
                waitAbsent("区域找图")
            }
            if (imageLibraryOnly && exists("选择图片文件夹")) click("取消")
            if (imageLibraryOnly && exists("图片使用位置")) { click("关闭"); waitAbsent("图片使用位置") }
            if (imageLibraryOnly && exists("未能删除图片")) { click("知道了"); waitAbsent("未能删除图片") }
            if (imageLibraryOnly && exists("图片工作台")) {
                check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                waitAbsent("图片工作台")
            }
            if (imageLibraryOnly && exists("关闭图片库")) click("关闭图片库")
            if (imageLibraryOnly && exists("识别测试")) {
                check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                waitAbsent("识别测试")
            }
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
        val began = SystemClock.elapsedRealtime()
        val record = JSONObject().put("case", name).put("pass", false)
            .put("budgetRemainingMs", (deadline - began).coerceAtLeast(0))
        cases.put(record)
        sendStatus(1, Bundle().apply { putString("stream", "QA_CASE:$name\n") })
        try { work(); record.put("pass", true) }
        catch (failure: Throwable) {
            if (controlUsageStage.isNotEmpty()) record.put("ownedQaFailure", failure.message?.take(1800))
            if (landscapeOnly) captureLandscape("case-failure")
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
        } finally { record.put("elapsedMs", SystemClock.elapsedRealtime() - began) }
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
            val dispatchBegan = SystemClock.elapsedRealtime()
            // The two-argument API waits for animations even when sync=false.
            // Use the framework's fixed TestApi overload when available; no
            // animation scales/settings change, and every UI result is checked.
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                val accepted = noAnimationInput?.invoke(uiAutomation, event, true, false) as? Boolean
                    ?: uiAutomation.injectInputEvent(event, true)
                check(accepted) { "TAP_REJECTED" }
            }
            finally { event.recycle() }
            if (qaInputDispatch.length() < 16) qaInputDispatch.put(JSONObject().put("action", action)
                .put("dispatchMs", SystemClock.elapsedRealtime() - dispatchBegan).put("synchronous", true)
                .put("waitForAnimations", noAnimationInput == null))
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
        try {
            node.refresh()
            Rect().also(node::getBoundsInScreen).apply {
                uiAutomation.windows.firstOrNull { it.id == node.windowId }?.let { window ->
                    val correction = nativeControlWindowTranslation(window)
                    offset(correction.first, correction.second)
                }
            }
        } finally { node.recycle() }
    }
    // A moved/resized native runtime toolbar can retain its old accessibility root
    // origin (also observed on MuMu API32). API24/25 can do the same for TYPE_PHONE.
    // Translate only an equal-sized owned root: on newer APIs require the runtime
    // toolbar's native LinearLayout, never a Compose dialog. No scaling or inset
    // guesses; real touch and the resulting UI transition must still succeed.
    private fun nativeControlWindowTranslation(window: android.view.accessibility.AccessibilityWindowInfo): Pair<Int, Int> {
        val root = window.root ?: return 0 to 0
        return try {
            if (root.packageName?.toString() != targetContext.packageName) return 0 to 0
            if (Build.VERSION.SDK_INT !in 24..25 && root.className?.toString() != "android.widget.LinearLayout") return 0 to 0
            val native = Rect().also(window::getBoundsInScreen)
            val reported = Rect().also(root::getBoundsInScreen)
            if (native.isEmpty || native.width() != reported.width() || native.height() != reported.height()) 0 to 0
            else (native.left - reported.left) to (native.top - reported.top)
        } finally { root.recycle() }
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
        var stableSince = 0L
        var priorPoint: Rect? = null
        var priorWindow: Rect? = null
        waitFor {
            val point = find(label)?.let { it.recycle(); bounds(label) }
            val window = if (point != null) windowBoundsOrNull(label) else null
            if (point == null || window == null || point.isEmpty || !Rect.intersects(point, window)) {
                stableSince = 0L; false
            } else {
                val now = SystemClock.uptimeMillis()
                if (point != priorPoint || window != priorWindow || stableSince == 0L) {
                    if (label == "QA_DIALOG_CLOSE_ACTION" && qaDialogClickGeometry.length() < 16) {
                        qaDialogClickGeometry.put(JSONObject().put("point", point.flattenToString()).put("window", window.flattenToString())
                            .put("ime", uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                                ?.let { Rect().also(it::getBoundsInScreen).flattenToString() } ?: JSONObject.NULL))
                    }
                    priorPoint = Rect(point); priorWindow = Rect(window); stableSince = now; false
                } else now - stableSince >= 300L
            }
        }
        val point = requireNotNull(priorPoint)
        if (landscapeOnly) captureLandscape("click")
        lastClick = JSONObject().put("label", label).put("bounds", point.flattenToString()).put("window", requireNotNull(priorWindow).flattenToString())
        tap(point)
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        var budget = 4096
        var nativeWindowBounds = Rect()
        var windowTranslation = 0 to 0
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (--budget < 0) { node.recycle(); error("UI_TREE_BUDGET") }
            // Compose may replace virtual children when its backdrop disappears. Refreshing only
            // the root leaves detached child IDs with valid-looking old text/bounds in the cache.
            if (!node.refresh()) { node.recycle(); return null }
            val matches = if (label == "QA_FIRST_EDITABLE") node.isEditable
            else if (label == "QA_INSERTED_ROW") node.text?.toString()?.contains("PUBLIC_QA_INSERTION_COMPLETION") == true
            else if (label == "QA_DIALOG_CLOSE_ACTION") node.text?.toString() == "关闭"
            else if (label == "QA_START_ROW") node.text?.toString()?.let {
                it.contains("运行提示") && it.contains("PUBLIC_QA_PROMPT")
            } == true else node.text?.toString() == label || node.contentDescription?.toString() == label ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == label }
            val ownNativeBall = (Build.VERSION.SDK_INT in 24..25 || windowTranslation != (0 to 0)) && label in setOf(
                "智构 · 展开运行控制", "智构 · 收起运行控制", "智构 · 恢复窗口") && matches &&
                Rect().also(node::getBoundsInScreen).apply { offset(windowTranslation.first, windowTranslation.second) }
                    .let { !it.isEmpty && Rect.intersects(it, nativeWindowBounds) }
            if (node.packageName?.toString() == targetContext.packageName && (node.isVisibleToUser || ownNativeBall) && matches) return node
            for (index in 0 until node.childCount.coerceAtMost(256)) {
                val found = node.getChild(index)?.let(::visit)
                if (found != null) { node.recycle(); return found }
            }
            node.recycle(); return null
        }
        for (window in uiAutomation.windows.sortedByDescending { it.layer }.take(32)) {
            nativeWindowBounds = Rect().also(window::getBoundsInScreen)
            windowTranslation = nativeControlWindowTranslation(window)
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
