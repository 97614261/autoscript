package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRunEntryTest {
    @Test fun onlyProjectLaunchOpensTheDesignedInterface() {
        assertTrue(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = true))
        assertFalse(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = false))
        assertFalse(StudioRunEntry.LAUNCH.opensInterface(hasDefinition = true, hasSubmittedValues = true))
        assertFalse(StudioRunEntry.EDITOR.opensInterface(hasDefinition = true))
        assertFalse(StudioRunEntry.SINGLE_STEP.opensInterface(hasDefinition = true))
    }

    @Test fun editorRunAndStepHaveExplicitSeparateEntryAndKeepTheEditorVisible() {
        val editor = File("src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt").readText()
        assertTrue(editor.contains("onRun = { runProject(StudioRunEntry.EDITOR) }"))
        assertTrue(editor.contains("runProject(StudioRunEntry.SINGLE_STEP)"))
        assertTrue(editor.contains("runtimeClient.compileVisualNodeTest"))
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

    // Static wiring regression: native View behavior is still checked manually on the emulator.
    @Test fun submittedInterfaceHidesBeforeTheStartupCallback() {
        val source = File("src/main/java/com/autoscript/studio/designer/ProjectInterfacePreview.kt").readText()
        val submit = source.substringAfter("confirming = true").substringBefore("\"close\" ->")
        val hide = submit.indexOf("dialog.hide()")
        val start = submit.indexOf("confirm(validated)")
        assertTrue("设计弹窗必须在提交启动前立即隐藏", hide >= 0 && start > hide)
        assertTrue("同步提交失败应恢复弹窗", submit.contains("dialog.show()"))
        val editor = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertFalse("编辑器不应套用设计界面的运行收起逻辑", editor.contains("runPresentation"))
    }

    @Test fun nativePreviewAndCaptureHandoffAreOwnedByTheirEditorSession() {
        val preview = File("src/main/java/com/autoscript/studio/designer/ProjectInterfacePreview.kt").readText()
        assertTrue(preview.contains("overlayHost.registerChild(managedWindow)"))
        assertTrue(preview.contains("overlayHost.unregisterChild(managedWindow)"))
        assertTrue(preview.contains("!visible || minimized || confirming || unavailable"))
        assertTrue(preview.contains("overlayHost?.isClosed != true"))
        val editor = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        val invalid = editor.substringAfter("if (!validEditorProjectId(id)) {").substringBefore("when (editorSessionDecision")
        assertTrue(invalid.contains("if (projectId == null) stopSelf()"))
        assertTrue(invalid.contains("session.closeRequest?.invoke() ?: stopSelf()"))
        assertTrue(editor.contains("withContext(Dispatchers.IO) { decoded = decodeCaptureHandoff"))
        assertTrue(editor.contains("finally { decoded?.recycle() }"))
        for (file in listOf("editor/VisualProjectScreen.kt", "project/ProjectPage.kt")) {
            val source = File("src/main/java/com/autoscript/studio/$file").readText()
            assertTrue(source.contains("withContext(Dispatchers.IO) { decoded = BitmapFactory.decodeFile"))
            assertFalse(source.contains("decoded = try {"))
        }
    }
}
