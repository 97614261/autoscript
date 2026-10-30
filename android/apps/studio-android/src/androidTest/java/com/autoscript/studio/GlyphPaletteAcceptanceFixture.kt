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
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.client.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File

/** Only an owned project and a public red/green 1.1 raster; no private capture or input. */
internal object GlyphPaletteAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val id=initial.manifest.projectId;check(id.startsWith("project-qa-"))
        var snapshot by mutableStateOf(initial)
        var showing by mutableStateOf(true)
        var handoff by mutableStateOf<ImageToolOcrConfig?>(null)
        var accepted by mutableStateOf(false)
        val rows=listOf("#...#","#...#","#.#.#")
        val bitmap=Bitmap.createBitmap(40,24,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until height) for(x in 0 until width) setPixel(x,y,
                if(rows[y/8][x/8]=='#') {if(y<12) 0xffff0000.toInt() else 0xff00ff00.toInt()} else 0xff000000.toInt())
        }
        val area=ImageToolCodeGen.Roi(0,0,40,24)
        var saved by mutableStateOf<ImageToolOcrConfig?>(null)
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            // Reapplying identical service info clears UiAutomation's stale unmerged node cache.
            test.uiAutomation.serviceInfo=test.uiAutomation.serviceInfo
            fun walk(current:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(current==null || !current.refresh()) return null
                if(predicate(current)) return current
                for(i in 0 until current.childCount) walk(current.getChild(i))?.let {return it}
                return null
            }
            for(window in test.uiAutomation.windows.sortedByDescending {it.layer}) {
                val root=window.root ?: continue
                if(root.packageName?.toString()==test.targetContext.packageName && !root.className.toString().contains("PopupDecorView"))
                    walk(root)?.let {return it}
                root.recycle()
            }
            return walk(test.uiAutomation.rootInActiveWindow)
        }
        fun exists(label:String)=node {it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label}!=null
        fun waitFor(predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+15_000;while(!predicate() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(60);check(predicate()) {"GLYPH_PALETTE_QA_TIMEOUT"}}
        fun tap(x:Float,y:Float) {
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
                try {check(test.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
                SystemClock.sleep(20)
            }
        }
        fun visibleLabelBounds(label:String, clickable:Boolean=false):Rect? {
            var target=node {!it.isEditable && (it.text?.toString()?.let {text->if(clickable) text==label else text.contains(label)}==true || it.contentDescription?.toString()==label)}
            if(clickable) while(target!=null && !target.isClickable) target=target.parent
            val found=target ?: return null
            if(!found.isVisibleToUser) return null
            val bounds=Rect().also(found::getBoundsInScreen)
            if(bounds.isEmpty) return null
            val visible=Rect(bounds)
            var parent=found.parent
            while(parent!=null) {
                if(!visible.intersect(Rect().also(parent::getBoundsInScreen))) return null
                parent=parent.parent
            }
            test.uiAutomation.windows.firstOrNull {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}
                ?.let {visible.bottom=minOf(visible.bottom,Rect().also(it::getBoundsInScreen).top)}
            return bounds.takeIf {visible.contains(bounds.centerX(),bounds.centerY())}
        }
        fun reveal(label:String, clickable:Boolean=false) {
            for(forward in listOf(true,false)) repeat(6) {
                if(visibleLabelBounds(label,clickable)!=null) return
                if(!scrollOwnedQaForm(test,forward) {visibleLabelBounds(label,clickable)!=null}) return
            }
        }
        fun click(label:String) {
            try {test.uiAutomation.waitForIdle(150,1500)} catch(_:java.util.concurrent.TimeoutException) { }
            node {!it.isEditable && (it.text?.toString()==label || it.contentDescription?.toString()==label)}
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            test.waitForIdleSync()
            try {test.uiAutomation.waitForIdle(200,1500)} catch(_:java.util.concurrent.TimeoutException) { }
            reveal(label,clickable=true)
            var target:AccessibilityNodeInfo?=null
            waitFor {var n=node {!it.isEditable && (it.text?.toString()==label || it.contentDescription?.toString()==label)};while(n!=null && !n.isClickable) n=n.parent;target=n;n?.refresh()==true && n.isEnabled}
            var previous:Rect?=null
            var stableSince=SystemClock.elapsedRealtime()
            var observed:Rect?=null
            waitFor {
                val next=visibleLabelBounds(label,clickable=true)
                if(next==null || next!=previous) {previous=next;stableSince=SystemClock.elapsedRealtime()}
                observed=next
                next!=null && SystemClock.elapsedRealtime()-stableSince>=300
            }
            val rect=requireNotNull(observed) {"GLYPH_PALETTE_QA_CLICK_CLIPPED"}
            if(label=="追加并测试") test.uiAutomation.takeScreenshot()?.let {image ->
                try {File(activity.externalCacheDir,"qa-glyph-palette-before-append.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}
                finally {image.recycle()}
            }
            test.sendStatus(1,Bundle().apply {putString("stream","QA_GLYPH_PALETTE_CLICK:$label:${rect.flattenToString()}\n")})
            check(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {"GLYPH_PALETTE_QA_CLICK_ACTION:$label"}
            test.waitForIdleSync()
        }
        fun fill(label:String,text:String) {waitFor {node {it.isEditable && it.contentDescription?.toString()==label}!=null};check(node {it.isEditable && it.contentDescription?.toString()==label}?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)})==true)}
        fun disabled(label:String):Boolean? {
            var current=node {it.text?.toString()==label || it.contentDescription?.toString()==label} ?: return null
            while(true) {
                if(!current.isEnabled) return true
                // With unmerged accessibility nodes, Material's button can be a sibling of its text.
                for(index in 0 until current.childCount) current.getChild(index)?.let { child ->
                    if(child.className?.toString()=="android.widget.Button") return !child.isEnabled
                }
                if(current.isClickable) return false
                current=current.parent ?: return null
            }
        }
        fun pick(y:Float) {
            val rect=Rect().also {requireNotNull(node {it.contentDescription?.toString()=="字库取样预览"}).getBoundsInScreen(it)}
            val image=requireNotNull(test.uiAutomation.takeScreenshot())
            var point:Pair<Float,Float>?=null
            try {
                check(rect.intersect(0,0,image.width,image.height))
                // The keyboard may clip a scrolled preview. Locate a real public
                // red/green band inside its observed viewport, not its former 144dp origin.
                var best=0
                for(row in rect.top+2 until rect.bottom-2 step 2) {
                    var start=-1
                    for(column in rect.left+2 until rect.right-2) {
                        val color=image.getPixel(column,row)
                        val r=android.graphics.Color.red(color);val g=android.graphics.Color.green(color);val b=android.graphics.Color.blue(color)
                        // Exclude the red selection outline/handles: only sample the
                        // known public raster's saturated red/green interior.
                        val hit=if(y<.5f) r>80 && g<24 && b<24 else g>80 && r<24 && b<24
                        if(hit && start<0) start=column
                        if((!hit || column==rect.right-3) && start>=0) {
                            val length=column-start
                            if(length>best) {best=length;point=(start+length/2f) to row.toFloat()}
                            start=-1
                        }
                    }
                }
                check(best>=12) {"GLYPH_PALETTE_PUBLIC_BAND_NOT_VISIBLE"}
            } finally {image.recycle()}
            val selected=requireNotNull(point);tap(selected.first,selected.second)
        }
        fun capture(name:String) {val screen=test.uiAutomation.takeScreenshot() ?: return;try {File(activity.externalCacheDir,"qa-glyph-palette-$name.png").outputStream().use {screen.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {screen.recycle()}}
        fun stopOwned() {
            if(client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING,RuntimeEngineState.PAUSED,RuntimeEngineState.STOPPING)) {
                val proof=client.debugSnapshot(id);check(proof.status==com.autoscript.runtime.api.RuntimeDebugReply.SUCCESS && proof.runRevision>0 && proof.sessionGeneration==client.runtimeStates.value.sessionGeneration) {"GLYPH_PALETTE_RUN_IDENTITY"}
                check(client.requestStop());runBlocking {withTimeout(10_000) {client.runtimeStates.first {it.engineState==RuntimeEngineState.STOPPED}}}
            }
        }
        test.runOnMainSync {activity.setContent {AutoScriptTheme {
            if(showing) GlyphMakerDialog(snapshot,store,client,{snapshot=it},{showing=false},initialBitmap=bitmap,initialRoi=area,initialTolerance=0,
                onUseSaved={updated,config,roi->check(roi==area);snapshot=updated;saved=config;handoff=config;showing=false})
            handoff?.let {config -> ImageToolOcrDialog(bitmap,area,snapshot,client,onDismiss={handoff=null},
                onConfirm={selected,roi->check(selected==config && roi==area);accepted=true;handoff=null},confirmLabel="QA确认多色",initialConfig=config)}
        }}}
        try {
            record("glyph-palette-continuous-real-picks-append-deduplicate-and-keep-per-color-tolerance") {
                waitFor {exists("字库制作 · 固定字形") && node {it.text?.toString()?.let {text->text.contains("原图40×24") && (Regex("显示(\\d+)%").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0)>100}==true}!=null}
                click("取色");pick(.25f);waitFor {node {it.isEditable && it.text?.toString()=="FF0000"}!=null}
                pick(.75f);waitFor {exists("前景色2") && node {it.isEditable && it.text?.toString()=="00FF00"}!=null}
                fill("容差","8");click("前景色1");fill("容差","4");click("前景色2")
                waitFor {node {it.isEditable && it.contentDescription?.toString()=="容差" && it.text?.toString()=="8"}!=null}
                pick(.25f);check(!exists("前景色3"));capture("two-colors")
            }
            record("glyph-palette-incomplete-color-blocks-extraction-and-removal-restores-settings") {
                click("+颜色")
                waitFor {exists("前景色3")}
                reveal("按空隙批量切分")
                waitFor {disabled("按空隙批量切分")==true}
                click("前景色3");click("删除颜色");waitFor {exists("前景色2") && !exists("前景色3")}
                reveal("按空隙批量切分");waitFor {disabled("按空隙批量切分")==false}
            }
            record("glyph-palette-two-color-batch-and-real-rust-preview-agree") {
                click("按空隙批量切分");waitFor {exists("已标0/3")};fill("顺序字符","1.1");click("追加并测试")
                reveal("识别文本：1.1");waitFor {exists("识别文本：1.1") && exists("覆盖率 1000‰")};capture("recognized")
            }
            record("glyph-palette-save-use-retains-palette-and-invalidates-changed-preview-proof") {
                fill("字库名称","palette-digits")
                click("保存并识字");waitFor {handoff!=null && exists("取字 · 配置与测试")}
                val config=requireNotNull(saved);check(config.foregroundRgb==0xff0000 && config.tolerance==4 && config.additionalColors=="00FF00:8")
                click("测试识别");waitFor {exists("真实逐字位置") && disabled("QA确认多色")==false}
                fill("容差","5");waitFor {disabled("QA确认多色")==true};fill("容差","4")
                click("测试识别");waitFor {exists("真实逐字位置") && disabled("QA确认多色")==false}
                click("QA确认多色");waitFor {accepted};check(saved==config)
            }
            record("glyph-palette-saved-visual-blocks-compile-and-run-ordinary-and-detailed-results") {
                val config=requireNotNull(saved)
                val bytes=ByteArrayOutputStream().apply {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,this))}.toByteArray()
                val before=snapshot.resourcePaths();snapshot=store.importResource(id,ProjectResourceKind.IMAGE,"public-palette.png",bytes.inputStream(),before)
                val imagePath=(snapshot.resourcePaths()-before).single()
                val vars=listOf(ProjectVariable("paletteFrame",ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE))+
                    listOf("paletteText","paletteDetails","paletteDetailsText","paletteFoundText").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING)}+
                    listOf("paletteScore","paletteCoverage").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER)}
                snapshot=store.updateVariables(id,snapshot.manifest.variables+vars,snapshot.manifest.variables)
                snapshot=store.updateCapabilities(id,snapshot.manifest.capabilities.toSet()+setOf("ocr.glyph","vision.template"),snapshot.manifest.capabilities.toSet())
                fun args(vararg pairs:Pair<String,Any>)=JsonObject().apply {pairs.forEach {(key,value)->if(value is Number) addProperty(key,value) else addProperty(key,value.toString())}}
                fun block(kind:String,value:JsonObject)=VisualBlockTree(requireNotNull(BlockCatalog.find(kind)),value)
                val ordinary=config.arguments(area).apply {addProperty("frameVariable","paletteFrame");addProperty("textVariable","paletteText");addProperty("scoreVariable","paletteScore");addProperty("coverageVariable","paletteCoverage")}
                val detailed=args("frameVariable" to "paletteFrame","dictionaryPath" to config.dictionaryPath,"foregroundRgb" to config.foregroundRgb,
                    "tolerance" to config.tolerance,"additionalColors" to config.additionalColors,"similarityPermille" to 1000,"spaceGapColumns" to 0,
                    "left" to 0,"top" to 0,"right" to 40,"bottom" to 24,"resultVariable" to "paletteDetails")
                val flow=snapshot.manifest.flows.single()
                val source=StudioWorkflowAcceptanceFixture.source(listOf(block("screen.loadimage",args("imagePath" to imagePath,"resultVariable" to "paletteFrame")),
                    block("ocr.glyph",ordinary),block("ocr.glyphdetails",detailed),
                    block("ocr.findtext",args("detailsVariable" to "paletteDetails","text" to "1.1","resultVariable" to "paletteFoundText")),
                    block("vision.readresult",args("sourceVariable" to "paletteDetails","shape" to "GLYPH_SUMMARY","index" to 0,"field" to "text","resultVariable" to "paletteDetailsText")),
                    block("screen.release",args("frameVariable" to "paletteFrame")),block("task.sleep",args("milliseconds" to 60_000))),flow.rootBlockId)
                snapshot=store.saveFlows(snapshot,mapOf(flow.flowId to source))
                val compiled=client.compileVisualProject(id);check(compiled is VisualCompileResult.Success) {"GLYPH_PALETTE_COMPILE"}
                val frozen=store.openProject(id);val plan=RuntimeProjectPlan.fromVisualSnapshot(frozen,compiled.generationId).forEditorRun(frozen)
                check(runBlocking {withTimeout(30_000) {client.startProject(id,plan.luaSource,plan.resources,plan.capabilities,plan.designWidth,plan.designHeight,plan.scaleMode,
                    floatingControl=false,scriptUiJson=plan.scriptUiJson,requiresPointerInput=plan.requiresPointerInput,requiredVisionComponents=plan.requiredVisionComponents)}}) {"GLYPH_PALETTE_START"}
                runBlocking {withTimeout(15_000) {client.executionPosition.first {it?.projectId==id && it.nodeId==org.json.JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")}}}
                val values=client.debugSnapshot(id).variables.associate {it.name to it.value}
                check(values["paletteText"]=="1.1" && values["paletteDetailsText"]=="1.1" && values["paletteCoverage"]=="1000") {"GLYPH_PALETTE_RUNTIME_RESULT"}
                check(org.json.JSONArray(values["paletteFoundText"].orEmpty()).length() == 1) { "GLYPH_PALETTE_FIND_TEXT" }
                stopOwned()
            }
        } catch(failure:Throwable) {
            runCatching {capture("failure")}
            runCatching {
                val output=StringBuilder();var remaining=512
                fun walk(current:AccessibilityNodeInfo?) {
                    if(current==null || --remaining<0 || !current.refresh()) return
                    output.appendLine("${current.className} text=${current.text} desc=${current.contentDescription} enabled=${current.isEnabled} clickable=${current.isClickable} scrollable=${current.isScrollable} bounds=${Rect().also(current::getBoundsInScreen)}")
                    for(index in 0 until current.childCount.coerceAtMost(64)) walk(current.getChild(index))
                }
                for(window in test.uiAutomation.windows.sortedByDescending {it.layer}) {
                    val root=window.root ?: continue
                    if(root.packageName?.toString()==test.targetContext.packageName) walk(root)
                }
                File(activity.externalCacheDir,"qa-glyph-palette-controls.txt").writeText(output.toString())
            }
            throw failure
        }
        finally {stopOwned();test.runOnMainSync {showing=false;handoff=null};test.waitForIdleSync();bitmap.recycle()}
        return snapshot
    }
}
