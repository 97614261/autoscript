package com.autoscript.script.ui

/** Fixed data properties only; never Android method names or reflection. */
enum class ScriptUiRuntimeProperty(val operation: String) {
    VALUE("value"), TEXT("text"), VISIBLE("visible"), ENABLED("enabled"), ITEMS("items"), PROGRESS("progress"),
    X("x"), Y("y"), WIDTH("width"), HEIGHT("height"), FONT_PX("fontPx"),
    TEXT_COLOR("textColor"), BACKGROUND("background"), ALIGNMENT("alignment"),
    FONT_BOLD("fontBold"), TEXT_UNDERLINE("textUnderline"), BORDER_COLOR("borderColor"), BORDER_WIDTH_PX("borderWidthPx"), CORNER_PX("cornerPx");

    val presentation: Boolean get() = ordinal >= X.ordinal

    companion object {
        fun fromOperation(operation: String): ScriptUiRuntimeProperty =
            entries.singleOrNull { it.operation == operation } ?: error("界面属性无效：$operation")
    }
}

internal fun UiPresentation.withRuntimeProperty(property: ScriptUiRuntimeProperty, value: String): UiPresentation {
    fun integer(range: IntRange): Int = (value.toIntOrNull() ?: error("界面属性需要整数"))
        .also { require(it in range) { "界面属性超出范围" } }
    fun color(): String = value.also { require(Regex("#[0-9a-fA-F]{8}").matches(it)) { "颜色需要 #AARRGGBB" } }
    return when (property) {
        ScriptUiRuntimeProperty.X -> copy(x = integer(0..4096))
        ScriptUiRuntimeProperty.Y -> copy(y = integer(0..8192))
        ScriptUiRuntimeProperty.WIDTH -> copy(width = integer(20..4096))
        ScriptUiRuntimeProperty.HEIGHT -> copy(height = integer(20..8192))
        ScriptUiRuntimeProperty.FONT_PX -> copy(fontPx = integer(8..160))
        ScriptUiRuntimeProperty.TEXT_COLOR -> copy(textColor = color())
        ScriptUiRuntimeProperty.BACKGROUND -> copy(background = color())
        ScriptUiRuntimeProperty.ALIGNMENT -> copy(alignment = value.also { require(it in setOf("left", "center", "right")) })
        ScriptUiRuntimeProperty.FONT_BOLD -> copy(fontBold = value.toBooleanStrict())
        ScriptUiRuntimeProperty.TEXT_UNDERLINE -> copy(textUnderline = value.toBooleanStrict())
        ScriptUiRuntimeProperty.BORDER_COLOR -> copy(borderColor = color())
        ScriptUiRuntimeProperty.BORDER_WIDTH_PX -> copy(borderWidthPx = integer(0..32))
        ScriptUiRuntimeProperty.CORNER_PX -> copy(cornerPx = integer(0..512))
        else -> error("不是布局样式属性")
    }
}

internal fun UiPresentation.runtimeProperty(property: ScriptUiRuntimeProperty): String = when (property) {
    ScriptUiRuntimeProperty.X -> x.toString()
    ScriptUiRuntimeProperty.Y -> y.toString()
    ScriptUiRuntimeProperty.WIDTH -> width.toString()
    ScriptUiRuntimeProperty.HEIGHT -> height.toString()
    ScriptUiRuntimeProperty.FONT_PX -> fontPx.toString()
    ScriptUiRuntimeProperty.TEXT_COLOR -> textColor
    ScriptUiRuntimeProperty.BACKGROUND -> background
    ScriptUiRuntimeProperty.ALIGNMENT -> alignment
    ScriptUiRuntimeProperty.FONT_BOLD -> fontBold.toString()
    ScriptUiRuntimeProperty.TEXT_UNDERLINE -> textUnderline.toString()
    ScriptUiRuntimeProperty.BORDER_COLOR -> borderColor
    ScriptUiRuntimeProperty.BORDER_WIDTH_PX -> borderWidthPx.toString()
    ScriptUiRuntimeProperty.CORNER_PX -> cornerPx.toString()
    else -> error("不是布局样式属性")
}
