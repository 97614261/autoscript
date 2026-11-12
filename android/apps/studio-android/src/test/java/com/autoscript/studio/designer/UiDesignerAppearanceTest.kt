package com.autoscript.studio

import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiDesignerAppearanceTest {
    @Test fun enablingBordersFromTransparentDefaultsAlwaysProducesAVisibleFrame() {
        val original = UiPresentation(borderColor = "#00000000", borderWidthPx = 0)
        val width = original.withDesignerBorderWidth(2)
        assertEquals(2, width.visibleBorderWidthPx)
        val color = original.withDesignerBorderColor("#80AABBCC")
        assertEquals(1, color.visibleBorderWidthPx)
        assertEquals("#80AABBCC", color.borderColor)
        val disabled = color.withDesignerBorderWidth(0)
        assertEquals(0, disabled.visibleBorderWidthPx)
        assertEquals(color.borderColor, disabled.borderColor)
        assertEquals(color, disabled.withDesignerBorderWidth(1))
        assertEquals(0, width.withDesignerBorderColor("#00000000").visibleBorderWidthPx)
        assertThrows(IllegalArgumentException::class.java) { original.withDesignerBorderWidth(33) }
    }

    @Test fun allNewControlStylesRoundTripWithTheOriginalCompactGeometry() {
        val draft = RunnerUiControlKind.entries.fold(RunnerUiDesignerDraft()) { d, kind -> d.add(kind) }
        assertEquals(DESIGNER_PAGE_BACKGROUND, draft.pages.single().background)
        assertEquals(draft, runnerUiDesignerDraft(draft.toRunnerUiJson()))
        assertTrue(draft.fields.all { it.ui.width == 208 && it.ui.height == 40 && it.ui.fontPx == 20 })
        val input = draft.fields.first { it.kind == RunnerUiControlKind.TEXT }.ui
        assertEquals("#FFFFFFFF", input.background)
        assertEquals(1, input.visibleBorderWidthPx)
        assertEquals("left", input.alignment)
        val button = draft.fields.first { it.kind == RunnerUiControlKind.BUTTON }
        assertEquals("#FFFFFFFF", button.ui.textColor)
        assertTrue(button.ui.fontBold)
        assertTrue(button.ui.events.isEmpty())
        assertEquals("#00000000", draft.fields.first { it.kind == RunnerUiControlKind.LABEL }.ui.background)
    }

    @Test fun appearancePresetsPreserveGeometryBindingsEventsAndVisibility() {
        val original = UiPresentation(control = "BUTTON", pageId = "second", parentId = "panel", x = 70, y = 90,
            width = 180, height = 72, fontPx = 31, binding = "global:count", events = mapOf("click" to "flow:worker"),
            visible = false, enabled = false)
        for (preset in DesignerAppearance.entries) {
            val updated = original.withDesignerAppearance(RunnerUiControlKind.BUTTON, preset)
            assertEquals(original.copy(textColor = updated.textColor, background = updated.background,
                borderColor = updated.borderColor, borderWidthPx = updated.borderWidthPx, cornerPx = updated.cornerPx,
                fontBold = updated.fontBold, textUnderline = updated.textUnderline, alignment = updated.alignment), updated)
        }
    }

    @Test fun loadingExistingDesignsDoesNotApplyNewDefaults() {
        val legacy = ScriptUiDefinition(version = 2, fields = listOf(UiField("label", "old", "text", false, "hello",
            ui = UiPresentation(control = "LABEL", background = "#FF112233", textColor = "#FFEEEEEE",
                borderWidthPx = 3, borderColor = "#80FFFFFF", cornerPx = 24, height = 96))))
        val loaded = runnerUiDesignerDraft(legacy.toJson())
        assertEquals(legacy, ScriptUiDefinition.parse(requireNotNull(loaded.toRunnerUiJson()).toString()))
        val added = loaded.add(RunnerUiControlKind.TEXT)
        assertEquals(loaded.pages, added.pages)
        assertEquals(loaded.fields.single(), added.fields.first())
        assertEquals(1, added.fields.last().ui.visibleBorderWidthPx)
    }

    @Test fun quickBorderPresetsClearOnlyTheReplacedInvalidWidthDraft() {
        val inputs = DesignerPropertyInputs()
        val owner = DesignerInputOwner("main", "control1", "样式")
        inputs.edit(owner, "边框 px", "bad", 0..32)
        inputs.edit(owner, "圆角 px", "bad", 0..512)
        inputs.clearBorderWidths(setOf("control1"))
        assertEquals("2", inputs.text(owner, "边框 px", "2"))
        assertEquals("bad", inputs.text(owner, "圆角 px", "8"))
        assertNotNull(inputs.firstIssue())
        inputs.clearAppearance(setOf("control1"))
        assertNull(inputs.firstIssue())
    }
}
