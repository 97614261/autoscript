package com.autoscript.studio

import com.autoscript.script.ui.UiControl

internal fun RunnerUiControlKind.designerEvents(): List<Pair<String, String>> = when (control) {
    UiControl.INPUT, UiControl.PASSWORD, UiControl.TEXTAREA, UiControl.NUMBER, UiControl.DECIMAL ->
        listOf("change" to "内容变化", "click" to "点击", "longClick" to "长按")
    UiControl.CHECKBOX, UiControl.SWITCH -> listOf("change" to "状态变化", "click" to "点击", "longClick" to "长按")
    UiControl.RADIO, UiControl.SELECT, UiControl.LIST, UiControl.MULTISELECT ->
        listOf("selection" to "选择变化", "click" to "点击", "longClick" to "长按")
    UiControl.SLIDER -> listOf("change" to "数值变化", "click" to "拖动结束", "longClick" to "长按")
    else -> listOf("click" to "点击", "longClick" to "长按")
}

internal fun RunnerUiControlKind.hasTextStyle() = control !in setOf(UiControl.IMAGE, UiControl.CONTAINER, UiControl.SLIDER, UiControl.PROGRESS)
internal fun RunnerUiControlKind.hasRequiredChoice() = control in setOf(UiControl.INPUT, UiControl.PASSWORD, UiControl.TEXTAREA, UiControl.MULTISELECT)
