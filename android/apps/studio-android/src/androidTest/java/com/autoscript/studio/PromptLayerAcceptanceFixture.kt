package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import java.io.File

/** Real same-type native overlay ordering, without running scripts or reading private projects. */
internal object PromptLayerAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        var parent by mutableStateOf(true)
        var prompt by mutableStateOf(false)
        var late by mutableStateOf(false)
        var nested by mutableStateOf(false)
        var configuration by mutableStateOf(false)
        var variables by mutableStateOf(false)
        var confirmations = 0
        val host = EditorOverlaySession({})

        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null || !node.refresh() || !node.isVisibleToUser) return null
                if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
                for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                return null
            }
            return test.uiAutomation.windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { window ->
                val root = window.root
                if (root?.packageName?.toString() == test.targetContext.packageName) walk(root) else null
            }
        }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(predicate()) { "PROMPT_LAYER_QA_TIMEOUT" }
        }
        fun layer(label: String): Int? {
            val id = find(label)?.windowId ?: return null
            return test.uiAutomation.windows.firstOrNull { it.id == id }?.layer
        }
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = null
            waitFor {
                node = find(label)
                while (node != null && !node!!.isClickable) node = node!!.parent
                node?.isEnabled == true
            }
            val rect = Rect().also(requireNotNull(node)::getBoundsInScreen)
            check(!rect.isEmpty)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(20)
            }
        }
        fun capture(name: String) {
            // Accessibility is ready before the native dialog enter/fade animation finishes.
            // This delay is screenshot-only; it never enters app or script execution paths.
            test.waitForIdleSync()
            SystemClock.sleep(350)
            val screen = requireNotNull(test.uiAutomation.takeScreenshot())
            try { File(activity.externalCacheDir, "qa-prompt-layer-$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { screen.recycle() }
        }
        test.runOnMainSync {
            activity.setContent {
                AutoScriptTheme {
                    CompositionLocalProvider(LocalEditorOverlay provides host) {
                        // Deliberately place the prompt before a later ordinary panel.
                        if (prompt) StudioConfirmDialog(onDismissRequest = { prompt = false },
                            title = { Text("置顶提示验收") }, text = {
                                Column {
                                    Text("公开合成提示，不执行脚本")
                                    TextButton(onClick = { nested = true }) { Text("打开提示内选择") }
                                }
                                if (nested) StudioPanelDialog("提示内选择验收", { nested = false }) { Text("提示内选择内容") }
                            }, actions = listOf(StudioConfirmAction("确认提示", { confirmations++; prompt = false })))
                        if (parent) StudioPanelDialog("原参数窗验收", { parent = false }) {
                            var value by remember { mutableStateOf("保留的公开参数") }
                            OutlinedTextField(value, { value = it }, label = { Text("原参数") })
                        }
                        if (late) StudioPanelDialog("迟到工具窗验收", { late = false }) { Text("迟到工具内容") }
                        if (configuration) StudioConfirmDialog(onDismissRequest = { configuration = false }, prompt = false,
                            title = { Text("配置表单验收") }, text = {
                                TextButton(onClick = { variables = true }) { Text("维护变量验收") }
                            }, actions = listOf(StudioConfirmAction("返回配置前", { configuration = false })))
                        // This is a root sibling, as in the workbench's real onManageVariables route.
                        if (variables) StudioPanelDialog("变量维护验收", { variables = false }) { Text("公开变量列表") }
                    }
                }
            }
        }
        try {
            record("prompt-above-existing-panel-with-real-touch-and-retained-parameters") {
                waitFor { find("保留的公开参数") != null }
                var input = find("保留的公开参数")
                while (input != null && !input.isEditable) input = input.parent
                check(requireNotNull(input).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "已修改的公开参数")
                }))
                waitFor { find("已修改的公开参数") != null }
                test.runOnMainSync { prompt = true }
                waitFor { layer("置顶提示验收") != null }
                layer("原参数窗验收")?.let { check(requireNotNull(layer("置顶提示验收")) > it) }
                capture("above-parent")
                click("确认提示")
                waitFor { !prompt && confirmations == 1 && find("已修改的公开参数") != null }
            }
            record("late-normal-panel-cannot-cover-prompt-and-opens-after-confirmation") {
                test.runOnMainSync { prompt = true; late = true }
                waitFor { find("确认提示") != null }
                check(find("迟到工具窗验收") == null)
                test.runOnMainSync { host.systemRequest(true) }
                waitFor { find("置顶提示验收") == null }
                test.runOnMainSync { host.restore() }
                check(host.hidden)
                test.runOnMainSync { host.systemRequest(false) }
                waitFor { find("确认提示") != null }
                check(find("迟到工具窗验收") == null)
                capture("late-window-deferred")
                click("确认提示")
                waitFor { confirmations == 2 && find("迟到工具窗验收") != null }
                click("关闭")
                waitFor { !late && find("已修改的公开参数") != null }
            }
            record("prompt-owned-picker-remains-interactive-and-returns-to-prompt") {
                test.runOnMainSync { prompt = true }
                waitFor { find("打开提示内选择") != null }
                click("打开提示内选择")
                waitFor { find("提示内选择验收") != null }
                layer("置顶提示验收")?.let { check(requireNotNull(layer("提示内选择验收")) > it) }
                click("关闭")
                waitFor { !nested && find("确认提示") != null }
                click("确认提示")
                waitFor { confirmations == 3 && find("已修改的公开参数") != null }
            }
            record("configuration-form-does-not-block-sibling-variable-maintenance") {
                test.runOnMainSync { configuration = true }
                waitFor { find("维护变量验收") != null }
                click("维护变量验收")
                waitFor { find("变量维护验收") != null }
                click("关闭")
                waitFor { !variables && find("返回配置前") != null }
                click("返回配置前")
                waitFor { !configuration && find("已修改的公开参数") != null }
            }
            record("closed-prompt-session-rejects-late-restore-and-removes-owned-windows") {
                test.runOnMainSync { prompt = true; late = true }
                waitFor { find("确认提示") != null }
                test.runOnMainSync { host.closeDialogs(); host.restore(); host.systemRequest(false) }
                waitFor { find("确认提示") == null && find("原参数窗验收") == null && find("迟到工具窗验收") == null }
                check(host.isClosed && !host.hasOpenDialogs)
            }
        } catch (failure: Throwable) { runCatching { capture("failure") }; throw failure }
        finally {
            test.runOnMainSync { host.closeDialogs(); prompt = false; nested = false; late = false; parent = false; configuration = false; variables = false }
            test.waitForIdleSync()
        }
    }
}
