package com.autoscript.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.autoscript.brand.ui.R as BrandR
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class SpacePalette(val top: Color, val bottom: Color, val primary: Color, val secondary: Color)
private fun HomeSpaceTheme.palette(): SpacePalette = when (this) {
    HomeSpaceTheme.AURORA -> SpacePalette(Color(0xFF111026), Color(0xFF080E1B), Color(0xFFAF7CFF), Color(0xFF6BE5D5))
    HomeSpaceTheme.ORBITS -> SpacePalette(Color(0xFF0A1429), Color(0xFF0A0B20), Color(0xFF7DA8FF), Color(0xFFE1A5FF))
    HomeSpaceTheme.PARTICLES -> SpacePalette(Color(0xFF21102B), Color(0xFF0C0C1C), Color(0xFFF397D5), Color(0xFFFFCE9C))
}

/** Interactive brand playground only: no projects, permissions, network or runtime access. */
@Composable
internal fun StudioInteractiveSpace(modifier: Modifier = Modifier, spaceState: HomeSpaceState = rememberHomeSpaceState()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val theme = spaceState.theme
    val palette = theme.palette()
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun motionAllowed(): Boolean = runCatching {
        val enabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        homeAmbientMotionAllowed(enabled, context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }.getOrDefault(false)
    var motion by remember { mutableStateOf(motionAllowed()) }
    val clock = remember { mutableLongStateOf(0L) }
    var demoStart by remember { mutableStateOf<Long?>(0L) }
    var staticDemo by remember { mutableStateOf(false) }
    val drag = remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var rippleAt by remember { mutableStateOf(Offset.Unspecified) }
    var rippleStart by remember { mutableLongStateOf(-2_000L) }
    fun play() { demoStart = clock.longValue; staticDemo = true; rippleStart = clock.longValue }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { motion = motionAllowed(); resumed = true }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) resumed = false
        }
        owner.lifecycle.addObserver(observer)
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { motion = motionAllowed() }
        }
        ContextCompat.registerReceiver(context, powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val animationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = motionAllowed() }
        }
        val animationObserverRegistered = runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver)
            true
        }.getOrElse { motion = false; false }
        onDispose {
            owner.lifecycle.removeObserver(observer); context.unregisterReceiver(powerReceiver)
            if (animationObserverRegistered) context.contentResolver.unregisterContentObserver(animationObserver)
        }
    }
    LaunchedEffect(resumed, motion) {
        if (!resumed || !motion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            if (now - last < 33_000_000L) continue
            clock.longValue += ((now - last) / 1_000_000L).coerceAtMost(100L)
            last = now
            if (!dragging && drag.value != Offset.Zero) {
                val returning = drag.value * .77f
                drag.value = if (returning.getDistance() < .005f) Offset.Zero else returning
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))) {
        val density = LocalDensity.current
        val layout = homeSpaceLayout(maxWidth.value, maxHeight.value, density.fontScale)
        val wide = layout.landscape
        val diameter = layout.diameterDp.dp
        val sidePanelWidth = maxWidth * .47f
        val stackedCaption = !wide && density.fontScale > 1.2f
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { layout.contentHeightDp.dp.toPx() }
        val diameterPx = with(density) { diameter.toPx() }
        val center = with(density) { Offset(layout.centerXDp.dp.toPx(), layout.centerYDp.dp.toPx()) }
        val currentTheme by rememberUpdatedState(theme)
        val scrollNeeded = layout.contentHeightDp > maxHeight.value + 1f
        val interaction = Modifier.fillMaxWidth().height(layout.contentHeightDp.dp).semantics {
            contentDescription = "智构互动空间"; stateDescription = "${theme.label}主题"
            customActions = listOf(CustomAccessibilityAction("播放流程演示") { play(); true },
                CustomAccessibilityAction("下一个视觉主题") { spaceState.theme = nextHomeTheme(currentTheme); true })
        }.pointerInput(center, diameterPx, widthPx, motion, scrollNeeded) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val logoDrag = (down.position - center).getDistance() < diameterPx * .38f
                var travel = Offset.Zero
                val slop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                    // Leave vertical background drags to the parent scroll container.
                    if (logoDrag || !scrollNeeded || kotlin.math.abs(over.x) > kotlin.math.abs(over.y)) {
                        change.consume(); travel = over
                    }
                } ?: return@awaitEachGesture
                dragging = logoDrag
                fun moveLogo() {
                    if (logoDrag) drag.value = Offset(homeDragFraction(travel.x, diameterPx * .25f), homeDragFraction(travel.y, diameterPx * .25f))
                }
                moveLogo()
                val ended = drag(slop.id) { change ->
                    travel += change.position - change.previousPosition
                    change.consume(); moveLogo()
                }
                if (ended && !logoDrag) spaceState.theme = homeThemeAfterSwipe(currentTheme, travel.x, travel.y, widthPx)
                dragging = false
                if (!motion || !ended) drag.value = Offset.Zero
            }
        }.pointerInput(Unit) { detectTapGestures { position -> rippleAt = position; play() } }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = scrollNeeded)) {
        Box(interaction) {
            Canvas(Modifier.fillMaxSize()) {
                val frame = if (motion) homeDemoFrame(demoStart?.let { (clock.longValue - it) % 6_400L }) else HomeDemoFrame(completed = staticDemo)
                drawSpaceBackdrop(theme, palette, clock.longValue / 1_000f, center, diameterPx, drag.value)
                if (motion && frame.completed) {
                    val from = if (wide) Offset(widthPx * .72f, center.y) else Offset(widthPx * .5f, heightPx - 175.dp.toPx())
                    val p = frame.progress
                    val spark = from * (1f - p) + center * p + Offset(sin(p * PI.toFloat()) * diameterPx * .12f, 0f)
                    drawCircle(Brush.radialGradient(listOf(palette.secondary.copy(alpha = .6f * (1f - p)), Color.Transparent), spark, 15.dp.toPx()), 15.dp.toPx(), spark)
                    drawCircle(Color.White.copy(alpha = 1f - p), 2.dp.toPx(), spark)
                }
                val age = (clock.longValue - rippleStart) / 1_000f
                if (motion && age in 0f..1.1f) {
                    val origin = if (rippleAt.isUnspecified) center else rippleAt
                    drawCircle(palette.secondary.copy(alpha = (1f - age / 1.1f) * .45f),
                        diameterPx * (.12f + age * .6f), origin, style = Stroke(1.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (wide) 10.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ZHIGOU", color = palette.primary.copy(alpha = .85f), fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 3.sp)
                    Text("智构空间", color = Color(0xFFF4F0FF), fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
                }
                Text("${theme.label}  /  0${theme.ordinal + 1}", color = Color(0xFFAFAAC8), fontSize = 10.sp, lineHeight = 14.sp,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Image(painterResource(BrandR.drawable.zhigou_brand_mark), null,
                Modifier.offset { IntOffset((center.x - diameterPx * .32f).roundToInt(), (center.y - diameterPx * .32f).roundToInt()) }
                    .size(diameter * .64f).graphicsLayer {
                        translationX = drag.value.x * diameterPx * .13f
                        translationY = drag.value.y * diameterPx * .13f
                    }.semantics {
                        contentDescription = "播放流程演示"; role = Role.Button
                        onClick("播放流程演示") { play(); true }
                    })
            Column(Modifier.then(if (wide) Modifier.align(Alignment.CenterEnd).padding(top = layout.headerReserveDp.dp, bottom = layout.footerReserveDp.dp, end = 20.dp).width(sidePanelWidth).fillMaxHeight()
                else Modifier.align(Alignment.BottomCenter).padding(bottom = layout.footerReserveDp.dp).widthIn(max = 340.dp).fillMaxWidth(.85f)),
                verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 10.dp, Alignment.CenterVertically)) {
                HomePlaybackPanel(clock, demoStart, staticDemo, palette, wide, stackedCaption, layout.compactBlocks, motion, resumed)
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 8.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeSpaceTheme.entries.forEach { option ->
                        val active = theme == option
                        Row(Modifier.semantics { contentDescription = "切换至${option.label}主题"; selected = active }
                            .clickable(role = Role.Button) { spaceState.theme = option }
                            .border(1.dp, if (active) palette.primary.copy(alpha = .45f) else Color.Transparent, RoundedCornerShape(16.dp))
                            .background(if (active) palette.primary.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(16.dp))
                            .padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, fontSize = 10.sp, lineHeight = 14.sp, color = if (active) Color(0xFFF5ECFF) else Color(0xFF8985A4))
                        }
                    }
                }
                if (!wide) Text("轻触重播 · 拖动光场 · 左右切换", color = Color(0xFF77758D), fontSize = 9.sp, lineHeight = 12.sp, textAlign = TextAlign.Center)
            }
        }
        }
    }
}

/** Only this small subtree observes the timeline in composition; the backdrop reads it in draw. */
@Composable
private fun ColumnScope.HomePlaybackPanel(clock: State<Long>, demoStart: Long?, staticDemo: Boolean, palette: SpacePalette,
    wide: Boolean, stackedCaption: Boolean, compact: Boolean, motion: Boolean, resumed: Boolean) {
    val frame = if (motion) homeDemoFrame(demoStart?.let { (clock.value - it) % 6_400L }) else HomeDemoFrame(completed = staticDemo)
    val label = when { frame.activeLine >= 0 -> "演示：第 ${frame.activeLine + 1} 行"; frame.completed -> "演示完成"; else -> "轻触重播" }
    if (wide) Text("交互演示 · 不执行脚本", color = Color(0xFF9692B3), fontSize = 8.sp, lineHeight = 11.sp)
    else if (stackedCaption) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("交互演示 · 不执行脚本", color = Color(0xFF9692B3), fontSize = 9.sp, lineHeight = 12.sp)
        Text(label, color = palette.secondary, fontSize = 9.sp, lineHeight = 12.sp)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("交互演示 · 不执行脚本", color = Color(0xFF9692B3), fontSize = 9.sp, lineHeight = 12.sp)
        Text(label, color = palette.secondary, fontSize = 9.sp, lineHeight = 12.sp)
    }
    HomeBlockPlayback(frame, palette.primary, palette.secondary, compact, motion && resumed)
}

private fun DrawScope.drawSpaceBackdrop(theme: HomeSpaceTheme, palette: SpacePalette, seconds: Float,
    heroCenter: Offset, diameter: Float, drag: Offset) {
    if (diameter <= 0f || size.minDimension <= 0f) return
    val c = heroCenter + drag * (diameter * .08f)
    val radius = diameter * .47f
    val breath = .82f + .18f * sin(seconds * .85f)
    drawCircle(Brush.radialGradient(listOf(palette.primary.copy(alpha = .21f * breath), Color.Transparent), c, radius * 1.5f), radius * 1.5f, c)
    // Fixed particle budget; no per-frame randomness, bitmaps or external assets.
    repeat(48) { index ->
        val x = ((index * 137.508f) % 997f) / 997f * size.width
        val y = ((index * 73.319f + 31f) % 991f) / 991f * size.height
        val shimmer = .16f + .13f * sin(seconds * .4f + index)
        drawCircle(Color.White.copy(alpha = shimmer), if (index % 7 == 0) 1.dp.toPx() else .55.dp.toPx(), Offset(x, y))
    }
    when (theme) {
        HomeSpaceTheme.AURORA -> repeat(3) { index ->
            val shift = sin(seconds * .22f + index) * size.width * .08f
            val p = Path().apply {
                moveTo(-size.width * .3f + shift, size.height * (.12f + index * .12f))
                cubicTo(size.width * .7f, -size.height * .12f, -size.width * .1f, size.height * .8f, size.width * 1.3f, size.height * (.38f + index * .15f))
            }
            val color = if (index == 1) palette.secondary else palette.primary
            drawPath(p, color.copy(alpha = .035f), style = Stroke(size.minDimension * .14f))
            drawPath(p, color.copy(alpha = .09f), style = Stroke(1.dp.toPx()))
        }
        HomeSpaceTheme.ORBITS -> rotate(-24f, c) {
            repeat(4) { index ->
                val rx = radius * (.7f + index * .25f)
                val ry = rx * .45f
                drawOval(palette.primary.copy(alpha = .12f + index * .025f), c - Offset(rx, ry), Size(rx * 2, ry * 2), style = Stroke(.7.dp.toPx()))
                val angle = seconds * (.24f + index * .08f) + index * 1.8f
                drawCircle(palette.secondary.copy(alpha = .75f), 2.dp.toPx(), c + Offset(cos(angle) * rx, sin(angle) * ry))
            }
        }
        HomeSpaceTheme.PARTICLES -> repeat(48) { index ->
            val angle = index * 2.39996f + seconds * (.06f + index % 3 * .025f)
            val r = radius * (.5f + (index % 17) / 16f * .9f)
            val p = c + Offset(cos(angle) * r, sin(angle) * r * .85f)
            drawCircle(if (index % 3 == 0) palette.secondary.copy(alpha = .6f) else palette.primary.copy(alpha = .4f),
                if (index % 9 == 0) 2.dp.toPx() else .9.dp.toPx(), p)
        }
    }
    drawCircle(palette.primary.copy(alpha = .13f), radius, c, style = Stroke(.7.dp.toPx()))
    drawCircle(palette.primary.copy(alpha = .2f), radius * .76f, c, style = Stroke(.5.dp.toPx()))
    val arcStart = seconds * 12f
    drawArc(Brush.sweepGradient(listOf(Color.Transparent, palette.primary.copy(alpha = .1f), palette.secondary.copy(alpha = .65f)), c),
        arcStart, 100f, false, c - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
    val angle = (arcStart + 100) * PI.toFloat() / 180
    val point = c + Offset(cos(angle), sin(angle)) * radius
    drawCircle(Brush.radialGradient(listOf(palette.secondary.copy(alpha = .65f), Color.Transparent), point, 10.dp.toPx()), 10.dp.toPx(), point)
    drawCircle(Color(0xFFF4E9FF), 1.6.dp.toPx(), point)
}
