package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal class ImagePanelPlacementState {
    var portrait by mutableStateOf<ImagePanelPosition?>(null)
    var landscape by mutableStateOf<ImagePanelPosition?>(null)
}

internal val LocalImagePanelDrag = staticCompositionLocalOf<((Offset) -> Unit)?> { null }

@Composable
internal fun ImageDraggablePanelHost(
    landscape: Boolean, placement: ImagePanelPlacementState, modifier: Modifier = Modifier,
    defaultPosition: ImagePanelPosition = ImagePanelPosition(.5f, .5f), draggable: Boolean = true,
    content: @Composable BoxWithConstraintsScope.(Modifier) -> Unit,
) {
    // Only controls are inset. The image canvas keeps its original coordinate space.
    BoxWithConstraints(modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        var measured by remember { mutableStateOf(IntSize.Zero) }
        val position = (if (landscape) placement.landscape else placement.portrait) ?: defaultPosition
        val panelWidth = measured.width.toFloat().coerceAtMost(width)
        val panelHeight = measured.height.toFloat().coerceAtMost(height)
        val (x, y) = imagePanelOffset(position, width, height, panelWidth, panelHeight)
        val drag: (Offset) -> Unit = { delta ->
            val current = (if (landscape) placement.landscape else placement.portrait) ?: defaultPosition
            val next = imagePanelDrag(current, delta.x, delta.y, width, height, panelWidth, panelHeight)
            if (landscape) placement.landscape = next else placement.portrait = next
        }
        CompositionLocalProvider(LocalImagePanelDrag provides drag.takeIf { draggable }) {
            content(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { measured = it })
        }
    }
}
