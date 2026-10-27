package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme

internal val AccessibilityNodeInfo.qaStateDescription: CharSequence?
    get() = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(this).stateDescription

/** Actual shared widgets on synthetic state; no project execution or system setting changes. */
internal object StudioExperienceAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit,
        branchesOnly: Boolean = false) {
        fun node(label: String): AccessibilityNodeInfo? {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null || !n.refresh()) return null
                if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            return find(test.uiAutomation.rootInActiveWindow)
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < until) {
                if (condition()) return
                SystemClock.sleep(60)
            }
            run {
                val labels = org.json.JSONArray()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null || !n.refresh() || labels.length() >= 60) return
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
                if (!n.refresh()) { candidate = null; return@repeat }
                if (n.isClickable && n.isEnabled) {
                    check(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "EXPERIENCE_CLICK_FAILED:$label" }
                    SystemClock.sleep(150); return
                }
                candidate = n.parent
            }
            error("EXPERIENCE_NOT_CLICKABLE:$label")
        }
        fun bounds(label: String): Rect = Rect().also { requireNotNull(node(label)).getBoundsInScreen(it) }
        if (!branchesOnly) {
        record("editor-sidebar-new-labels-open-existing-tools-and-ai-remains-visible") {
            var variablesOpened = 0
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme {
                        EditorDock("QA_SIDEBAR", {}, {}, initiallyExpanded = true, programNodes = emptyList(),
                            onOpenSettings = {}, onManageVariables = { variablesOpened++ },
                            capabilities = setOf("core.task"), modifier = Modifier.fillMaxSize())
                    }
                }
            }
            val labels = listOf("变量", "触控", "图像", "条件", "循环", "流程", "输出", "函数", "AI")
            await { labels.all { node(it) != null } }
            check(listOf("按键", "判断", "跳转", "调试", "积木库").none { node(it) != null }) { "QA_OLD_SIDEBAR_LABEL_VISIBLE" }
            click("变量"); check(variablesOpened == 1) { "QA_VARIABLE_ROUTE_CHANGED" }
            click("触控"); await { node("单击") != null && node("滑动") != null }
            click("取消"); await { node("单击") == null }
            click("条件"); await { node("否则如果") != null && node("否则") != null }
            check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            await { node("否则如果") == null }
            click("流程"); await { node("流程 · 插件调用") != null && node("调用插件") != null }
            click("取消"); await { node("流程 · 插件调用") == null }
            click("输出"); await { node("弹窗样式") != null && node("对话框") != null }
            click("关闭"); await { node("弹窗样式") == null }
            click("函数"); await { node("关闭函数库") != null }
            click("关闭函数库"); await { node("关闭函数库") == null }
            click("AI"); await { node("AI · 尚未开放") != null }
            click("关闭"); await { node("AI · 尚未开放") == null && node("AI") != null }
        }
        record("three-character-sidebar-action-grows-at-double-font-without-clipping") {
            var clicked = false
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        AutoScriptTheme {
                            Column(Modifier.width(51.dp).height(360.dp).verticalScroll(rememberScrollState())
                                .padding(horizontal = 3.dp, vertical = 2.dp)) {
                                EditorDockAction("变量") {}
                                EditorDockAction("函数库") { clicked = true }
                                EditorDockAction("AI") {}
                            }
                        }
                    }
                }
            }
            await { node("函数库") != null && node("AI") != null }
            val variable = bounds("变量")
            val library = bounds("函数库")
            check(library.height() > variable.height() && variable.bottom <= library.top && library.bottom <= bounds("AI").top) {
                "QA_THREE_CHARACTER_SIDEBAR_CLIPPED"
            }
            click("函数库"); check(clicked) { "QA_THREE_CHARACTER_SIDEBAR_NOT_CLICKABLE" }
        }
        var current by mutableStateOf(true)
        var running by mutableStateOf(true)
        var recent by mutableStateOf(false)
        var selected by mutableStateOf(true)
        record("editor-row-real-widget-distinguishes-executing-recent-next-and-selection") {
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme { Column(Modifier.width(320.dp)) {
                        EditorProgramTreeRow(EditorTreeNode("qa-appearance", "QA_ROW_LABEL", ""),
                            false, selected, current, running, recent, {}, {}, {})
                    } }
                }
            }
            fun state(): String? {
                var n = node("QA_ROW_LABEL")
                repeat(4) {
                    if (n?.refresh() != true) return null
                    if (n?.qaStateDescription != null) return n?.qaStateDescription?.toString()
                    n = n?.parent
                }
                return null
            }
            await { state() == "执行中" }
            val labelBounds = bounds("QA_ROW_LABEL")
            test.runOnMainSync { recent = true }
            await { state() == "刚执行" }
            check(bounds("QA_ROW_LABEL") == labelBounds) { "RECENT_MARKER_MOVED_LABEL" }
            test.runOnMainSync { recent = false; running = false }
            await { state() == "下一步" }
            check(bounds("QA_ROW_LABEL") == labelBounds) { "NEXT_MARKER_MOVED_LABEL" }
            test.runOnMainSync { current = false }
            await {
                var n = node("QA_ROW_LABEL")
                var found = false
                repeat(4) {
                    if (n?.refresh() == true && n?.qaStateDescription?.toString() == "已选中") found = true
                    n = n?.parent
                }
                found
            }
            check(bounds("QA_ROW_LABEL") == labelBounds) { "EXECUTION_MARKER_MOVED_LABEL" }
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
        record("plugin-call-empty-branches-visible-in-main-and-edit-list-with-shared-folding") {
            var id = 0
            val editor = VisualEditorState.create("", "qa-branches-root") { "qa-branch-${++id}" }
            val owner = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("flow.call.branch")),
                com.google.gson.JsonObject().apply {
                    addProperty("targetFlowId", "qa-worker")
                    add("arguments", com.google.gson.JsonObject())
                }))
            val original = editor.currentSource
            var selectedId by mutableStateOf<String?>(owner)
            var selectedBranch by mutableStateOf<EditorBranchSelection?>(null)
            var revision by mutableIntStateOf(0)
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme {
                        val nodes = remember(revision) { editor.programDisplayNodes() }
                        EditorDock("QA_PLUGIN_BRANCHES", {}, {}, initiallyExpanded = true,
                            sourceName = "QA_BRANCHES", programNodes = nodes, programSource = editor.currentSource,
                            programSelectedNodeId = selectedId, programSelectedNodeIds = setOfNotNull(selectedId),
                            onProgramNodeSelected = { selectedBranch = null; editor.selectOnly(it); selectedId = it },
                            programSelectedBranch = selectedBranch,
                            onProgramBranchSelected = { parent, slot ->
                                editor.selectOnly(parent); selectedId = parent
                                selectedBranch = EditorBranchSelection(parent, slot, editor.currentSource)
                            },
                            onInsertIntoBranch = { hint, branch, committed ->
                                check(branch == selectedBranch && branch.matches(editor)) { "QA_BRANCH_SELECTION_STALE" }
                                val contract = requireNotNull(FunctionCatalog.blockKindOf(hint)?.let(BlockCatalog::find)
                                    ?: BlockCatalog.search(legacyDockBlockQuery(hint), setOf("core.task")).singleOrNull()?.contract)
                                val defaults = requireNotNull(initialBlockArguments(contract, emptyList(), emptyList(), "main"))
                                val args = legacyDockBlockArguments(contract, hint, defaults)
                                selectedId = requireNotNull(editor.insertBlock(contract, args, intoChildBlockName = branch.slot))
                                selectedBranch = null; revision++
                                committed()
                            },
                            capabilities = setOf("core.task"), stepEnabled = selectedBranch == null,
                            onOpenSettings = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            await { node("成功分支") != null && node("失败分支") != null }
            check(bounds("成功分支").bottom <= bounds("失败分支").top) { "QA_PLUGIN_BRANCH_ORDER_WRONG" }
            test.uiAutomation.takeScreenshot()?.let { screenshot ->
                try {
                    java.io.File(test.targetContext.cacheDir, "qa-plugin-branches.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
            click("编辑"); await { node("编辑积木") != null }
            await { node("成功分支") != null && node("失败分支") != null }
            check(node("已选 1 项 · 共 1 行") != null) { "QA_PLUGIN_HEADINGS_COUNTED_AS_NODES" }
            click("折叠全部")
            await { node("成功分支") == null && node("失败分支") == null }
            click("展开全部")
            await { node("成功分支") != null && node("失败分支") != null }
            click("关闭"); await { node("编辑积木") == null && node("成功分支") != null }
            check(selectedId == owner && editor.currentSource == original && editor.rows.size == 1) {
                "QA_PLUGIN_BRANCH_DISPLAY_MUTATED_DRAFT"
            }
            fun isSelected(label: String): Boolean {
                var candidate = node(label)
                repeat(5) {
                    if (candidate?.refresh() != true) return false
                    if (candidate?.isSelected == true || candidate?.qaStateDescription?.toString() == "已选中加入位置") return true
                    candidate = candidate?.parent
                }
                return false
            }
            click("成功分支"); await { isSelected("成功分支") }
            check(selectedBranch?.slot == "success" && editor.currentSource == original) { "QA_SUCCESS_BRANCH_NOT_SELECTABLE" }
            click("失败分支"); await { isSelected("失败分支") && !isSelected("成功分支") }
            test.uiAutomation.takeScreenshot()?.let { screenshot ->
                try {
                    java.io.File(test.targetContext.cacheDir, "qa-plugin-branch-selected.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
            click("编辑"); await { node("加入失败分支 · 共 1 行") != null }
            click("成功分支"); await { node("加入成功分支 · 共 1 行") != null }
            click("关闭"); await { node("编辑积木") == null }
            for (slot in listOf("success", "failure")) {
                click(childBlockLabel(slot)); await { selectedBranch?.slot == slot }
                click("流程"); click("等待时间"); click("加入")
                await { editor.rows.count { it.kind == "task.sleep" && it.childSlot == slot } == 1 }
                check(node("加入位置") == null) { "QA_SELECTED_BRANCH_IGNORED_BY_INSERTION" }
            }
            check(editor.rows.size == 3) { "QA_BRANCH_INSERTION_CREATED_EXTRA_NODES" }
            check(editor.undo() && editor.rows.size == 2 && editor.undo() && editor.currentSource == original) {
                "QA_BRANCH_INSERTION_UNDO_FAILED"
            }
        }
    }
}
