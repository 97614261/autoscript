package com.autoscript.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.autoscript.project.store.ProjectMaskPoint

internal enum class ImageToolbarMenu { MODE, SELECTION, VIEW, TEST, PROCESS }

internal val imageQuickTests = listOf("match" to "找图", "ocr" to "取字", "glyph" to "制字")
internal val imageQuickProcessing = listOf(
    "saveAll" to "存整图", "insert" to "加入处理",
    "crop" to "裁剪", "keep" to "外透明", "ignore" to "内透明", "gray" to "灰度",
    "filter" to "滤色", "threshold" to "二值化", "mask" to "蒙版", "cleanup" to "清理",
    "resize" to "改尺寸", "rotate270" to "左转", "rotate90" to "右转", "rotate180" to "转180°",
    "flipX" to "左右翻", "flipY" to "上下翻", "recipe" to "方案", "coordinate" to "坐标还原",
)

/** Incomplete vertices stay editable; saving and emitting always use the same strict validation. */
internal fun imageSelectionProblem(selection: ImageToolSelection, width: Int, height: Int): String? {
    if (selection.polygon.isNotEmpty()) {
        if (selection.polygon.size < 3) return "再加${3 - selection.polygon.size}个顶点"
        if (selection.polygon.any { it.x !in 0..width || it.y !in 0..height }) return "顶点超出图片"
        runCatching { ImageSearchPolygon.validate(selection.polygon) }.exceptionOrNull()?.let {
            return it.message ?: "多边形无效，请调整顶点"
        }
        if (selection.box != ImageSearchPolygon.bounds(selection.polygon)) return "选区已变化，请重新选择"
    }
    val box = selection.box ?: return "请先框选或添加多边形顶点"
    return if (box.left < 0 || box.top < 0 || box.right > width || box.bottom > height || box.width < 1 || box.height < 1)
        "选区超出图片或为空" else null
}

internal data class ImageSaveTarget(val region: ImageToolCodeGen.Roi, val polygon: List<ProjectMaskPoint>)

/** No selection saves the current image; an unfinished selection must never silently become a full-image save. */
internal fun imageSaveTarget(selection: ImageToolSelection, width: Int, height: Int, wholeImage: Boolean = false): ImageSaveTarget? {
    if (width < 1 || height < 1) return null
    val target = if (wholeImage || selection.box == null && selection.polygon.isEmpty())
        ImageToolSelection(box = ImageToolCodeGen.Roi(0, 0, width, height)) else selection
    if (imageSelectionProblem(target, width, height) != null) return null
    return ImageSaveTarget(requireNotNull(target.box), target.polygon.toList())
}

internal fun polygonToolSelection(points: List<ProjectMaskPoint>, previous: ImageToolSelection): ImageToolSelection =
    previous.copy(polygon = points.toList(), box = points.takeIf { it.isNotEmpty() }?.let(ImageSearchPolygon::bounds))

/** One completed gesture is one history entry; zoom, canceled gestures and reselecting do not add entries. */
internal class ImagePolygonHistory(initial: List<ProjectMaskPoint>) {
    private var history by mutableStateOf(listOf(initial.toList()))
    private var index by mutableIntStateOf(0)
    var points by mutableStateOf(initial.toList())
        private set
    val canUndo get() = index > 0
    val canRedo get() = index < history.lastIndex

    fun synchronize(external: List<ProjectMaskPoint>) {
        if (external == points) return
        history = listOf(external.toList()); index = 0; points = external.toList()
    }

    fun publish(next: List<ProjectMaskPoint>): Boolean {
        if (next == points) return true
        if (next.size > 128 || next.distinct().size != next.size) return false
        history = (history.take(index + 1) + listOf(next.toList())).takeLast(33)
        index = history.lastIndex; points = history[index]
        return true
    }

    fun undo() { if (canUndo) { index--; points = history[index] } }
    fun redo() { if (canRedo) { index++; points = history[index] } }
}
