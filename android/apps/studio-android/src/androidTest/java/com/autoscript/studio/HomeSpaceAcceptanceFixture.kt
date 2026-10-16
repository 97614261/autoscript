package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.autoscript.core.designsystem.AutoScriptTheme
import java.io.File

/** Own local brand scene only. Gestures cannot invoke a project or the runtime. */
internal object HomeSpaceAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        var width by mutableIntStateOf(360)
        var height by mutableIntStateOf(580)
        var fontScale by mutableFloatStateOf(1f)
        var sceneVisible by mutableStateOf(true)
        test.runOnMainSync {
            activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    AutoScriptTheme {
                        val state = rememberHomeSpaceState()
                        Box(Modifier.width(width.dp).height(height.dp)) {
                            if (sceneVisible) StudioHomePage(spaceState = state) else Text("QA_HOME_AWAY")
                        }
                    }
                }
            }
        }
        fun node(label: String): AccessibilityNodeInfo? {
            fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
                for (index in 0 until n.childCount) walk(n.getChild(index))?.let { return it }
                return null
            }
            return walk(test.uiAutomation.rootInActiveWindow)
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5_000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(60)
            check(condition()) { "HOME_SCENE_WAIT_FAILED" }
        }
        fun click(label: String) {
            await { node(label) != null }
            var candidate = node(label)
            repeat(6) {
                val current = candidate ?: return@repeat
                if (current.isClickable) {
                    check(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "HOME_SCENE_CLICK_FAILED:$label" }
                    return
                }
                candidate = current.parent
            }
            error("HOME_SCENE_NOT_CLICKABLE:$label")
        }
        fun screenshot(name: String) = test.runOnMainSync {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap)); File(activity.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        fun gesture(points: List<Pair<Float, Float>>, hold: (() -> Unit)? = null) {
            val downAt = SystemClock.uptimeMillis()
            fun event(action: Int, point: Pair<Float, Float>) {
                val motion = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
                motion.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(motion, true)) { "HOME_SCENE_GESTURE_FAILED" } }
                finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, points.first())
            try {
                points.drop(1).forEach { SystemClock.sleep(50); event(MotionEvent.ACTION_MOVE, it) }
                hold?.invoke()
            } finally { event(MotionEvent.ACTION_UP, points.last()) }
            SystemClock.sleep(200)
        }
        record("home-pure-brand-scene-no-duplicate-entries") {
            await { node("智构空间") != null && node("播放流程演示") != null }
            for (label in listOf("软件权限", "运行环境", "新建", "编辑", "启动", "函数文档")) check(node(label) == null) { "HOME_DUPLICATE_ENTRY:$label" }
            check(node("交互演示 · 不执行脚本") != null) { "HOME_DEMO_SCOPE_MISSING" }
            screenshot("home-space-aurora.png")
        }
        record("home-three-theme-controls-and-background-swipe") {
            for (label in listOf("星轨", "粒子", "极光")) {
                click("切换至${label}主题")
                await { node("智构互动空间")?.stateDescription?.toString() == "${label}主题" }
                screenshot("home-space-${when (label) { "星轨" -> "orbits"; "粒子" -> "particles"; else -> "aurora" }}.png")
            }
            val bounds = Rect(); requireNotNull(node("智构互动空间")).getBoundsInScreen(bounds)
            val y = bounds.top + bounds.height() * .2f
            gesture((0..5).map { (bounds.left + bounds.width() * (.82f - it * .11f)) to y })
            await { node("智构互动空间")?.stateDescription?.toString() == "星轨主题" }
        }
        record("home-logo-real-drag-and-replay") {
            val initial = Rect(); requireNotNull(node("播放流程演示")).getBoundsInScreen(initial)
            val density = activity.resources.displayMetrics.density
            // graphicsLayer translation is not reliably reflected by Compose's cached
            // accessibility bounds. Verify the actual pixels of our own logo instead.
            fun markCenterX(): Float {
                var result = 0f
                test.runOnMainSync {
                    val view = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        var sum = 0L; var count = 0
                        val yStart = (initial.top - location[1]).coerceIn(0, bitmap.height)
                        val yEnd = (initial.bottom - location[1]).coerceIn(0, bitmap.height)
                        for (y in yStart until yEnd step 2) for (x in 0 until bitmap.width step 2) {
                            val pixel = bitmap.getPixel(x, y)
                            if (Color.red(pixel) > 100 && Color.blue(pixel) > 150 && Color.green(pixel) < 130) { sum += x; count++ }
                        }
                        check(count > 100) { "HOME_LOGO_PIXEL_SAMPLE_MISSING" }
                        result = sum.toFloat() / count
                    } finally { bitmap.recycle() }
                }
                return result
            }
            val before = markCenterX()
            screenshot("home-space-before-drag.png")
            gesture((0..4).map { (initial.exactCenterX() + it * 7 * density) to initial.exactCenterY() }) {
                SystemClock.sleep(100)
                screenshot("home-space-during-drag.png")
                check(markCenterX() > before + 2 * density) { "HOME_LOGO_DRAG_DID_NOT_MOVE" }
            }
            await { kotlin.math.abs(markCenterX() - before) <= 2 * density }
            click("播放流程演示")
            await { node("演示：第 1 行") != null || node("演示：第 2 行") != null || node("演示完成") != null }
            screenshot("home-space-demo.png")
        }
        record("home-visual-blocks-highlight-each-line-and-recent-trail") {
            click("播放流程演示")
            for (index in 1..5) {
                await { node("演示积木第${index}行")?.stateDescription?.toString() == "演示当前行" }
                if (index > 1) check(node("演示积木第${index - 1}行")?.stateDescription?.toString() == "演示已经过") { "HOME_PREVIOUS_LINE_TRAIL_MISSING" }
                if (index == 4) { SystemClock.sleep(220); screenshot("home-space-block-highlight.png") }
            }
            await { node("演示完成") != null }
        }
        record("home-compact-landscape-large-font-does-not-change-device-rotation") {
            test.runOnMainSync { width = 360; height = 250; fontScale = 1.4f }
            await { node("切换至极光主题") != null }
            click("切换至极光主题")
            await { node("智构互动空间")?.stateDescription?.toString() == "极光主题" }
            for (label in listOf("可视化积木演示", "播放流程演示")) {
                val b = Rect(); requireNotNull(node(label)).getBoundsInScreen(b)
                check(b.width() > 0 && b.height() > 0) { "HOME_LANDSCAPE_ELEMENT_MISSING:$label" }
            }
            val title = Rect(); requireNotNull(node("智构空间")).getBoundsInScreen(title)
            val caption = Rect(); requireNotNull(node("交互演示 · 不执行脚本")).getBoundsInScreen(caption)
            val blocks = Rect(); requireNotNull(node("可视化积木演示")).getBoundsInScreen(blocks)
            val footer = Rect(); requireNotNull(node("切换至极光主题")).getBoundsInScreen(footer)
            check(caption.top >= title.bottom) { "HOME_LANDSCAPE_CAPTION_OVERLAPS_HEADER" }
            check(blocks.bottom <= footer.top) { "HOME_LANDSCAPE_BLOCKS_OVERLAP_THEME_CONTROLS" }
            screenshot("home-space-landscape.png")
        }
        record("home-theme-survives-leaving-and-returning-to-scene") {
            click("切换至粒子主题")
            await { node("智构互动空间")?.stateDescription?.toString() == "粒子主题" }
            test.runOnMainSync { sceneVisible = false }
            await { node("QA_HOME_AWAY") != null }
            test.runOnMainSync { sceneVisible = true }
            await { node("智构互动空间")?.stateDescription?.toString() == "粒子主题" }
        }
        record("home-small-portrait-double-font-keeps-content-and-scroll-gestures") {
            test.runOnMainSync { width = 320; height = 360; fontScale = 2f }
            await { node("智构空间") != null }
            SystemClock.sleep(300)
            val title = Rect(); requireNotNull(node("智构空间")).getBoundsInScreen(title)
            val logo = Rect(); requireNotNull(node("播放流程演示")).getBoundsInScreen(logo)
            val blocks = Rect(); requireNotNull(node("可视化积木演示")).getBoundsInScreen(blocks)
            check(logo.top >= title.bottom && logo.bottom <= blocks.top) { "HOME_PORTRAIT_REGIONS_OVERLAP" }
            val density = activity.resources.displayMetrics.density
            val bounds = Rect(); requireNotNull(node("智构互动空间")).getBoundsInScreen(bounds)
            // Vertical background gesture must scroll rather than being swallowed by the light-field drag.
            gesture((0..5).map { (bounds.left + 6 * density) to (bounds.top + (290 - it * 36) * density) })
            await {
                val moved = Rect(); node("智构空间")?.getBoundsInScreen(moved)
                node("智构空间") == null || moved.top < title.top - 10 * density
            }
            click("切换至星轨主题")
            await { node("智构互动空间")?.stateDescription?.toString() == "星轨主题" }
            val scrolledBlocks = Rect(); requireNotNull(node("可视化积木演示")).getBoundsInScreen(scrolledBlocks)
            val footer = Rect(); requireNotNull(node("切换至星轨主题")).getBoundsInScreen(footer)
            check(scrolledBlocks.bottom <= footer.top) { "HOME_PORTRAIT_BLOCKS_OVERLAP_THEME_CONTROLS" }
            screenshot("home-space-small-portrait-large-font.png")
        }
    }
}
