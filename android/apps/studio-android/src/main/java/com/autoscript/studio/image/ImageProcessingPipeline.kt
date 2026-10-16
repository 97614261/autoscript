package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** A surviving preview must retain all operations even after older bitmap states are evicted. */
internal class ImageProcessingPipeline(val sourceWidth: Int, val sourceHeight: Int,
    operations: List<ImagePixelOperation> = emptyList()) {
    val operations = operations.toList()
    val dimensions: Pair<Int, Int>
    init {
        require(this.operations.size <= MAX_OPERATIONS) { "处理流水线最多64步，请保存图片后重新打开作为新来源" }
        var size = ImagePixelOperation("gray").dimensions(sourceWidth, sourceHeight)
        this.operations.forEach { size = it.dimensions(size.first, size.second) }
        dimensions = size
    }
    fun append(operation: ImagePixelOperation) = ImageProcessingPipeline(sourceWidth, sourceHeight, operations + operation)
    companion object { const val MAX_OPERATIONS = 64 }
}

internal data class ImageProcessingVersion<T : Any>(val image: T, val pipeline: ImageProcessingPipeline)

/** Source=null means an explicitly chosen live screenshot; asset paths must be declared images. */
internal fun imageProcessingBlocks(pipeline: ImageProcessingPipeline, imagePath: String?, scratchVariable: String,
    resultVariable: String, variables: List<CalculationVariable>, resources: List<JsonObject>): List<Pair<BlockContract, JsonObject>> {
    require(scratchVariable != resultVariable) { "中间帧和结果帧必须使用不同变量" }
    for (name in listOf(scratchVariable, resultVariable)) {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && variables.any { it.name == name && it.type == ProjectVariableType.IMAGE }) {
            "请选择当前插件可访问的已声明图像变量"
        }
    }
    if (imagePath != null) require(imagePath.startsWith("assets/images/") && imagePath.toByteArray(Charsets.UTF_8).size <= 512 && '\\' !in imagePath &&
        imagePath.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        resources.any { it.get("kind")?.asString == "image" && it.get("path")?.asString == imagePath }) {
        "来源图片必须在项目中声明"
    }
    val blocks = mutableListOf<Pair<BlockContract, JsonObject>>()
    fun add(kind: String, args: JsonObject) { blocks += requireNotNull(BlockCatalog.find(kind)) to args }
    // Set starting parity so the final output is always the explicitly selected result slot.
    var source = if (pipeline.operations.size % 2 == 0) resultVariable else scratchVariable
    add(if (imagePath == null) "screen.capture" else "screen.loadimage", JsonObject().apply {
        addProperty("resultVariable", source)
        imagePath?.let { addProperty("imagePath", it) }
    })
    pipeline.operations.forEach { operation ->
        val output = if (source == scratchVariable) resultVariable else scratchVariable
        val args = JsonObject().apply {
            addProperty("frameVariable", source); addProperty("resultVariable", output)
            if (operation.mode == "crop") {
                val roi = requireNotNull(operation.roi)
                addProperty("left", roi.left); addProperty("top", roi.top); addProperty("right", roi.right); addProperty("bottom", roi.bottom)
            } else {
                addProperty("mode", operation.mode); addProperty("width", operation.width); addProperty("height", operation.height)
                if (operation.mode == "threshold") addProperty("threshold", operation.threshold)
            }
        }
        add(if (operation.mode == "crop") "screen.crop" else "screen.transform", args)
        add("screen.release", JsonObject().apply { addProperty("frameVariable", source) })
        source = output
    }
    check(source == resultVariable)
    return blocks
}
