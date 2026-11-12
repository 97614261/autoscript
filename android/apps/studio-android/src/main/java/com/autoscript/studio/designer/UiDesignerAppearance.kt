package com.autoscript.studio

import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.UiPresentation

internal const val DESIGNER_PAGE_BACKGROUND = "#FFF4F6FA"
private const val DESIGNER_STYLE_ACCENT = "#FF386AFF"
private const val DESIGNER_STYLE_INK = "#FF27344A"
private const val DESIGNER_STYLE_LINE = "#FFD5DDEA"

internal enum class DesignerAppearance(val label: String) {
    DEFAULT("默认"), PRIMARY("主色"), SOFT("浅底"), OUTLINE("描边"), PLAIN("透明"),
}

internal fun designerPage(id: String = "main", title: String = "主页面") =
    UiPage(id = id, title = title, background = DESIGNER_PAGE_BACKGROUND)

/** Apply appearance only: position, size, binding, events and input values stay intact. */
internal fun UiPresentation.withDesignerAppearance(kind: RunnerUiControlKind,
    appearance: DesignerAppearance = DesignerAppearance.DEFAULT): UiPresentation {
    val base = copy(textColor = DESIGNER_STYLE_INK, background = "#00000000", borderColor = DESIGNER_STYLE_LINE,
        borderWidthPx = 0, cornerPx = 8, fontBold = false, textUnderline = false)
    return when (appearance) {
        DesignerAppearance.PRIMARY -> base.copy(background = DESIGNER_STYLE_ACCENT, textColor = "#FFFFFFFF", fontBold = true)
        DesignerAppearance.SOFT -> base.copy(background = "#FFEAF0FF", textColor = "#FF315DC0", borderWidthPx = 1, borderColor = "#FFD6E1FF")
        DesignerAppearance.OUTLINE -> base.copy(borderWidthPx = 1, borderColor = DESIGNER_STYLE_ACCENT, textColor = DESIGNER_STYLE_ACCENT)
        DesignerAppearance.PLAIN -> base
        DesignerAppearance.DEFAULT -> when (kind) {
            RunnerUiControlKind.BUTTON -> base.copy(background = DESIGNER_STYLE_ACCENT, textColor = "#FFFFFFFF", fontBold = true, alignment = "center")
            RunnerUiControlKind.NAVIGATION -> base.withDesignerAppearance(kind, DesignerAppearance.SOFT).copy(fontBold = true, alignment = "center")
            RunnerUiControlKind.TEXT, RunnerUiControlKind.INTEGER, RunnerUiControlKind.DECIMAL,
            RunnerUiControlKind.PASSWORD, RunnerUiControlKind.TEXTAREA, RunnerUiControlKind.CHOICE,
            RunnerUiControlKind.LIST, RunnerUiControlKind.MULTISELECT -> base.copy(background = "#FFFFFFFF", borderWidthPx = 1, alignment = "left")
            RunnerUiControlKind.CONTAINER -> base.copy(background = "#FFFFFFFF", borderWidthPx = 1, cornerPx = 12)
            RunnerUiControlKind.LABEL, RunnerUiControlKind.BOOLEAN, RunnerUiControlKind.SWITCH,
            RunnerUiControlKind.RADIO -> base.copy(alignment = "left")
            else -> base
        }
    }
}

internal fun UiPresentation.withDesignerBorderWidth(width: Int): UiPresentation {
    require(width in 0..32)
    val invisible = borderColor.takeIf { it.length == 9 }?.substring(1, 3)?.toIntOrNull(16) == 0
    return copy(borderWidthPx = width, borderColor = if (width > 0 && invisible) DESIGNER_STYLE_LINE else borderColor)
}

internal fun UiPresentation.withDesignerBorderColor(color: String): UiPresentation {
    val visible = color.takeIf { it.length == 9 }?.substring(1, 3)?.toIntOrNull(16)?.let { it > 0 } == true
    return copy(borderColor = color, borderWidthPx = if (visible && borderWidthPx == 0) 1 else borderWidthPx)
}
