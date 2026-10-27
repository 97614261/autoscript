package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.autoscript.core.designsystem.AutoScriptTheme
import java.io.File

/** Exercises only the management launcher; no private project operations or script execution. */
internal object PluginManagerAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        var showing by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var largeText by mutableStateOf(false)
        var session by mutableStateOf(0)
        val dispatched = mutableListOf<PluginManagerAction>()
        fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null || !current.refresh()) return null
                if (predicate(current)) return current
                for (index in 0 until current.childCount) walk(current.getChild(index))?.let { return it }
                return null
            }
            for (window in test.uiAutomation.windows.sortedByDescending { it.layer }) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == test.targetContext.packageName) walk(root)?.let { return it }
            }
            return null
        }
        fun exists(label: String) = node { it.text?.toString() == label || it.contentDescription?.toString() == label } != null
        fun disabled(label: String): Boolean {
            var current = node { it.text?.toString() == label }
            while (current != null && !current.isClickable) current = current.parent
            return current != null && !current.isEnabled
        }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 8_000
            while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            check(predicate()) { "PLUGIN_MANAGER_QA_TIMEOUT" }
        }
        fun click(label: String) {
            try { test.uiAutomation.waitForIdle(150, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
            var target: AccessibilityNodeInfo? = null
            waitFor {
                var candidate = node { it.text?.toString() == label || it.contentDescription?.toString() == label }
                while (candidate != null && !candidate.isClickable) candidate = candidate.parent
                target = candidate
                candidate?.refresh() == true && candidate.isEnabled
            }
            val rect = Rect().also(requireNotNull(target)::getBoundsInScreen)
            check(!rect.isEmpty)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(20)
            }
        }
        fun bounds(label: String) = Rect().also { requireNotNull(node { it.text?.toString() == label }).getBoundsInScreen(it) }
        fun capture(name: String) {
            val screen = requireNotNull(test.uiAutomation.takeScreenshot())
            try { File(activity.externalCacheDir, "qa-plugin-manager-$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { screen.recycle() }
        }
        fun open(available: Boolean = true, large: Boolean = false) {
            test.runOnMainSync { session++; enabled = available; largeText = large; showing = true }
            waitFor { exists("插件管理") && exists("插件创建") }
        }
        test.runOnMainSync {
            activity.setContent {
                AutoScriptTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else 1f)) {
                        key(session) {
                            if (showing) PluginManagerDialog(enabled, "公开合成主插件", 5, { showing = false }) {
                                dispatched += it
                                showing = false
                            }
                        }
                    }
                }
            }
        }
        try {
            record("plugin-manager-eight-compact-rows-in-original-order") {
                open()
                val rows = PluginManagerAction.entries.map { bounds(it.label) }
                check(rows.zipWithNext().all { (first, second) -> first.left == second.left && first.bottom <= second.top })
                check(rows.last().bottom - rows.first().top < 300 * activity.resources.displayMetrics.density)
                check(!exists("确定") && !exists("取消") && exists("后续开放"))
                capture("compact")
            }
            record("plugin-manager-help-can-expand-collapse-without-action") {
                click("插件管理说明")
                waitFor { node { it.text?.toString()?.contains("不会自动删除") == true } != null }
                click("插件管理说明")
                waitFor { node { it.text?.toString()?.contains("不会自动删除") == true } == null }
                check(dispatched.isEmpty())
            }
            record("plugin-manager-template-remains-visible-disabled-and-cannot-dispatch") {
                check(exists("存储为模板") && disabled("存储为模板"))
                val count = dispatched.size
                node { it.text?.toString() == "存储为模板" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                test.waitForIdleSync()
                check(dispatched.size == count && showing)
            }
            for (action in PluginManagerAction.entries.filter { it != PluginManagerAction.TEMPLATE }) {
                record("plugin-manager-direct-action-${action.name.lowercase()}") {
                    if (!showing) open()
                    val count = dispatched.size
                    click(action.label)
                    waitFor { !showing }
                    check(dispatched.size == count + 1 && dispatched.last() == action)
                }
            }
            record("plugin-manager-disabled-state-does-not-dispatch-and-close-is-accessible") {
                open(available = false)
                for (action in PluginManagerAction.entries) {
                    check(disabled(action.label))
                }
                val count = dispatched.size
                click("关闭"); waitFor { !showing }; check(dispatched.size == count)
            }
            record("plugin-manager-large-text-keeps-eight-rows-and-stays-dismissible") {
                open(large = true)
                val rows = PluginManagerAction.entries.map { bounds(it.label) }
                check(rows.all { !it.isEmpty })
                check(rows.zipWithNext().all { (first, second) -> first.left == second.left && first.bottom <= second.top })
                capture("large-text")
                click("关闭"); waitFor { !showing }
            }
        } catch (failure: Throwable) { runCatching { capture("failure") }; throw failure }
        finally { test.runOnMainSync { showing = false }; test.waitForIdleSync() }
    }
}
