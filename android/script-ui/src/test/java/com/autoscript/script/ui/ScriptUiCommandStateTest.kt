package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiCommandStateTest {
    private val model = ScriptUiDefinition(version = 2, fields = listOf(
        UiField("text", "文字", "text", false, "old", ui = UiPresentation(control = "INPUT")),
        UiField("choice", "选项", "choice", false, "A", options = listOf("A", "B"), ui = UiPresentation(control = "SELECT")),
        UiField("number", "数值", "integer", false, "3", minimum = 0, maximum = 10, ui = UiPresentation(control = "NUMBER")),
        UiField("flag", "开关", "boolean", false, "false", ui = UiPresentation(control = "CHECKBOX")),
        UiField("progress", "进度", "integer", false, "0", minimum = 0, maximum = 100, ui = UiPresentation(control = "PROGRESS")),
    ))

    @Test fun hiddenOrOffPageControlsRetainUpdatesWithoutWidgets() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("text", "value", "new")
        state.apply("flag", "value", "true")
        state.apply("progress", "progress", "80")
        state.apply("text", "visible", "false")
        state.apply("text", "enabled", "false")
        assertEquals("new", state.values["text"])
        assertEquals("true", state.values["flag"])
        assertEquals("80", state.values["progress"])
        assertEquals(false, state.visibility["text"])
        assertEquals(false, state.enabled["text"])
    }

    @Test fun replacingOptionsPreservesSelectionOrFallsBackAndAcceptsNewValues() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("choice", "items", "B\nA")
        assertEquals("A", state.values["choice"])
        state.apply("choice", "items", "C\nD")
        assertEquals("C", state.values["choice"])
        state.apply("choice", "value", "D")
        assertEquals("D", state.values["choice"])
        assertThrows(IllegalArgumentException::class.java) { state.apply("choice", "value", "A") }
    }

    @Test fun invalidUpdatesDoNotPartiallyMutateState() {
        val state = ScriptUiCommandState(model, model.initialValues())
        listOf("A\nA", "", "A\nB\u0000", "A\nB\t").forEach { options ->
            assertThrows(IllegalArgumentException::class.java) { state.apply("choice", "items", options) }
        }
        assertEquals("A", state.values["choice"])
        assertTrue(state.items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { state.apply("number", "value", "11") }
        assertThrows(IllegalArgumentException::class.java) { state.apply("flag", "value", "1") }
        assertThrows(IllegalArgumentException::class.java) { state.apply("progress", "progress", "101") }
        assertEquals("3", state.values["number"])
    }

    @Test fun resetDropsRuntimeOverridesButPreservesStateMapIdentity() {
        val state = ScriptUiCommandState(model, model.initialValues())
        val values = state.values
        state.apply("choice", "items", "C\nD")
        state.apply("text", "enabled", "false")
        state.reset()
        assertSame(values, state.values)
        assertEquals(model.initialValues(), values)
        assertTrue(state.items.isEmpty()); assertTrue(state.enabled.isEmpty())
    }
}
