package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** New public raster and an owned project only; never runs a user's script. */
internal object ImageEnhancementAcceptanceFixture {
    fun run(test:Instrumentation,activity:ComponentActivity,client:RuntimeClient,store:ProjectStore,initial:ProjectSnapshot,
        record:(String,()->Unit)->Unit):ProjectSnapshot {
        val id=initial.manifest.projectId;check(id.startsWith("project-qa-"))
        var snapshot by mutableStateOf(initial)
        val templatePixels=IntArray(64) {i->0xff000000.toInt() or ((i*31+32)%220 shl 16) or ((i*43+24)%210 shl 8) or ((i*17+16)%200)}
        val template=Bitmap.createBitmap(templatePixels,8,8,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(64*40) {0xff000000.toInt()}
        for(y in 0 until 16) for(x in 0 until 16) pixels[(y+8)*64+x+8]=templatePixels[y/2*8+x/2]
        pixels[1]=-1
        val bitmap=Bitmap.createBitmap(pixels,64,40,Bitmap.Config.ARGB_8888)
        fun import(name:String,image:Bitmap):String {
            val bytes=ByteArrayOutputStream().apply {check(image.compress(Bitmap.CompressFormat.PNG,100,this))}.toByteArray()
            val old=snapshot.manifest.resources.map {it["path"].asString}.toSet()
            snapshot=store.importResource(id,ProjectResourceKind.IMAGE,name,bytes.inputStream(),old)
            return snapshot.manifest.resources.single {it["path"].asString !in old}["path"].asString
        }
        val sourcePath=import("public-enhancement.png",bitmap);val templatePath=import("public-scale-template.png",template)
        var visible by mutableStateOf(true)
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            fun walk(current:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(current==null || !current.refresh()) return null
                if(predicate(current)) return current
                for(i in 0 until current.childCount) walk(current.getChild(i))?.let {return it}
                return null
            }
            // Nested confirmation/menu windows can precede the active-window focus notification.
            // Search only the topmost owned window, never an identical button behind the modal.
            val owner=test.targetContext.packageName
            for(window in test.uiAutomation.windows.sortedByDescending {it.layer}) {
                val root=window.root ?: continue
                if(root.packageName?.toString()==owner && !root.className.toString().contains("PopupDecorView")) return walk(root)
                root.recycle()
            }
            val root=test.uiAutomation.rootInActiveWindow ?: return null
            if(root.packageName?.toString()!=owner) {root.recycle();return null}
            return walk(root)
        }
        fun exists(label:String)=node {it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label}!=null
        fun waitFor(predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+15_000;while(!predicate() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(60);check(predicate()) {"ENHANCEMENT_UI_TIMEOUT"}}
        fun reveal(label:String) {
            for(forward in listOf(true,false)) repeat(6) {
                if(exists(label)) return
                if(!scrollOwnedQaForm(test,forward)) return@repeat
            }
        }
        fun click(label:String) {
            // Set-text and preview readiness publish semantics on a later frame; never click a stale disabled button.
            try {test.uiAutomation.waitForIdle(200,1500)} catch(_:java.util.concurrent.TimeoutException) { }
            reveal(label)
            var target:AccessibilityNodeInfo?=null
            waitFor {var n=node {!it.isEditable && (it.text?.toString()==label || it.contentDescription?.toString()==label)};while(n!=null && !n.isClickable) n=n.parent;target=n;n?.refresh()==true && n.isEnabled}
            val bounds=android.graphics.Rect().also(requireNotNull(target)::getBoundsInScreen)
            check(!bounds.isEmpty) {"ENHANCEMENT_CLICK_BOUNDS"}
            val down=SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try {check(test.uiAutomation.injectInputEvent(event,true)) {"ENHANCEMENT_CLICK_TOUCH"}} finally {event.recycle()}
                SystemClock.sleep(20)
            }
        }
        fun fill(label:String,value:String) {waitFor {node {it.isEditable && it.contentDescription?.toString()==label}!=null};check(node {it.isEditable && it.contentDescription?.toString()==label}?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)})==true)}
        fun confirm(title:String,label:String) {
            var target:AccessibilityNodeInfo?=null
            waitFor {
                val heading=node {it.text?.toString()==title} ?: return@waitFor false
                val anchor=android.graphics.Rect().also(heading::getBoundsInScreen)
                target=node {current->
                    val bounds=android.graphics.Rect().also(current::getBoundsInScreen)
                    current.text?.toString()==label && !current.isEditable && current.isEnabled &&
                        !bounds.isEmpty && bounds.top>=anchor.bottom
                }
                target!=null
            }
            val bounds=android.graphics.Rect().also(requireNotNull(target)::getBoundsInScreen)
            val down=SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try {check(test.uiAutomation.injectInputEvent(event,true)) {"ENHANCEMENT_CONFIRM_TOUCH"}} finally {event.recycle()}
                SystemClock.sleep(20)
            }
        }
        fun args(vararg pairs:Pair<String,Any>)=JsonObject().apply {pairs.forEach {(key,value)->when(value) {is Number->addProperty(key,value);is Boolean->addProperty(key,value);else->addProperty(key,value.toString())}}}
        fun block(kind:String,a:JsonObject=JsonObject())=VisualBlockTree(requireNotNull(BlockCatalog.find(kind)),a)
        val ints=listOf("start","clock","elapsed","attempt","outcome","fresh","streak","distance","px","py","found","x","y","score","value")
        val vars=ints.map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER)}+
            listOf("details","mapped","matches").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING)}+
            listOf("frame","previous","processed").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE)}
        snapshot=store.updateVariables(id,snapshot.manifest.variables+vars,snapshot.manifest.variables)
        snapshot=store.updateCapabilities(id,snapshot.manifest.capabilities.toSet()+setOf("core.task","screen.capture","vision.pixel","vision.template"),snapshot.manifest.capabilities.toSet())
        fun stop() {if(client.runtimeStates.value.engineState in setOf(RuntimeEngineState.RUNNING,RuntimeEngineState.PAUSED,RuntimeEngineState.STOPPING)) {
            val proof=client.debugSnapshot(id)
            check(proof.status==com.autoscript.runtime.api.RuntimeDebugReply.SUCCESS && proof.runRevision>0 &&
                proof.sessionGeneration==client.runtimeStates.value.sessionGeneration) {"QA_RUN_IDENTITY_CHANGED"}
            check(client.requestStop());runBlocking {withTimeout(10_000) {client.runtimeStates.first {it.engineState==RuntimeEngineState.STOPPED}}}
        }}
        fun execute(forest:List<VisualBlockTree>):Map<String,String> {
            stop();val current=store.openProject(id);val flow=current.manifest.flows.first {it.flowId==current.manifest.entryFlowId}
            val source=StudioWorkflowAcceptanceFixture.source(forest+block("task.sleep",args("milliseconds" to 60_000)),flow.rootBlockId)
            snapshot=store.saveFlows(current,mapOf(flow.flowId to source))
            val compiled=client.compileVisualProject(id);check(compiled is VisualCompileResult.Success) {"ENHANCEMENT_COMPILE"}
            val frozen=store.openProject(id);val plan=RuntimeProjectPlan.fromVisualSnapshot(frozen,compiled.generationId).forEditorRun(frozen)
            check(runBlocking {withTimeout(30_000) {client.startProject(id,plan.luaSource,plan.resources,plan.capabilities,plan.designWidth,plan.designHeight,plan.scaleMode,
                floatingControl=false,scriptUiJson=plan.scriptUiJson,requiresPointerInput=plan.requiresPointerInput,requiredVisionComponents=plan.requiredVisionComponents)}}) {"ENHANCEMENT_START"}
            val tail=JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
            runBlocking {withTimeout(20_000) {client.executionPosition.first {it?.projectId==id && it.nodeId==tail}}}
            return client.debugSnapshot(id).variables.associate {it.name to it.value}.also {stop()}
        }
        val insertionFlow = snapshot.manifest.flows.first { it.flowId == snapshot.manifest.entryFlowId }
        val insertionEditor = VisualEditorState.create(snapshot.flowSources.getValue(insertionFlow.flowId), insertionFlow.rootBlockId)
        var insertions by mutableIntStateOf(0)
        val insertionTarget = validatedWorkbenchTarget(store, client, id, insertionFlow.flowId, insertionEditor,
            currentSnapshot = { snapshot }, drafts = { snapshot.flowSources + (insertionFlow.flowId to insertionEditor.currentSource) },
            canApply = { client.runtimeStates.value.engineState !in setOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING) },
            onManageVariables = {}, onCommitted = { insertions++ })
        test.runOnMainSync {activity.setContent {AutoScriptTheme {if(visible) ImageProcessingWorkbench(bitmap,snapshot,store,client,{snapshot=it},{visible=false},originalImagePath=sourcePath,insertionTarget=insertionTarget)}}}
        try {
            record("enhancement-mask-rectangle-preview-undo-redo-and-apply") {
                click("蒙版");waitFor {exists("模板蒙版")}
                fill("左","0");fill("上","0");fill("右","4");fill("下","40");click("忽略此矩形")
                reveal("1/64笔");waitFor {exists("1/64笔")};click("撤销");reveal("0/64笔");waitFor {exists("0/64笔")};click("重做")
                waitFor {exists("1/64笔") && exists("有效像素 2400/2560px")}
                click("应用蒙版");waitFor {exists("处理完成 · 64×40px")}
            }
            record("enhancement-mask-saves-transparent-original-pixels-without-overwriting-source") {
                val before=store.openProject(id).manifest.resources.map {it["path"].asString}.toSet()
                click("保存当前图");waitFor {exists("图片名称")}
                UsageTestUi(test).replaceFirst("public-mask-copy")
                confirm("保存图片", "保存图片")
                waitFor {store.openProject(id).manifest.resources.size==before.size+1 && exists("已保存：")}
                val path=store.openProject(id).manifest.resources.single {it["path"].asString !in before}["path"].asString
                val saved=requireNotNull(android.graphics.BitmapFactory.decodeFile(imageRecognitionFile(snapshot,path).path))
                val original=requireNotNull(android.graphics.BitmapFactory.decodeFile(imageRecognitionFile(snapshot,sourcePath).path))
                try {check(saved.width==64 && saved.height==40 && saved.getPixel(0,0) ushr 24==0 && saved.getPixel(8,8)==bitmap.getPixel(8,8))
                    check(original.width==64 && original.height==40 && original.getPixel(0,0) ushr 24==255)}
                finally {saved.recycle();original.recycle()}
            }
            record("enhancement-insertion-position-cancel-preserves-source-and-output-choices") {
                click("加入处理积木"); waitFor {exists("加入处理流水线")}
                click("运行时截图"); click("最终图像变量"); click("processed")
                click("选择加入位置"); waitFor {exists("图像加入位置")}; click("取消")
                waitFor {exists("加入处理流水线") && exists("● 运行时截图") && exists("processed")}
                check(insertions == 0 && insertionEditor.currentSource == snapshot.flowSources.getValue(insertionFlow.flowId))
            }
            record("enhancement-insertion-stale-draft-rejected-and-original-configuration-restored") {
                click("选择加入位置"); waitFor {exists("图像加入位置")}
                test.runOnMainSync {insertionEditor.insertBlock(requireNotNull(BlockCatalog.find("task.sleep")), args("milliseconds" to 1))}
                val changed = insertionEditor.currentSource
                click("确定"); waitFor {exists("加入处理流水线")}
                check(insertions == 0 && insertionEditor.currentSource == changed)
                check(exists("● 运行时截图") && exists("processed"))
            }
            record("enhancement-insertion-validated-once-selects-new-row-keeps-workbench-and-one-undo") {
                val before = insertionEditor.currentSource
                val previous = insertionEditor.rows.map {it.nodeId}.toSet()
                click("选择加入位置"); waitFor {exists("图像加入位置")}; click("确定")
                waitFor {insertions == 1 && !exists("加入处理流水线") && exists("图片工作台预览")}
                check(insertionEditor.selectedNodeId !in previous)
                check(store.openProject(id).flowSources == snapshot.flowSources) {"QA_WORKBENCH_INSERT_SAVED_OR_RAN"}
                test.runOnMainSync {check(insertionEditor.undo())}
                check(insertionEditor.currentSource == before)
            }
            record("enhancement-cleanup-corrects-invalid-area-after-mode-switch-and-applies") {
                click("数字清理");waitFor {exists("数字图像清理")};click("保留小数点等小区域（面积1）")
                waitFor {node {it.isEditable && it.contentDescription?.toString()=="保留面积≥" && it.text?.toString()=="1"}!=null}
                fill("保留面积≥","0")
                waitFor {node {it.isEditable && it.contentDescription?.toString()=="保留面积≥" && it.text?.toString()=="0"}!=null}
                click("处理");click("腐蚀 · 变细")
                waitFor {exists("保留面积≥")};fill("保留面积≥","1")
                click("应用清理");waitFor {!exists("数字图像清理")};click("撤销")
            }
            record("enhancement-recipe-save-copy-load-confirm-does-not-run-script") {
                click("图像方案");waitFor {exists("图像识别方案")};fill("方案名","公开方案");click("保存当前")
                waitFor {store.readImageRecipes(id).size==1};click("复制所选")
                waitFor {exists("同名内容已存在")};click("编号另存")
                waitFor {store.readImageRecipes(id).size==2};fill("方案名","备用方案");click("重命名")
                waitFor {store.readImageRecipes(id).any {it.name=="备用方案"}};fill("搜索","备用方案")
                click("删除");waitFor {exists("删除图像方案？")};click("取消");waitFor {!exists("删除图像方案？")};check(store.readImageRecipes(id).size==2)
                click("删除");waitFor {exists("删除图像方案？")};confirm("删除图像方案？","删除");waitFor {store.readImageRecipes(id).size==1 && !exists("删除图像方案？")}
                fill("搜索","");click("载入");waitFor {exists("载入图像方案？")};click("载入方案")
                waitFor {exists("已载入 公开方案")};check(client.runtimeStates.value.engineState !in setOf(RuntimeEngineState.RUNNING,RuntimeEngineState.PAUSED))
            }
            record("enhancement-coordinate-preview-rejects-file-screen-origin") {
                click("坐标还原");waitFor {exists("文件图片只有原图坐标")};click("知道了")
            }
            record("enhancement-difference-preview-uses-explicit-candidate-and-keeps-proof-separate") {
                click("找图测试");waitFor {exists("处理图 · 找图测试")};click("分析候选位置差异")
                waitFor {exists("候选位置 · 差异分析")};click("分析此位置");waitFor {exists("本地分析")};click("返回");click("返回")
            }
            record("enhancement-multiscale-preview-uses-real-rust-and-rejects-unbounded-range") {
                val config=RecognitionPreviewConfiguration(Reply.SCALED_TEMPLATE,templatePath,0,1000,64,40,ImageToolCodeGen.Roi(0,0,64,40),0,2000,2000,100)
                val reply=runBlocking {client.testRecognitionPreview(System.nanoTime().coerceAtLeast(1),Reply.SCALED_TEMPLATE,
                    imageRecognitionFile(snapshot,sourcePath),imageRecognitionFile(snapshot,templatePath),config.options())}
                check(reply.status==Reply.SUCCESS && reply.x==8 && reply.y==8 && reply.scorePermille==1000)
                check(JSONObject(com.google.gson.JsonParser.parseString(reply.text).asJsonArray[0].toString()).getInt("scalePermille")==2000)
                check(!Reply.validOptions(Reply.SCALED_TEMPLATE,config.options().apply {this[8]=250;this[9]=4000;this[10]=1}))
            }
            test.runOnMainSync {visible=false};test.waitForIdleSync()
            record("enhancement-region-wait-entry-requires-no-template-or-unused-result-variables") {
                var open by mutableStateOf(true);var forest:List<VisualBlockTree>?=null
                val configured=args("frameVariable" to "frame","left" to 0,"top" to 0,"right" to 20,"bottom" to 20,"tolerance" to 8)
                test.runOnMainSync {activity.setContent {AutoScriptTheme {if(open) ImageExtraEntryDialog(requireNotNull(BlockCatalog.find("screen.compareframes")),
                    configured,snapshot,requireNotNull(snapshot.manifest.entryFlowId),true,{}, {open=false},{error("UNEXPECTED_SINGLE_COMPARISON")},
                    onWorkflow={forest=it;open=false})}}}
                click("区域变化 / 稳定等待");waitFor {exists("不需要模板、字库或识别输出")}
                click("快速分配");waitFor {exists("确认使用声明变量？")};click("使用")
                click("两份图像变量均专用于本组合，允许覆盖和释放");click("选择位置并加入")
                waitFor {forest!=null};val all=requireNotNull(forest).flatMap {it.flattened()}
                check(all.count {it.contract.kind=="screen.capture"}==2 && all.none {it.contract.kind=="vision.findimage"})
            }
            record("enhancement-real-compiled-mask-and-scale-share-existing-frame-pool") {
                val mask=ProjectImageMask(64,40,listOf(ProjectMaskStroke("rect",false,0,listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(4,40)))))
                val values=execute(listOf(block("screen.loadimage",args("imagePath" to sourcePath,"resultVariable" to "frame")),
                    block("screen.transform",args("frameVariable" to "frame","mode" to "mask","width" to 0,"height" to 0,"mask" to mask.encode(),"resultVariable" to "processed")),
                    block("vision.findscaled",RecognitionPreviewConfiguration(Reply.SCALED_TEMPLATE,templatePath,0,1000,64,40,ImageToolCodeGen.Roi(0,0,64,40),0,2000,2000,100).arguments().apply {addProperty("frameVariable","processed");addProperty("resultVariable","matches")}),
                    block("vision.readresult",args("sourceVariable" to "matches","shape" to "SCALED_IMAGE","index" to 1,"field" to "scalePermille","resultVariable" to "value")),
                    block("screen.release",args("frameVariable" to "processed")),block("screen.release",args("frameVariable" to "frame"))))
                check(values["value"]=="2000")
            }
            record("enhancement-root-capture-geometry-mapping-and-fresh-stability-compile-and-run") {
                val sample=args("frameVariable" to "frame","foundVariable" to "found","xVariable" to "x","yVariable" to "y")
                sample.add("region",args("left" to 0,"top" to 0,"right" to 20,"bottom" to 20))
                val draft=RecognitionWaitDraft(mode=RecognitionWaitMode.REGION_STABLE,timeout="10000",interval="150",consecutive="2",changePermille="1000",
                    start="start",clock="clock",elapsed="elapsed",attempt="attempt",outcome="outcome",previousFrame="previous",difference="details",fresh="fresh",streak="streak",distance="distance",previousX="px",previousY="py",confirmFrames=true)
                val choices=store.openProject(id).manifest.variables.map {CalculationVariable(it.name,it.type,"全局")}
                val stable=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("screen.compareframes")),sample,draft,choices)
                val values=execute(stable+listOf(block("screen.capture",args("resultVariable" to "frame")),
                    block("screen.transform",args("frameVariable" to "frame","mode" to "flipX","width" to 0,"height" to 0,"resultVariable" to "processed")),
                    block("screen.mappoint",args("frameVariable" to "processed","x" to 0,"y" to 0,"xVariable" to "","yVariable" to "","resultVariable" to "mapped")),
                    block("vision.readresult",args("sourceVariable" to "mapped","shape" to "SCREEN_POINT","index" to 0,"field" to "x","resultVariable" to "value")),
                    block("screen.release",args("frameVariable" to "processed")),block("screen.release",args("frameVariable" to "frame"))))
                check(values["outcome"]=="1" && values["fresh"]=="1" && requireNotNull(values["value"]).toInt()>0)
            }
            record("enhancement-consecutive-fresh-matches-observe-real-position-without-touch") {
                val surface=object:android.view.View(activity) {
                    val paint=android.graphics.Paint()
                    override fun onDraw(canvas:android.graphics.Canvas) {
                        canvas.drawColor(android.graphics.Color.BLACK)
                        for(y in 0 until 8) for(x in 0 until 8) {paint.color=templatePixels[y*8+x];canvas.drawRect(20f+x,100f+y,21f+x,101f+y,paint)}
                    }
                }
                test.runOnMainSync {activity.setContentView(surface)};test.waitForIdleSync()
                val position=IntArray(2);test.runOnMainSync {surface.getLocationOnScreen(position)}
                val left=position[0]+20;val top=position[1]+100
                val sample=args("frameVariable" to "frame","imagePath" to templatePath,"tolerance" to 0,"similarityPermille" to 1000,
                    "direction" to 0,"foundVariable" to "found","xVariable" to "x","yVariable" to "y")
                sample.add("region",args("left" to left,"top" to top,"right" to left+8,"bottom" to top+8))
                val draft=RecognitionWaitDraft(mode=RecognitionWaitMode.STABLE,timeout="10000",interval="150",consecutive="3",driftPx="0",
                    start="start",clock="clock",elapsed="elapsed",attempt="attempt",outcome="outcome",previousFrame="previous",difference="details",fresh="fresh",streak="streak",distance="distance",previousX="px",previousY="py",confirmFrames=true)
                val choices=store.openProject(id).manifest.variables.map {CalculationVariable(it.name,it.type,"全局")}
                val values=execute(recognitionWaitBlocks(requireNotNull(BlockCatalog.find("vision.findimage")),sample,draft,choices))
                check(values["outcome"]=="1" && values["streak"]=="3" && values["x"]==left.toString() && values["y"]==top.toString())
            }
            record("enhancement-recipes-survive-backup-without-pixels-proof-or-script-changes") {
                val before=store.openProject(id);val bytes=ByteArrayOutputStream();store.exportProjectBackup(id,bytes)
                val restored=store.importProjectBackup(bytes.toByteArray().inputStream())
                try {check(store.readImageRecipes(restored.manifest.projectId)==store.readImageRecipes(id));check(before.flowSources==restored.flowSources)}
                finally {store.deleteProject(restored.manifest.projectId)}
            }
        } catch(failure:Throwable) {
            // This Activity contains only this fixture's public raster and owned project.
            runCatching {test.uiAutomation.takeScreenshot()?.let {image ->
                try {java.io.File(requireNotNull(activity.externalCacheDir),"qa-image-enhancement-failure.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}
                finally {image.recycle()}
            }}
            throw failure
        } finally {stop();test.runOnMainSync {visible=false};test.waitForIdleSync();bitmap.recycle();template.recycle()}
        return store.openProject(id)
    }
}
