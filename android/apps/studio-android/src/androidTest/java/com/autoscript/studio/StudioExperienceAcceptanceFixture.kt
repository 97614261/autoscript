package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme

/** Actual shared widgets on synthetic state; no project execution or system setting changes. */
internal object StudioExperienceAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        fun node(label: String): AccessibilityNodeInfo? {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            return find(test.uiAutomation.rootInActiveWindow)
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5_000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(60)
            if (!condition()) {
                val labels = org.json.JSONArray()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null || labels.length() >= 60) return
                    val label = (n.text ?: n.contentDescription)?.toString()
                    if (!label.isNullOrBlank()) labels.put(org.json.JSONObject().put("label", label.take(64))
                        .put("bounds", Rect().also(n::getBoundsInScreen).toShortString()))
                    for (i in 0 until n.childCount) collect(n.getChild(i))
                }
                val qaRoot = test.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == test.targetContext.packageName }
                collect(qaRoot)
                test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_PUBLIC_EXPERIENCE_TREE:$labels\n") })
                (if (qaRoot != null) test.uiAutomation.takeScreenshot() else null)?.let { screenshot ->
                    try {
                        val file = java.io.File(test.targetContext.cacheDir, "qa-experience-failure-${java.util.UUID.randomUUID()}.png")
                        file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_PUBLIC_SCREENSHOT:${file.name}\n") })
                    } finally { screenshot.recycle() }
                }
                error("EXPERIENCE_UI_WAIT_FAILED")
            }
        }
        fun click(label: String) {
            await { node(label) != null }
            var candidate = node(label)
            repeat(6) {
                val n = candidate ?: return@repeat
                if (n.isClickable) {
                    check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "EXPERIENCE_CLICK_FAILED:$label" }
                    SystemClock.sleep(150); return
                }
                candidate = n.parent
            }
            error("EXPERIENCE_NOT_CLICKABLE:$label")
        }
        fun bounds(label: String): Rect = Rect().also { requireNotNull(node(label)).getBoundsInScreen(it) }
        var current by mutableStateOf(true)
        var running by mutableStateOf(true)
        var recent by mutableStateOf(false)
        var selected by mutableStateOf(true)
        record("editor-row-real-widget-distinguishes-executing-recent-next-and-selection") {
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme { Column(Modifier.width(320.dp)) {
                        EditorProgramTreeRow(EditorTreeNode("qa-appearance", "QA_ROW_LABEL", "", EditorToolPanel.DEBUG),
                            false, selected, current, running, recent, {}, {}, {})
                    } }
                }
            }
            fun state(): String? {
                var n = node("▶ ${editorExecutionEmphasis(current, running, recent).label}  QA_ROW_LABEL")
                repeat(4) { if (n?.stateDescription != null) return n?.stateDescription?.toString(); n = n?.parent }
                return null
            }
            await { state() == "执行中" }
            test.runOnMainSync { recent = true }
            await { state() == "刚执行" }
            test.runOnMainSync { recent = false; running = false }
            await { state() == "下一步" }
            test.runOnMainSync { current = false }
            await {
                var n = node("QA_ROW_LABEL")
                var found = false
                repeat(4) { if (n?.stateDescription?.toString() == "已选中") found = true; n = n?.parent }
                found
            }
        }
        var showPanel by mutableStateOf(true)
        var fullScreen by mutableStateOf(false)
        var showConfirm by mutableStateOf(false)
        var confirmed = 0
        var confirmFontScale = 0f
        record("shared-panel-double-font-fullscreen-and-confirm-actions-retain-state") {
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        AutoScriptTheme {
                            Text("QA_SHARED_BACKGROUND")
                            if (showPanel) StudioPanelDialog("QA_SHARED_TITLE", { showPanel = false },
                                confirmLabel = "QA_SHARED_DONE", fullScreen = fullScreen,
                                onToggleFullScreen = { fullScreen = !fullScreen }) {
                                Text("QA_RETAINED_CONTENT", Modifier.padding(12.dp))
                            }
                            if (showConfirm) StudioConfirmDialog({ showConfirm = false },
                                title = {
                                    val scale = LocalDensity.current.fontScale
                                    SideEffect { confirmFontScale = scale }
                                    Text("QA_CONFIRM_TITLE")
                                }, text = { Text("公开合成确认内容，不执行项目操作") },
                                actions = listOf(StudioConfirmAction("QA_DISABLED", { confirmed += 100 }, enabled = false),
                                    StudioConfirmAction("QA_CONFIRM", { confirmed++; showConfirm = false })))
                        }
                    }
                }
            }
            await { node("QA_SHARED_DONE") != null }
            val title = bounds("QA_SHARED_TITLE")
            val body = bounds("QA_RETAINED_CONTENT")
            val footer = bounds("QA_SHARED_DONE")
            check(title.bottom <= body.top && body.bottom <= footer.top) { "SHARED_PANEL_DOUBLE_FONT_OVERLAP" }
            click("最大化全屏"); await { node("还原窗口") != null }
            check(node("QA_RETAINED_CONTENT") != null) { "SHARED_PANEL_LOST_STATE" }
            click("还原窗口"); await { node("最大化全屏") != null }
            click("QA_SHARED_DONE")
            await { node("QA_SHARED_DONE") == null }
            test.runOnMainSync { showConfirm = true }
            await { node("QA_CONFIRM") != null }
            test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_CONFIRM_METRICS:fontScale=$confirmFontScale;disabled=${bounds("QA_DISABLED")};confirm=${bounds("QA_CONFIRM")}") })
            check(confirmFontScale == 2f) { "SHARED_DIALOG_RESET_CALLER_FONT_SCALE" }
            check(bounds("QA_DISABLED").bottom <= bounds("QA_CONFIRM").top) { "SHARED_CONFIRM_ACTIONS_NOT_STACKED" }
            node("QA_DISABLED")?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            check(confirmed == 0) { "SHARED_CONFIRM_DISABLED_ACTION_RAN" }
            click("QA_CONFIRM"); await { node("QA_CONFIRM") == null }
            check(confirmed == 1) { "SHARED_CONFIRM_CALLBACK_FAILED" }
        }
        record("shared-panel-repeated-open-maximize-close-keeps-single-owned-window") {
            repeat(8) { iteration ->
                test.runOnMainSync { fullScreen = false; showPanel = true }
                await { node("QA_SHARED_DONE") != null }
                click("最大化全屏"); await { node("还原窗口") != null }
                check(node("QA_RETAINED_CONTENT") != null) { "QA_REPEAT_PANEL_CONTENT_LOST" }
                click("还原窗口"); await { node("最大化全屏") != null }
                click("QA_SHARED_DONE"); await { node("QA_SHARED_DONE") == null }
                check(node("QA_SHARED_BACKGROUND") != null) { "QA_REPEAT_PANEL_OBSCURED_BACKGROUND" }
                test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_PANEL_REPEAT:${iteration + 1}\n") })
            }
        }
        record("real-editor-deep-tree-long-label-edit-list-fold-and-restore") {
            var selectedId by mutableStateOf<String?>(null)
            val nodes = (0..24).map { depth -> EditorProgramNode("qa-ui-deep-$depth",
                if (depth == 24) "QA_DEEP_LEAF · " + "公开长参数".repeat(24) else "QA_DEEP_LEVEL_$depth",
                if (depth == 24) "task.noop" else "control.repeat", depth,
                if (depth == 24) emptyList() else listOf("body")) }
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme {
                        EditorDock("QA_DEPTH_PREVIEW", {}, {}, initiallyExpanded = true, sourceName = "QA_LONG_PLUGIN_".repeat(12),
                            programNodes = nodes, programSelectedNodeId = selectedId,
                            programSelectedNodeIds = setOfNotNull(selectedId), onProgramNodeSelected = { selectedId = it },
                            onOpenSettings = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            await { node("打开项目设置") != null }
            test.runOnMainSync { selectedId = "qa-ui-deep-24" }
            // The production compact renderer separates title/details with two spaces.
            val label = nodes.last().label.replace(" · ", "  · ")
            await { node(label) != null }
            val leaf = bounds(label)
            check(leaf.width() > 0 && leaf.height() > 0) { "QA_DEEP_ROW_CROPPED_AWAY" }
            click("编辑"); await { node("编辑积木") != null }
            click("折叠全部")
            await { node(label) == null }
            check(selectedId == "qa-ui-deep-0") { "QA_FOLD_SELECTED_HIDDEN_DESCENDANT" }
            click("展开全部")
            test.runOnMainSync { selectedId = "qa-ui-deep-24" }
            await { node(label) != null }
            click("最大化全屏"); await { node("还原窗口") != null }
            check(selectedId == "qa-ui-deep-24") { "QA_DEEP_SELECTION_LOST" }
            click("还原窗口"); click("关闭")
            await { node("编辑积木") == null && node("打开项目设置") != null }
            check(selectedId == "qa-ui-deep-24") { "QA_DEEP_CLOSE_CHANGED_SELECTION" }
        }
    }
}
