package com.autoscript.studio

import com.autoscript.project.store.PopupStyle
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.RunPromptFilters
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PopupStyleDraftTest {
    @Test fun projectSettingsPresetsUseUnsavedDimensionsAndPreserveOtherDraftFields() {
        val initial = ProjectRunSettingsDraft.from(ProjectDebugSettings())
        val draft = initial.copy(numbers = initial.numbers + mapOf(
            "width" to "200", "height" to "80", "delay" to "1234", "font" to "-",
        ))
        val positioned = requireNotNull(draft.popupStyleDraft().withPosition(PopupPositionPreset.BOTTOM_RIGHT, 1280, 720))
        val updated = draft.withPopupPosition(positioned)
        assertEquals("1080", updated.numbers["x"])
        assertEquals("640", updated.numbers["y"])
        assertEquals(draft.numbers - setOf("x", "y"), updated.numbers - setOf("x", "y"))
        assertEquals(draft.settings, updated.settings)
        assertNull(updated.parse())
        assertNull(draft.copy(numbers = draft.numbers + ("width" to "")).popupStyleDraft()
            .withPosition(PopupPositionPreset.CENTER, 1280, 720))
    }

    @Test fun bothSettingsFormsSharePositionShortcutsAndProjectDisabledState() {
        val ui = File("src/main/java/com/autoscript/studio/runtime/DebugPopupStyleEditor.kt").readText()
        val project = File("src/main/java/com/autoscript/studio/project/ProjectRunSettingsForm.kt").readText()
        assertTrue(ui.contains("PopupPositionShortcuts(draft, onChange = onChange)"))
        assertTrue(ui.contains("enabled = enabled && positioned != null"))
        assertTrue(project.contains("PopupPositionShortcuts(draft.popupStyleDraft(), enabled)"))
        assertTrue(project.contains("onChange(draft.withPopupPosition(it))"))
    }

    @Test fun eightPositionsAndCenterUsePhysicalScreenAndOnlyChangeCoordinates() {
        val draft = PopupStyleDraft.from(PopupStyle(widthPx = 200, heightPx = 80))
        val expected = listOf(0 to 0, -1 to 0, 1080 to 0, 0 to -1, -1 to -1,
            1080 to -1, 0 to 640, -1 to 640, 1080 to 640)
        assertEquals(9, PopupPositionPreset.entries.size)
        PopupPositionPreset.entries.zip(expected).forEach { (preset, xy) ->
            val positioned = requireNotNull(draft.withPosition(preset, 1280, 720))
            assertEquals(xy.first.toString(), positioned.inputs["x"])
            assertEquals(xy.second.toString(), positioned.inputs["y"])
            assertEquals(draft.inputs - setOf("x", "y"), positioned.inputs - setOf("x", "y"))
            assertEquals(draft.textAlign, positioned.textAlign)
            assertNotNull(positioned.parse())
        }
    }

    @Test fun presetsMatchRuntimeClampingAndDoNotTurnStoredPixelsIntoRotationAnchors() {
        val draft = PopupStyleDraft.from(PopupStyle(widthPx = 1000, heightPx = 1000))
        val portrait = requireNotNull(draft.withPosition(PopupPositionPreset.BOTTOM_RIGHT, 720, 1280))
        assertEquals("0", portrait.inputs["x"])
        assertEquals("280", portrait.inputs["y"])
        val landscape = requireNotNull(draft.withPosition(PopupPositionPreset.BOTTOM_RIGHT, 1280, 720))
        assertEquals("280", landscape.inputs["x"])
        assertEquals("0", landscape.inputs["y"])
        assertEquals("280", portrait.inputs["y"])
        assertNull(draft.withPosition(PopupPositionPreset.BOTTOM_RIGHT, 0, 720))
        assertNull(draft.edit("width", "").withPosition(PopupPositionPreset.CENTER, 1280, 720))
        assertNull(draft.edit("height", "0").withPosition(PopupPositionPreset.TOP_LEFT, 1280, 720))
        assertNull(draft.withPosition(PopupPositionPreset.TOP_RIGHT, 20000, 720))
    }

    @Test fun presetRemainsAnUnsavedDraftUntilActualSaveAcknowledgement() {
        val settings = ProjectDebugSettings()
        val state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        val positioned = requireNotNull(state.draft.withPosition(PopupPositionPreset.BOTTOM_RIGHT, 1280, 720))
        val edited = state.edit(positioned)
        assertTrue(edited.dirty)
        assertNull(edited.pending)
        val submitted = edited.submit(settings)
        val request = requireNotNull(submitted.pending)
        assertEquals(760, request.settings.popupStyle.xPx)
        assertEquals(576, request.settings.popupStyle.yPx)
        assertFalse(submitted.complete(request, Result.success(request.settings)).dirty)
    }

    @Test fun existingStylesRoundTripAndColorsNormalizeWithoutChangingPixelValues() {
        val style = PopupStyle(widthPx = 100, heightPx = 30, xPx = 0, yPx = -1, fontPx = 10,
            cornerPx = 0, durationMs = 500, textAlign = "left")
        val draft = PopupStyleDraft.from(style)
        assertEquals(style, draft.parse())
        assertEquals(style.copy(backgroundColor = "#AA12BC34"), draft.edit("background", "#aa12bc34").parse())
        assertEquals(PopupStyle(), PopupStyleDraft.from(PopupStyle()).parse())
    }

    @Test fun numberRangesAndMissingValuesBlockSaveWithoutSilentClamping() {
        val base = PopupStyleDraft.from(PopupStyle())
        for ((key, range) in POPUP_NUMBER_RANGES) {
            assertNotNull(base.edit(key, range.first.toString()).parse())
            assertNotNull(base.edit(key, range.last.toString()).parse())
            assertNull(base.edit(key, (range.first - 1).toString()).parse())
            assertNull(base.edit(key, (range.last + 1).toString()).parse())
            assertNull(base.edit(key, "").parse())
            assertNull(base.edit(key, "9999999999999999").parse())
        }
        assertNull(base.copy(inputs = base.inputs - "width").parse())
        assertNull(base.edit("x", "-").parse())
    }

    @Test fun incompleteColorAndUnknownAlignCannotBeSavedOrUsedForDrawing() {
        val style = PopupStyle()
        val base = PopupStyleDraft.from(style)
        for (color in listOf("", "#123456", "#1234567G", "12345678", "#123456789")) {
            assertNull(base.edit("background", color).parse())
            assertEquals(style.backgroundColor, base.edit("background", color).preview(style).backgroundColor)
        }
        assertNull(base.copy(textAlign = "unknown").parse())
        assertEquals("center", base.copy(textAlign = "unknown").preview(style).textAlign)
    }

    @Test fun invalidInputStaysInDraftWhileOtherValidEditsPreviewNormally() {
        val style = PopupStyle()
        val draft = PopupStyleDraft.from(style).edit("width", "").edit("font", "24").edit("x", "0")
        assertEquals("", draft.inputs["width"])
        assertNull(draft.parse())
        assertEquals(style.copy(fontPx = 24, xPx = 0), draft.preview(style))
        assertEquals(style.copy(widthPx = 100, fontPx = 24, xPx = 0), draft.edit("width", "100").parse())
    }

    @Test fun previewFitsUniformlyAndNeverEnlargesSmallPopup() {
        assertEquals(1f, popupPreviewScale(100, 30, 560f, 144f), .0001f)
        assertEquals(.25f, popupPreviewScale(1000, 200, 250f, 144f), .0001f)
        assertEquals(.12f, popupPreviewScale(200, 1200, 560f, 144f), .0001f)
        assertTrue(popupPreviewScale(100, 30, Float.NaN, -1f).isFinite())
    }

    @Test fun opacityShortcutsChangeOnlyAlphaAndNeverRepairIncompleteColor() {
        val base = PopupStyleDraft.from(PopupStyle(backgroundColor = "#B312AB34", textColor = "#FFABCDEF"))
        assertEquals("#0012AB34", base.withOpacity("background", 0).inputs["background"])
        assertEquals("#8012AB34", base.withOpacity("background", 50).inputs["background"])
        assertEquals("#FF12AB34", base.withOpacity("background", 100).inputs["background"])
        assertEquals("#40ABCDEF", base.withOpacity("text", 25).inputs["text"])
        assertEquals(base.inputs["text"], base.withOpacity("background", 75).inputs["text"])
        assertEquals(base.edit("background", "#12"), base.edit("background", "#12").withOpacity("background", 100))
        assertThrows(IllegalArgumentException::class.java) { base.withOpacity("font", 50) }
        assertThrows(IllegalArgumentException::class.java) { base.withOpacity("background", 101) }
    }

    @Test fun onlyAcknowledgedSaveClearsDirtyAndReportsSuccess() {
        val settings = ProjectDebugSettings()
        var state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        state = state.edit(state.draft.edit("width", "300")).submit(settings)
        val request = requireNotNull(state.pending)
        assertTrue(state.dirty)
        assertEquals("正在保存配置…", state.message)
        assertEquals(state, state.observe(request.settings.popupStyle))
        state = state.complete(request, Result.success(request.settings))
        assertFalse(state.dirty)
        assertNull(state.pending)
        assertNull(state.error)
        assertEquals("配置已保存", state.message)
        assertEquals(300, state.draft.parse()?.widthPx)
        assertTrue(state.edit(state.draft.edit("height", "200")).dirty)
    }

    @Test fun savingEquivalentNumberAndColorSpellingDoesNotRemainDirty() {
        val settings = ProjectDebugSettings(runDelayMs = 500, traceExecution = true,
            runPromptFilters = RunPromptFilters(loops = true))
        var state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        state = state.edit(state.draft.edit("width", "0520").edit("text", "#ffffffff"))
        assertTrue(state.dirty)
        state = state.submit(settings)
        val request = requireNotNull(state.pending)
        assertEquals(settings, request.settings)
        state = state.observe(settings.popupStyle).complete(request, Result.success(settings))
        assertFalse(state.dirty)
        assertEquals("520", state.draft.inputs["width"])
        assertEquals("#FFFFFFFF", state.draft.inputs["text"])
        assertEquals("配置已保存", state.message)
    }

    @Test fun savedStyleAndEmptyOutputChannelChoiceAllowImmediateClose() {
        val settings = ProjectDebugSettings()
        var state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        state = state.edit(state.draft.edit("width", "300")).submit(settings)
        val request = requireNotNull(state.pending)
        state = state.complete(request, Result.success(request.settings))
        val initialOutput = DebugOutputDraft()
        val emptyPopup = initialOutput.copy(mode = DebugOutputCode.TOAST)
        assertFalse(state.dirty || emptyPopup.needsDiscard(initialOutput, editingExisting = false))
        assertEquals("配置已保存", state.message)
        assertTrue(emptyPopup.copy(text = "尚未加入的提示")
            .needsDiscard(initialOutput, editingExisting = false))
        assertFalse(state.dirty)
    }

    @Test fun failureRetainsInputAndRetryRejectsOldAcknowledgement() {
        val settings = ProjectDebugSettings()
        var state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        state = state.edit(state.draft.edit("x", "123")).submit(settings)
        val oldRequest = requireNotNull(state.pending)
        state = state.complete(oldRequest, Result.failure(IllegalStateException("disk failure")))
        assertTrue(state.dirty)
        assertEquals("123", state.draft.inputs["x"])
        assertNull(state.pending)
        assertNull(state.message)
        assertNotNull(state.error)
        state = state.submit(settings)
        val retry = requireNotNull(state.pending)
        assertNotSame(oldRequest, retry)
        assertSame(state, state.complete(oldRequest, Result.success(oldRequest.settings)))
        state = state.complete(retry, Result.success(retry.settings))
        assertFalse(state.dirty)
        assertNull(state.error)
    }

    @Test fun editsDuringSaveSurviveBothSnapshotRefreshAndAcknowledgement() {
        val settings = ProjectDebugSettings()
        var state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        state = state.edit(state.draft.edit("x", "100")).submit(settings)
        val request = requireNotNull(state.pending)
        state = state.edit(state.draft.edit("x", "200")).observe(request.settings.popupStyle)
        state = state.complete(request, Result.success(request.settings))
        assertTrue(state.dirty)
        assertEquals("200", state.draft.inputs["x"])
        assertEquals("100", state.saved.inputs["x"])
        assertEquals("上次配置已保存，当前修改尚未保存", state.message)
        assertFalse(state.edit(request.draft).dirty)
    }

    @Test fun invalidAndMismatchedSavesNeverReportSuccess() {
        val settings = ProjectDebugSettings()
        val state = PopupStyleSaveState(PopupStyleDraft.from(settings.popupStyle))
        val invalid = state.edit(state.draft.edit("x", "-")).submit(settings)
        assertTrue(invalid.dirty)
        assertNull(invalid.pending)
        assertNull(invalid.message)
        assertNotNull(invalid.error)
        val submitted = state.edit(state.draft.edit("x", "321")).submit(settings)
        val request = requireNotNull(submitted.pending)
        val mismatch = submitted.complete(request, Result.success(settings))
        assertTrue(mismatch.dirty)
        assertNull(mismatch.message)
        assertNull(mismatch.pending)
        assertNotNull(mismatch.error)
        assertEquals("321", mismatch.draft.inputs["x"])
    }

    @Test fun cleanFormsObserveSavedStyleButUnsubmittedTextIsNotReset() {
        val initial = PopupStyle()
        val changed = initial.copy(widthPx = 200)
        val state = PopupStyleSaveState(PopupStyleDraft.from(initial))
        assertEquals(changed, state.observe(changed).draft.parse())
        assertFalse(state.observe(changed).dirty)
        val dirty = state.edit(state.draft.edit("width", ""))
        assertSame(dirty, dirty.observe(changed))
        val pending = state.submit(ProjectDebugSettings())
        assertSame(pending, pending.submit(ProjectDebugSettings()))
        val newForm = state.submit(ProjectDebugSettings())
        assertSame(newForm, newForm.complete(requireNotNull(pending.pending), Result.success(ProjectDebugSettings())))
    }

    @Test fun configuredDebugEditingUsesOwnedCloseGuardAndUnrelatedSettingsDoNotResetStyle() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt").readText()
        val configured = source.substringAfter("internal fun ConfiguredEntryArgumentsDialog(")
            .substringBefore("internal fun EditorEntryDialog(")
        val debugBranch = configured.substringAfter("if (debug) {").substringBefore("Dialog(onDismissRequest")
        assertTrue(debugBranch.contains("DebugPage("))
        assertTrue(source.substringAfter("private fun DebugPage(").substringBefore("private fun DebugNavigation(")
            .contains("StudioPanelDialog(\"输出\""))
        assertFalse(source.contains("standalone: Boolean"))
        assertTrue(debugBranch.contains("return true"))
        assertTrue(source.contains("LaunchedEffect(initialSettings.popupStyle)"))
        assertTrue(source.contains("popupSave.observe(initialSettings.popupStyle)"))
        assertTrue(source.contains("if (formScope.isActive) popupSave = popupSave.complete(request, result)"))
        assertFalse(source.contains("var popupDraft by remember(initialSettings)"))
    }

    @Test fun previewHeaderDoesNotConsumeTheHeightReservedForPreviewAndInputs() {
        val ui = File("src/main/java/com/autoscript/studio/runtime/DebugPopupStyleEditor.kt").readText()
        val header = ui.substringAfter("Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)")
            .substringBefore("PopupStylePreview(preview, previewHeight)")
        assertTrue(header.contains("样式预览 · px"))
        assertTrue(header.contains("Modifier.heightIn(min = 24.dp).clickable"))
        assertFalse(header.contains("fillMaxHeight("))
        assertFalse(header.contains("fillMaxSize("))
        assertTrue(ui.contains("Column(Modifier.weight(1f).fillMaxWidth().verticalScroll("))
    }

    @Test fun previewIsOutsideScrollingInputsAndDelayOnlyRemainsInScriptSettings() {
        val ui = File("src/main/java/com/autoscript/studio/runtime/DebugPopupStyleEditor.kt").readText()
        val previewPosition = ui.indexOf("PopupStylePreview(preview, previewHeight)")
        assertTrue(previewPosition >= 0 && previewPosition < ui.indexOf(".verticalScroll("))
        assertTrue(ui.contains("if (helpVisible) Text("))
        assertTrue(ui.contains("studioPopupPreviewHeight(maxHeight.value)"))
        assertTrue(ui.contains(".height(previewHeight)"))
        assertEquals(2, Regex("PopupStyleColorInput\\(\"").findAll(ui).count())
        val source = File("src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt").readText()
        val debug = source.substringAfter("private fun DebugPage(").substringBefore("private fun DebugNavigation(")
        assertFalse(debug.contains("运行设置"))
        assertFalse(debug.contains("runDelayMs"))
        assertTrue(debug.contains("popupSave.submit(initialSettings)"))
        assertTrue(debug.contains("onSaveSettings(request.settings)"))
        assertTrue(debug.contains("popupSave.message?.let"))
        assertTrue(debug.contains("if (popupSave.pending != null) return"))
        assertTrue(debug.contains("已保存的弹窗样式不受影响"))
        assertTrue(debug.contains("if (settingsDirty || outputDirty)"))
        assertTrue(debug.contains("val initialOutput = remember { DebugOutputDraft.from(initialKind, initialArguments) }"))
        assertTrue(debug.contains(".needsDiscard(initialOutput, editingExisting = initialKind != null)"))
        assertTrue(debug.contains("if (!settingsDirty && outputDirty) selected = 0"))
        assertTrue(debug.contains("放弃待加入的输出？"))
        assertFalse(source.contains("private fun DebugSettingsPanel"))
        val settings = File("src/main/java/com/autoscript/studio/project/ProjectRunSettingsForm.kt").readText()
        assertTrue(settings.contains("RunField(\"启动延迟 ms · 0～60000\""))
        assertTrue(settings.contains("settings.copy(runDelayMs = number(\"delay\")"))
        val icon = File("src/main/res/drawable/editor_window_minimize_20.xml").readText()
        assertTrue(icon.contains("M4,10 H16"))
    }
}
