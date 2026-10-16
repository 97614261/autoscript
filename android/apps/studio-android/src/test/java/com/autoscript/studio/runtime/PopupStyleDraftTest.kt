package com.autoscript.studio

import com.autoscript.project.store.PopupStyle
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PopupStyleDraftTest {
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

    @Test fun configuredDebugEditingUsesOwnedCloseGuardAndUnrelatedSettingsDoNotResetStyle() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt").readText()
        val configured = source.substringAfter("internal fun ConfiguredEntryArgumentsDialog(")
            .substringBefore("internal fun EditorEntryDialog(")
        val debugBranch = configured.substringAfter("if (debug) {").substringBefore("Dialog(onDismissRequest")
        assertTrue(debugBranch.contains("standalone = true"))
        assertTrue(debugBranch.contains("return true"))
        assertTrue(source.contains("remember(initialSettings.popupStyle)"))
        assertFalse(source.contains("var popupDraft by remember(initialSettings)"))
    }

    @Test fun previewIsOutsideScrollingInputsAndDelayOnlyRemainsInScriptSettings() {
        val ui = File("src/main/java/com/autoscript/studio/runtime/DebugPopupStyleEditor.kt").readText()
        assertTrue(ui.indexOf("PopupStylePreview(preview)") < ui.indexOf(".verticalScroll("))
        assertTrue(ui.contains("if (helpVisible) Text("))
        assertTrue(ui.contains(".height(72.dp)"))
        assertEquals(2, Regex("PopupStyleColorInput\\(\"").findAll(ui).count())
        val source = File("src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt").readText()
        val debug = source.substringAfter("private fun DebugPage(").substringBefore("private fun DebugNavigation(")
        assertFalse(debug.contains("运行设置"))
        assertFalse(debug.contains("runDelayMs"))
        assertTrue(debug.contains("initialSettings.copy(popupStyle = parsed)"))
        assertTrue(debug.contains("val parsed = popupDraft.parse()"))
        assertTrue(debug.contains("if (settingsDirty || outputDirty)"))
        assertFalse(source.contains("private fun DebugSettingsPanel"))
        val settings = File("src/main/java/com/autoscript/studio/project/ProjectRunSettingsForm.kt").readText()
        assertTrue(settings.contains("RunField(\"启动延迟 ms · 0～60000\""))
        assertTrue(settings.contains("settings.copy(runDelayMs = number(\"delay\")"))
        val icon = File("src/main/res/drawable/editor_window_minimize_20.xml").readText()
        assertTrue(icon.contains("M4,10 H16"))
    }
}
