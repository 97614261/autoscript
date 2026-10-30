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
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import java.io.File

/** Only an owned temporary project and a public red 1.1 raster; no private scripts. */
internal object ImageFilterAcceptanceFixture {
    fun run(test: Instrumentation,activity: ComponentActivity,client: RuntimeClient,store: ProjectStore,
        initial: ProjectSnapshot,record:(String,()->Unit)->Unit):ProjectSnapshot {
        var snapshot by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        val rows=listOf("#...#","#...#","#.#.#")
        val bitmap=Bitmap.createBitmap(40,24,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until height) for(x in 0 until width) setPixel(x,y,if(rows[y/8][x/8]=='#') 0xffff0000.toInt() else 0xff000000.toInt())
        }
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            fun walk(current:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(current==null || !current.refresh()) return null
                if(predicate(current)) return current
                for(i in 0 until current.childCount) walk(current.getChild(i))?.let {return it}
                return null
            }
            // A legacy IME can become the active accessibility window after set-text.
            // Keep assertions on the topmost owned form, never the keyboard or a background form.
            for(window in test.uiAutomation.windows.sortedByDescending {it.layer}) {
                val root=window.root ?: continue
                if(root.packageName?.toString()==test.targetContext.packageName && !root.className.toString().contains("PopupDecorView")) return walk(root)
                root.recycle()
            }
            return null
        }
        fun exists(label:String)=node {it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label}!=null
        fun waitFor(predicate:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+15_000
            while(!predicate() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(60)
            check(predicate()) {"FILTER_QA_TIMEOUT"}
        }
        fun reveal(label:String,partial:Boolean=false) {
            fun visible()=node {it.isVisibleToUser && ((if(partial) it.text?.toString()?.contains(label)==true else it.text?.toString()==label) || it.contentDescription?.toString()==label)}!=null
            for(forward in listOf(true,false)) repeat(6) {
                if(visible()) return
                if(!scrollOwnedQaForm(test,forward,::visible)) return
            }
        }
        fun click(label:String,anchor:String?=null) {
            reveal(label)
            var target:AccessibilityNodeInfo?=null
            var previous:Rect?=null
            var stableSince=0L
            waitFor {
                var current=node {it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)}
                while(current!=null && !(current.refresh() && current.isClickable)) current=current.parent
                target=current
                val bounds=current?.let {Rect().also(it::getBoundsInScreen)}
                val anchored=anchor==null || current?.windowId==node {it.contentDescription?.toString()==anchor}?.windowId
                if(bounds==null || bounds.isEmpty || !anchored || current?.isEnabled!=true) false
                else {
                    if(previous!=bounds) {previous=bounds;stableSince=SystemClock.elapsedRealtime()}
                    SystemClock.elapsedRealtime()-stableSince>=150
                }
            }
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) {"FILTER_QA_CLICK:$label"}
        }
        fun fill(label:String,value:String) {
            reveal(label)
            waitFor {node {it.isEditable && it.contentDescription?.toString()==label}!=null}
            check(node {it.isEditable && it.contentDescription?.toString()==label}?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)})==true)
        }
        fun capture(tag:String) {
            val image=test.uiAutomation.takeScreenshot() ?: return
            try { File(test.targetContext.cacheDir,"qa-color-filter-$tag.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)} }
            finally {image.recycle()}
        }
        fun tapPreview() {
            val target=requireNotNull(node {it.contentDescription?.toString()=="滤色预览取色"})
            val bounds=Rect().also(target::getBoundsInScreen)
            // Focused 40x24 source fills 90% of height: red left bar at source x=4,y=12.
            val scale=minOf(bounds.width()*.9f/40,bounds.height()*.9f/24,16f)
            val x=bounds.exactCenterX()-16*scale;val y=bounds.exactCenterY()
            val time=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,x,y,0).also {
                    it.source=InputDevice.SOURCE_TOUCHSCREEN
                    try {check(test.uiAutomation.injectInputEvent(it,true))} finally {it.recycle()}
                }
            }
        }
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            if(visible) ImageToolWindow(bitmap,40,24,"letterbox",capturing=false,message=null,onCapture={},onEmit={},onCropToTemplate={},
                onClose={visible=false},snapshot=snapshot,runtimeClient=client,projectStore=store,onWorkbenchSnapshotChanged={snapshot=it})
        } } }
        try {
            record("filter-screenshot-direct-entry-opens-rule-workspace") {
                val live = UsageTestUi(test)
                live.click("工具");live.reveal("滤色");live.click("滤色");waitFor {exists("滤色 · 规则与方案")}
                check(exists("滤色预览取色"));capture("open")
            }
            record("filter-picks-original-red-and-previews-binary-with-bounded-rules") {
                tapPreview();waitFor {exists("已取原图颜色 #FF0000")}
                fill("R差","0");fill("G差","0");fill("B差","0")
                reveal("前景 448/960px",partial=true);waitFor {exists("前景 448/960px")}
                click("加颜色");fill("RGB","000000");click("保留该颜色")
                fill("R差","0");fill("G差","0");fill("B差","0")
                reveal("前景 448/960px",partial=true);waitFor {exists("前景 448/960px")}
                capture("rules")
            }
            record("filter-invalid-edit-cannot-apply-and-toggle-fullscreen-retains-input") {
                fill("R差","999")
                waitFor {exists("颜色需6位十六进制")}
                waitFor {exists("参数无效，当前显示原图")}
                waitFor {
                    var current=node {it.text?.toString()=="应用滤色" || it.contentDescription?.toString()=="应用滤色"};var disabled=false
                    while(current!=null) {if(current.refresh() && !current.isEnabled) disabled=true;current=current.parent}
                    disabled
                }
                click("最大化全屏");waitFor {exists("还原窗口")};click("还原窗口")
                check(exists("颜色需6位十六进制"));fill("R差","0")
                waitFor {exists("前景 448/960px")}
            }
            record("filter-presets-save-copy-conflict-safe-and-confirm-delete") {
                fill("方案名","红字");click("保存方案")
                waitFor {store.readImageFilterPresets(initial.manifest.projectId).size==1 && exists("已保存方案 红字")}
                val first=store.readImageFilterPresets(initial.manifest.projectId).single()
                check(first.filter.rules.size==2 && first.filter.rules.first().rgb==0xff0000 && first.filter.rules.last().exclude)
                click("保存方案");waitFor {exists("同名内容已存在")};click("编号另存")
                waitFor {store.readImageFilterPresets(initial.manifest.projectId).size==2 && exists("已保存方案 红字-2")}
                check(store.readImageFilterPresets(initial.manifest.projectId).first()==first)
                click("红字-2");waitFor {exists("载入滤色方案？")};click("载入方案")
                click("删除方案");waitFor {exists("删除滤色方案？")};click("取消")
                check(store.readImageFilterPresets(initial.manifest.projectId).size==2)
                click("删除方案");waitFor {exists("删除滤色方案？")};click("删除")
                waitFor {store.readImageFilterPresets(initial.manifest.projectId).size==1 && exists("方案已删除")}
                check(store.readImageFilterPresets(initial.manifest.projectId).single().filter==first.filter)
                capture("presets")
            }
            record("filter-applies-to-real-workbench-and-can-undo-redo-before-making-glyphs") {
                click("应用滤色");waitFor {exists("处理完成 · 40×24px") && !exists("滤色 · 规则与方案")}
                click("撤销");click("重做")
            }
            record("filter-processed-frame-matches-real-rust-template-at-exact-origin") {
                val pixels=IntArray(40*24) { index -> if(rows[index/40/8][index%40/8]=='#') -1 else 0xff000000.toInt() }
                val template=Bitmap.createBitmap(pixels,40,24,Bitmap.Config.ARGB_8888)
                val file=File.createTempFile("qa-filter-template-",".png",activity.cacheDir)
                val updated=try {
                    file.outputStream().use { check(template.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    file.inputStream().use { input -> store.importResource(initial.manifest.projectId,
                        com.autoscript.project.store.ProjectResourceKind.IMAGE,"public-filter-template.png",input,
                        snapshot.manifest.resources.map { it["path"].asString }.toSet()) }
                } finally { template.recycle();file.delete() }
                test.runOnMainSync {snapshot=updated}
                click("找图测试");waitFor {exists("处理图 · 找图测试")}
                click("开始测试");waitFor {exists("已找到：(0, 0) · 1000‰")}
                capture("matched");click("返回");waitFor {!exists("处理图 · 找图测试") && exists("图片工作台预览")}
            }
            record("filter-processed-frame-samples-fixed-glyphs-through-real-rust") {
                click("制作字库",anchor="图片工作台预览");waitFor {exists("字库制作 · 固定字形")}
                click("按空隙批量切分");reveal("已标0/3",partial=true);waitFor {exists("已标0/3")}
                fill("顺序字符","1.1");click("追加并测试")
                reveal("识别文本：1.1",partial=true);waitFor {exists("识别文本：1.1") && exists("覆盖率 1000‰")}
                capture("glyph")
            }
            record("filter-sampled-dictionary-saves-and-retests-current-processed-frame") {
                click("保存并识字");waitFor {exists("取字 · 配置与测试")}
                val resource=snapshot.manifest.resources.single {isGlyphDictionaryResource(it)}
                val samples=GlyphDictionaryMaker.decode(File(snapshot.directory,resource["path"].asString).readBytes())
                check(samples.map {it.label}==listOf("1",".") && samples.all {it.height==24})
                click("测试识别");reveal("真实逐字位置",partial=true);waitFor {exists("真实逐字位置")}
                capture("recognized")
            }
            record("filter-preset-portable-backup-retains-rules-without-altering-script") {
                val before=store.openProject(initial.manifest.projectId)
                val buffer=java.io.ByteArrayOutputStream();store.exportProjectBackup(initial.manifest.projectId,buffer)
                val restored=store.importProjectBackup(buffer.toByteArray().inputStream())
                try {
                    check(store.readImageFilterPresets(restored.manifest.projectId)==store.readImageFilterPresets(initial.manifest.projectId))
                    check(before.flowSources==restored.flowSources)
                } finally {store.deleteProject(restored.manifest.projectId)}
            }
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
        finally {test.runOnMainSync {visible=false};test.waitForIdleSync();bitmap.recycle()}
        return snapshot
    }
}
