package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiFrameTest {
    @Test fun borderVisibilityUsesBothAlphaAndWidthWithoutChangingSerializedValues() {
        for (alpha in listOf("00", "01", "80", "FF")) for (width in listOf(0, 1, 4, 32)) {
            val style = UiPresentation(control = "IMAGE", borderColor = "#${alpha}ABCDEF", borderWidthPx = width)
            assertEquals(if (alpha == "00") 0 else width, style.visibleBorderWidthPx)
            val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("image", "image", "text", false, "", ui = style)))
            assertEquals(ui, ScriptUiDefinition.parse(ui.toJson().toString()))
        }
    }

    @Test fun runtimeFrameCommandsRetainGeometryAndCanDisableThenRestoreTheFrame() {
        val model = ScriptUiDefinition(version = 2, fields = listOf(UiField("panel", "panel", "text", false, "",
            ui = UiPresentation(control = "CONTAINER", x = 44, y = 80, width = 320, height = 200))))
        val state = ScriptUiCommandState(model, model.initialValues())
        state.apply("panel", "borderWidthPx", "4")
        state.apply("panel", "borderColor", "#80AABBCC")
        val active = requireNotNull(state.presentation(model.fields.single()))
        assertEquals(4, active.visibleBorderWidthPx)
        state.apply("panel", "borderWidthPx", "0")
        assertEquals(0, requireNotNull(state.presentation(model.fields.single())).visibleBorderWidthPx)
        state.apply("panel", "borderWidthPx", "4")
        assertEquals(active, state.presentation(model.fields.single()))
        assertEquals(model.fields.single().ui!!.copy(borderColor = "#80AABBCC", borderWidthPx = 4), active)
    }
}
