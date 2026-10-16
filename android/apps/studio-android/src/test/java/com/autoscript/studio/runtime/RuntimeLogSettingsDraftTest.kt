package com.autoscript.studio

import com.autoscript.project.store.PopupStyle
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.RunPromptFilters
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeLogSettingsDraftTest {
    @Test fun asynchronousSaveKeepsDraftAndBusyStateDoesNotRemoveDialog() {
        val log = File("src/main/java/com/autoscript/studio/runtime/RuntimeLogDialog.kt").readText()
        assertFalse(log.contains("onSaveDebugSettings(settings); recordingSettings = false"))
        val dock = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertTrue(dock.contains("debugSettings = debugSettings, settingsEditable = editingEnabled"))
        val dialog = File("src/main/java/com/autoscript/studio/runtime/RuntimeLogSettingsDialog.kt").readText()
        assertTrue(dialog.contains("submitted == RuntimeLogSettingsDraft.from(settings)"))
        assertTrue(dialog.contains("if (draft != RuntimeLogSettingsDraft.from(settings)) confirmDiscard"))
        assertTrue(dialog.contains("enabled = enabled && draft != RuntimeLogSettingsDraft.from(settings)"))
    }
    @Test fun openingAndCancellingDoesNotChangeProjectSettings() {
        val settings = ProjectDebugSettings(traceExecution = false, recordVisionMetrics = true)
        val draft = RuntimeLogSettingsDraft.from(settings)
        assertEquals(settings, draft.applyTo(settings))
        val edited = draft.copy(traceExecution = true, recordVisionMetrics = false)
        assertNotEquals(draft, edited)
        assertFalse(settings.traceExecution)
        assertTrue(settings.recordVisionMetrics)
    }

    @Test fun savingChangesOnlyTheTwoLogOptionsAndPreservesLatestOtherSettings() {
        val initial = ProjectDebugSettings()
        val latest = initial.copy(runDelayMs = 500, showRunPrompts = false,
            runPromptFilters = RunPromptFilters(loops = true, jumps = true),
            popupStyle = PopupStyle(widthPx = 300, fontPx = 20, xPx = 0))
        val edited = RuntimeLogSettingsDraft.from(initial).copy(traceExecution = true, recordVisionMetrics = false)
        val saved = edited.applyTo(latest)
        assertEquals(latest.copy(traceExecution = true, recordVisionMetrics = false), saved)
        assertEquals(latest.runPromptFilters, saved.runPromptFilters)
        assertEquals(latest.popupStyle, saved.popupStyle)
    }
}
