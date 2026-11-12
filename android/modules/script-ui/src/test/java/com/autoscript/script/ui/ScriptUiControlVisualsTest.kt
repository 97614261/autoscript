package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiControlVisualsTest {
    @Test fun emptyInputsAreHintsAndValuesRemainLiteral() {
        for (control in listOf(UiControl.INPUT, UiControl.TEXTAREA, UiControl.NUMBER, UiControl.DECIMAL, UiControl.PASSWORD)) {
            assertEquals(ScriptUiPreviewText("输入内容", true), ScriptUiControlVisuals.text(control, "", "输入内容"))
            assertEquals(ScriptUiPreviewText(if (control == UiControl.PASSWORD) "\u2022\u2022\u2022\u2022" else "+190"), ScriptUiControlVisuals.text(control, "+190", "输入内容"))
        }
        assertEquals(ScriptUiPreviewText("按钮"), ScriptUiControlVisuals.text(UiControl.BUTTON, "", "按钮"))
    }

    @Test fun unselectedDropdownIsNotTheFirstOptionAndSavedSelectionIsUnchanged() {
        assertEquals(ScriptUiPreviewText("未选择", true), ScriptUiControlVisuals.text(UiControl.SELECT, "", "选项"))
        assertEquals(ScriptUiPreviewText("选项2"), ScriptUiControlVisuals.text(UiControl.SELECT, "选项2", "选项"))
    }

    @Test fun hintsMultiplyExistingAlphaAndRetainRgb() {
        for (alpha in listOf(0, 1, 64, 128, 255)) {
            val color = (alpha shl 24) or 0x123456
            val hint = ScriptUiControlVisuals.hintColor(color)
            assertEquals(0x123456, hint and 0xFFFFFF)
            assertTrue(hint ushr 24 <= alpha)
        }
        assertEquals(140, ScriptUiControlVisuals.hintColor(0xFF123456.toInt()) ushr 24)
    }

    @Test fun savedCornersAreClampedToActualBoundsWithoutChangingTheProperty() {
        assertEquals(10f, ScriptUiControlVisuals.cornerRadius(100f, 100f, 20f), 0f)
        assertEquals(6f, ScriptUiControlVisuals.cornerRadius(6f, 100f, 20f), 0f)
        assertEquals(0f, ScriptUiControlVisuals.cornerRadius(6f, 0f, 20f), 0f)
    }
}
