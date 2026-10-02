package com.autoscript.studio

/** Editor-only dimensions; never alter stored design pixels or script controls. */
internal data class DesignerWorkspaceLayout(
    val toolboxWidth: Int,
    val inspectorWidth: Int,
    val showTools: Boolean,
    val showInspector: Boolean,
)

internal fun designerWorkspaceLayout(width: Float, tools: Boolean, inspector: Boolean): DesignerWorkspaceLayout {
    val narrow = width < 560f
    return DesignerWorkspaceLayout(
        toolboxWidth = if (width < 700f) 104 else 116,
        inspectorWidth = if (width < 560f) 164 else if (width < 700f) 184 else 208,
        showTools = tools && (!narrow || !inspector),
        showInspector = inspector,
    )
}
