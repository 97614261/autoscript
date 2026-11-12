package com.autoscript.studio

import com.autoscript.script.ui.UiPresentation

/** A value snapshot, independent of the source control and its later edits/deletion. */
internal data class DesignerStyleClipboard(
    val textColor: String, val background: String, val borderColor: String,
    val borderWidthPx: Int, val cornerPx: Int, val fontPx: Int,
    val fontBold: Boolean, val textUnderline: Boolean, val alignment: String,
) {
    fun applyTo(target: UiPresentation) = target.copy(textColor = textColor, background = background,
        borderColor = borderColor, borderWidthPx = borderWidthPx, cornerPx = cornerPx,
        fontPx = fontPx, fontBold = fontBold, textUnderline = textUnderline, alignment = alignment)

    companion object {
        fun from(source: UiPresentation) = DesignerStyleClipboard(source.textColor, source.background,
            source.borderColor, source.borderWidthPx, source.cornerPx, source.fontPx,
            source.fontBold, source.textUnderline, source.alignment)
    }
}

internal fun RunnerUiDesignerDraft.pasteStyle(ids: Set<String>, style: DesignerStyleClipboard) =
    copy(fields = fields.map { if (it.id in ids) it.copy(ui = style.applyTo(it.ui)) else it })
