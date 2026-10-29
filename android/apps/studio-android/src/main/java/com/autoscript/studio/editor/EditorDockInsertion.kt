package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal fun editorInsertionContext(projectId: String, flowId: String, editor: VisualEditorState) =
    EditorPasteRequest(projectId, flowId, editor.currentSource, editor.selectedNodeId, editor.selectedNodeIds.toSet())

/** One configured gesture: reject stale context before touching selection/history. */
internal fun applyConfiguredEditorInsertion(context: EditorPasteRequest, projectId: String, flowId: String,
    editor: VisualEditorState, contract: BlockContract, arguments: JsonObject, position: EditorInsertPosition,
    childSlot: String?, capabilities: Set<String>, canMutate: Boolean): String? {
    if (!canMutate) return "请停止运行并完成当前操作后再修改插件"
    if (!context.matches(projectId, flowId, editor)) return "插件、草稿或选择已变化，未加入；请返回重新选择"
    if (!capabilities.containsAll(contract.requiredCapabilities)) return "项目能力已变化，未加入${contract.title}"
    visualJumpInsertionError(contract.kind, arguments, editor, childSlot, position)?.let { return it }
    val accepted = if (position == EditorInsertPosition.REPLACE) editor.replaceSelectedBlock(contract, arguments)
        else editor.insertBlocks(listOf(contract to arguments),
            if (childSlot != null && position == EditorInsertPosition.BELOW) EditorInsertPosition.INSIDE else position,
            context.primary, childSlot)
    return if (accepted) null else "无法把${contract.title}加入当前位置"
}

internal fun applyConfiguredElseIfInsertion(context: EditorPasteRequest, projectId: String, flowId: String,
    editor: VisualEditorState, arguments: JsonObject, capabilities: Set<String>, canMutate: Boolean): String? {
    if (!canMutate) return "请停止运行并完成当前操作后再修改插件"
    if (!context.matches(projectId, flowId, editor)) return "插件、草稿或选择已变化，未加入；请返回重新选择"
    val contract = requireNotNull(BlockCatalog.find("control.if"))
    if (!capabilities.containsAll(contract.requiredCapabilities)) return "项目能力已变化，未加入否则如果"
    if (!validConfiguredConditionArguments(arguments)) return "否则如果条件无效，未加入分支"
    return if (editor.addElseIfBranch(arguments, context.primary)) null
        else "请选中一个“如果”节点；否则如果最多32个"
}

/** View-only insertion target; the real owner remains an anchor, never a branch node identity. */
internal data class EditorBranchSelection(val ownerId: String, val slot: String, val source: String) {
    fun matches(editor: VisualEditorState): Boolean = source == editor.currentSource &&
        ownerId == editor.selectedNodeId && editor.rows.any { it.nodeId == ownerId &&
            slot in editorBranchSlots(it.kind, editor.childBlockNames(ownerId)) }
}

/** A branch choice is bound to the exact plugin draft and anchor that opened it. */
internal data class EditorDockInsertRequest(
    val projectId: String,
    val flowId: String,
    val source: String,
    val anchorId: String?,
    val hint: String,
    val insideOnly: Boolean,
    val selection: Set<String> = setOfNotNull(anchorId),
    val onCommitted: () -> Unit = {},
) {
    fun matches(projectId: String, flowId: String, editor: VisualEditorState): Boolean =
        this.projectId == projectId && this.flowId == flowId && source == editor.currentSource &&
            anchorId == editor.selectedNodeId && selection == editor.selectedNodeIds

    fun options(slots: List<String>): List<Pair<String?, String>> =
        (if (insideOnly) emptyList() else listOf(null to "当前节点之后")) +
            slots.distinct().map { slot -> slot to "${childBlockLabel(slot)}内新增" }
}
