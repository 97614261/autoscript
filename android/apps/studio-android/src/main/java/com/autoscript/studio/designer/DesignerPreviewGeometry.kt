package com.autoscript.studio

import com.autoscript.script.ui.ScriptUiControlVisuals

internal data class DesignerRoundedClip(val left: Int, val top: Int, val right: Int, val bottom: Int, val radius: Float)
internal data class DesignerPreviewState(val enabled: Boolean, val visible: Boolean)

private fun RunnerUiDesignerDraft.previewAncestors(field: RunnerUiFieldDraft): List<RunnerUiFieldDraft> {
    val ancestors = mutableListOf<RunnerUiFieldDraft>()
    val seen = mutableSetOf(field.id)
    var parent = field.ui.parentId
    while (parent != null && seen.add(parent)) {
        val owner = fields.firstOrNull { it.id == parent } ?: break
        ancestors.add(owner); parent = owner.ui.parentId
    }
    return ancestors
}

internal fun RunnerUiDesignerDraft.previewState(field: RunnerUiFieldDraft): DesignerPreviewState {
    val chain = previewAncestors(field) + field
    return DesignerPreviewState(chain.all { it.ui.enabled }, chain.all { it.ui.visible })
}

/** Keep the full ancestor rectangle: intersecting its bounds first would move its rounded corners. */
internal fun RunnerUiDesignerDraft.previewRoundedClips(field: RunnerUiFieldDraft): List<DesignerRoundedClip> {
    val (x, y) = absolutePosition(field)
    return previewAncestors(field).filter { it.ui.cornerPx > 0 }.map { owner ->
        val (px, py) = absolutePosition(owner)
        DesignerRoundedClip(px - x, py - y, px - x + owner.ui.width, py - y + owner.ui.height,
            ScriptUiControlVisuals.cornerRadius(owner.ui.cornerPx.toFloat(), owner.ui.width.toFloat(), owner.ui.height.toFloat()))
    }
}
