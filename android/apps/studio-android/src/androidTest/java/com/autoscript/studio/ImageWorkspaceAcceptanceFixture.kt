package com.autoscript.studio

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import java.io.ByteArrayOutputStream

/** Actual touch on production controls, public synthetic pixels and one owned project only. */
internal object ImageWorkspaceAcceptanceFixture {
    fun run(test:Instrumentation,activity:ComponentActivity,client:RuntimeClient,store:ProjectStore,
        initial:ProjectSnapshot,record:(String,()->Unit)->Unit,remainingOnly:Boolean=false,insertionOnly:Boolean=false):ProjectSnapshot {
        val id=initial.manifest.projectId;check(id.startsWith("project-qa-"))
        var snapshot by mutableStateOf(initial)
        val bitmap=Bitmap.createBitmap(IntArray(160*80) { i ->
            0xff000000.toInt() or ((i*31%230) shl 16) or ((i*43%220) shl 8) or (i*17%210)
        },160,80,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().apply {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,this))}.toByteArray()
        snapshot=store.importResource(id,ProjectResourceKind.IMAGE,"workspace-public.png",bytes.inputStream(),emptySet())
        val source=snapshot.manifest.resources.single {it["kind"].asString=="image"}["path"].asString
        snapshot=store.updateVariables(id,snapshot.manifest.variables+listOf("qaFrame","qaOutput","qaScratch").map {
            ProjectVariable(it,ProjectVariableScope.GLOBAL,type=ProjectVariableType.IMAGE)
        },snapshot.manifest.variables)
        snapshot=store.updateCapabilities(id,snapshot.manifest.capabilities.toSet()+setOf("screen.capture","vision.pixel","vision.template"),snapshot.manifest.capabilities.toSet())
        val flow=snapshot.manifest.flows.first {it.flowId==snapshot.manifest.entryFlowId}
        val editor=VisualEditorState.create(snapshot.flowSources.getValue(flow.flowId),flow.rootBlockId)
        var insertions by mutableIntStateOf(0)
        val target=validatedWorkbenchTarget(store,client,id,flow.flowId,editor,currentSnapshot={snapshot},
            drafts={snapshot.flowSources+(flow.flowId to editor.currentSource)},canApply={true},
            onManageVariables={},onCommitted={insertions++})
        val originalOrientation=activity.requestedOrientation
        var visible by mutableStateOf(true)
        val ui=UsageTestUi(test)
        fun reveal(label:String) {
            for(forward in listOf(true,false)) repeat(12) {
                if(ui.exists(label)) return
                scrollOwnedQaForm(test,forward);SystemClock.sleep(90)
            }
            ui.wait(label)
        }
        fun click(label:String) {
            val actual=when(label) {"找图测试"->"找图";"取字测试"->"取字";"保存整图"->"存整图";"加入积木"->"加入处理";"矩形框选"->"矩形";else->label}
            if(label in setOf("找图测试","取字测试") && !ui.exists("找图")) ui.click("测试")
            reveal(actual);ui.click(actual)
        }
        fun fill(label:String,value:String) {
            reveal(label)
            ui.await("editable:$label") {ui.find {it.isEditable && it.contentDescription?.toString()==label}?.let {it.recycle();true}==true}
            val node=requireNotNull(ui.find {it.isEditable && it.contentDescription?.toString()==label})
            try {check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
            }))} finally {node.recycle()}
            SystemClock.sleep(120)
        }
        fun value(label:String):String {
            reveal(label)
            val node=requireNotNull(ui.find {it.isEditable && it.contentDescription?.toString()==label})
            return try {node.text.toString()} finally {node.recycle()}
        }
        fun focusInput(label:String) {
            reveal(label)
            val input=requireNotNull(ui.find {it.isEditable && it.contentDescription?.toString()==label})
            val bounds=Rect().also(input::getBoundsInScreen);input.recycle();ui.tap(bounds)
        }
        fun saveFooter() {
            val screen=android.graphics.Point().also {@Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(it)}
            val button=requireNotNull(ui.find {
                val bounds=Rect().also(it::getBoundsInScreen)
                it.text?.toString()=="保存图片" && bounds.top>screen.y/2
            })
            val bounds=Rect().also(button::getBoundsInScreen);button.recycle();ui.tap(bounds)
        }
        fun selectOnCanvas() {
            val canvas=requireNotNull(ui.label("图像工具画布"));val box=Rect().also(canvas::getBoundsInScreen);canvas.recycle()
            val viewport=ImageToolViewport.fit(bitmap.width,bitmap.height,box.width().toFloat(),box.height().toFloat())
            val from=viewport.toView(20,20);val to=viewport.toView(110,60)
            val down=SystemClock.uptimeMillis()
            for(step in 0..8) {
                val action=when(step) {0->android.view.MotionEvent.ACTION_DOWN;8->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE}
                val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,
                    box.left+from.first+(to.first-from.first)*step/8,box.top+from.second+(to.second-from.second)*step/8,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {check(test.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
                SystemClock.sleep(35)
            }
        }
        fun message(part:String):Boolean=ui.find {it.text?.toString()?.contains(part)==true}?.let {it.recycle();true}==true
        fun buttonVisible(label:String) {
            val node=requireNotNull(ui.label(label));val box=Rect().also(node::getBoundsInScreen);node.recycle()
            val screen=android.graphics.Point().also {@Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(it)}
            check(!box.isEmpty && box.left>=0 && box.top>=0 && box.right<=screen.x && box.bottom<=screen.y) {"QA_WORKSPACE_BUTTON_OFFSCREEN:$label"}
        }
        fun rotate(landscape:Boolean) {
            test.runOnMainSync {activity.requestedOrientation=if(landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            ui.await("orientation") {activity.resources.configuration.orientation==if(landscape) 2 else 1}
            SystemClock.sleep(350)
        }
        fun tools() {
            if(ui.exists("返回图像处理")) ui.click("返回图像处理")
            else if(!ui.exists("存整图")) ui.click("工具")
            ui.wait("存整图")
        }
        try {
            rotate(true)
            test.runOnMainSync {activity.setContent {AutoScriptTheme {if(visible)
                ImageProcessingWorkbench(bitmap,snapshot,store,client,{snapshot=it},{visible=false},
                    originalImagePath=source,insertionTarget=target,initialFilterOpen=true)
            }}}
            if(!remainingOnly && !insertionOnly) {
            record("workspace-landscape-filter-fixed-actions-and-return") {
                ui.wait("应用滤色");buttonVisible("应用滤色");buttonVisible("取消")
                ui.screenshot("workspace-landscape-filter")
                tools();ui.wait("存整图");buttonVisible("存整图")
            }
            record("workspace-match-return-keeps-raw-settings-and-real-preview") {
                click("找图测试");fill("相似度 ‰","999");fill("容差","3")
                tools();click("找图测试")
                check(value("相似度 ‰")=="999" && value("容差")=="3")
                click("开始测试")
                ui.await("real pixel match") {message("已找到：")}
                ui.screenshot("workspace-landscape-match")
                fill("相似度 ‰","1001")
                check(!message("已找到："));check(message("参数已修改") || message("上次结果已清除"))
                fill("相似度 ‰","1000")
                tools();click("找图测试");check(!message("已找到："));tools()
            }
            record("workspace-ocr-return-and-rotation-retain-inputs") {
                click("取字测试");fill("宽","120");fill("高","40");fill("置信度 ‰","650")
                tools();click("取字测试")
                check(value("宽")=="120" && value("高")=="40" && value("置信度 ‰")=="650")
                rotate(false);buttonVisible("测试识别");ui.screenshot("workspace-portrait-ocr")
                check(value("置信度 ‰")=="650")
                rotate(true);buttonVisible("测试识别");ui.screenshot("workspace-landscape-ocr")
            }
            } else if(!insertionOnly) {ui.wait("应用滤色");tools();click("取字测试");fill("置信度 ‰","650")}
            if(!insertionOnly) {
            record("workspace-visible-ime-and-footer-accessibility") {
                focusInput("置信度 ‰")
                val bounds=Rect()
                ui.await("real existing IME window") {
                    test.uiAutomation.windows.firstOrNull {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}?.getBoundsInScreen(bounds)
                    !bounds.isEmpty
                }
                test.sendStatus(1,Bundle().apply {putString("stream","QA_WORKSPACE_IME_HEIGHT:${bounds.height()}\n")})
                buttonVisible("测试识别")
                val action=requireNotNull(ui.label("测试识别"));val footer=Rect().also(action::getBoundsInScreen);action.recycle()
                check(footer.bottom<=bounds.top) {"QA_WORKSPACE_FOOTER_COVERED_BY_IME"}
                ui.screenshot("workspace-landscape-ime")
                if(!bounds.isEmpty) test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                tools()
            }
            record("workspace-filter-discard-protection-apply-and-save-copy") {
                click("滤色");click("白字");ui.clickButton("返回图像处理")
                ui.wait("放弃未应用滤色？");ui.clickButton("继续编辑");ui.wait("应用滤色")
                click("应用滤色");ui.await("filter applied") {message("处理完成")}
                tools();click("保存整图");ui.wait("名称")
                ui.replaceFirst("workspace-saved-copy")
                saveFooter()
                ui.await("saved resource") {store.openProject(id).manifest.resources.count {it["kind"].asString=="image"}==2}
                val saved=store.openProject(id)
                val original=requireNotNull(android.graphics.BitmapFactory.decodeFile(imageRecognitionFile(saved,source).path))
                try {check(original.sameAs(bitmap))} finally {original.recycle()}
                ui.await("save closed") {!ui.exists("名称")}
            }
            } else {
                ui.wait("应用滤色");tools();ui.click("收起")
                record("workspace-selection-real-drag-retains-original-pixel-region") {
                    click("选区");click("矩形框选");selectOnCanvas()
                    ui.await("selected inclusive pixel endpoints") {message("宽: 91, 高: 41")}
                    ui.screenshot("workspace-landscape-selection")
                    ui.click("收起");tools();click("滤色");click("应用滤色")
                    ui.await("filter ready for insert") {message("处理完成")};tools()
                }
            }
            record("workspace-insertion-cancel-retains-vars-and-single-undo") {
                if(!ui.exists("存整图")) tools()
                click("加入积木");ui.wait("加入处理与识别")
                click("中间帧");click("qaScratch")
                val before=editor.currentSource
                click("选择加入位置");ui.wait("图像加入位置");ui.clickButton("取消")
                ui.wait("加入处理与识别");check(insertions==0 && editor.currentSource==before)
                click("返回");click("加入积木")
                ui.wait("qaScratch");ui.wait("qaOutput")
                click("选择加入位置");ui.wait("图像加入位置");ui.clickButton("确定")
                ui.await("insert exactly once") {insertions==1}
                check(editor.currentSource!=before)
                test.runOnMainSync {check(editor.undo())};check(editor.currentSource==before)
                check(store.openProject(id).flowSources==snapshot.flowSources)
            }
        } catch(failure:Throwable) {
            runCatching {ui.screenshot("workspace-failure")}
            test.sendStatus(1,Bundle().apply {putString("stream","QA_WORKSPACE_FAILURE:${failure.message?.take(300)}\n")})
            throw failure
        } finally {
            test.runOnMainSync {visible=false;activity.requestedOrientation=originalOrientation}
            test.waitForIdleSync();bitmap.recycle()
        }
        return snapshot
    }
}
