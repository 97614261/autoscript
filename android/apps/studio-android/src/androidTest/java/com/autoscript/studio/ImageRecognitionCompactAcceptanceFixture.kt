package com.autoscript.studio

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.api.RecognitionPreviewReply as Reply
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/** Focused production form/save controls and real matching, using public synthetic pixels only. */
internal object ImageRecognitionCompactAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit, remainingOnly: Boolean = false): ProjectSnapshot {
        val id = initial.manifest.projectId
        check(id.startsWith("project-qa-"))
        var snapshot by mutableStateOf(initial)
        val bitmap = Bitmap.createBitmap(IntArray(1600 * 900) { i ->
            0xff000000.toInt() or ((i * 31 % 251) shl 16) or ((i * 43 % 241) shl 8) or (i * 17 % 233)
        }, 1600, 900, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray()
        snapshot = store.importResource(id, ProjectResourceKind.IMAGE, "public-source.png", bytes.inputStream(), emptySet())
        val source = snapshot.manifest.resources.single { it["kind"].asString == "image" }["path"].asString
        snapshot = store.updateVariables(id, snapshot.manifest.variables +
            listOf("found", "foundX", "foundY").map { ProjectVariable(it, ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER) } +
            ProjectVariable("frame", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE), snapshot.manifest.variables)
        snapshot = store.updateCapabilities(id, snapshot.manifest.capabilities.toSet() + setOf("screen.capture", "vision.template"), snapshot.manifest.capabilities.toSet())
        val points = listOf(ProjectMaskPoint(1010, 120), ProjectMaskPoint(1130, 120), ProjectMaskPoint(1010, 200))
        val roi = ImageSearchPolygon.bounds(points)
        val orientation = activity.requestedOrientation
        val ui = UsageTestUi(test)
        var visible by mutableStateOf(true)
        var savedTemplate: String? = null
        fun rotate(landscape: Boolean) {
            test.runOnMainSync { activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.await("compact form orientation") { activity.resources.configuration.orientation == if (landscape) 2 else 1 }
            SystemClock.sleep(300)
        }
        fun reveal(label: String) {
            for (forward in listOf(true, false)) repeat(8) {
                if (ui.exists(label)) return
                scrollOwnedQaForm(test, forward) { ui.exists(label) }
            }
            ui.wait(label)
        }
        fun click(label: String) { reveal(label); ui.clickButton(label) }
        fun save(name: String) {
            ui.wait("名称"); ui.replaceFirst(name)
            val screen = android.graphics.Point().also { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(it) }
            val footer = requireNotNull(ui.find {
                val bounds = Rect().also(it::getBoundsInScreen)
                it.text?.toString() == "保存图片" && bounds.top > screen.y / 2
            })
            val bounds = Rect().also(footer::getBoundsInScreen); footer.recycle(); ui.tap(bounds)
            ui.await("saved public polygon") { savedTemplate != null }
            ui.absent("名称")
        }
        fun checkSaved(path: String) {
            val file = imageRecognitionFile(snapshot, path)
            val hint = file.inputStream().use(ScreenshotTemplatePng::read)
            check(hint == ScreenshotTemplateRegion(1600, 900, roi)) { "QA_POLYGON_ORIGIN:$hint" }
            val image = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                check(image.width == roi.width && image.height == roi.height)
                check(image.getPixel(0, 0) == bitmap.getPixel(roi.left, roi.top))
                check(image.getPixel(image.width - 1, image.height - 1) ushr 24 == 0)
            } finally { image.recycle() }
            val config = RecognitionPreviewConfiguration(Reply.TEMPLATE, path, 0, 1000, 1600, 900, roi)
            val reply = runBlocking { client.testRecognitionPreview(System.nanoTime(), Reply.TEMPLATE,
                imageRecognitionFile(snapshot, source), file, config.options()) }
            check(reply.status == Reply.SUCCESS && reply.x == roi.left && reply.y == roi.top) {
                "QA_POLYGON_EXACT_RANGE:${reply.status}:${reply.x},${reply.y}"
            }
        }
        fun assertVisible(label: String) {
            val node = requireNotNull(ui.label(label)) { "QA_COMPACT_MISSING:$label" }
            val bounds = Rect().also(node::getBoundsInScreen); node.recycle()
            val screen = android.graphics.Point().also { @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(it) }
            check(!bounds.isEmpty && bounds.left >= 0 && bounds.right <= screen.x && bounds.top >= 0 && bounds.bottom <= screen.y)
        }
        try {
            rotate(true)
            if (!remainingOnly) test.runOnMainSync { activity.setContent { AutoScriptTheme { if (visible)
                ImageProcessingWorkbench(bitmap, snapshot, store, client, { snapshot = it }, { visible = false }, capturedScreen = true,
                    capture = ImageWorkspaceCapture(720, 1280, "letterbox", ImageToolMode.REGION, ImageSearchPolygon.encode(points), false,
                        {}, {}, null, { error("QA_POLYGON_USED_RECTANGLE_SAVE") }, null, { savedTemplate = it }))
            } } }
            if (!remainingOnly) record("compact-direct-polygon-save-alpha-origin-and-exact-range-match") {
                ui.clickButton("存图"); save("public-polygon-direct")
                checkSaved(requireNotNull(savedTemplate))
            }
            fun saveProcessedTemplate() {
                val pipeline = ImageProcessingPipeline(bitmap.width, bitmap.height,
                    polygonSelectionOperations(bitmap.width, bitmap.height, points, crop = true, keep = true))
                val patch = processImagePipelineBitmap(bitmap, pipeline) {}
                try {
                    val local = ImageToolCodeGen.Roi(0, 0, patch.width, patch.height)
                    val saved = cropAndImportTemplate(store, snapshot, patch, local, test.targetContext,
                        imageName = "public-polygon-cropped.png", screenRegion = pipeline.screenshotTemplateRegion(local))
                    snapshot = saved.first; savedTemplate = saved.second
                } finally { patch.recycle() }
            }
            if (remainingOnly) saveProcessedTemplate()
            else record("compact-processed-polygon-crop-save-origin-and-exact-range-match") {
                saveProcessedTemplate(); checkSaved(requireNotNull(savedTemplate))
            }
            test.runOnMainSync { visible = false }; test.waitForIdleSync()
            var confirmed: com.google.gson.JsonObject? = null
            val contract = requireNotNull(BlockCatalog.find("vision.findimage"))
            val args = initialRecognitionArguments(contract, snapshot).apply {
                addProperty("imagePath", requireNotNull(savedTemplate)); addProperty("autoCapture", true)
            }
            test.runOnMainSync { activity.setContent { AutoScriptTheme {
                VisualImageRecognitionDialog(contract, args, snapshot.manifest.resources, emptyList(), snapshot,
                    confirmLabel = "加入", allowKindChange = true, onDismiss = {}, onConfirm = { _, a -> confirmed = a },
                    currentFlowId = snapshot.manifest.entryFlowId.orEmpty(), onPickFromScreen = {}, applyInitialTemplateRegion = true)
            } } }
            record("compact-landscape-no-tabs-range-footer-and-disclosures") {
                ui.wait("模板范围")
                check(!ui.exists("常用配置") && !ui.exists("更多参数"))
                for (label in listOf("模板范围", "框选", "取消", "加入", "找到后", "结果变量", "类型 ▾")) assertVisible(label)
                ui.screenshot("compact-recognition-landscape")
                ui.clickButton("模板范围"); ui.clickButton("结果变量"); ui.wait("是否找到")
                click("结果变量"); click("动作与高级参数"); reveal("参数说明")
                click("动作与高级参数")
                ui.clickButton("加入")
                ui.await("form accepted") { confirmed != null }
                check(confirmed?.getAsJsonObject("region")?.let { it["left"].asInt == roi.left && it["top"].asInt == roi.top && it["right"].asInt == roi.right && it["bottom"].asInt == roi.bottom } == true)
            }
            record("compact-portrait-fixed-footer-and-template-shortcut") {
                rotate(false); ui.wait("模板范围"); assertVisible("取消"); assertVisible("加入")
                ui.clickButton("模板范围"); ui.screenshot("compact-recognition-portrait")
            }
        } catch (failure: Throwable) {
            runCatching { ui.screenshot("compact-recognition-failure") }; throw failure
        } finally {
            test.runOnMainSync { activity.setContent {}; activity.requestedOrientation = orientation }
            test.waitForIdleSync(); bitmap.recycle()
        }
        return store.openProject(id)
    }
}
