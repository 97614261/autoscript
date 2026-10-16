package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DesignerPropertyInputsTest {
    private val owner = DesignerInputOwner("main", "control1", "样式")

    @Test fun incompleteAndOutOfRangeTextCannotBecomePreviouslySavedValues() {
        val inputs = DesignerPropertyInputs()
        for (text in listOf("", "-", "7", "161", "1.5", "9999999999999999")) {
            assertNull(inputs.edit(owner, "字号 px", text, 8..160))
            assertEquals(text, inputs.text(owner, "字号 px", "20"))
            assertEquals(DesignerValidationIssue("control1", "main", "样式", "字号 px须为8～160的整数，请完成输入后再保存"), inputs.firstIssue())
        }
        assertEquals(24, inputs.edit(owner, "字号 px", "24", 8..160))
        assertNull(inputs.firstIssue())
        assertEquals("24", inputs.text(owner, "字号 px", "24"))
    }

    @Test fun pendingTextIsIsolatedByControlPageAndTabAndCanBeExplicitlyDiscarded() {
        val inputs = DesignerPropertyInputs()
        inputs.edit(owner, "字号 px", "", 8..160)
        assertEquals("20", inputs.text(owner.copy(controlId = "other"), "字号 px", "20"))
        assertEquals("20", inputs.text(owner.copy(tab = "布局"), "字号 px", "20"))
        assertEquals("20", inputs.text(owner.copy(pageId = "page2"), "字号 px", "20"))
        assertEquals("", inputs.text(owner, "字号 px", "20"))
        inputs.clear()
        assertNull(inputs.firstIssue())
        assertEquals("20", inputs.text(owner, "字号 px", "20"))
    }

    @Test fun deletingControlsPrunesOnlyTheirErrorsAndPresetsReplaceOnlySizeText() {
        val inputs = DesignerPropertyInputs()
        inputs.edit(owner, "字号 px", "", 8..160)
        inputs.edit(owner, "圆角 px", "-", 0..512)
        inputs.clearSizes(setOf("other")); assertEquals("", inputs.text(owner, "字号 px", "20"))
        inputs.clearSizes(setOf("control1")); assertEquals("20", inputs.text(owner, "字号 px", "20"))
        assertEquals("-", inputs.text(owner, "圆角 px", "16"))
        inputs.prune(RunnerUiDesignerDraft())
        assertNull(inputs.firstIssue())
    }

    @Test fun screenOwnsStateByProjectAndChecksPendingInputsBeforeSaveAndPreview() {
        val ui = File("src/main/java/com/autoscript/studio/designer/RunnerUiDesignerScreen.kt").readText()
        assertTrue(ui.contains("key(snapshot.manifest.projectId)"))
        assertTrue(ui.contains("validationIssue = propertyInputs.firstIssue() ?: draft.firstValidationIssue()"))
        assertTrue(ui.contains("if (draft != saved || propertyInputs.firstIssue() != null)"))
        assertTrue(ui.contains("busy || propertyInputs.firstIssue() != null, onRestore"))
        assertTrue(ui.contains("val inputIssue = propertyInputs.firstIssue()"))
        assertTrue(ui.contains("editorOperation({ failure ->"))
    }
}
