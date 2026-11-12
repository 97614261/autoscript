package com.autoscript.studio

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import kotlin.math.roundToInt

/** Actual toolbar controls and PNG saves; public synthetic pixels only. */
internal object ImageToolbarAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        var snapshot by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        var saved: String? = null
        val source=Bitmap.createBitmap(IntArray(640*400) {i -> 0xff000000.toInt() or ((i*31%251) shl 16) or ((i*43%241) shl 8) or (i*17%233)},640,400,Bitmap.Config.ARGB_8888)
        val ui=UsageTestUi(test)
        val orientation=activity.requestedOrientation
        fun bounds(label:String):Rect {
            val node=requireNotNull(ui.label(label))
            return try {Rect().also(node::getBoundsInScreen)} finally {node.recycle()}
        }
        fun rotate(landscape:Boolean) {
            test.runOnMainSync {activity.requestedOrientation=if(landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            ui.await("toolbar orientation") {activity.resources.configuration.orientation==if(landscape) 2 else 1}
        }
        try {
            rotate(true)
            test.runOnMainSync {activity.setContent {AutoScriptTheme {if(visible) ImageProcessingWorkbench(source,snapshot,store,client,
                {snapshot=it},{visible=false},capturedScreen=true,
                capture=ImageWorkspaceCapture(640,400,"letterbox",ImageToolMode.REGION,null,false,{}, {},null,
                    {error("QA_INLINE_POLYGON_USED_RECTANGULAR_SAVE")},null,{saved=it}))}}}
            ui.wait("图像工具画布")
            val canvas=bounds("图像工具画布")
            record("toolbar-selection-is-a-strip-with-unchanged-full-canvas") {
                ui.click("选区");ui.click("多边形")
                ui.wait("多边形选区画布")
                check(bounds("多边形选区画布")==canvas) {"QA_SELECTION_SHRANK_CANVAS"}
                ui.absent("使用范围")
                val v=ImageToolViewport.fit(640,400,canvas.width().toFloat(),canvas.height().toFloat())
                listOf(60 to 30,580 to 30,580 to 350).forEachIndexed {i,(x,y) ->
                    val p=v.toView(x,y);val px=(canvas.left+p.first).roundToInt();val py=(canvas.top+p.second).roundToInt()
                    ui.tap(Rect(px-1,py-1,px+1,py+1));ui.wait("${i+1}/128点")
                }
                ui.click("撤点");ui.wait("2/128点")
                ui.click("重做");ui.wait("3/128点")
                ui.screenshot("image-toolbar-polygon-landscape")
            }
            record("toolbar-polygon-save-directly-preserves-alpha-and-original-range") {
                ui.click("存图");ui.wait("名称");ui.replaceFirst("public-toolbar-polygon.png")
                val footer=requireNotNull(ui.find {
                    it.text?.toString()=="保存图片" && Rect().also(it::getBoundsInScreen).top>canvas.centerY()
                })
                val b=try {Rect().also(footer::getBoundsInScreen)} finally {footer.recycle()}
                ui.tap(b);ui.await("toolbar saved polygon") {saved!=null};ui.absent("名称")
                val file=imageRecognitionFile(snapshot,requireNotNull(saved))
                val region=file.inputStream().use(ScreenshotTemplatePng::read)
                check(region==ScreenshotTemplateRegion(640,400,ImageToolCodeGen.Roi(60,30,580,350))) {"QA_TOOLBAR_SAVE_REGION:$region"}
                val image=requireNotNull(BitmapFactory.decodeFile(file.path))
                try {
                    check(image.width==520 && image.height==320)
                    check(image.getPixel(0,image.height-1) ushr 24==0)
                    check(image.getPixel(image.width-1,0) ushr 24==255)
                } finally {image.recycle()}
            }
            record("toolbar-one-step-processing-does-not-open-a-panel") {
                ui.click("工具");ui.click("灰度");ui.wait("撤销")
                ui.wait("多边形选区画布")
                check(bounds("多边形选区画布")==canvas) {"QA_QUICK_PROCESS_SHRANK_CANVAS"}
                ui.absent("图像处理")
                ui.click("撤销")
            }
            record("toolbar-portrait-save-and-close-remain-visible-with-one-secondary-strip") {
                rotate(false);ui.wait("多边形选区画布");ui.click("选区")
                val image=bounds("多边形选区画布");val save=bounds("存图");val close=bounds("关闭图像工具")
                check(image.contains(save) && image.contains(close) && save.right<=close.left) {"QA_TOOLBAR_FIXED_ACTIONS_CLIPPED"}
                ui.screenshot("image-toolbar-polygon-portrait")
            }
        } catch(failure:Throwable) {
            runCatching {ui.screenshot("image-toolbar-failure")}
            throw failure
        } finally {
            test.runOnMainSync {visible=false;activity.requestedOrientation=orientation}
            test.waitForIdleSync();source.recycle()
        }
        return snapshot
    }
}
