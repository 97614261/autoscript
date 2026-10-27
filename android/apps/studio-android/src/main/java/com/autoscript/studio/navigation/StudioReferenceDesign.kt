package com.autoscript.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val StudioViolet = Color(0xFF793FFF)
internal val StudioInk = Color(0xFF101A33)
internal val StudioMuted = Color(0xFF7D879F)
internal val StudioPaper = Color(0xFFF5F6FF)
internal val StudioLilac = Color(0xFFF0EAFF)
internal val StudioBrandGradient = Brush.linearGradient(listOf(Color(0xFF8150FF), Color(0xFFA66BFF)))

/** Static, bounded decoration; scrolling lists never need an animation clock. */
@Composable
internal fun StudioPageBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFFFCFCFF), StudioPaper))).clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = minOf(size.width * .39f, 220.dp.toPx())
            drawCircle(Brush.radialGradient(listOf(Color(0xFFEDE5FF), Color(0x00EDE5FF)),
                center = Offset(size.width, 0f), radius = radius * 1.7f), radius * 1.7f, Offset(size.width, 0f))
            drawCircle(Color(0xFFBFA9FF).copy(alpha = .13f), radius,
                Offset(size.width * 1.04f, radius * .25f))
        }
        content()
    }
}

internal fun projectActionColumns(availableWidthDp: Float, fontScale: Float): Int {
    val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 3f) else 1f
    return if (availableWidthDp >= 4 * (48f * scale + 20f)) 4
        else if (availableWidthDp >= 2 * (48f * scale + 20f)) 2 else 1
}
