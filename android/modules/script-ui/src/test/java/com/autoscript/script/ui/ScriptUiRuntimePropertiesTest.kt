package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiRuntimePropertiesTest {
    private val model = ScriptUiDefinition(version = 2, pages = listOf(UiPage(), UiPage("second", "第二页")), fields = listOf(
        UiField("label", "文字", "text", false, "old", ui = UiPresentation(control = "LABEL", pageId = "second", visible = false)),
        UiField("choice", "选项", "choice", false, "A", options = listOf("A", "B"), ui = UiPresentation(control = "LIST")),
        UiField("progress", "进度", "integer", false, "4", minimum = 0, maximum = 100, ui = UiPresentation(control = "PROGRESS")),
    ))

    @Test fun defaultsAndAllRuntimePropertiesUseDesignPixelsAndExactColors() {
        val state = ScriptUiCommandState(model, model.initialValues())
        val overrides = mapOf("x" to "41", "y" to "63", "width" to "141", "height" to "221", "fontPx" to "33",
            "textColor" to "#8020aB43", "background" to "#10aBcD12", "alignment" to "right",
            "fontBold" to "true", "textUnderline" to "true", "borderColor" to "#80123456", "borderWidthPx" to "32", "cornerPx" to "512")
        assertEquals("20", state.read("label", ScriptUiRuntimeProperty.X))
        assertEquals("false", state.read("label", ScriptUiRuntimeProperty.VISIBLE))
        overrides.forEach { (operation, value) ->
            state.apply("label", operation, value)
            assertEquals(value, state.read("label", ScriptUiRuntimeProperty.fromOperation(operation)))
        }
        val effective = requireNotNull(state.presentation(model.fields.first()))
        assertEquals(41, effective.x); assertEquals(221, effective.height); assertEquals(33, effective.fontPx)
        assertEquals(20, model.fields.first().ui?.x)
        assertFalse(effective.visible)
    }

    @Test fun invalidPropertiesAreRejectedBeforeChangingHiddenOrOffPageState() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("label", "x", "99")
        mapOf("x" to "-1", "y" to "8193", "width" to "19", "height" to "8193", "fontPx" to "7",
            "textColor" to "#FFFFFF", "background" to "red", "alignment" to "top",
            "fontBold" to "1", "textUnderline" to "TRUE", "borderColor" to "red", "borderWidthPx" to "33", "cornerPx" to "-1").forEach { (operation, value) ->
            assertThrows(IllegalArgumentException::class.java) { state.apply("label", operation, value) }
        }
        assertEquals("99", state.read("label", ScriptUiRuntimeProperty.X))
        assertEquals("28", state.read("label", ScriptUiRuntimeProperty.FONT_PX))
        assertEquals("#FFFFFFFF", state.read("label", ScriptUiRuntimeProperty.BACKGROUND))
        assertThrows(IllegalStateException::class.java) { state.apply("label", "setView", "anything") }
    }

    @Test fun readingCurrentValueOptionsAndFlagsNeedsNoMountedWidget() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("label", "text", "new")
        state.apply("label", "enabled", "false")
        state.apply("choice", "items", "C\nD")
        state.apply("progress", "progress", "80")
        assertEquals("new", state.read("label", ScriptUiRuntimeProperty.TEXT))
        assertEquals("false", state.read("label", ScriptUiRuntimeProperty.ENABLED))
        assertEquals("C\nD", state.read("choice", ScriptUiRuntimeProperty.ITEMS))
        assertEquals("C", state.read("choice", ScriptUiRuntimeProperty.VALUE))
        assertEquals("80", state.read("progress", ScriptUiRuntimeProperty.PROGRESS))
        assertThrows(IllegalArgumentException::class.java) { state.read("label", ScriptUiRuntimeProperty.ITEMS) }
        assertThrows(IllegalArgumentException::class.java) { state.read("label", ScriptUiRuntimeProperty.PROGRESS) }
    }

    @Test fun resetClearsRuntimePresentationWithoutModifyingSavedModel() {
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("label", "width", "400")
        state.apply("label", "textColor", "#FFAABBCC")
        state.apply("label", "fontBold", "true")
        state.apply("label", "textUnderline", "true")
        state.apply("label", "cornerPx", "512")
        state.apply("label", "borderWidthPx", "32")
        state.reset()
        assertEquals("320", state.read("label", ScriptUiRuntimeProperty.WIDTH))
        assertEquals(model.fields.first().ui, state.presentation(model.fields.first()))
    }

    @Test fun legacyValuesRemainSupportedButDoNotInventDesignCoordinates() {
        val legacy = ScriptUiDefinition(fields = listOf(UiField("text", "输入", "text", false, "old")))
        val state = ScriptUiCommandState(legacy, legacy.initialValues())
        state.apply("text", "text", "new")
        assertEquals("new", state.read("text", ScriptUiRuntimeProperty.VALUE))
        assertEquals("true", state.read("text", ScriptUiRuntimeProperty.VISIBLE))
        assertThrows(IllegalArgumentException::class.java) { state.read("text", ScriptUiRuntimeProperty.X) }
    }
}
