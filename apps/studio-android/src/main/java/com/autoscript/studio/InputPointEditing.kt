package com.autoscript.studio

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.autoscript.runtime.api.InputPointAction
import com.autoscript.runtime.api.InputPointPickReply
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal data class InputInsertionDraft(
    val projectId: String, val flowId: String, val source: String, val anchorNodeId: String?,
    val snippet: ImageToolCodeGen.Snippet,
) {
    fun matches(projectId: String, flowId: String, editor: VisualEditorState): Boolean =
        this.projectId == projectId && this.flowId == flowId && source == editor.currentSource &&
            (anchorNodeId == null || editor.rows.any { it.nodeId == anchorNodeId })
}

internal data class InputPickTarget(val projectId: String, val flowId: String, val source: String, val anchorNodeId: String?) {
    fun draft(snippet: ImageToolCodeGen.Snippet) = InputInsertionDraft(projectId, flowId, source, anchorNodeId, snippet)
}

internal fun inputPointAction(mode: ImageToolMode): InputPointAction = when (mode) {
    ImageToolMode.TAP -> InputPointAction.TAP
    ImageToolMode.SWIPE -> InputPointAction.SWIPE
    ImageToolMode.LONG_PRESS -> InputPointAction.LONG_PRESS
    ImageToolMode.DRAG -> InputPointAction.DRAG
    ImageToolMode.POINTER_DOWN -> InputPointAction.DOWN
    ImageToolMode.POINTER_MOVE -> InputPointAction.MOVE
    ImageToolMode.POINTER_UP -> InputPointAction.UP
    else -> error("不是输入动作")
}

internal fun inputPointSnippet(reply: InputPointPickReply, designWidth: Int, designHeight: Int, scaleMode: String): ImageToolCodeGen.Snippet {
    require(reply.validFor(reply.requestId, 3)) { "选点结果无效" }
    val map = ImageToolCodeGen.DesignMapping(reply.width, reply.height, designWidth, designHeight, scaleMode)
    val start = ImageToolCodeGen.PickedPoint(reply.x1, reply.y1, 0)
    val end = ImageToolCodeGen.PickedPoint(reply.x2, reply.y2, 0)
    return when (requireNotNull(InputPointAction.fromWire(reply.action))) {
        InputPointAction.TAP -> ImageToolCodeGen.tap(start, map)
        InputPointAction.SWIPE -> ImageToolCodeGen.swipe(start, end, reply.durationMs, map)
        InputPointAction.LONG_PRESS -> ImageToolCodeGen.longPress(start, reply.durationMs, map)
        InputPointAction.DRAG -> ImageToolCodeGen.drag(start, end, reply.durationMs, map)
        InputPointAction.DOWN -> ImageToolCodeGen.pointerDown(start, map)
        InputPointAction.MOVE -> ImageToolCodeGen.pointerMove(start, map)
        InputPointAction.UP -> ImageToolCodeGen.pointerUp()
    }
}

internal fun prepareInputBlocks(snippet: ImageToolCodeGen.Snippet, capabilities: Set<String>): List<Pair<BlockContract, JsonObject>> {
    require(snippet.rejection == null) { snippet.rejection.orEmpty() }
    require(snippet.flowBlocks.size in 1..256) { "输入动作积木数量无效" }
    val supported = setOf("input.tap", "input.swipe", "input.pointerdown", "input.pointermove", "input.pointerup", "task.sleep")
    return snippet.flowBlocks.map { block ->
        require(block.kind in supported) { "不是受支持的输入积木" }
        val contract = requireNotNull(BlockCatalog.find(block.kind)) { "缺少正式积木：${block.kind}" }
        require(contract.requiredCapabilities.all { it in capabilities }) { "${contract.title}缺少项目能力声明" }
        require(block.arguments.keys.all { name -> contract.properties.any { it.path == name } }) { "输入参数与积木契约不匹配" }
        contract to JsonObject().apply { block.arguments.forEach { (name, value) -> addProperty(name, value) } }
    }
}

@Composable
internal fun InputInsertPositionDialog(draft: InputInsertionDraft, editor: VisualEditorState,
    onDismiss: () -> Unit, onConfirm: (EditorInsertPosition, String?) -> Unit) {
    var selectingChild by remember(draft) { mutableStateOf(false) }
    val children = draft.anchorNodeId?.let(editor::childBlockNames).orEmpty()
    if (selectingChild) {
        EditorOptionDialog(title = "选择内部位置", options = children.map { it to childBlockLabel(it) },
            onDismiss = { selectingChild = false },
            onConfirm = { slot -> onConfirm(EditorInsertPosition.INSIDE, slot) })
    } else {
        EditorInsertPositionDialog(hasSelection = draft.anchorNodeId != null, canInsertInside = children.isNotEmpty(),
            allowReplace = false, onDismiss = onDismiss, onConfirm = { position ->
                if (position == EditorInsertPosition.INSIDE && children.size > 1) selectingChild = true
                else onConfirm(position, if (position == EditorInsertPosition.INSIDE) children.singleOrNull() else null)
            })
    }
}

internal fun Context.inputPickerActivity(): Activity? {
    var current = this
    repeat(16) {
        if (current is Activity) return current as Activity
        val wrapped = current as? ContextWrapper ?: return null
        if (wrapped.baseContext === current) return null
        current = wrapped.baseContext
    }
    return null
}
