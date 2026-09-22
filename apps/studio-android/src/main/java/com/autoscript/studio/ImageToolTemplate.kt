package com.autoscript.studio

import android.content.Context
import android.graphics.Bitmap
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

/** 与 `ProjectToolScreens` 的整屏截图保存共用同一组上限，避免两条路径产出不同规格的模板。 */
private const val MAX_TEMPLATE_EDGE = 4_096
private const val MAX_TEMPLATE_PIXELS = 4_194_304L

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
): Pair<ProjectSnapshot, String> {
    val left = roi.left.coerceIn(0, source.width - 1)
    val top = roi.top.coerceIn(0, source.height - 1)
    // ROI 是半开区间，右/下可以等于位图尺寸；宽高至少为 1，否则 createBitmap 会抛。
    val width = (roi.right.coerceAtMost(source.width) - left).coerceAtLeast(1)
    val height = (roi.bottom.coerceAtMost(source.height) - top).coerceAtLeast(1)

    val cropped = Bitmap.createBitmap(source, left, top, width, height)
    val scale = minOf(
        1.0,
        MAX_TEMPLATE_EDGE.toDouble() / cropped.width,
        MAX_TEMPLATE_EDGE.toDouble() / cropped.height,
        sqrt(MAX_TEMPLATE_PIXELS.toDouble() / (cropped.width.toLong() * cropped.height)),
    )
    val exportWidth = (cropped.width * scale).toInt().coerceAtLeast(1)
    val exportHeight = (cropped.height * scale).toInt().coerceAtLeast(1)
    val exported = if (exportWidth == cropped.width && exportHeight == cropped.height) {
        cropped
    } else {
        Bitmap.createScaledBitmap(cropped, exportWidth, exportHeight, true)
    }

    val temporary = File.createTempFile("template-", ".png", context.cacheDir)
    try {
        FileOutputStream(temporary).use { output ->
            require(exported.compress(Bitmap.CompressFormat.PNG, 100, output)) { "模板图片编码失败" }
            output.flush()
            output.fd.sync()
        }
        val updated = FileInputStream(temporary).use { input ->
            store.importResource(
                projectId = snapshot.manifest.projectId,
                kind = ProjectResourceKind.IMAGE,
                sourceName = "template-${System.currentTimeMillis()}.png",
                source = input,
                expectedResourcePaths = snapshot.manifest.resources
                    .mapNotNull { it.get("path")?.asString }
                    .toSet(),
            )
        }
        val path = updated.manifest.resources.last().get("path").asString
        return updated to path
    } finally {
        temporary.delete()
        if (exported !== cropped) exported.recycle()
        cropped.recycle()
    }
}
