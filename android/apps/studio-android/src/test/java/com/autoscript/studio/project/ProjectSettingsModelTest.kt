package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProjectSettingsModelTest {
    private fun manifest() = com.autoscript.project.store.ProjectManifestDocument(
        projectId = "project-settings", name = "Settings", sourceMode = com.autoscript.project.store.ProjectSourceMode.VISUAL,
        capabilities = listOf("core.task", "custom.future"))

    @Test fun dirtySettingsSurviveExternalUpdatesAndKeepOriginalConflictBaseline() {
        val original = manifest()
        val base = ProjectExecutionSettingsDraft.from(original)
        val edited = base.copy(capabilities = setOf("core.task"), run = base.run.copy(numbers = base.run.numbers + ("width" to "")))
        val external = original.copy(debugSettings = original.debugSettings.copy(runDelayMs = 500))
        assertSame(edited, edited.observe(external))
        org.junit.Assert.assertTrue(edited.isDirty)
        org.junit.Assert.assertTrue(edited.conflictsWith(external))
        assertEquals(original.debugSettings, edited.baseline.debug)
        org.junit.Assert.assertNull(edited.run.parse())
        assertEquals(setOf("core.task"), edited.capabilities)
        val fresh = base.observe(external)
        org.junit.Assert.assertFalse(fresh.isDirty)
        org.junit.Assert.assertFalse(fresh.conflictsWith(external))
        assertEquals(500, fresh.run.settings.runDelayMs)
    }

    @Test fun resourceOnlyRefreshDoesNotReplaceExecutionDraftAndReloadIsExplicit() {
        val original = manifest()
        val base = ProjectExecutionSettingsDraft.from(original)
        val edited = base.copy(random = base.random.copy(touchEnabled = true, touchX = "2"))
        val refreshed = original.copy(resources = listOf(resource("image", "assets/images/a.png")))
        assertSame(edited, edited.observe(refreshed))
        org.junit.Assert.assertFalse(edited.conflictsWith(refreshed))
        val reset = ProjectExecutionSettingsDraft.from(refreshed)
        org.junit.Assert.assertFalse(reset.isDirty)
        assertEquals(original.capabilities.toSet(), reset.capabilities)
    }

    @Test fun settingsWindowIsProjectScopedAndDoesNotDuplicateSystemPermissions() {
        val ui = java.io.File("src/main/java/com/autoscript/studio/project/ProjectSettingsDialog.kt").readText()
        org.junit.Assert.assertTrue(ui.contains("key(snapshot.manifest.projectId)"))
        org.junit.Assert.assertTrue(ui.contains("val expected = executionDraft.baseline"))
        org.junit.Assert.assertTrue(ui.contains("if (settingsConflict)"))
        org.junit.Assert.assertTrue(ui.contains("if (settingsDirty) reloadVisible = true"))
        org.junit.Assert.assertTrue(ui.contains("if (runnerUiDraft != runnerUiInitial) discardRunnerUiVisible = true"))
        org.junit.Assert.assertFalse(ui.contains("onOpenPermissions"))
        org.junit.Assert.assertFalse(ui.contains("runCatching {\n                            withContext(Dispatchers.IO)"))
    }

    @Test fun removedUiHasNoSimulatorActionsOrShrinkAllImplementation() {
        val ui = java.io.File("src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt").readText()
        org.junit.Assert.assertTrue(ui.contains("AI · 尚未开放"))
        org.junit.Assert.assertFalse(ui.contains("private fun AiProgrammingPage"))
        org.junit.Assert.assertFalse(ui.contains("private fun AiSidePanelDialog"))
        org.junit.Assert.assertFalse(ui.contains("attachments++"))
        org.junit.Assert.assertFalse(ui.contains("private fun EditorColumnPage"))
        val designer = java.io.File("src/main/java/com/autoscript/studio/designer/RunnerUiDesignerModel.kt").readText()
        org.junit.Assert.assertFalse(designer.contains("fun shrinkAll("))
    }
    @Test
    fun newProjectsSelectAllCurrentBlockCapabilities() {
        assertEquals(BlockCatalog.insertable.flatMap { it.requiredCapabilities }.toSet(), defaultScriptCapabilities())
        org.junit.Assert.assertFalse("vision.pixel.legacy" in defaultScriptCapabilities())
        org.junit.Assert.assertTrue("ui.floating" in defaultScriptCapabilities())
        org.junit.Assert.assertTrue("device.clipboard" in defaultScriptCapabilities())
    }

    @Test
    fun inlineRunSettingsKeepLogsAndValidateUnfinishedNumericAndColorInputs() {
        val initial = com.autoscript.project.store.ProjectDebugSettings(traceExecution = true, recordVisionMetrics = false)
        val draft = ProjectRunSettingsDraft.from(initial)
        assertEquals(initial, draft.parse())
        org.junit.Assert.assertNull(draft.copy(numbers = draft.numbers + ("width" to "")).parse())
        org.junit.Assert.assertNull(draft.copy(numbers = draft.numbers + ("delay" to "60001")).parse())
        org.junit.Assert.assertNull(draft.copy(settings = initial.copy(popupStyle = initial.popupStyle.copy(backgroundColor = "#123"))).parse())
        org.junit.Assert.assertNull(draft.copy(settings = initial.copy(popupStyle = initial.popupStyle.copy(textAlign = "unknown"))).parse())
        val edited = draft.copy(numbers = draft.numbers + mapOf("width" to "100", "height" to "30", "x" to "0"))
        assertEquals(100, edited.parse()!!.popupStyle.widthPx)
        assertEquals(30, edited.parse()!!.popupStyle.heightPx)
        assertEquals(0, edited.parse()!!.popupStyle.xPx)
        assertEquals(true, edited.parse()!!.traceExecution)
        assertEquals(false, edited.parse()!!.recordVisionMetrics)
    }

    @Test
    fun capabilityChoicesPreserveDeclaredUnknownCapabilitiesAndDeduplicate() {
        val options = settingsCapabilityOptions(
            listOf("vision.pixel", "core.task", "vision.pixel"),
            listOf("custom.future", "core.task", "vision.pixel.legacy"),
        )
        assertEquals(listOf("core.task", "custom.future", "vision.pixel", "vision.pixel.legacy"), options)
        assertEquals("custom.future", settingsCapabilityTitle("custom.future"))
        assertEquals("按键与触摸", settingsCapabilityTitle("input.basic"))
    }

    @Test
    fun resourceFiltersPreserveOrderIdentityAndOriginalList() {
        val image = resource("image", "assets/images/a.png")
        val dictionary = resource("glyphDictionary", "dictionaries/test.asglyph")
        val other = resource("future", "assets/other.dat")
        val resources = listOf(image, dictionary, other)
        assertEquals(resources, filterSettingsResources(resources, null))
        assertEquals(listOf(image), filterSettingsResources(resources, "image"))
        assertEquals(listOf(dictionary), filterSettingsResources(resources, "glyphDictionary"))
        assertEquals(emptyList<JsonObject>(), filterSettingsResources(resources, "missing"))
        assertSame(image, filterSettingsResources(resources, "image").single())
        assertEquals(3, resources.size)
    }

    @Test
    fun settingsSectionsIncludeAutomaticPromptSettingsWithDistinctTitles() {
        assertEquals(listOf("项目资源", "脚本能力", "运行界面", "运行设置"), ProjectSettingsPage.entries.map { it.title })
    }

    private fun resource(kind: String, path: String) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("path", path)
    }
}
