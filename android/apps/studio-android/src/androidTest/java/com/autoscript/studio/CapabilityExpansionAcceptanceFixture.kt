package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import com.autoscript.core.model.RuntimeEngineState
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RuntimeDebugReply
import com.autoscript.runtime.api.RuntimeProtocol
import com.autoscript.runtime.client.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual compiled calls, Root touch, interactive overlay and production IME, owned QA data only. */
internal object CapabilityExpansionAcceptanceFixture {
    fun run(test:Instrumentation,activity:ComponentActivity,client:RuntimeClient,store:ProjectStore,initial:ProjectSnapshot,
        record:(String,()->Unit)->Unit,includeProductionIme:Boolean=false):ProjectSnapshot {
        val id=initial.manifest.projectId;check(id.startsWith("project-qa-"))
        var snapshot=store.updateCapabilities(id,setOf("core.task","vision.template","vision.opencv","ocr.onnx","input.multi","input.ime","network.http","device.info","device.display","ui.control","ui.floating.interactive","storage.data"),initial.manifest.capabilities.toSet())
        val variables=listOf("value","clock").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.INTEGER)}+
            listOf("text","response","frameInfo","event","pages","controls").map {ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.STRING)}+
            ProjectVariable("frame",ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE)
        snapshot=store.updateVariables(id,variables,snapshot.manifest.variables)
        val definition=com.autoscript.script.ui.ScriptUiDefinition(version=2,pages=listOf(com.autoscript.script.ui.UiPage()),
            fields=listOf(com.autoscript.script.ui.UiField(id="publicField",label="公开测试字段",kind="text",required=false,initialValue="PUBLIC_QA_DO_NOT_EXPORT",
                ui=com.autoscript.script.ui.UiPresentation(control="INPUT",pageId="main",x=20,y=20,width=400,height=48,fontPx=20))))
        snapshot=store.updateRunnerUi(id,definition.toJson(),snapshot.manifest.runnerUi)
        val bitmap=Bitmap.createBitmap(16,12,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff9955ee.toInt())}
        val bytes=try {ByteArrayOutputStream().apply {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,this))}.toByteArray()} finally {bitmap.recycle()}
        snapshot=store.importResource(id,ProjectResourceKind.IMAGE,"public-expansion.png",bytes.inputStream(),emptySet())
        val path=snapshot.manifest.resources.single()["path"].asString
        fun importImage(name:String,image:Bitmap):String {
            val old=snapshot.manifest.resources.map {it["path"].asString}.toSet()
            val data=ByteArrayOutputStream().apply {check(image.compress(Bitmap.CompressFormat.PNG,100,this))}.toByteArray()
            snapshot=store.importResource(id,ProjectResourceKind.IMAGE,name,data.inputStream(),old)
            return snapshot.manifest.resources.single {it["path"].asString !in old}["path"].asString
        }
        fun args(vararg values:Pair<String,Any>)=JsonObject().apply {values.forEach {(key,value)->when(value) {is Number->addProperty(key,value);is Boolean->addProperty(key,value);else->addProperty(key,value.toString())}}}
        fun block(kind:String,vararg values:Pair<String,Any>)=VisualBlockTree(requireNotNull(BlockCatalog.find(kind)),args(*values))
        fun await(predicate:()->Boolean) {val end=SystemClock.elapsedRealtime()+12000;while(!predicate() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(50);check(predicate()) {"EXPANSION_QA_TIMEOUT"}}
        val active=setOf(RuntimeEngineState.RUNNING,RuntimeEngineState.PAUSED,RuntimeEngineState.STOPPING)
        fun stop() {
            if(client.runtimeStates.value.engineState in active) {
                val proof=client.debugSnapshot(id);check(proof.status==RuntimeDebugReply.SUCCESS && proof.runRevision>0 && proof.sessionGeneration==client.runtimeStates.value.sessionGeneration) {"EXPANSION_QA_RUN_CHANGED"}
                check(client.requestStop());runBlocking {withTimeout(10000) {client.runtimeStates.first {it.engineState !in active}}}
            }
        }
        fun run(nodes:List<VisualBlockTree>,during:()->Unit={},inspect:(Map<String,String>)->Unit) {
            try {
                val flow=snapshot.manifest.flows.single()
                val source=StudioWorkflowAcceptanceFixture.source(nodes+block("task.sleep","milliseconds" to 60000),flow.rootBlockId)
                snapshot=store.saveFlows(store.openProject(id),mapOf(flow.flowId to source))
                val compiled=client.compileVisualProject(id)
                if(compiled !is VisualCompileResult.Success) test.sendStatus(1,android.os.Bundle().apply {
                    putString("stream","QA_COMPILE_DIAGNOSTIC:${compiled.toString().take(800)}\n")
                })
                // This fixture owns every source and value. Keep diagnostics bounded to its public QA data.
                check(compiled is VisualCompileResult.Success) {
                    when(compiled) {
                        is VisualCompileResult.Invalid->"EXPANSION_QA_COMPILE:${compiled.diagnostic.code}:${compiled.diagnostic.line}:${compiled.diagnostic.message.take(600)}"
                        is VisualCompileResult.Unavailable->"EXPANSION_QA_COMPILE_UNAVAILABLE:${compiled.message.take(600)}"
                        else->"EXPANSION_QA_COMPILE"
                    }
                }
                snapshot=store.openProject(id);val plan=RuntimeProjectPlan.fromVisualSnapshot(snapshot,compiled.generationId).forEditorRun(snapshot)
                check(runBlocking {withTimeout(30000) {client.startProject(id,plan.luaSource,plan.resources,plan.capabilities,plan.designWidth,plan.designHeight,plan.scaleMode,
                    floatingControl=false,scriptUiJson=plan.scriptUiJson,requiresPointerInput=plan.requiresPointerInput,requiredVisionComponents=plan.requiredVisionComponents)}}) {"EXPANSION_QA_START"}
                during()
                val tail=JSONObject(source.lineSequence().last(String::isNotBlank)).getString("nodeId")
                runBlocking {withTimeout(15000) {client.executionPosition.first {it?.projectId==id && it.nodeId==tail}}}
                val result=client.debugSnapshot(id);check(result.status==RuntimeDebugReply.SUCCESS) {"EXPANSION_QA_DEBUG"}
                inspect(result.variables.associate {it.name to it.value})
            } catch(failure:Throwable) {
                test.sendStatus(1,android.os.Bundle().apply {putString("stream","QA_RUN_DIAGNOSTIC:${client.runtimeStates.value.engineState}:${client.runtimeStates.value.message?.take(1000)}\n")})
                throw failure
            } finally {stop()}
        }
        fun imageBounds():Rect? {
            fun find(node:android.view.accessibility.AccessibilityNodeInfo?):Rect? {
                if(node==null) return null
                try {
                    if(node.contentDescription?.toString()=="脚本交互图片") return Rect().also(node::getBoundsInScreen)
                    for(index in 0 until node.childCount) find(node.getChild(index))?.let {return it}
                } finally {node.recycle()}
                return null
            }
            for(window in test.uiAutomation.windows) find(window.root)?.let {return it}
            return null
        }
        fun gesture(points:List<Pair<Float,Float>>) {
            val start=SystemClock.uptimeMillis()
            points.forEachIndexed {index,point->
                val action=if(index==0) MotionEvent.ACTION_DOWN else if(index==points.lastIndex) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,point.first,point.second,0).apply {source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try {check(test.uiAutomation.injectInputEvent(event,true)) {"EXPANSION_QA_TOUCH_REJECTED"}} finally {event.recycle()}
                SystemClock.sleep(30)
            }
        }
        try {
            record("expansion-codec-path-file-frame-and-device-real-rust") {
                run(listOf(block("text.urlencode","text" to "智构 A+1","mode" to "component","resultVariable" to "text"),
                    block("text.urldecode","text" to "","textVariable" to "text","mode" to "component","resultVariable" to "text"),
                    block("files.write","path" to "public.txt","text" to "PUBLIC_QA"),
                    block("files.modifiedtime","path" to "public.txt","resultVariable" to "clock"),
                    block("screen.loadimage","imagePath" to path,"resultVariable" to "frame"),
                    block("screen.frameinfo","frameVariable" to "frame","resultVariable" to "frameInfo"),
                    block("screen.release","frameVariable" to "frame"),
                    block("system.info","section" to "permissions","resultVariable" to "response"))) {values->
                    check(values["text"]=="智构 A+1" && (values["clock"]?.toLongOrNull() ?: 0)>0)
                    val info=JSONObject(values["frameInfo"].orEmpty());check(info.getInt("width")==16 && info.getInt("height")==12 && !info.getBoolean("hasScreenOrigin"))
                    check(!JSONObject(values["response"].orEmpty()).getBoolean("captureTested"))
                }
            }
            record("expansion-http-loopback-no-private-network") {
                ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {server->
                    val worker=Thread {server.accept().use {socket->socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();while(reader.readLine()?.isNotEmpty()==true) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 14\r\nConnection: close\r\n\r\nPUBLIC_QA_HTTP".toByteArray())}}.apply {isDaemon=true;start()}
                    try {run(listOf(block("network.request","method" to "GET","url" to "http://127.0.0.1:${server.localPort}/qa","headersJson" to "{}","body" to "","timeoutMs" to 4000,"maxResponseBytes" to 1024,"resultVariable" to "response"))) {values->
                        val response=JSONObject(values["response"].orEmpty());check(response.getInt("status")==200 && String(android.util.Base64.decode(response.getString("bodyBase64"),0))=="PUBLIC_QA_HTTP")
                    }} finally {server.close();worker.join(5000);check(!worker.isAlive)}
                }
            }
            record("expansion-window-metadata-excludes-control-values") {
                run(listOf(block("ui.command","controlId" to "window","operation" to "get:pages","value" to "","resultVariable" to "pages"),
                    block("ui.command","controlId" to "window","operation" to "get:controls","value" to "","resultVariable" to "controls"))) {values->
                    val pages=org.json.JSONArray(values["pages"].orEmpty());check(pages.length()==1 && pages.getJSONObject(0).getString("id")==definition.pages.single().id)
                    val controls=JSONObject(values["controls"].orEmpty()).getJSONArray("controls");check(controls.length()==1 && controls.getJSONObject(0).getString("id")=="publicField")
                    check(!values["controls"].orEmpty().contains("PUBLIC_QA_DO_NOT_EXPORT"))
                }
            }
            record("expansion-display-information-and-run-owned-awake-enable-disable") {
                run(listOf(block("system.keepawake","enabled" to 1,"resultVariable" to "value"),
                    block("system.info","section" to "display","resultVariable" to "response"),
                    block("system.keepawake","enabled" to 0,"resultVariable" to "value"))) {values->
                    val display=JSONObject(values["response"].orEmpty());check(display.getInt("widthPx")>0 && display.getInt("heightPx")>0 && values["value"]=="1")
                }
            }
            record("expansion-feature-preview-and-compiled-resource-path-use-real-opencv") {
                val template=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                val scene=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
                val templatePath:String;val scenePath:String
                try {
                    val canvas=android.graphics.Canvas(template);canvas.drawColor(android.graphics.Color.WHITE)
                    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);val random=java.util.Random(193)
                    repeat(180) {paint.color=android.graphics.Color.rgb(random.nextInt(180),random.nextInt(180),random.nextInt(180));canvas.drawCircle(random.nextInt(120)+4f,random.nextInt(120)+4f,random.nextInt(5)+1f,paint)}
                    android.graphics.Canvas(scene).apply {drawColor(android.graphics.Color.WHITE);drawBitmap(template,136f,136f,null)}
                    templatePath=importImage("public-feature-template.png",template);scenePath=importImage("public-feature-scene.png",scene)
                } finally {template.recycle();scene.recycle()}
                val configuration=RecognitionPreviewConfiguration(com.autoscript.runtime.api.RecognitionPreviewReply.FEATURE_TEMPLATE,templatePath,0,500,400,400)
                val preview=runBlocking {client.testRecognitionPreview(System.nanoTime().coerceAtLeast(1),configuration.operation,
                    imageRecognitionFile(snapshot,scenePath),imageRecognitionFile(snapshot,templatePath),configuration.options())}
                check(preview.status==com.autoscript.runtime.api.RecognitionPreviewReply.SUCCESS && kotlin.math.abs(preview.x-136)<=8 && kotlin.math.abs(preview.y-136)<=8)
                run(listOf(block("screen.loadimage","imagePath" to scenePath,"resultVariable" to "frame"),
                    block("vision.findfeatures","frameVariable" to "frame","imagePath" to templatePath,"left" to 0,"top" to 0,"right" to 400,"bottom" to 400,"minimumScorePermille" to 500,"resultVariable" to "response"),
                    block("screen.release","frameVariable" to "frame"))) {values->
                    val result=org.json.JSONArray(values["response"].orEmpty());check(result.length()==1)
                    check(kotlin.math.abs(result.getJSONObject(0).getInt("x")-136)<=8 && kotlin.math.abs(result.getJSONObject(0).getInt("y")-136)<=8)
                }
            }
            record("expansion-v5-preview-and-compiled-digit-ocr-keep-explicit-selection") {
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.BLACK;textSize=32f;typeface=android.graphics.Typeface.SANS_SERIF}
                val image=Bitmap.createBitmap(kotlin.math.ceil(paint.measureText("1234567890")).toInt()+24,56,Bitmap.Config.ARGB_8888)
                val imagePath:String;val width=image.width
                try {android.graphics.Canvas(image).apply {drawColor(android.graphics.Color.WHITE);drawText("1234567890",12f,39f,paint)};imagePath=importImage("public-ocr-digits.png",image)} finally {image.recycle()}
                val reply=com.autoscript.runtime.api.RecognitionPreviewReply
                val preview=runBlocking {client.testRecognitionPreview(System.nanoTime().coerceAtLeast(1),reply.ALPHANUMERIC_V5,
                    imageRecognitionFile(snapshot,imagePath),null,intArrayOf(0,0,width,56,500,0xffffff,0,0))}
                check(preview.status==reply.SUCCESS && preview.text=="1234567890")
                run(listOf(block("screen.loadimage","imagePath" to imagePath,"resultVariable" to "frame"),
                    block("ocr.alphanumericv5","frameVariable" to "frame","left" to 0,"top" to 0,"right" to width,"bottom" to 56,"minimumScorePermille" to 500,"resultVariable" to "response"),
                    block("screen.release","frameVariable" to "frame"))) {values->check(JSONObject(values["response"].orEmpty()).getString("text")=="1234567890")}
            }
            for(drag in listOf(false,true)) record(if(drag) "expansion-image-window-real-drag-event" else "expansion-image-window-real-click-event") {
                run(listOf(block("floating.image","name" to "qaImage","imagePath" to path,"x" to 100,"y" to 150,"width" to 96,"height" to 96,"draggable" to 1,"resultVariable" to "value"),
                    block("floating.waitevent","name" to "qaImage","timeoutMs" to 6000,"resultVariable" to "event"),block("floating.closeimage","name" to "qaImage","resultVariable" to "value")),during={
                    await {imageBounds()!=null};val bounds=requireNotNull(imageBounds());val x=bounds.exactCenterX();val y=bounds.exactCenterY()
                    gesture(if(drag) listOf(x to y,x+12 to y+10,x+28 to y+24,x+40 to y+30) else listOf(x to y,x to y))
                }) {values->check(JSONObject(values["event"].orEmpty()).getString("kind")==if(drag) "drag" else "click")}
                await {imageBounds()==null}
            }
            for(pointerCount in listOf(2,5)) record(if(pointerCount==2) "expansion-real-root-two-pointer-motion-events" else "expansion-real-root-five-pointer-motion-events") {
                check(client.inputFeatures() and RuntimeProtocol.INPUT_FEATURE_MULTI_POINTER!=0) {"MULTI_TOUCH_NOT_NEGOTIATED"}
                val maximum=AtomicInteger();val ids=AtomicReference<Set<Int>>(emptySet())
                test.runOnMainSync {activity.setContentView(object:View(activity) {override fun onTouchEvent(event:MotionEvent):Boolean {
                    maximum.updateAndGet {maxOf(it,event.pointerCount)};ids.updateAndGet {it+(0 until event.pointerCount).map(event::getPointerId)};return true
                }})}
                val nodes=List(pointerCount) {p->block("input.touchdown","pointerId" to p,"x" to (160+p*55),"y" to 400,"resultVariable" to "value")}+
                    List(pointerCount) {p->block("input.touchmove","pointerId" to p,"x" to (150+p*60),"y" to 410,"resultVariable" to "value")}+
                    block("task.sleep","milliseconds" to 120)+List(pointerCount) {index->block("input.touchup","pointerId" to (pointerCount-1-index),"resultVariable" to "value")}
                run(nodes) {
                    await {maximum.get()==pointerCount && ids.get()==(0 until pointerCount).toSet()}
                }
            }
            for(pointerCount in listOf(2,5)) record(if(pointerCount==2) "expansion-stop-cancels-both-root-pointers-and-next-run-recovers" else "expansion-stop-cancels-five-root-pointers-and-next-run-recovers") {
                val downCount=AtomicInteger();val cancelled=AtomicInteger()
                test.runOnMainSync {activity.setContentView(object:View(activity) {override fun onTouchEvent(event:MotionEvent):Boolean {
                    if(event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) downCount.incrementAndGet()
                    if(event.actionMasked==MotionEvent.ACTION_CANCEL) cancelled.set(event.pointerCount)
                    return true
                }})}
                run(List(pointerCount) {p->block("input.touchdown","pointerId" to p,"x" to (160+p*55),"y" to 400,"resultVariable" to "value")}) {
                    await {downCount.get()==pointerCount}
                }
                await {cancelled.get()==pointerCount}
                downCount.set(0)
                run(listOf(block("input.touchdown","pointerId" to 0,"x" to 200,"y" to 400,"resultVariable" to "value"),
                    block("input.touchup","pointerId" to 0,"resultVariable" to "value"))) {
                    await {downCount.get()==1}
                }
            }
            if(includeProductionIme) record("expansion-production-ime-commits-only-public-qa-text") {
                lateinit var input:EditText
                test.runOnMainSync {input=EditText(activity).apply {hint="PUBLIC_QA_INPUT";isSingleLine=true};activity.setContentView(input);input.requestFocus();activity.getSystemService(InputMethodManager::class.java).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)}
                test.sendStatus(1,android.os.Bundle().apply {putString("stream","QA_IME_READY\n")})
                val expected=android.content.ComponentName(test.targetContext.packageName,"com.autoscript.runtime.service.RuntimeInputMethodService")
                val selectionDeadline=SystemClock.elapsedRealtime()+40000
                while(android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(test.targetContext.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())!=expected && SystemClock.elapsedRealtime()<selectionDeadline) SystemClock.sleep(100)
                check(android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(test.targetContext.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())==expected) {"PRODUCTION_IME_NOT_SELECTED_FOR_QA"}
                SystemClock.sleep(700)
                run(listOf(block("input.imestatus","resultVariable" to "response"),block("input.imetext","text" to "PUBLIC_QA_Abc123","resultVariable" to "value"))) {values->
                    val status=JSONObject(values["response"].orEmpty());check(status.getBoolean("selected") && status.getBoolean("connected"))
                    val actual=AtomicReference<String>();test.runOnMainSync {actual.set(input.text.toString())};check(actual.get()=="PUBLIC_QA_Abc123")
                }
                test.runOnMainSync {activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken,0);input.clearFocus()}
            }
        } finally {stop()}
        return snapshot
    }
}
