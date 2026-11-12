package com.autoscript.studio

import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test

class DesignerStyleClipboardTest {
    @Test fun newButtonsAreInertAndSavedActionsAreNotReplaced() {
        val blank = RunnerUiDesignerDraft().add(RunnerUiControlKind.BUTTON)
        assertTrue(blank.fields.single().ui.events.isEmpty())
        assertTrue(ScriptUiDefinition.parse(blank.toRunnerUiJson()!!.toString()).fields.single().ui!!.events.isEmpty())
        for (action in listOf("run", "close", "minimize", "page:main", "flow:worker")) {
            val field = blank.fields.single()
            val configured = blank.update(0, field.copy(ui = field.ui.copy(events = mapOf("click" to action, "longClick" to "close"))))
            val loaded = runnerUiDesignerDraft(configured.toRunnerUiJson())
            val added = loaded.add(RunnerUiControlKind.BUTTON)
            assertEquals(configured.fields.single(), added.fields.first())
            assertTrue(added.fields.last().ui.events.isEmpty())
        }
    }

    @Test fun crossControlPasteChangesOnlyAppearanceAndRoundTripsAllOtherData() {
        val source = RunnerUiDesignerDraft().add(RunnerUiControlKind.BUTTON).fields.single().ui.copy(
            textColor = "#80ABCDEF", background = "#CC123456", borderColor = "#EE987654", borderWidthPx = 4,
            cornerPx = 25, fontPx = 27, fontBold = true, textUnderline = true, alignment = "right")
        val clipboard = DesignerStyleClipboard.from(source)
        val draft = RunnerUiControlKind.entries.fold(RunnerUiDesignerDraft()) { d, kind -> d.add(kind) }
        val targets = draft.copy(fields = draft.fields.map { it.copy(ui = it.ui.copy(visible = false, enabled = false, events = mapOf("click" to "close"))) })
        val updated = targets.pasteStyle(targets.fields.map { it.id }.toSet(), clipboard)
        targets.fields.zip(updated.fields).forEach { (before, after) ->
            assertEquals(before.copy(ui = before.ui.copy(textColor = source.textColor, background = source.background,
                borderColor = source.borderColor, borderWidthPx = source.borderWidthPx, cornerPx = source.cornerPx,
                fontPx = source.fontPx, fontBold = source.fontBold, textUnderline = source.textUnderline, alignment = source.alignment)), after)
        }
        assertEquals(updated, runnerUiDesignerDraft(updated.toRunnerUiJson()))
        assertEquals(clipboard, DesignerStyleClipboard.from(source.copy(x = 999, events = emptyMap())))
    }

    @Test fun clipboardSurvivesSourceDeletionAndDoesNotTouchUnselectedControls() {
        val draft = RunnerUiDesignerDraft().add(RunnerUiControlKind.BUTTON).add(RunnerUiControlKind.TEXT).add(RunnerUiControlKind.LABEL)
        val source = draft.fields.first()
        val clipboard = DesignerStyleClipboard.from(source.ui)
        val deleted = draft.remove(0)
        val target = deleted.fields.first()
        val updated = deleted.pasteStyle(setOf(target.id, "deleted-id"), clipboard)
        assertEquals(deleted.fields.last(), updated.fields.last())
        assertEquals(clipboard, DesignerStyleClipboard.from(updated.fields.first().ui))
        assertEquals(deleted, deleted.pasteStyle(emptySet(), clipboard))
        assertEquals(source.ui, draft.fields.first().ui)
    }

    @Test fun pastingClearsOnlyReplacedStyleDraftsAndKeepsInvalidGeometry() {
        val inputs = DesignerPropertyInputs()
        val owner = DesignerInputOwner("main", "target", "样式")
        inputs.edit(owner, "边框 px", "bad", 0..32)
        inputs.edit(owner, "圆角 px", "bad", 0..512)
        inputs.edit(owner, "字号 px", "bad", 8..160)
        inputs.edit(owner.copy(tab = "布局"), "宽", "bad-width", 20..4096)
        inputs.edit(owner.copy(controlId = "other"), "边框 px", "other-bad", 0..32)
        assertTrue(inputs.hasUnfinishedStyle("target"))
        inputs.clearPastedStyle(setOf("target"))
        assertFalse(inputs.hasUnfinishedStyle("target"))
        assertTrue(inputs.hasUnfinishedStyle("other"))
        assertEquals("27", inputs.text(owner, "字号 px", "27"))
        assertEquals("bad-width", inputs.text(owner.copy(tab = "布局"), "宽", "208"))
        assertNotNull(inputs.firstIssue())
    }

    @Test fun nestedContainersClipAtOriginalRoundedCornersAndInheritDisabledState() {
        val outer = RunnerUiFieldDraft("outer", "outer", RunnerUiControlKind.CONTAINER, false, "",
            ui = UiPresentation(control = "CONTAINER", x = 50, y = 60, width = 100, height = 80, cornerPx = 90, enabled = false))
        val inner = outer.copy(id = "inner", ui = outer.ui.copy(parentId = "outer", x = 10, y = 20, width = 60, height = 50, cornerPx = 12, enabled = true))
        val child = RunnerUiFieldDraft("child", "child", RunnerUiControlKind.BUTTON, false, "",
            ui = UiPresentation(control = "BUTTON", parentId = "inner", x = 5, y = 8, width = 30, height = 20))
        val draft = RunnerUiDesignerDraft(fields = listOf(outer, inner, child))
        assertEquals(listOf(DesignerRoundedClip(-5, -8, 55, 42, 12f), DesignerRoundedClip(-15, -28, 85, 52, 40f)), draft.previewRoundedClips(child))
        assertEquals(DesignerPreviewState(false, true), draft.previewState(child))
        assertEquals(DesignerPreviewState(false, false), draft.copy(fields = listOf(outer.copy(ui = outer.ui.copy(visible = false)), inner, child)).previewState(child))
        assertEquals(child, draft.fields.last())
    }
}
