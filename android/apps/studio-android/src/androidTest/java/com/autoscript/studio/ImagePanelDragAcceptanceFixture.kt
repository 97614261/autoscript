package com.autoscript.studio

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient

/** Real title gestures on the production workspace; no screenshots or input through Root. */
internal object ImagePanelDragAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        var snapshot by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        val source = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff6080a0.toInt()) }
        val ui = UsageTestUi(test)
        val orientation = activity.requestedOrientation
        fun bounds(label: String): Rect {
            val node = requireNotNull(ui.label(label))
            return try { Rect().also(node::getBoundsInScreen) } finally { node.recycle() }
        }
        fun rotate(landscape: Boolean) {
            test.runOnMainSync { activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.await("panel orientation") { activity.resources.configuration.orientation == if (landscape) 2 else 1 }
            test.waitForIdleSync()
        }
        fun drag(label: String, dx: Float, dy: Float) {
            val from = bounds(label)
            val down = SystemClock.uptimeMillis()
            for (step in 0..12) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    from.exactCenterX() + dx * step / 12, from.exactCenterY() + dy * step / 12, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(25)
            }
        }
        fun assertControlsInsideCanvas() {
            val canvas = bounds("滤色预览取色")
            for (label in listOf("拖动图像面板", "收起滤色", "应用滤色", "取消"))
                check(canvas.contains(bounds(label))) { "QA_PANEL_CONTROL_OUTSIDE_CANVAS:$label" }
        }
        try {
            rotate(true)
            test.runOnMainSync { activity.setContent { AutoScriptTheme { if (visible)
                ImageProcessingWorkbench(source, snapshot, store, client, { snapshot = it }, { visible = false },
                    capturedScreen = true, initialFilterOpen = true,
                    capture = ImageWorkspaceCapture(640, 400, "letterbox", ImageToolMode.REGION, null, false, {}, {}, null, {}, null, null))
            } } }
            record("image-panel-title-drag-moves-the-form-without-moving-the-canvas") {
                ui.wait("应用滤色"); ui.wait("拖动图像面板")
                val canvas = bounds("滤色预览取色")
                val before = bounds("拖动图像面板")
                drag("拖动图像面板", -canvas.width() * .45f, 0f)
                ui.await("panel moved left") { bounds("拖动图像面板").left < before.left - 60 }
                check(bounds("滤色预览取色") == canvas) { "QA_PANEL_DRAG_CHANGED_CANVAS" }
                assertControlsInsideCanvas()
                ui.screenshot("image-panel-drag-landscape")
            }
            record("image-panel-close-and-reopen-keeps-placement-and-buttons-work") {
                val before = bounds("拖动图像面板")
                ui.click("返回图像处理"); ui.wait("滤色"); ui.click("滤色"); ui.wait("应用滤色")
                ui.await("retained panel position") { kotlin.math.abs(bounds("拖动图像面板").left - before.left) <= 2 }
                ui.click("取消"); ui.absent("应用滤色")
                ui.click("工具"); ui.click("滤色"); ui.wait("应用滤色")
            }
            record("image-panel-portrait-title-drag-stays-visible-and-rotation-restores-position") {
                val landscapeLeft = bounds("拖动图像面板").left
                rotate(false); ui.wait("应用滤色")
                val before = bounds("拖动图像面板")
                drag("拖动图像面板", 0f, -220f)
                ui.await("panel moved up") { bounds("拖动图像面板").top < before.top - 60 }
                assertControlsInsideCanvas()
                ui.screenshot("image-panel-drag-portrait")
                rotate(true); ui.wait("应用滤色")
                ui.await("landscape placement restored") { kotlin.math.abs(bounds("拖动图像面板").left - landscapeLeft) <= 2 }
                assertControlsInsideCanvas()
                ui.click("取消"); ui.absent("应用滤色")
            }
        } catch (failure: Throwable) {
            runCatching { ui.screenshot("image-panel-drag-failure") }
            throw failure
        } finally {
            test.runOnMainSync { visible = false; activity.requestedOrientation = orientation }
            test.waitForIdleSync(); source.recycle()
        }
        return snapshot
    }
}
