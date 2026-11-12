package com.autoscript.script.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

val UiPresentation.visibleBorderWidthPx: Int
    get() = if (borderColor.length == 9 && borderColor.substring(1, 3).toIntOrNull(16)?.let { it > 0 } == true)
        borderWidthPx else 0

/** Foreground survives images, container children and native widget background tinting. */
internal fun View.applyScriptUiFrame(style: UiPresentation, scale: Float) {
    backgroundTintList = null
    foregroundTintList = null
    foreground = if (style.visibleBorderWidthPx > 0 || style.control in setOf("SELECT", "BUTTON", "NAVIGATION"))
        ScriptUiOverlayDrawable(style, scale) else null
    clipToOutline = style.cornerPx > 0
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val radius = ScriptUiControlVisuals.cornerRadius(style.cornerPx * scale, view.width.toFloat(), view.height.toFloat())
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}
