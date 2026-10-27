package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.core.model.RuntimeRootState
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Presentation-only synthetic cards. All callbacks are counters, never real project actions. */
internal object ProjectWorkspaceAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        val clicks = ConcurrentHashMap<String, AtomicInteger>()
        fun action(name: String) { clicks.getOrPut(name) { AtomicInteger() }.incrementAndGet() }
        var expanded by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var editEnabled by mutableStateOf(true)
        var width by mutableIntStateOf(360)
        var fontScale by mutableFloatStateOf(1f)
        var query by mutableStateOf("")
        var order by mutableStateOf(ProjectListOrder.UPDATED)
        val sample = ProjectSummary("project-01234567-89ab-cdef-0123-456789abcdef", "每日签到 · 合成演示", ProjectSourceMode.VISUAL,
            720, 1280, 1_792_108_800_000, 1_792_195_200_000, null)
        test.runOnMainSync {
            activity.setContent {
                val deviceDensity = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale)) {
                    AutoScriptTheme {
                        Column(Modifier.width(width.dp).fillMaxHeight().background(Color(0xFFF6F5FA))) {
                            WorkspaceHeader(enabled, query, order, { query = it }, { order = it },
                                { action("new") }, { action("import") }, { action("backups") }, { action("refresh") })
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProjectRow(sample, expanded, enabled, editEnabled, false,
                                    { expanded = !expanded }, { action("edit") }, { action("run") }, { action("settings") },
                                    { action("ui") }, { action("package") }, { action("backup") }, { action("rename") }, { action("delete") })
                                if (!expanded) repeat(2) { index ->
                                    ProjectRow(sample.copy(projectId = "qa-$index", name = listOf("图像查找流程", "定时任务")[index]),
                                        false, false, false, false, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        fun node(label: String): AccessibilityNodeInfo? {
            fun walk(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null || !current.refresh()) return null
                if (current.text?.toString() == label || current.contentDescription?.toString() == label) return current
                for (i in 0 until current.childCount) walk(current.getChild(i))?.let { return it }
                return null
            }
            return walk(test.uiAutomation.rootInActiveWindow)
        }
        fun settle(label: String) {
            val end = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < end) {
                // Do not invalidate a successful observation with a second tree read during recomposition.
                if (node(label) != null) return
                SystemClock.sleep(80)
            }
            test.sendStatus(1, android.os.Bundle().apply {
                putString("stream", "QA_PROJECT_LAYOUT:" + org.json.JSONObject().put("expected", label)
                    .put("expanded", expanded).put("idVisible", node("项目 ID") != null)
                    .put("expandVisible", node("展开项目详情") != null).put("collapseVisible", node("收起项目详情") != null)
                    .toString() + "\n")
            })
            error("PROJECT_UI_MISSING:$label")
        }
        fun settleAbsent(label: String) {
            val end = SystemClock.elapsedRealtime() + 4_000
            while (node(label) != null && SystemClock.elapsedRealtime() < end) SystemClock.sleep(80)
            check(node(label) == null) { "PROJECT_UI_STILL_VISIBLE:$label" }
        }
        fun click(label: String) {
            settle(label)
            val end = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < end) {
                var candidate = node(label)
                repeat(6) {
                    val current = candidate ?: return@repeat
                    // A refreshed text node can still return a cached, previously disabled parent.
                    if (!current.refresh()) { candidate = null; return@repeat }
                    if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        SystemClock.sleep(180)
                        return
                    }
                    candidate = current.parent
                }
                SystemClock.sleep(80)
            }
            val ancestors = org.json.JSONArray()
            var current = node(label)
            repeat(6) {
                current?.let { item ->
                    item.refresh()
                    val rect = android.graphics.Rect().also(item::getBoundsInScreen)
                    ancestors.put(org.json.JSONObject().put("clickable", item.isClickable).put("enabled", item.isEnabled)
                        .put("visible", item.isVisibleToUser).put("bounds", rect.flattenToString()))
                    current = item.parent
                }
            }
            test.sendStatus(1, android.os.Bundle().apply {
                putString("stream", "QA_PROJECT_CLICK_FAILURE:" + org.json.JSONObject().put("label", label)
                    .put("ancestors", ancestors) + "\n")
            })
            screenshotOnClickFailure(activity, label)
            error("PROJECT_UI_NOT_CLICKABLE_OR_ENABLED:$label")
        }
        fun screenshot(name: String) = test.runOnMainSync {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                File(activity.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        fun checkActionLabelWidths() {
            val density = activity.resources.displayMetrics.density
            val paint = android.graphics.Paint().apply { textSize = 11 * density * fontScale }
            for (label in listOf("启动", "编辑", "界面", "设置", "备份", "重命名", "打包", "删除")) {
                val bounds = android.graphics.Rect()
                val actionNode = requireNotNull(node("项目操作：$label"))
                actionNode.getBoundsInScreen(bounds)
                if (!actionNode.isVisibleToUser || bounds.width() + 1 < paint.measureText(label) + 28 * density) {
                    test.sendStatus(1, android.os.Bundle().apply {
                        putString("stream", "QA_PROJECT_ACTION:" + org.json.JSONObject().put("label", label)
                            .put("widthPx", bounds.width()).put("expectedMinimumPx", paint.measureText(label) + 28 * density)
                            .put("visible", actionNode.isVisibleToUser).put("fontScale", fontScale).toString() + "\n")
                    })
                }
                check(actionNode.isVisibleToUser && bounds.width() + 1 >= paint.measureText(label) + 28 * density) {
                    "PROJECT_ACTION_LABEL_CLIPPED:$label"
                }
            }
        }
        record("project-toolbar-and-collapsed-common-actions") {
            settle("编辑")
            check(node("项目") == null) { "PROJECT_DECORATIVE_TITLE_REMAINED" }
            check(node("项目操作：删除") != null) { "PROJECT_DELETE_ACTION_MISSING" }
            for (label in listOf("新建", "导入", "备份", "刷新", "编辑", "启动")) click(label)
            for (key in listOf("new", "import", "backups", "refresh", "edit", "run")) check(clicks[key]?.get() == 1) { "PROJECT_CALLBACK:$key" }
            for (label in listOf("界面", "设置", "备份", "重命名", "打包", "删除")) {
                check(node("项目操作：$label") != null) { "PROJECT_COMMON_ACTION_MISSING:$label" }
            }
            for ((label, key) in listOf(
                "界面" to "ui", "设置" to "settings", "备份" to "backup",
                "重命名" to "rename", "打包" to "package", "删除" to "delete",
            )) {
                click("项目操作：$label")
                check(clicks[key]?.get() == 1) { "PROJECT_CALLBACK:$key" }
            }
            val runBounds = android.graphics.Rect()
            requireNotNull(node("启动")).getBoundsInScreen(runBounds)
            val editBounds = android.graphics.Rect()
            requireNotNull(node("编辑")).getBoundsInScreen(editBounds)
            check(runBounds.left < editBounds.left) { "PROJECT_RUN_ACTION_NOT_FIRST" }
            check(kotlin.math.abs(editBounds.top - runBounds.top) <= 28 * activity.resources.displayMetrics.density) { "PROJECT_ACTIONS_NOT_IN_COMPACT_STRIP" }
            check(editBounds.width() <= 96 * activity.resources.displayMetrics.density && runBounds.width() <= 96 * activity.resources.displayMetrics.density) { "PROJECT_ACTIONS_STRETCHED" }
            screenshot("project-page-compact.png")
        }
        record("project-expanded-tools-and-sort") {
            click("收起项目详情")
            check(!expanded) { "PROJECT_COLLAPSE_CALLBACK_FAILED" }
            settleAbsent("项目 ID")
            click("展开项目详情")
            check(expanded) { "PROJECT_EXPAND_CALLBACK_FAILED" }
            settle("项目 ID")
            settle(compactProjectId(sample.projectId))
            check(node(sample.projectId) == null) { "PROJECT_FULL_ID_VISIBLE_BY_DEFAULT" }
            click("查看完整项目ID")
            settle(sample.projectId)
            settle("创建 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sample.createdAt))}")
            check(node("复制项目ID") != null) { "PROJECT_FULL_ID_COPY_MISSING" }
            click("收起完整项目ID")
            settleAbsent(sample.projectId)
            click("最近修改"); click("项目名称")
            check(order == ProjectListOrder.NAME) { "PROJECT_ORDER_NOT_SELECTED" }
            test.runOnMainSync { query = "公开搜索" }
            settle("清空搜索"); click("清空搜索")
            check(query.isEmpty()) { "PROJECT_SEARCH_NOT_CLEARED" }
            screenshot("project-page-expanded.png")
        }
        record("project-reference-two-row-grid-and-compact-metadata") {
            for (cardWidth in listOf(360, 320)) {
                test.runOnMainSync { width = cardWidth; fontScale = 1f }
                test.waitForIdleSync()
                SystemClock.sleep(180)
                checkActionLabelWidths()
                val actionBounds = listOf("启动", "编辑", "界面", "设置", "备份", "重命名", "打包", "删除").map { label ->
                    android.graphics.Rect().also { requireNotNull(node("项目操作：$label")).getBoundsInScreen(it) }
                }
                val density = activity.resources.displayMetrics.density
                check(actionBounds.take(4).maxOf { it.top } - actionBounds.take(4).minOf { it.top } <= 2 * density &&
                    actionBounds.drop(4).maxOf { it.top } - actionBounds.drop(4).minOf { it.top } <= 2 * density &&
                    actionBounds[4].top >= actionBounds[0].bottom) {
                    "PROJECT_REFERENCE_GRID_NOT_TWO_ROWS"
                }
                val idBounds = android.graphics.Rect().also { requireNotNull(node("项目 ID")).getBoundsInScreen(it) }
                val timeBounds = listOf("创建" to sample.createdAt, "更新" to sample.updatedAt).map { (label, timestamp) ->
                    val text = "$label ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))}"
                    settle(text)
                    android.graphics.Rect().also { requireNotNull(node(text)).getBoundsInScreen(it) }
                }
                check(timeBounds.map { it.top }.distinct().size == 1 && timeBounds.maxOf { it.bottom } - idBounds.top <= 48 * density) {
                    "PROJECT_METADATA_NOT_COMPACT"
                }
                screenshot("project-page-compact-$cardWidth.png")
            }
        }
        record("project-narrow-large-font-and-session-lock") {
            test.runOnMainSync { width = 320; fontScale = 1.6f; expanded = true; enabled = false; editEnabled = true }
            settle("编辑")
            test.waitForIdleSync()
            SystemClock.sleep(180)
            screenshot("project-page-large-font.png")
            checkActionLabelWidths()
            click("编辑")
            check(clicks["edit"]?.get() == 2) { "PROJECT_EDIT_RESTORE_DISABLED" }
            for (label in listOf("编辑", "启动")) {
                val bounds = android.graphics.Rect()
                requireNotNull(node(label)).getBoundsInScreen(bounds)
                check(bounds.width() <= 150 * activity.resources.displayMetrics.density) { "PROJECT_LARGE_FONT_GRID_WIDTH:$label" }
            }
            for (label in listOf("新建", "导入", "备份", "刷新")) {
                val bounds = android.graphics.Rect()
                requireNotNull(node(label)).getBoundsInScreen(bounds)
                check(bounds.width() >= bounds.height()) { "PROJECT_TOOLBAR_VERTICAL_TEXT:$label" }
            }
            val before = clicks["run"]?.get()
            node("启动")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(150)
            check(clicks["run"]?.get() == before) { "PROJECT_BUSY_RUN_ALLOWED" }
            screenshot("project-page-large-font.png")
        }
        var profileFontScale by mutableFloatStateOf(1f)
        var profileWidth by mutableIntStateOf(360)
        record("profile-sections-real-entry-callbacks-and-single-runtime-summary") {
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, profileFontScale)) {
                        AutoScriptTheme {
                            StudioProfilePage(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
                                rootState = RuntimeRootState.READY, engineState = RuntimeEngineState.PAUSED),
                                { action("profile-${it.name}") }, Modifier.width(profileWidth.dp).fillMaxHeight())
                        }
                    }
                }
            }
            settle("设备与运行")
            check(node("我的") == null) { "PROFILE_DECORATIVE_TITLE_REMAINED" }
            fun countRuntimeEntries(current: AccessibilityNodeInfo?): Int {
                if (current == null) return 0
                var count = if (current.contentDescription?.toString() == StudioEntry.ENVIRONMENT.title) 1 else 0
                for (i in 0 until current.childCount) count += countRuntimeEntries(current.getChild(i))
                return count
            }
            check(countRuntimeEntries(test.uiAutomation.rootInActiveWindow) == 1) { "PROFILE_DUPLICATE_RUNTIME_SUMMARY" }
            for (entry in studioProfileSections.flatMap { it.entries }) {
                click(entry.title)
                check(clicks["profile-${entry.name}"]?.get() == 1) { "PROFILE_CALLBACK:${entry.name}" }
            }
            check(node("使用指南") == null) { "PROFILE_RETIRED_GUIDE_VISIBLE" }
            check(node("我的项目") == null) { "PROFILE_DUPLICATE_PROJECT_SHORTCUT" }
            screenshot("profile-page-compact.png")
        }
        record("profile-narrow-large-font-scrolls-without-changing-system-font") {
            test.runOnMainSync { profileWidth = 320; profileFontScale = 1.6f }
            settle("软件权限")
            click("软件权限")
            check(clicks["profile-PERMISSIONS"]?.get() == 2) { "PROFILE_LARGE_FONT_CALLBACK" }
            fun scrollForward(): Boolean {
                fun find(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (current == null) return null
                    if (current.isScrollable) return current
                    for (i in 0 until current.childCount) find(current.getChild(i))?.let { return it }
                    return null
                }
                return find(test.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            }
            repeat(4) { if (node("关于智构") == null) { check(scrollForward()) { "PROFILE_LARGE_FONT_CANNOT_SCROLL" }; SystemClock.sleep(180) } }
            settle("关于智构")
            click("关于智构")
            check(clicks["profile-ABOUT"]?.get() == 2) { "PROFILE_LARGE_FONT_ABOUT_MISSING" }
            screenshot("profile-page-large-font.png")
        }
        record("about-only-version-and-existing-update-controls-back-callback") {
            var returned = false
            test.runOnMainSync {
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    val updater = remember { StudioUpdateController(activity.applicationContext, scope) }
                    DisposableEffect(updater) { onDispose { updater.close() } }
                    CompositionLocalProvider(LocalStudioUpdater provides updater) {
                        AutoScriptTheme { StudioAboutScreen(onBack = { returned = true }) }
                    }
                }
            }
            settle("当前版本"); settle("应用更新"); settle("检查更新"); settle("更新地址")
            val installedVersion = test.targetContext.packageManager.getPackageInfo(test.targetContext.packageName, 0).versionName
            settle("$installedVersion (${BuildConfig.VERSION_CODE})")
            for (removed in listOf("技术架构", "运行边界", "本地存储", "协议与隐私说明", "函数文档", "使用指南"))
                check(node(removed) == null) { "ABOUT_REMOVED_CONTENT:$removed" }
            click("返回我的"); check(returned) { "ABOUT_BACK_NOT_HANDLED" }
        }
        var placeholderFiltered by mutableStateOf(false)
        var placeholderEnabled by mutableStateOf(false)
        record("project-empty-and-no-match-states-double-font-preserve-disabled-actions") {
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        AutoScriptTheme {
                            Column(Modifier.width(280.dp)) {
                                ProjectListPlaceholder(placeholderFiltered, placeholderEnabled, { action("placeholder") })
                            }
                        }
                    }
                }
            }
            settle("还没有项目"); settle("新建项目")
            node("新建项目")?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(100)
            check(clicks["placeholder"] == null) { "PROJECT_EMPTY_DISABLED_ACTION_RAN" }
            test.runOnMainSync { placeholderEnabled = true }
            test.waitForIdleSync()
            click("新建项目")
            check(clicks["placeholder"]?.get() == 1) { "PROJECT_EMPTY_ACTION_FAILED" }
            test.runOnMainSync { placeholderFiltered = true }
            settle("没有匹配项目"); click("清空搜索")
            check(clicks["placeholder"]?.get() == 2) { "PROJECT_FILTER_CLEAR_FAILED" }
            screenshot("project-empty-large-font.png")
        }
        record("startup-reference-artwork-portrait-and-landscape") {
            for ((artWidth, artHeight) in listOf(360 to 580, 360 to 240)) {
                test.runOnMainSync {
                    activity.setContent {
                        AutoScriptTheme { StudioLaunchArtwork(Modifier.size(artWidth.dp, artHeight.dp), .5f) }
                    }
                }
                settle("智构")
                settle("让 自 动 化 更 简 单")
                test.waitForIdleSync()
                screenshot("startup-reference-${artWidth}x$artHeight.png")
            }
        }
        record("reference-project-and-profile-full-shell-synthetic-screenshots") {
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme {
                        Column(Modifier.fillMaxSize()) {
                            StudioPageBackdrop(Modifier.weight(1f).fillMaxWidth()) {
                                Column {
                                    WorkspaceHeader(true, "", ProjectListOrder.UPDATED, {}, {}, {}, {}, {}, {})
                                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        items(5) { index ->
                                            ProjectRow(sample.copy(projectId = "project-public-$index",
                                                name = listOf("新测试", "公开合成验收", "公开合成验收更新", "图像识别示例", "定时任务")[index]),
                                                index == 0, true, true, false, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                        }
                                    }
                                }
                            }
                            StudioBottomNavigation(StudioDestination.WORKSPACE) {}
                        }
                    }
                }
            }
            settle("新测试")
            test.waitForIdleSync()
            screenshot("reference-project.png")
            test.runOnMainSync {
                activity.setContent {
                    AutoScriptTheme {
                        Column(Modifier.fillMaxSize()) {
                            StudioProfilePage(RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED,
                                rootState = RuntimeRootState.READY, engineState = RuntimeEngineState.IDLE), {},
                                Modifier.weight(1f).fillMaxWidth())
                            StudioBottomNavigation(StudioDestination.PROFILE) {}
                        }
                    }
                }
            }
            settle("软件权限")
            test.waitForIdleSync()
            screenshot("reference-profile.png")
        }
    }

    private fun screenshotOnClickFailure(activity: ComponentActivity, label: String) {
        // This fixture contains only fixed synthetic cards and callback counters.
        if (label != "新建项目") return
        activity.runOnUiThread {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                File(activity.cacheDir, "project-placeholder-click-failure.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }
}
