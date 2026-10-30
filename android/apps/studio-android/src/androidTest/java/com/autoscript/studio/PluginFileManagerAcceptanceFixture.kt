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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.*
import java.io.File

/** Only the newly owned QA project is mutated. Does not start a script, capture another app or change the IME. */
internal object PluginFileManagerAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, store: ProjectStore, initial: ProjectSnapshot,
        record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        check(initial.manifest.projectId.startsWith("project-qa-"))
        val files = ProjectSourceFiles(store)
        var snapshot = files.createFlow(initial, "工具")
        snapshot = files.createFlow(snapshot, "辅助")
        files.createGroup(snapshot, "常用"); files.createGroup(snapshot, "备用")
        var tree by mutableStateOf(files.load(snapshot))
        val helperId = tree.entries.single { it.name == "工具" }.flowId
        val auxiliaryId = tree.entries.single { it.name == "辅助" }.flowId
        val mainId = requireNotNull(snapshot.manifest.entryFlowId)
        var showing by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var large by mutableStateOf(false)
        var session by mutableStateOf(0)
        var message by mutableStateOf<String?>(null)
        var opened: String? = null
        var inserted: String? = null
        val actions = mutableListOf<SourceManagerAction>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fun ancestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var current = node
            while (current != null && !current.isClickable) current = current.parent
            return current
        }
        fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null || !current.refresh()) return null
                if (predicate(current)) return current
                for (i in 0 until current.childCount) walk(current.getChild(i))?.let { return it }
                return null
            }
            for (window in test.uiAutomation.windows.sortedByDescending { it.layer }) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == test.targetContext.packageName) walk(root)?.let { return it }
            }
            return null
        }
        fun matches(n: AccessibilityNodeInfo, text: String) = n.text?.toString() == text || n.contentDescription?.toString() == text
        fun exists(text: String) = node { matches(it, text) } != null
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(predicate()) { "PLUGIN_FILES_QA_TIMEOUT" }
        }
        fun click(label: String, below: Int = Int.MIN_VALUE) {
            try { test.uiAutomation.waitForIdle(150, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
            var target: AccessibilityNodeInfo? = null
            waitFor {
                target = node { matches(it, label) && Rect().also(it::getBoundsInScreen).top >= below &&
                    ancestor(it)?.isEnabled == true }?.let(::ancestor)
                target?.refresh() == true && target?.isEnabled == true
            }
            val bounds = Rect().also(requireNotNull(target)::getBoundsInScreen)
            check(!bounds.isEmpty)
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(20)
            }
        }
        fun fill(label: String, value: String) {
            fun labelledInput(n: AccessibilityNodeInfo): Boolean {
                if (!n.isEditable && n.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
                var current: AccessibilityNodeInfo? = n
                repeat(4) {
                    if (current?.contentDescription?.toString()?.contains(label) == true) return true
                    current = current?.parent
                }
                return false
            }
            fun input() = node { labelledInput(it) } ?: node {
                it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            } // Every owned modal in this fixture has exactly one input, topmost window first.
            waitFor { input() != null }
            check(input()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }) == true)
        }
        fun disabled(label: String): Boolean {
            var current = node { matches(it, label) }
            while (current != null) {
                if (!current.isEnabled) return true
                current = current.parent
            }
            return false
        }
        fun capture(name: String) {
            if (name == "failure") {
                val fields = mutableListOf<String>()
                node {
                    if (fields.size < 100) fields += listOf(it.className, it.text, it.contentDescription, it.isEditable,
                        it.actionList.joinToString(",") { action -> action.id.toString() }).joinToString(" | ")
                    false
                }
                test.sendStatus(1, Bundle().apply { putString("stream", "QA_OWNED_NODES:\n" + fields.joinToString("\n") + "\n") })
            }
            val screen = requireNotNull(test.uiAutomation.takeScreenshot())
            try { File(activity.externalCacheDir, "qa-plugin-files-" + name + ".png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { screen.recycle() }
        }
        fun open(big: Boolean = false) {
            test.runOnMainSync { session++; large = big; showing = true }
            waitFor { exists("项目插件") && exists("新建插件") }
        }
        fun search(value: String) { fill("搜索插件", value); test.waitForIdleSync() }
        fun select(name: String) { search(name); click("选择插件 " + name); waitFor { exists("取消选择") } }
        test.runOnMainSync {
            activity.setContent { AutoScriptTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    key(session) {
                        if (showing) PluginFileManagerDialog(tree, mainId, busy, message, onDismiss = { showing = false }, projectName = "公开插件验收",
                            onAction = { action ->
                                check(!busy)
                                actions += action
                                when (action) {
                                    is SourceManagerAction.Open -> { opened = action.flowId; showing = false }
                                    is SourceManagerAction.InsertCall -> { inserted = action.flowId; showing = false }
                                    else -> {
                                        busy = true
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    val updated = when (action) {
                                                        is SourceManagerAction.CreateFile -> files.createFlow(snapshot, action.name, action.group)
                                                        is SourceManagerAction.CreateGroup -> { files.createGroup(snapshot, action.name); snapshot }
                                                        is SourceManagerAction.RenameFile -> files.renameFlow(snapshot, action.flowId, action.name)
                                                        is SourceManagerAction.RenameGroup -> { files.renameGroup(snapshot, action.group, action.name); snapshot }
                                                        is SourceManagerAction.SaveAs -> files.copyFlow(snapshot, action.flowId, action.name)
                                                        is SourceManagerAction.AddToGroup -> { files.addToGroup(snapshot, action.group, action.flowIds); snapshot }
                                                        is SourceManagerAction.RemoveFromGroup -> { files.removeFromGroup(snapshot, action.flowIds); snapshot }
                                                        is SourceManagerAction.SetEntry -> files.setEntry(snapshot, action.flowId)
                                                        is SourceManagerAction.Delete -> {
                                                            files.deleteGroups(snapshot, action.groups)
                                                            val deletion = files.deleteFlows(snapshot, action.flowIds)
                                                            check(deletion.failures.isEmpty())
                                                            deletion.snapshot
                                                        }
                                                        SourceManagerAction.Refresh -> store.openProject(snapshot.manifest.projectId)
                                                        else -> error("UNEXPECTED_QA_ACTION")
                                                    }
                                                    updated to files.load(updated)
                                                }
                                                snapshot = result.first; tree = result.second
                                            } catch (failure: Throwable) { message = "QA_" + failure.javaClass.simpleName }
                                            finally { busy = false }
                                        }
                                    }
                                }
                            })
                    }
                }
            } }
        }
        try {
            record("plugin-files-main-selection-footer-labels-visible-and-delete-protected") {
                open(); select("main")
                for (label in listOf("打开", "插入调用", "另存为", "重命名", "加入分组", "移出分组", "删除")) {
                    val bounds = Rect().also { requireNotNull(node { matches(it, label) }).getBoundsInScreen(it) }
                    check(!bounds.isEmpty && bounds.bottom < 1280)
                }
                check(disabled("删除") && disabled("插入调用")); capture("main-selected")
                click("取消选择")
            }
            record("plugin-files-separate-group-creation-persists-real-virtual-group") {
                click("新建分组"); fill("插件或分组名称", "归档"); click("创建")
                waitFor { !busy && tree.groups.any { it.name == "归档" } }
            }
            record("plugin-files-separate-plugin-creation-is-not-confused-with-group") {
                click("新建插件"); fill("插件或分组名称", "新插件"); click("创建")
                waitFor { !busy && tree.entries.any { it.name == "新插件" } }
                check(actions.last() == SourceManagerAction.CreateFile("新插件", null))
            }
            record("plugin-files-rename-error-is-inline-and-unchanged-name-is-noop") {
                select("工具"); click("重命名"); fill("插件或分组名称", "main"); click("确定")
                waitFor { exists("名称已存在") && exists("重命名") }
                val count = actions.size
                fill("插件或分组名称", "工具"); click("确定")
                waitFor { !exists("插件或分组名称") }; check(actions.size == count)
            }
            record("plugin-files-rename-keeps-identity-and-selection") {
                click("重命名"); fill("插件或分组名称", "工具改名"); click("确定")
                waitFor { !busy && tree.entry(helperId)?.name == "工具改名" }
                check(actions.last() == SourceManagerAction.RenameFile(helperId, "工具改名"))
                check(exists("已选 1 项")); capture("helper-selected")
            }
            record("plugin-files-save-as-validates-name-and-keeps-original") {
                click("另存为"); fill("插件或分组名称", "../bad"); click("确定")
                waitFor { node { it.text?.toString()?.contains("使用中文") == true } != null }
                fill("插件或分组名称", "工具副本"); click("确定")
                waitFor { !busy && tree.entries.any { it.name == "工具副本" } }
                check(tree.entry(helperId)?.name == "工具改名" && exists("已选 1 项"))
            }
            record("plugin-files-existing-group-choice-and-ungroup-use-real-store") {
                click("加入分组"); click("常用"); click("加入")
                waitFor { !busy && tree.entry(helperId)?.group == "常用" }
                click("移出分组"); waitFor { !busy && tree.entry(helperId)?.group == null }
            }
            record("plugin-files-filtered-select-all-keeps-hidden-selection") {
                search("辅助"); click("选择插件 辅助"); waitFor { exists("已选 2 项") }
                check(disabled("重命名") && disabled("打开"))
                click("取消全选"); waitFor { exists("已选 1 项") }
                check(actions.last() == SourceManagerAction.RemoveFromGroup(listOf(helperId)))
                click("取消选择")
            }
            record("plugin-files-delete-cancel-then-confirm-only-owned-selected-file") {
                select("辅助"); click("删除"); waitFor { exists("删除所选项目") }; click("取消")
                check(tree.entry(auxiliaryId) != null)
                click("删除"); waitFor { exists("删除所选项目") }; click("删除")
                waitFor { !busy && tree.entry(auxiliaryId) == null }
                check(tree.entry(mainId) != null && tree.entry(helperId) != null)
            }
            record("plugin-files-entry-change-is-confirmed-and-preserves-original-plugin") {
                select("工具副本"); click("设为主插件"); waitFor { node { it.text?.toString()?.contains("项目运行入口") == true } != null }
                val target = tree.entries.single { it.name == "工具副本" }.flowId
                check(snapshot.manifest.entryFlowId == mainId)
                val heading = Rect().also(requireNotNull(node { matches(it, "设为主插件") })::getBoundsInScreen)
                click("设为主插件", below = heading.bottom); waitFor { !busy && snapshot.manifest.entryFlowId == target }
                check(tree.entry(mainId) != null && exists("当前主插件")); click("取消选择")
            }
            record("plugin-files-selected-open-and-insert-callback-use-exact-flow-id") {
                select("工具改名"); click("打开"); waitFor { !showing }; check(opened == helperId)
                open(); select("工具改名"); click("插入调用"); waitFor { !showing }; check(inserted == helperId)
            }
            record("plugin-files-group-rename-and-dissolution-preserve-member-plugin") {
                open(); select("工具改名"); click("加入分组"); click("归档"); click("加入")
                waitFor { !busy && tree.entry(helperId)?.group == "归档" }; click("取消选择")
                search("归档"); click("选择分组 归档"); click("重命名")
                fill("插件或分组名称", "归档重命名"); click("确定")
                waitFor { !busy && tree.entry(helperId)?.group == "归档重命名" && exists("新建插件") }
                search("归档重命名"); click("选择分组 归档重命名"); click("删除")
                waitFor { exists("删除所选项目") }; click("删除")
                waitFor { !busy && tree.groups.none { it.name == "归档重命名" } }
                check(tree.entry(helperId)?.group == null && tree.entry(mainId) != null)
                click("关闭"); waitFor { !showing }
            }
            record("plugin-files-large-text-fullscreen-preserves-selection-and-footer") {
                open(big = true); select("工具改名"); click("最大化全屏")
                waitFor { exists("还原窗口") && exists("已选 1 项") }
                check(node { it.contentDescription?.toString() == "选择插件 工具改名" }?.isChecked == true)
                for (label in listOf("打开", "插入调用", "另存为", "重命名", "加入分组", "移出分组", "删除")) check(exists(label))
                capture("large-selected"); click("还原窗口"); waitFor { exists("最大化全屏") && exists("已选 1 项") }
            }
            record("plugin-files-busy-state-freezes-selection-and-write-actions") {
                test.runOnMainSync { busy = true }
                waitFor { disabled("重命名") && disabled("删除") && disabled("取消选择") }
                check(disabled("选择插件 工具改名"))
                test.runOnMainSync { busy = false }
            }
            check(message == null) { "PLUGIN_FILES_QA_STORE_OPERATION_FAILED" }
        } catch (failure: Throwable) { runCatching { capture("failure") }; throw failure }
        finally {
            test.runOnMainSync { showing = false }
            try { runBlocking { withTimeout(10_000) { scope.coroutineContext.job.children.toList().joinAll() } } }
            finally { scope.cancel(); test.waitForIdleSync() }
        }
        return snapshot
    }
}
