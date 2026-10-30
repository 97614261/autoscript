package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Point
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Public raster only. Activity orientation is restored; persisted device settings stay unchanged. */
internal object ImageSelectionAcceptanceFixture {
    fun run(test:Instrumentation,activity:ComponentActivity,client:RuntimeClient,store:ProjectStore,
        snapshot:ProjectSnapshot,record:(String,()->Unit)->Unit) {
        check(snapshot.manifest.projectId.startsWith("project-qa-"))
        val bitmap=Bitmap.createBitmap(640,400,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff9166dd.toInt())}
        var mode by mutableIntStateOf(0)
        var fontScale by mutableFloatStateOf(1f)
        var panelFullScreen by mutableStateOf(false)
        var panelConfirms by mutableIntStateOf(0)
        var rectangleMode by mutableStateOf(ImageToolMode.CROP)
        var rectangleSelection by mutableStateOf(ImageToolSelection())
        var rectangleViewport by mutableStateOf<ImageToolViewport?>(null)
        val renderedRectangle = AtomicReference<Pair<ImageToolMode, ImageToolCodeGen.Roi?>?>()
        val requestedOrientation=activity.requestedOrientation
        fun display()=Point().also {@Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(it)}
        val initialDisplay=display()
        fun node(label:String):AccessibilityNodeInfo? {
            fun walk(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(n==null || !n.refresh()) return null
                if(n.text?.toString()==label || n.contentDescription?.toString()==label ||
                    n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == label }) return n
                for(i in 0 until n.childCount) walk(n.getChild(i))?.let {return it}
                return null
            }
            for(window in test.uiAutomation.windows.sortedByDescending {it.layer}) {
                val root=window.root ?: continue
                if(root.packageName?.toString()==test.targetContext.packageName) walk(root)?.let {return it}
            }
            return null
        }
        fun await(block:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+10_000
            while(!block() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(60)
            check(block()) {"SELECTION_QA_TIMEOUT"}
        }
        fun bounds(label:String)=Rect().also {requireNotNull(node(label)) {"SELECTION_QA_MISSING:$label"}.getBoundsInScreen(it)}
        fun actionBounds(label: String): Rect {
            var current = requireNotNull(node(label))
            try {
                repeat(5) {
                    if (current.isClickable) return Rect().also(current::getBoundsInScreen)
                    val parent = current.parent ?: return Rect().also(current::getBoundsInScreen)
                    current.recycle(); current = parent
                }
                error("SELECTION_QA_ACTION_MISSING:$label")
            } finally { current.recycle() }
        }
        fun tap(x:Float,y:Float) {
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
                try {check(test.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
            }
        }
        fun drag(from: Pair<Float, Float>, to: Pair<Float, Float>, cancel: Boolean = false) {
            val down = SystemClock.uptimeMillis()
            for (step in 0..9) {
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    9 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val fraction = minOf(step, 8) / 8f
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    from.first + (to.first - from.first) * fraction,
                    from.second + (to.second - from.second) * fraction, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                SystemClock.sleep(24)
            }
            test.waitForIdleSync()
        }
        fun prepareRectangle(tool: ImageToolMode, box: ImageToolCodeGen.Roi) {
            test.runOnMainSync { mode = 4; rectangleMode = tool; rectangleSelection = ImageToolSelection(box = box) }
            // An idle main queue does not prove that Compose has applied the new pointer-input key.
            await { node("图像工具画布") != null && rectangleViewport != null && renderedRectangle.get() == tool to box }
            test.waitForIdleSync()
        }
        fun awaitRectangle(expected: ImageToolCodeGen.Roi) {
            try { await { rectangleSelection.box == expected } }
            catch (failure: Throwable) {
                test.sendStatus(1, Bundle().apply { putString("stream", "QA_SELECTION_DIAGNOSTIC:" + JSONObject()
                    .put("mode", rectangleMode.name).put("expected", expected.toString())
                    .put("actual", rectangleSelection.box.toString()).put("viewport", rectangleViewport.toString())
                    .put("rendered", renderedRectangle.get().toString()).toString() + "\n") })
                throw failure
            }
        }
        fun screenPoint(imageX: Int, imageY: Int): Pair<Float, Float> {
            val b = bounds("图像工具画布")
            val p = requireNotNull(rectangleViewport).toView(imageX, imageY)
            return b.left + p.first to b.top + p.second
        }
        fun click(label:String) {
            await {node(label)!=null}
            val b=bounds(label);check(!b.isEmpty) {"SELECTION_QA_CLIPPED:$label"}
            tap(b.exactCenterX(),b.exactCenterY())
        }
        fun onScreen(b:Rect):Boolean {
            val size=display()
            return !b.isEmpty && b.left>=0 && b.top>=0 && b.right<=size.x && b.bottom<=size.y
        }
        fun unobscured(b: Rect): Boolean {
            if (!onScreen(b)) return false
            return test.uiAutomation.windows.filter {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }.all { window ->
                val keyboard = Rect().also(window::getBoundsInScreen)
                // Some IMEs report a transparent fullscreen host; real clicks remain required.
                keyboard.top <= 0 || !Rect.intersects(b, keyboard)
            }
        }
        fun capture(tag:String) {
            val image=requireNotNull(test.uiAutomation.takeScreenshot())
            try {File(test.targetContext.cacheDir,"qa-image-selection-$tag.png").outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
            finally {image.recycle()}
        }
        test.runOnMainSync {activity.setContent {
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale)) { AutoScriptTheme {
            when(mode) {
                0 -> Box(Modifier.size(320.dp,220.dp)) {ImageToolWindow(bitmap,640,400,"letterbox",capturing=false,message=null,
                    onCapture={},onEmit={},onCropToTemplate={},onClose={mode=3})}
                1 -> ImageProcessingWorkbench(bitmap,snapshot,store,client,{},onDismiss={mode=3})
                2 -> ImagePolygonDialog(bitmap,onDismiss={mode=3},onApply={mode=3})
                3 -> Box(Modifier.fillMaxSize().background(Color(0xff223044))
                    .semantics { contentDescription = "横屏测试准备" })
                4 -> {
                    val displayedTool = rectangleMode
                    val displayedSelection = rectangleSelection
                    Box(Modifier.size(320.dp, 220.dp).onSizeChanged {
                        if (it.width > 0 && it.height > 0) rectangleViewport = ImageToolViewport.fit(640, 400, it.width.toFloat(), it.height.toFloat())
                    }) {
                        ImageToolCanvas(bitmap, rectangleViewport, displayedTool, false, false, displayedSelection,
                            Offset.Zero, null, null, { rectangleViewport = it }, { rectangleSelection = it }, {}, { _, _ -> })
                    }
                    SideEffect { renderedRectangle.set(displayedTool to displayedSelection.box) }
                }
                5 -> FunctionLibraryDialog(FunctionCatalog.visualGroups(), snapshot.manifest.capabilities.toSet(),
                    onDismiss = { mode = 3 }, onInsert = {})
                6 -> StudioPanelDialog("横屏适配验收", onDismiss = { mode = 3 }, confirmLabel = "确认并保留",
                    onConfirm = { panelConfirms++ }, fullScreen = panelFullScreen,
                    onToggleFullScreen = { panelFullScreen = !panelFullScreen }) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                        .semantics { contentDescription = "横屏滚动内容" }) {
                        repeat(40) { Text("公开测试参数 $it", Modifier.padding(6.dp)) }
                    }
                }
                7 -> ImageTemplateSaveDialog(emptyList(), store, snapshot.manifest.projectId,
                    defaultName = "public-landscape.png", onDismiss = { mode = 3 }, onSave = { _, _, _ -> mode = 3 })
                8 -> RunnerUiDesignerScreen(snapshot, store, {}, { mode = 3 })
                9 -> ImageToolOcrDialog(bitmap, ImageToolCodeGen.Roi(0, 0, bitmap.width, bitmap.height), snapshot, client,
                    onDismiss = { mode = 3 }, onConfirm = { _, _ -> })
                10 -> GlyphMakerDialog(snapshot, store, client, {}, { mode = 3 }, initialBitmap = bitmap)
                11 -> RecordingWorkspaceScreen(snapshot.manifest.projectId, requireNotNull(snapshot.manifest.entryFlowId), snapshot.manifest.design,
                    setOf("core.task", "input.basic"), client, { false }, { mode = 3 })
            }
        }}}}
        try {
            record("new-main-and-additional-plugin-remain-empty-on-device") {
                check(snapshot.flowSources.values.all { it.isEmpty() }) { "NEW_MAIN_NOT_EMPTY" }
                val created = ProjectSourceFiles(store).createFlow(snapshot, "空白插件")
                check(created.manifest.flows.size == snapshot.manifest.flows.size + 1)
                val reopened = store.openProject(created.manifest.projectId)
                for (flow in reopened.manifest.flows) {
                    val source = reopened.flowSources.getValue(flow.flowId)
                    val editor = VisualEditorState.create(source, flow.rootBlockId)
                    check(source.isEmpty() && editor.currentSource.isEmpty() && editor.rows.isEmpty()) { "NEW_PLUGIN_NOT_EMPTY" }
                }
            }
            record("image-toolbar-short-narrow-complete-and-close-remain-visible") {
                await {node("关闭图像工具")!=null}
                val close=bounds("关闭图像工具");val confirm=bounds("确定")
                val canvas=bounds("图像工具画布")
                check(canvas.contains(close) && canvas.contains(confirm)) {"TOOLBAR_OUTSIDE_NARROW_VIEWPORT"}
                check(confirm.right<=close.left) {"TOOLBAR_ACTIONS_OVERLAP"}
                capture("toolbar-320x220")
            }
            for (tool in listOf(ImageToolMode.CROP, ImageToolMode.REGION, ImageToolMode.OCR)) {
                record("rectangle-${tool.name.lowercase()}-normal-and-small-interior-real-drag") {
                    for (box in listOf(ImageToolCodeGen.Roi(100, 90, 260, 230), ImageToolCodeGen.Roi(160, 160, 172, 172))) {
                        prepareRectangle(tool, box)
                        val from = screenPoint((box.left + box.right) / 2, (box.top + box.bottom) / 2)
                        drag(from, from.first + 40f to from.second + 24f)
                        val expected = resizeBox(box, BoxEdge.MOVE, 40f, 24f, requireNotNull(rectangleViewport).scale, 640, 400)
                        awaitRectangle(expected)
                        check(rectangleSelection.box?.width == box.width && rectangleSelection.box?.height == box.height)
                    }
                    capture("rectangle-${tool.name.lowercase()}-moved")
                }
            }
            record("rectangle-handle-priority-and-cancel-rollback-with-real-touch") {
                val box = ImageToolCodeGen.Roi(100, 90, 260, 230)
                prepareRectangle(ImageToolMode.CROP, box)
                val handle = screenPoint(box.right, (box.top + box.bottom) / 2)
                drag(handle, handle.first + 20f to handle.second)
                val resized = resizeBox(box, BoxEdge.RIGHT, 20f, 0f, requireNotNull(rectangleViewport).scale, 640, 400)
                awaitRectangle(resized)
                prepareRectangle(ImageToolMode.CROP, box)
                val from = screenPoint(180, 160)
                drag(from, from.first + 40f to from.second + 24f, cancel = true)
                awaitRectangle(box)
            }
            record("workbench-landscape-return-footer-and-canvas-stay-on-screen") {
                test.runOnMainSync {mode=3;activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                await {display().let {it.x>it.y}}
                await {
                    val ready = node("横屏测试准备") ?: return@await false
                    val area = Rect().also { ready.getBoundsInScreen(it) }
                    if (area.width() <= area.height()) return@await false
                    val frame = test.uiAutomation.takeScreenshot() ?: return@await false
                    try {
                        frame.width > frame.height && area.centerX() in 0 until frame.width && area.centerY() in 0 until frame.height &&
                            frame.getPixel(area.centerX(), area.centerY()) and 0xFFFFFF == 0x223044
                    } finally { frame.recycle() }
                }
                test.runOnMainSync {mode=1};await {node("图片工作台")!=null}
                await {node("图像工具画布")!=null}
                val canvas=bounds("图像工具画布");val back=bounds("返回")
                check(onScreen(canvas) && onScreen(back) && canvas.top>=back.bottom) {"WORKBENCH_CHROME_COVERS_PREVIEW"}
                await {node("保存当前图")!=null}
                val minimumButtonHeight=(26*activity.resources.displayMetrics.density).toInt()
                for(label in listOf("保存当前图","找图测试","取字测试","制作字库")) {
                    val action=actionBounds(label)
                    test.sendStatus(1, Bundle().apply { putString("stream", "QA_WORKBENCH_ACTION:$label:$action;canvas=$canvas;minimum=$minimumButtonHeight\n") })
                    check(onScreen(action) && !Rect.intersects(action, canvas) &&
                        (action.top>=canvas.bottom || action.left>=canvas.right) && action.height()>=minimumButtonHeight) {
                        "WORKBENCH_FOOTER_CLIPPED:$label:$action"
                    }
                }
                capture("workbench-landscape")
                click("返回");await {node("图片工作台")==null}
            }
            record("landscape-workbench-large-font-keeps-preview-and-primary-actions-separate") {
                test.runOnMainSync { fontScale = 1.6f; mode = 1 }
                await { node("保存当前图") != null && node("图片工作台预览") != null }
                val preview = bounds("图片工作台预览")
                val density = activity.resources.displayMetrics.density
                check(preview.width() >= 280 * density && preview.height() >= 160 * density) { "WORKBENCH_LANDSCAPE_PREVIEW_TOO_SMALL" }
                for (label in listOf("保存当前图", "找图测试", "取字测试", "制作字库", "返回")) {
                    val action = bounds(label)
                    check(onScreen(action) && !Rect.intersects(action, preview)) { "WORKBENCH_LARGE_FONT_OVERLAP:$label" }
                }
                capture("workbench-landscape-large-font")
                click("返回"); await { node("图片工作台") == null }
                test.runOnMainSync { fontScale = 1f }
            }
            record("landscape-library-list-search-close-and-maximize-remain-accessible") {
                test.runOnMainSync { mode = 5 }
                await { node("关闭函数库") != null && node("搜索名称、用途、参数或 API") != null }
                check(onScreen(bounds("关闭函数库")) && onScreen(bounds("最大化全屏")))
                val entry = FunctionCatalog.visualGroups().first().entries.first().title
                await { node(entry) != null }
                check(bounds(entry).height() > 0) { "LANDSCAPE_LIBRARY_LIST_COLLAPSED" }
                capture("library-landscape")
                click("最大化全屏"); await { node("还原窗口") != null }
                click("还原窗口"); await { node("最大化全屏") != null }
                click("关闭函数库"); await { node("关闭函数库") == null }
            }
            record("landscape-panel-large-font-pinned-footer-and-fullscreen-preserve-session") {
                test.runOnMainSync { fontScale = 1.6f; mode = 6 }
                await { node("确认并保留") != null && node("横屏滚动内容") != null }
                val body = bounds("横屏滚动内容")
                val footer = bounds("确认并保留")
                check(onScreen(footer) && body.height() >= 64 * activity.resources.displayMetrics.density && footer.top >= body.bottom) {
                    "LANDSCAPE_PANEL_BODY_OR_FOOTER_CLIPPED"
                }
                click("确认并保留"); await { panelConfirms == 1 }
                click("最大化全屏"); await { panelFullScreen }
                click("还原窗口"); await { !panelFullScreen }
                click("确认并保留"); await { panelConfirms == 2 }
                capture("panel-landscape-large-font")
                click("关闭"); await { node("横屏适配验收") == null }
                test.runOnMainSync { fontScale = 1f }
            }
            record("landscape-image-save-keeps-cancel-save-and-scroll-body-visible") {
                test.runOnMainSync { mode = 7 }
                await { node("保存图片") != null }
                check(onScreen(bounds("保存图片")) && onScreen(bounds("取消"))) { "LANDSCAPE_SAVE_FOOTER_CLIPPED" }
                capture("save-landscape")
                click("取消"); await { node("保存截图") == null }
            }
            record("landscape-designer-pins-return-preview-and-save-outside-scrolling-tools") {
                test.runOnMainSync { fontScale = 1.6f; mode = 8 }
                await { node("UI 设计") != null && node("保存") != null }
                for (label in listOf("‹ 返回", "预览", "保存")) check(onScreen(bounds(label))) { "DESIGNER_PINNED_ACTION_CLIPPED:$label" }
                await { node("页面属性") != null && node("名称") != null && node("宽 px") != null }
                check(bounds("页面属性").bottom <= bounds("名称").top && bounds("名称").bottom <= bounds("宽 px").top) {
                    "DESIGNER_LARGE_FONT_PROPERTY_ROWS_OVERLAP"
                }
                capture("designer-landscape-large-font")
                click("‹ 返回"); await { node("UI 设计") == null }
                test.runOnMainSync { fontScale = 1f }
            }
            record("landscape-ocr-large-font-pinned-return-and-fullscreen-preserve-form") {
                test.runOnMainSync { fontScale = 1.6f; mode = 9 }
                await { node("取字 · 配置与测试") != null && node("返回") != null }
                await { unobscured(bounds("返回")) && unobscured(bounds("关闭")) }
                click("最大化全屏"); await { node("还原窗口") != null }
                await { unobscured(bounds("返回")) }
                click("还原窗口"); await { unobscured(bounds("返回")) }; capture("ocr-landscape-large-font")
                click("返回"); await { node("取字 · 配置与测试") == null }
            }
            record("landscape-glyph-large-font-tabs-save-and-return-reachable") {
                test.runOnMainSync { mode = 10 }
                await { node("字库制作 · 固定字形") != null && node("返回") != null }
                for (label in listOf("取样", "样本 0", "测试设置", "保存字库", "返回"))
                    check(unobscured(bounds(label))) { "GLYPH_LANDSCAPE_ACTION_CLIPPED:$label" }
                click("测试设置"); click("样本 0"); click("取样")
                click("最大化全屏"); await { node("还原窗口") != null }
                click("还原窗口"); await { unobscured(bounds("返回")) }; capture("glyph-landscape-large-font")
                click("返回"); await { node("字库制作 · 固定字形") == null }
                test.runOnMainSync { fontScale = 1f }
            }
            record("landscape-recording-preparation-large-font-controls-and-help-reachable") {
                test.runOnMainSync { fontScale = 1.6f; mode = 11 }
                await { node("动作录制") != null }
                click("说明"); check(onScreen(bounds("开始录制")) && onScreen(bounds("关闭"))) { "RECORDING_LANDSCAPE_PREPARATION_CLIPPED" }
                click("最大化全屏"); await { node("还原窗口") != null }
                click("还原窗口"); capture("recording-landscape-large-font")
                click("关闭"); await { node("动作录制") == null }
                test.runOnMainSync { fontScale = 1f }
            }
            record("polygon-landscape-add-three-vertices-with-real-touches") {
                test.runOnMainSync {mode=2};await {node("多边形选区画布")!=null}
                val b=bounds("多边形选区画布")
                check(b.height()>0)
                val scale=minOf(b.width()/640f,b.height()/400f,1f)
                val left=b.exactCenterX()-320*scale;val top=b.exactCenterY()-200*scale
                for((i,p) in listOf(.15f to .15f,.85f to .15f,.5f to .85f).withIndex()) {
                    tap(left+640*p.first*scale,top+400*p.second*scale)
                    await {node("${i+1}/128点 · 选区外透明 · 原像素，不改变搜索ROI")!=null}
                }
                val apply=bounds("应用选区");check(onScreen(apply) && apply.top>=b.bottom) {"POLYGON_ACTION_OVERLAPS_CANVAS"}
                capture("polygon-landscape")
            }
            record("polygon-undo-redo-and-apply-remain-reachable") {
                click("撤销");await {node("2/128点 · 选区外透明 · 原像素，不改变搜索ROI")!=null}
                click("重做");await {node("3/128点 · 选区外透明 · 原像素，不改变搜索ROI")!=null}
                click("应用选区");await {node("多边形选区画布")==null}
            }
        } catch(failure:Throwable) {
            val geometry = org.json.JSONArray()
            for (label in listOf("取字 · 配置与测试", "字库制作 · 固定字形", "保存字库", "返回", "关闭", "最大化全屏", "还原窗口")) {
                node(label)?.let { current ->
                    try { geometry.put(JSONObject().put("label", label).put("bounds", Rect().also(current::getBoundsInScreen).flattenToString())) }
                    finally { current.recycle() }
                }
            }
            for (window in test.uiAutomation.windows.take(16)) {
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                    geometry.put(JSONObject().put("ime", Rect().also(window::getBoundsInScreen).flattenToString()))
            }
            test.sendStatus(1, Bundle().apply { putString("stream", "QA_SELECTION_UI_GEOMETRY:$geometry\n") })
            runCatching {capture("failure")};throw failure
        }
        finally {
            test.runOnMainSync {mode=3;fontScale=1f;activity.requestedOrientation=restoredScreenOrientation(requestedOrientation)}
            await {display().let {(it.x>it.y)==(initialDisplay.x>initialDisplay.y)}}
            test.waitForIdleSync();bitmap.recycle()
        }
    }
}
