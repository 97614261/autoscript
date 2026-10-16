package com.autoscript.studio

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

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

/** Menu surface bounds, not Material's shadow/margin envelope, follow the actual trigger. */
internal class DesignerMenuPositionProvider(private val marginPx: Int, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = marginPx.coerceAtLeast(0)
        val gap = gapPx.coerceAtLeast(0)
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val minX = margin.coerceAtMost(maxX)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val minY = margin.coerceAtMost(maxY)
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val y = when {
            above in minY..maxY -> above
            below in minY..maxY -> below
            else -> above.coerceIn(minY, maxY)
        }
        return IntOffset(preferredX.coerceIn(minX, maxX), y)
    }
}

/** Constrain tall menus to the larger free side; scrolling never inserts an anchor gap. */
internal fun designerMenuMaxHeight(anchor: IntRect, windowHeight: Int, marginPx: Int, gapPx: Int, capPx: Int): Int {
    val margin = marginPx.coerceAtLeast(0)
    val gap = gapPx.coerceAtLeast(0)
    val usable = (windowHeight - margin * 2).coerceAtLeast(1)
    val above = (anchor.top - margin - gap).coerceIn(0, usable)
    val below = (windowHeight - margin - gap - anchor.bottom).coerceIn(0, usable)
    return minOf(capPx.coerceAtLeast(1), maxOf(above, below).coerceAtLeast(1))
}
