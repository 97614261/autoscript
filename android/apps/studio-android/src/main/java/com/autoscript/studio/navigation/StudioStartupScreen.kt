package com.autoscript.studio

import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.autoscript.brand.ui.R as BrandR

/** Ends on the first real content layout, not on a timer or Root connection. */
@Composable
internal fun StudioStartupOverlay(ready: Boolean) {
    val context = LocalContext.current
    val motionAllowed = remember(context) {
        homeAmbientMotionAllowed(
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f,
            context.getSystemService(PowerManager::class.java).isPowerSaveMode,
        )
    }
    AnimatedVisibility(visible = !ready, enter = EnterTransition.None,
        exit = if (motionAllowed) fadeOut(tween(320)) else ExitTransition.None) {
        val phase by animateFloatAsState(if (ready && motionAllowed) 1f else 0f, tween(320), label = "startup-light-exit")
        StudioLaunchArtwork(Modifier.fillMaxSize(), phase)
    }
}

@Composable
internal fun StudioLaunchArtwork(modifier: Modifier = Modifier, phase: Float = 0f) {
    StudioPageBackdrop(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val ribbon = Path().apply {
                moveTo(w * .05f, h * .92f)
                cubicTo(-w * .28f, h * .53f, w * 1.45f, h * .55f, w * .73f, h * .33f)
                cubicTo(w * .05f, h * .13f, w * 1.44f, h * .29f, w * .7f, h * .04f)
            }
            val light = Brush.linearGradient(listOf(Color(0x00B49AFF), Color(0x66D0B4FF),
                Color(0x88FFBBEE), Color(0x337C4DFF)), Offset.Zero, Offset(w, h))
            drawPath(ribbon, light, style = Stroke(13.dp.toPx(), cap = StrokeCap.Round))
            drawPath(ribbon, Color.White.copy(alpha = .7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val unit = minOf(w, h)
            val places = listOf(Offset(.25f, .2f), Offset(.82f, .56f), Offset(.14f, .36f), Offset(.91f, .81f))
            places.forEachIndexed { index, point ->
                val side = unit * if (index % 2 == 0) .1f else .07f
                val x = w * point.x
                val y = h * point.y - phase * 14.dp.toPx()
                rotate(25f + index * 12f, Offset(x, y)) {
                    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFFCEFA), Color(0xFFCA9AFF), Color(0x888F6AF5)),
                        Offset(x - side / 2, y - side / 2), Offset(x + side / 2, y + side / 2)),
                        Offset(x - side / 2, y - side / 2), Size(side, side), CornerRadius(side * .18f))
                }
            }
            drawCircle(Color(0xFFF4B4EE), 3.dp.toPx(), Offset(w * .72f, h * .24f))
            drawCircle(Color(0xFFAD7CF7), 2.dp.toPx(), Offset(w * .18f, h * .3f))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth > maxHeight
            val markSize = minOf(if (wide) maxHeight * .75f else maxWidth * .68f, 250.dp)
            @Composable fun title() {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("智构", color = StudioInk, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                    Text("让 自 动 化 更 简 单", color = StudioMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    // Decorative brand stroke, deliberately not a loading percentage.
                    Box(Modifier.padding(top = 14.dp).size(width = 130.dp, height = 4.dp)
                        .background(Brush.horizontalGradient(listOf(StudioViolet, Color(0xFFFF8CEB), Color(0xFFE7DFF5))),
                            RoundedCornerShape(2.dp)))
                }
            }
            if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(painterResource(BrandR.drawable.zhigou_brand_mark), "智构 Logo", Modifier.size(markSize))
                title()
            } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(BrandR.drawable.zhigou_brand_mark), "智构 Logo", Modifier.size(markSize))
                title()
            }
        }
    }
}
