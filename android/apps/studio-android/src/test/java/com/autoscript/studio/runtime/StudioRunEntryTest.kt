package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRunEntryTest {
    @Test fun onlyProjectLaunchOpensTheDesignedInterface() {
        val launch = File("src/main/java/com/autoscript/studio/editor/ScriptLaunchOverlayService.kt").readText()
        assertTrue(launch.contains("requireNotNull(snapshot.manifest.runnerUi)"))
        assertTrue(launch.contains("parsed.validateValues(content.values())"))
        assertTrue(launch.contains("RuntimeProjectPlan.fromVisualSnapshot(refreshed, generation).withInterface(refreshed, values)"))
        val editor = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue(editor.contains("RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId, runFlowId, stepNodeId)"))
        assertTrue(editor.contains("if (singleStep) it else it.forEditorRun(refreshed)"))
        assertFalse(editor.contains(".withInterface("))
    }

    @Test fun editorRunAndStepHaveExplicitSeparateEntryAndKeepTheEditorVisible() {
        val editor = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue(editor.contains("onRun = { runProject(StudioRunEntry.EDITOR) }"))
        assertTrue(editor.contains("runProject(StudioRunEntry.SINGLE_STEP)"))
        assertTrue(editor.contains("runtimeClient.compileVisualNodeTest"))
        assertFalse(editor.contains(".forSingleStep("))
        assertTrue(editor.contains("floatingControl = false"))
        val visibility = editor.substringAfter("overlayHost?.suppress(when {").substringBefore("})")
        assertFalse(visibility.contains("VisualAction.STARTING"))
        assertFalse(visibility.contains("RuntimeEngineState.RUNNING"))
        val service = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        assertTrue(service.contains("{ session.restore() }"))
        assertFalse(service.contains("session.runtimeChanged"))
        val launch = File("src/main/java/com/autoscript/studio/editor/ScriptLaunchOverlayService.kt").readText()
        assertTrue(launch.contains("floatingControl = true"))
    }

    @Test fun editorOverlaySuppressesStaleRuntimeControlSurface() {
        val service = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        assertTrue(service.contains("runtimeControl.open(runtime, ::setRuntimeControlEnabled)"))
        assertTrue(service.contains("runtimeControl.observe(state, ::setRuntimeControlEnabled)"))
        assertTrue(service.contains("runtimeControl.close(client.runtimeStates.value, ::setRuntimeControlEnabled)"))
        val editor = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue("悬浮服务负责最终交还控制，不能在窗口关闭前启用运行球", editor.contains("if (overlayHost == null && currentRuntimeState.engineState"))
    }

    @Test fun projectPageUsesOnlyTheServiceOwnedEditorAndFormalLaunch() {
        val page = File("src/main/java/com/autoscript/studio/project/ProjectPage.kt").readText()
        assertTrue(page.contains("openOverlayEditor(project.projectId)"))
        assertTrue(page.contains("EditorOverlayService.openCapture(context, handoff)"))
        assertTrue(page.contains("onRun = { launchProject(project) }"))
        assertTrue(page.contains("openScriptInterface(project.projectId)"))
        assertTrue(page.contains("RuntimeProjectPlan.fromVisualSnapshot(refreshed, generationId)"))
        assertTrue(page.contains("floatingControl = true"))
        assertTrue(page.contains("RunnerUiDesignerScreen("))
        assertTrue(page.contains("ProjectPackageScreen("))
        assertTrue(page.contains("HistoricalProjectsDialog("))
        for (retired in listOf("floatingEditorProject", "floatingVisualEditor", "EditorDock(",
            "VisualProjectScreen(", "compileVisualNodeTest(", "saveFloatingFlow", "captureForImageTool")) {
            assertFalse("Duplicate editor route: $retired", page.contains(retired))
        }
        val service = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        assertTrue(service.contains("VisualProjectScreen("))
    }

    // Static wiring regression: native View behavior is still checked manually on the emulator.
    @Test fun submittedInterfaceHidesBeforeTheStartupCallback() {
        val source = File("src/main/java/com/autoscript/studio/designer/ProjectInterfacePreview.kt").readText()
        val submit = source.substringAfter("confirming = true").substringBefore("\"close\" ->")
        val hide = submit.indexOf("dialog.hide()")
        val start = submit.indexOf("confirm(validated)")
        assertTrue("设计弹窗必须在提交启动前立即隐藏", hide >= 0 && start > hide)
        assertTrue("同步提交失败应恢复弹窗", submit.contains("dialog.show()"))
        val editor = editorDockTestSources()
        assertFalse("编辑器不应套用设计界面的运行收起逻辑", editor.contains("runPresentation"))
    }

    @Test fun nativePreviewAndCaptureHandoffAreOwnedByTheirEditorSession() {
        val preview = File("src/main/java/com/autoscript/studio/designer/ProjectInterfacePreview.kt").readText()
        assertTrue(preview.contains("overlayHost.registerChild(managedWindow, prompt = promptLayer)"))
        assertTrue(preview.contains("overlayHost.unregisterChild(managedWindow)"))
        assertTrue(preview.contains("!visible || minimized || confirming || unavailable"))
        assertTrue(preview.contains("overlayHost.refreshChild(managedWindow)"))
        val session = File("src/main/java/com/autoscript/studio/editor/EditorOverlaySession.kt").readText()
        assertTrue(session.contains("if (!closed && childWindows.contains(window)) present(window)"))
        val editor = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        val invalid = editor.substringAfter("if (!validEditorProjectId(id)) {").substringBefore("when (editorSessionDecision")
        assertTrue(invalid.contains("if (projectId == null) stopSelf()"))
        assertTrue(invalid.contains("session.closeRequest?.invoke() ?: stopSelf()"))
        assertTrue(editor.contains("withContext(Dispatchers.IO) { decoded = decodeCaptureHandoff"))
        assertTrue(editor.contains("finally { decoded?.recycle() }"))
        val source = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue(source.contains("withContext(Dispatchers.IO) { decoded = BitmapFactory.decodeFile"))
        assertFalse(source.contains("decoded = try {"))
    }
}
