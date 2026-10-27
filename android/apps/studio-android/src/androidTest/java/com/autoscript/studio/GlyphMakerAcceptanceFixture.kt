package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import java.io.File
import kotlin.math.roundToInt

/** Only the harness's newly owned project and a public synthetic 1.1 image are used. */
internal object GlyphMakerAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        var snapshot by mutableStateOf(initial)
        var showing by mutableStateOf(false)
        var home by mutableStateOf(true)
        var showCapture by mutableStateOf(false)
        var useSaved by mutableStateOf<Pair<ImageToolOcrConfig, ImageToolCodeGen.Roi?>?>(null)
        var proofAccepted by mutableStateOf(false)
        var layoutProbe by mutableStateOf(false)
        var previewFontScale by mutableFloatStateOf(1f)
        val originalOrientation = activity.requestedOrientation
        // A full-screen portrait image focused on a thin ROI used to draw over both
        // the source actions and the selection toolbar. This color is absent from UI.
        val probeColor = 0xffff00ff.toInt()
        val portrait = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply { eraseColor(probeColor) }
        val scale = 8
        val rows = listOf("#...#", "#...#", "#.#.#")
        val bitmap = Bitmap.createBitmap(5 * scale, 3 * scale, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y,
                if (rows[y / scale][x / scale] == '#') -1 else 0xff000000.toInt())
        }
        fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null) return null
                // Compose may keep a node identity while its text/enabled state changes.
                // Read fresh node data rather than the UiAutomation accessibility cache.
                if (!current.refresh()) return null
                if (predicate(current)) return current
                for (index in 0 until current.childCount) walk(current.getChild(index))?.let { return it }
                return null
            }
            for (window in test.uiAutomation.windows.sortedByDescending { it.layer }) {
                val root = window.root ?: continue
                // Text selection handles have their own app-owned windows above the form.
                if (root.packageName?.toString() == test.targetContext.packageName) walk(root)?.let { return it }
                root.recycle()
            }
            return null
        }
        fun waitFor(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 12_000
            while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(60)
            check(predicate()) { "GLYPH_QA_TIMEOUT" }
        }
        fun exists(label: String) = node { it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label } != null
        fun input(label: String): AccessibilityNodeInfo? {
            var current = node { it.contentDescription?.toString() == label }
            // Focused BasicTextField may move its description into a child semantics node.
            while (current != null && !current.isEditable) current = current.parent
            return current
        }
        fun revealControl(label: String, editable: Boolean = false) {
            fun found() = if (editable) input(label) != null else
                node { it.text?.toString() == label || it.contentDescription?.toString() == label } != null
            if (editable && !found()) {
                for (forward in listOf(false, true)) {
                    repeat(12) {
                        if (found()) return
                        if (!scrollOwnedQaForm(test, forward, ::found)) return
                    }
                }
                return
            }
            if (!found()) {
                // Only scroll the owned vertical body, never the horizontal tool strips.
                for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    repeat(8) {
                        if (!found()) {
                            node { it.className?.toString() == "android.widget.ScrollView" && it.isScrollable }
                                ?.performAction(direction)
                            SystemClock.sleep(90)
                        }
                    }
                    if (found()) break
                }
            }
        }
        fun click(label: String, reveal: Boolean = true) {
            if (reveal) revealControl(label)
            waitFor { exists(label) }
            try { test.uiAutomation.waitForIdle(150, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
            var target: AccessibilityNodeInfo? = null
            waitFor {
                var current = node { !it.isEditable && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                while (current != null && !current.isClickable) current = current.parent
                target = current
                current?.refresh() == true && current.isEnabled
            }
            if (reveal) {
                // A stroke scrolls the canvas into view; reveal its preceding toolbar before tapping it.
                requireNotNull(target).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                test.waitForIdleSync()
                try { test.uiAutomation.waitForIdle(150, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
            }
            val bounds = Rect().also(requireNotNull(target)::getBoundsInScreen)
            check(!bounds.isEmpty) { "GLYPH_QA_CLICK_BOUNDS:$label" }
            if (reveal) {
                check(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "GLYPH_QA_CLICK_ACTION:$label" }
                test.waitForIdleSync()
                return
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(20)
            }
        }
        fun fill(label: String, text: String) {
            revealControl(label, editable = true)
            waitFor { input(label) != null }
            input(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            check(input(label)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }) == true) { "GLYPH_QA_FIELD:$label" }
            waitFor { input(label)?.text?.toString() == text }
        }
        fun stroke(label: String, startX: Float, startY: Float, endX: Float, endY: Float, cancel: Boolean = false,
            whilePressed: () -> Unit = {}) {
            val target = requireNotNull(node { it.contentDescription?.toString() == label })
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            test.waitForIdleSync()
            val bounds = Rect().also { requireNotNull(node { it.contentDescription?.toString() == label }).getBoundsInScreen(it) }
            val started = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, y: Float) {
                MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, bounds.left + x * bounds.width(), bounds.top + y * bounds.height(), 0).also {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { check(test.uiAutomation.injectInputEvent(it, true)) } finally { it.recycle() }
                }
            }
            event(MotionEvent.ACTION_DOWN, startX, startY)
            try {
                for (i in 1..5) { SystemClock.sleep(25); event(MotionEvent.ACTION_MOVE, startX + (endX - startX) * i / 5, startY + (endY - startY) * i / 5) }
                whilePressed()
            } finally { event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, endY) }
            test.waitForIdleSync()
        }
        fun capture(tag: String = "") {
            val directory = activity.externalCacheDir ?: return
            val image = test.uiAutomation.takeScreenshot() ?: return
            try { File(directory, "qa-glyph-maker-20261019${if (tag.isEmpty()) "" else "-$tag"}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { image.recycle() }
        }
        fun captureOwnedControls() {
            val output = StringBuilder()
            if (android.os.Build.VERSION.SDK_INT >= 30) test.runOnMainSync {
                for (root in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    val params = root.layoutParams as? android.view.WindowManager.LayoutParams
                    val insets = root.rootWindowInsets
                    val visible = Rect().also(root::getWindowVisibleDisplayFrame)
                    output.appendLine("native soft=${params?.softInputMode} flags=${params?.flags} type=${params?.type} height=${root.height} visible=$visible ime=${insets?.getInsets(android.view.WindowInsets.Type.ime())} imeVisible=${insets?.isVisible(android.view.WindowInsets.Type.ime())}")
                }
            }
            fun walk(current: AccessibilityNodeInfo?, depth: Int) {
                if (current == null || depth > 32) return
                val bounds = Rect().also(current::getBoundsInScreen)
                output.appendLine("${" ".repeat(depth)}${current.className} text=${current.text} desc=${current.contentDescription} editable=${current.isEditable} bounds=$bounds")
                for (index in 0 until current.childCount) walk(current.getChild(index), depth + 1)
            }
            for (window in test.uiAutomation.windows.sortedByDescending { it.layer }) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == test.targetContext.packageName) {
                    output.appendLine("window ${window.type} layer=${window.layer}")
                    walk(root, 0)
                }
            }
            activity.externalCacheDir?.let { File(it, "qa-glyph-maker-controls.txt").writeText(output.toString()) }
        }
        fun paintedWidth(): Int {
            val bounds = Rect().also { requireNotNull(node { it.contentDescription?.toString() == "修点字形" }).getBoundsInScreen(it) }
            val image = requireNotNull(test.uiAutomation.takeScreenshot())
            try {
                check(bounds.intersect(0, 0, image.width, image.height) && bounds.width() > 0)
                // This fixture's public '1' is a solid bar. Measure the rendered glyph,
                // not a status label that a small viewport may omit from accessibility.
                return (bounds.left until bounds.right).count { x ->
                    val pixel = image.getPixel(x, bounds.centerY())
                    (pixel ushr 16 and 255) < 128 && (pixel ushr 8 and 255) < 128 && (pixel and 255) < 128
                }
            } finally { image.recycle() }
        }
        test.runOnMainSync {
            activity.setContent { AutoScriptTheme {
                if (home) VisualImageHomeDialog(onDismiss = { home = false }, onCreate = {
                    check(it == GLYPH_MAKER_ENTRY); home = false; showing = true
                }, onCapture = null, onLibrary = null, onTest = null)
                if (showing) key(layoutProbe) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, previewFontScale)) {
                        GlyphMakerDialog(snapshot, store, client, { snapshot = it }, { showing = false },
                            initialBitmap = if (layoutProbe) portrait else bitmap,
                            initialRoi = if (layoutProbe) ImageToolCodeGen.Roi(113, 435, 276, 464)
                                else ImageToolCodeGen.Roi(0, 0, bitmap.width, bitmap.height), initialTolerance = 7,
                            onUseSaved = { updated, config, roi -> snapshot = updated; useSaved = config to roi; showing = false })
                    }
                }
                useSaved?.let { (config, roi) -> ImageToolOcrDialog(bitmap,
                    requireNotNull(roi), snapshot, client, onDismiss = { useSaved = null }, onConfirm = { selected, region ->
                        check(selected == config && region == roi); proofAccepted = true
                    }, confirmLabel = "QA确认配置", initialConfig = config) }
                if (showCapture) ImageToolWindow(bitmap, bitmap.width, bitmap.height, "letterbox", capturing = false, message = null,
                    onCapture = {}, onEmit = {}, onCropToTemplate = {}, onClose = { showCapture = false },
                    snapshot = snapshot, runtimeClient = client, projectStore = store, onWorkbenchSnapshotChanged = { snapshot = it })
            } }
        }
        try {
            record("glyph-maker-image-home-promotes-direct-authoring-entry") {
                click("字库制作")
                waitFor { exists("字库制作 · 固定字形") }
            }
            fun previewBounds() = Rect().also { requireNotNull(node { it.contentDescription?.toString() == "字库取样预览" }).getBoundsInScreen(it) }
            fun assertPreviewClipped() {
                test.waitForIdleSync()
                try { test.uiAutomation.waitForIdle(200, 1500) } catch (_: java.util.concurrent.TimeoutException) { }
                val preview = previewBounds().apply {
                    // Accessibility may trim the non-clickable preview's bottom to
                    // its neighbor's expanded touch target. Its allocated paint box
                    // remains exactly 144dp (288px on this 2x-density fixture).
                    bottom = top + (144 * activity.resources.displayMetrics.density).roundToInt()
                }
                val toolbar = Rect().also { requireNotNull(node { it.contentDescription?.toString() == "取样工具栏" }).getBoundsInScreen(it) }
                check(toolbar.top >= preview.bottom) { "GLYPH_QA_PREVIEW_LAYOUT_OVERLAP" }
                val screen = requireNotNull(test.uiAutomation.takeScreenshot())
                try {
                    // The center is deliberately shaded by the selected ROI; the
                    // top edge remains source pixels before and after zoom/pan.
                    check(screen.getPixel(preview.centerX(), preview.top + 3) == probeColor) { "GLYPH_QA_PREVIEW_NOT_DRAWN" }
                    for (y in (preview.top - 24).coerceAtLeast(0) until toolbar.bottom.coerceAtMost(screen.height)) {
                        if (y in preview.top until preview.bottom) continue
                        for (x in preview.left until preview.right) if (screen.getPixel(x, y) == probeColor) {
                            test.sendStatus(1, Bundle().apply { putString("stream",
                                "GLYPH_QA_CLIP_PIXEL:$x,$y preview=${preview.flattenToString()} toolbar=${toolbar.flattenToString()}\n") })
                            error("GLYPH_QA_PREVIEW_ESCAPED_BOUNDS")
                        }
                    }
                } finally { screen.recycle() }
            }
            record("glyph-maker-portrait-preview-clips-focused-zoom-and-pan") {
                test.runOnMainSync { layoutProbe = true }
                waitFor { exists("原图720×1280") && exists("字库取样预览") }
                assertPreviewClipped()
                click("放大预览")
                assertPreviewClipped()
                click("取色")
                stroke("字库取样预览", .50f, .80f, .50f, .98f, whilePressed = { assertPreviewClipped() })
                click("移动")
                stroke("字库取样预览", .7f, .7f, .3f, .3f)
                assertPreviewClipped()
                capture("portrait-clipped")
            }
            record("glyph-maker-toolbar-single-row-and-modes-keep-original-roi") {
                val bounds = listOf("框选", "取色", "移动", "重选", "看选区", "全图", "缩小预览", "100%", "放大预览").map { label ->
                    Rect().also { requireNotNull(node { it.contentDescription?.toString() == label }).getBoundsInScreen(it) }
                }
                check(bounds.all { !it.isEmpty && it.centerY() == bounds.first().centerY() }) { "GLYPH_QA_TOOLBAR_WRAPPED" }
                check(bounds.last().right <= previewBounds().right) { "GLYPH_QA_NORMAL_TOOLBAR_OFFSCREEN" }
                click("框选"); click("取色"); click("移动")
                check(exists("X113 Y435 · 163×29px")) { "GLYPH_QA_TOOL_CHANGED_ROI" }
                click("100%"); waitFor { exists("显示100%") }
                click("全图"); check(exists("X113 Y435 · 163×29px"))
            }
            record("glyph-maker-large-font-keeps-preview-clipped-and-tools-scrollable") {
                val beforeFont = Rect().also(requireNotNull(node { it.text?.toString() == "框选" })::getBoundsInScreen)
                test.runOnMainSync { previewFontScale = 1.5f }
                // Text exists before reflow too; wait for the new bounds, otherwise
                // accessibility may still report the old row while pixels have moved.
                waitFor {
                    val fontNode = node { it.text?.toString() == "框选" } ?: return@waitFor false
                    val previewNode = node { it.contentDescription?.toString() == "字库取样预览" } ?: return@waitFor false
                    val toolbarNode = node { it.contentDescription?.toString() == "取样工具栏" } ?: return@waitFor false
                    val font = Rect().also(fontNode::getBoundsInScreen)
                    val preview = Rect().also(previewNode::getBoundsInScreen)
                    val toolbar = Rect().also(toolbarNode::getBoundsInScreen)
                    font.height() > beforeFont.height() && toolbar.top >= preview.bottom
                }
                click("看选区")
                assertPreviewClipped()
                val plus = requireNotNull(node { it.contentDescription?.toString() == "放大预览" })
                check(plus.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id))
                test.waitForIdleSync()
                click("放大预览")
                assertPreviewClipped()
                capture("large-font-clipped")
                test.runOnMainSync { previewFontScale = 1f; layoutProbe = false }
                waitFor { exists("原图40×24") }
            }
            record("glyph-maker-small-image-focus-zoom-and-actual-pixels") {
                fun zoomPercent(): Int? = node { it.text?.toString()?.contains("原图40×24") == true }?.text?.toString()
                    ?.let { Regex("显示(\\d+)%").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                waitFor { zoomPercent()?.let { it > 100 } == true }
                val enlarged = requireNotNull(zoomPercent())
                check(enlarged <= 1600)
                click("100%")
                waitFor { exists("显示100%") }
                check(exists("X0 Y0 · 40×24px"))
                click("看选区")
                waitFor { zoomPercent() == enlarged }
                click("全图")
                check(exists("X0 Y0 · 40×24px"))
                capture("zoom")
            }
            record("glyph-maker-black-white-preview-and-pixel-region-input") {
                click("查看黑白字形")
                waitFor { exists("白色为保留笔画") }
                click("像素微调")
                fill("选区X", "1"); fill("选区Y", "1"); fill("选区宽", "38"); fill("选区高", "22")
                click("应用选区")
                waitFor { exists("X1 Y1 · 38×22px") }
                click("左1px")
                waitFor { exists("X0 Y1 · 38×22px") }
                click("右边"); click("边缘＋1px")
                waitFor { exists("X0 Y1 · 39×22px") }
                fill("选区宽", "9999")
                waitFor { exists("范围需在原图内") }
                check(exists("X0 Y1 · 39×22px"))
                click("重选"); click("整框")
                click("收起像素微调"); click("查看原图")
                check(exists("X0 Y0 · 40×24px"))
            }
            record("glyph-maker-opens-and-batch-segments-fixed-decimal") {
                click("按空隙批量切分")
                waitFor { exists("已标0/3") }
            }
            record("glyph-maker-inline-label-count-blocks-incomplete-batch") {
                fill("顺序字符", "1")
                waitFor { exists("整行1/3字") }
                var current = node { it.text?.toString() == "追加样本" }
                var disabled = false
                while (current != null) { if (!current.isEnabled) disabled = true; current = current.parent }
                check(disabled) { "GLYPH_QA_INCOMPLETE_BATCH_ENABLED" }
                click("按空隙批量切分")
                waitFor { exists("重新切分？") }
                click("保留当前")
                waitFor { exists("整行1/3字") && !exists("重新切分？") }
            }
            record("glyph-maker-labels-and-deduplicates-identical-samples") {
                fill("顺序字符", "1.1")
                click("待标注字形2")
                fill("第2个", "?"); click("替换单字")
                waitFor { exists("本批已标注完成") }
                click("待标注字形2")
                fill("第2个", "."); click("替换单字")
                click("待标注字形2"); click("移除此字形")
                waitFor { exists("已标2/2") }
                click("撤销移除")
                waitFor { exists("已标3/3") }
                click("追加样本")
                waitFor { exists("新增2个样本，忽略1个重复") }
                check(exists("按空隙批量切分")) { "GLYPH_QA_FORCED_PAGE_CHANGE" }
                capture("sampling")
            }
            record("glyph-maker-inline-sample-editor-undo-redo-and-navigation") {
                click("样本 2")
                waitFor { exists("字符标注") }
                fill("字符标注", "7"); click("应用")
                waitFor { exists("样本1：7") }
                click("撤销"); waitFor { exists("样本1：1") }
                click("重做"); waitFor { exists("样本1：7") }
                click("撤销"); waitFor { exists("样本1：1") }
                click("下一个"); waitFor { exists("样本2 ·") }
                fill("字符标注", "#"); waitFor { exists("恢复") }; click("上一个")
                check(exists("样本2 ·")) { "GLYPH_QA_DISCARDED_LABEL" }
                click("恢复"); waitFor { exists("已恢复样本标注") }; click("上一个")
                waitFor { exists("样本1 ·") }
                check(!exists("请先应用或恢复当前标注")) { "GLYPH_QA_STALE_WARNING" }
                capture("samples")
                click("继续取样")
            }
            record("glyph-maker-sample-search-keeps-original-selection-and-indices") {
                click("样本 2")
                fill("查找字符", ".")
                waitFor { exists("样本2：.") && !exists("样本1：1") }
                click("样本2：.")
                waitFor { exists("样本2 ·") }
                fill("查找字符", "8")
                waitFor { exists("没有匹配的字符") }
                check(exists("样本2 ·"))
                click("清除筛选"); click("上一个")
                waitFor { exists("样本1 ·") }
            }
            record("glyph-maker-drag-brush-and-eraser-use-one-undo-step") {
                click("画笔")
                // The public '1' is an 8x24 filled bar; erase a vertical stroke, then restore with one undo.
                click("橡皮")
                stroke("修点字形", .50f, .20f, .50f, .80f)
                waitFor { exists("一笔修点已应用") }
                click("撤销")
                waitFor { exists("已撤销样本操作") }
                click("重做"); waitFor { exists("已重做样本操作") }
                click("撤销"); waitFor { exists("已撤销样本操作") }
                stroke("修点字形", .50f, .20f, .50f, .80f, cancel = true)
                check(exists("已撤销样本操作") && !exists("一笔修点已应用")) { "GLYPH_QA_CANCELLED_STROKE_COMMITTED" }
                val originalWidth = paintedWidth()
                check(originalWidth > 0) { "GLYPH_QA_EMPTY_RENDERED_BAR" }
                capture("before-zoom")
                click("放大字形", reveal = true)
                capture("after-zoom")
                waitFor { kotlin.math.abs(paintedWidth() - originalWidth * 2) <= 2 }
                click("适应字形", reveal = true)
                waitFor { kotlin.math.abs(paintedWidth() - originalWidth) <= 2 }
                capture("pixel-editor")
                click("继续取样", reveal = true)
            }
            record("glyph-maker-maximize-restore-retains-samples") {
                click("最大化全屏"); waitFor { exists("还原窗口") }
                click("还原窗口"); waitFor { exists("样本 2") }
            }
            record("glyph-maker-append-and-test-shortcut-preserves-deduplicated-samples") {
                click("按空隙批量切分")
                waitFor { exists("已标0/3") }
                fill("顺序字符", "1.1")
                click("追加并测试")
                waitFor { exists("识别文本：1.1") }
                check(exists("覆盖率 1000‰"))
                check(exists("样本 2"))
            }
            record("glyph-maker-expected-text-checks-the-real-rust-result") {
                fill("预期文字", "1.1")
                waitFor { exists("预期核对：完全一致") }
                fill("预期文字", "1.2")
                waitFor { exists("首处差异第3字") && exists("尚未采样：2") }
                fill("预期文字", "1.1")
                waitFor { exists("预期核对：完全一致") }
            }
            record("glyph-maker-unknown-detail-returns-to-exact-original-region") {
                click("样本 2"); click("下一个"); click("删除")
                waitFor { exists("样本 1") }
                click("快速测试")
                waitFor { exists("真实逐字位置") && exists("未识别1项") }
                repeat(2) {
                    node { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id } }
                        ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                    test.waitForIdleSync()
                }
                click("识别字形2")
                click("回到取样并框选第2项")
                waitFor { exists("X16 Y0 · 8×24px") }
                click("撤销")
                waitFor { exists("样本 2") }
                click("重选")
                click("快速测试")
                waitFor { exists("识别文本：1.1") && exists("未识别0项") }
            }
            record("glyph-maker-saves-declared-resource-without-overwrite") {
                click("保存字库")
                waitFor { snapshot.manifest.resources.any { it.get("kind")?.asString == "glyphDictionary" } && exists("已保存字库") }
                val first = snapshot.manifest.resources.single { it.get("kind")?.asString == "glyphDictionary" }.get("path").asString
                val file = File(snapshot.directory, first)
                val bytes = file.readBytes()
                val samples = GlyphDictionaryMaker.decode(bytes)
                check(samples.map { it.label } == listOf("1", "."))
                check(samples.all { it.height == 3 * scale })
                click("保存字库")
                waitFor { exists("同名内容已存在") }
                click("编号另存")
                waitFor { snapshot.manifest.resources.count { it.get("kind")?.asString == "glyphDictionary" } == 2 && exists("已保存字库") }
                check(file.readBytes().contentEquals(bytes)) { "GLYPH_QA_OVERWROTE_ORIGINAL" }
                check(store.openProject(initial.manifest.projectId).manifest.resources.count { it.get("kind")?.asString == "glyphDictionary" } == 2)
                check(exists("识别文本：1.1")) { "GLYPH_QA_SAVE_ERASED_TEST_RESULT" }
                capture("testing")
            }
            record("glyph-maker-same-name-explicit-overwrite-keeps-resource-path") {
                val before = snapshot.manifest.resources.filter { isGlyphDictionaryResource(it) }.map { it.get("path").asString }.sorted()
                click("保存字库"); waitFor { exists("同名内容已存在") }; click("覆盖保存")
                waitFor { !exists("同名内容已存在") && exists("已保存字库") }
                check(snapshot.manifest.resources.filter { isGlyphDictionaryResource(it) }.map { it.get("path").asString }.sorted() == before)
            }
            record("glyph-maker-landscape-keyboard-keeps-fields-and-save-accessible") {
                test.runOnMainSync { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                revealControl("预期文字", editable = true)
                val field = requireNotNull(input("预期文字"))
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                fun keyboard(): Rect? = test.uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.let { Rect().also(it::getBoundsInScreen).takeUnless(Rect::isEmpty) }
                waitFor { keyboard() != null }
                for (label in listOf("相似度 ‰", "空格列数", "预期文字", "字库名称")) {
                    test.sendStatus(1, Bundle().apply { putString("stream", "QA_GLYPH_KEYBOARD_FIELD:$label\n") })
                    revealControl(label, editable = true)
                    // IME resize rebuilds Compose's accessibility tree after the label appears.
                    waitFor { input(label) != null }
                    val target = requireNotNull(input(label))
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    waitFor {
                        val current = input(label)
                        val bounds = Rect().also { current?.getBoundsInScreen(it) }
                        val ime = keyboard()
                        ime != null && bounds.height() > 0 && bounds.bottom <= ime.top && bounds.top >= 0
                    }
                }
                val save = requireNotNull(node { it.text?.toString() == "保存字库" })
                val bounds = Rect().also(save::getBoundsInScreen)
                check(bounds.height() > 0 && bounds.bottom <= requireNotNull(keyboard()).top) { "GLYPH_QA_KEYBOARD_COVERS_SAVE" }
                capture("landscape-keyboard")
                test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitFor { keyboard() == null }
                test.runOnMainSync { activity.requestedOrientation = restoredScreenOrientation(originalOrientation) }
                waitFor { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
                check(exists("样本 2"))
            }
            record("glyph-maker-saved-use-opens-exact-dictionary-settings-without-third-copy") {
                click("样本 2")
                click("样本1：1")
                fill("字符标注", "7"); click("应用")
                waitFor { exists("样本1：7") }
                click("撤销"); waitFor { exists("样本1：1") }
                click("保存并识字")
                waitFor { exists("取字 · 配置与测试") && !exists("字库制作 · 固定字形") }
                val (config, roi) = requireNotNull(useSaved)
                check(config.operation == com.autoscript.runtime.api.RecognitionPreviewReply.GLYPH)
                check(config.foregroundRgb == 0xffffff && config.tolerance == 7 && config.threshold == 900 && config.spaceGapColumns == 0)
                check(roi == ImageToolCodeGen.Roi(0, 0, bitmap.width, bitmap.height))
                check(snapshot.manifest.resources.count { isGlyphDictionaryResource(it) } == 2)
                check(exists("字库：${config.dictionaryPath.substringAfterLast('/')}"))
                capture()
            }
            record("glyph-maker-configured-recognition-retests-through-saved-dictionary") {
                click("测试识别")
                waitFor { exists("真实逐字位置") }
                click("QA确认配置")
                waitFor { proofAccepted }
                capture("recognized")
            }
            record("glyph-maker-handoff-close-does-not-leave-window") {
                test.runOnMainSync { useSaved = null }
                waitFor { !exists("取字 · 配置与测试") }
            }
            record("glyph-maker-screenshot-shortcut-opens-without-saving-or-processing") {
                test.runOnMainSync { showCapture = true }
                click("制字")
                waitFor { exists("字库制作 · 固定字形") && exists("原图40×24") }
                click("返回")
                waitFor { !exists("字库制作 · 固定字形") && exists("制字") }
                click("关闭图像工具")
                waitFor { !showCapture }
            }
        } catch (failure: Throwable) {
            runCatching { capture() }
            runCatching { captureOwnedControls() }
            throw failure
        } finally {
            test.runOnMainSync { showing = false; useSaved = null; showCapture = false; home = false; activity.requestedOrientation = restoredScreenOrientation(originalOrientation) }
            test.waitForIdleSync()
            bitmap.recycle()
            portrait.recycle()
        }
        return snapshot
    }
}
