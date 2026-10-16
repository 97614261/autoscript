package com.autoscript.studio

import android.content.Context
import android.graphics.Bitmap
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** 与 `ProjectToolScreens` 的整屏截图保存共用同一组上限，避免两条路径产出不同规格的模板。 */
private const val MAX_TEMPLATE_EDGE = 4_096
private const val MAX_TEMPLATE_PIXELS = 4_194_304L

/** Templates must keep source pixels: shrinking silently changes subsequent matching. */
internal fun templateCropDimensions(width: Int, height: Int, roi: ImageToolCodeGen.Roi): Pair<Int, Int> {
    require(width > 0 && height > 0 && roi.left >= 0 && roi.top >= 0 &&
        roi.right > roi.left && roi.bottom > roi.top && roi.right <= width && roi.bottom <= height) { "裁剪范围无效或超出截图" }
    val croppedWidth = roi.right - roi.left
    val croppedHeight = roi.bottom - roi.top
    require(croppedWidth <= MAX_TEMPLATE_EDGE && croppedHeight <= MAX_TEMPLATE_EDGE &&
        croppedWidth.toLong() * croppedHeight <= MAX_TEMPLATE_PIXELS) {
        "原像素图片超过4096边长或400万像素预算，请缩小裁剪范围；不会自动缩放模板"
    }
    return croppedWidth to croppedHeight
}

/**
 * 把截图上框选的区域裁出来，存成项目图片资源。
 *
 * 走 [ProjectStore.importResource] 的正式登记路径，所以裁出的图会进 `project.json` 的
 * resources 列表，脚本里可以用 `Screen.loadImage(path)` 加载——不是往沙箱里扔一个野文件。
 *
 * @return 新快照与资源相对路径
 */
internal fun cropAndImportTemplate(
    store: ProjectStore,
    snapshot: ProjectSnapshot,
    source: Bitmap,
    roi: ImageToolCodeGen.Roi,
    context: Context,
    imageDirectory: String = "",
    imageName: String = "template-${System.currentTimeMillis()}.png",
): Pair<ProjectSnapshot, String> {
    val (width, height) = templateCropDimensions(source.width, source.height, roi)
    val cropped = Bitmap.createBitmap(source, roi.left, roi.top, width, height)
    var temporary: File? = null
    try {
        val outputFile = File.createTempFile("template-", ".png", context.cacheDir)
        temporary = outputFile
        FileOutputStream(outputFile).use { output ->
            require(cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) { "模板图片编码失败" }
            output.flush()
            output.fd.sync()
        }
        val updated = FileInputStream(outputFile).use { input ->
            store.importResource(
                projectId = snapshot.manifest.projectId,
                kind = ProjectResourceKind.IMAGE,
                sourceName = imageName,
                source = input,
                expectedResourcePaths = snapshot.manifest.resources
                    .mapNotNull { it.get("path")?.asString }
                    .toSet(),
                imageDirectory = imageDirectory,
            )
        }
        val path = updated.manifest.resources.last().get("path").asString
        return updated to path
    } finally {
        temporary?.delete()
        // Full-frame createBitmap may alias source; only its owner may recycle it.
        if (cropped !== source) cropped.recycle()
    }
}
