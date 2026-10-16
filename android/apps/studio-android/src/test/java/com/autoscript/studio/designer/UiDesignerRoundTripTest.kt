package com.autoscript.studio

import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiDesignerRoundTripTest {
    @Test fun choiceContentAndStyleSurviveRepeatedDesignerRoundTrips() {
        val options = listOf("含,逗号", "\"引号\"", " 前后空格 ", "方括号[]")
        val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("choice", "选择", "choice", false,
            options[2], options = options, ui = UiPresentation(control = "SELECT", x = 24, y = 48, width = 160, height = 40, alignment = "right", fontBold = true))))
        var json = ui.toJson()
        repeat(3) { json = requireNotNull(runnerUiDesignerDraft(json).toRunnerUiJson()) }
        assertEquals(ui, ScriptUiDefinition.parse(json.toString()))
        assertEquals(listOf("A", "B"), designerOptions("A,B"))
        assertThrows(IllegalArgumentException::class.java) { designerOptions("[1]") }
    }

    @Test fun commandTargetsRejectDeletedControlsAndInapplicableProperties() {
        val model = ScriptUiDefinition(version = 2, fields = listOf(UiField("label", "标签", "text", false, "",
            ui = UiPresentation(control = "LABEL"))))
        assertNull(uiCommandTargetError(model, "ui.command", "window", "hide"))
        assertNotNull(uiCommandTargetError(model, "ui.command", "label", "hide"))
        assertNotNull(uiCommandTargetError(model, "ui.set", "deleted", "value"))
        assertNotNull(uiCommandTargetError(model, "ui.command", "label", "get:items"))
        assertNotNull(uiCommandTargetError(model, "ui.command", "label", "progress"))
        assertNull(uiCommandTargetError(model, "ui.command", "label", "get:width"))
    }
}
