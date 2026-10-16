package com.autoscript.studio

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Visible pixel boundaries only; caps also guard malformed/very large viewport input. */
internal fun imageWorkbenchMarks(imageSize: Int, translation: Float, scale: Float, viewSize: Float,
    pixelGrid: Boolean): List<Pair<Int, Float>> {
    require(imageSize > 0 && scale.isFinite() && scale > 0 && translation.isFinite() && viewSize.isFinite() && viewSize > 0)
    if (pixelGrid && scale < 8f) return emptyList()
    val start = ceil((-translation / scale).coerceIn(0f, imageSize.toFloat()).toDouble()).toInt()
    val end = floor(((viewSize - translation) / scale).coerceIn(0f, imageSize.toFloat()).toDouble()).toInt()
    if (start > end) return emptyList()
    val step = if (pixelGrid) 1 else max(1, ceil((end - start).toDouble() / 16).toInt())
    val first = (start.toLong() + step - 1) / step * step
    return generateSequence(first) { it + step }.takeWhile { it <= end }.take(if (pixelGrid) 512 else 18)
        .map { it.toInt() to (translation + it * scale).toFloat() }.toList()
}

@Composable
internal fun ImageWorkbenchRulers(viewport: ImageToolViewport) {
    Canvas(Modifier.fillMaxSize()) {
        val grid = Color.White.copy(alpha = .18f)
        imageWorkbenchMarks(viewport.imageWidth, viewport.translateX, viewport.scale, size.width, true).forEach { (_, x) ->
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        imageWorkbenchMarks(viewport.imageHeight, viewport.translateY, viewport.scale, size.height, true).forEach { (_, y) ->
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 10f * density }
        imageWorkbenchMarks(viewport.imageWidth, viewport.translateX, viewport.scale, size.width, false).forEach { (pixel, x) ->
            drawLine(Color.Cyan, Offset(x, 0f), Offset(x, 6f * density), 1f)
            drawContext.canvas.nativeCanvas.drawText(pixel.toString(), x + 2f, 16f * density, paint)
        }
        imageWorkbenchMarks(viewport.imageHeight, viewport.translateY, viewport.scale, size.height, false).forEach { (pixel, y) ->
            drawLine(Color.Cyan, Offset(0f, y), Offset(6f * density, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(pixel.toString(), 8f * density, y + 12f * density, paint)
        }
    }
}
