package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** One mutation/history entry; reuse copies parameters, never an existing container's children. */
internal fun applyReflectedArguments(
    editor: VisualEditorState, nodeId: String, contract: BlockContract, arguments: JsonObject,
    reusePosition: EditorInsertPosition? = null, childSlot: String? = null,
    expectedArguments: JsonObject? = null,
): Boolean {
    if (editor.rows.none { it.nodeId == nodeId && it.kind == contract.kind }) return false
    if (expectedArguments != null && editor.nodeArguments(nodeId) != expectedArguments) return false
    if (reusePosition == null || reusePosition == EditorInsertPosition.REPLACE) {
        if (editor.nodeArguments(nodeId) == arguments) return true
        return editor.updateArguments(nodeId, arguments)
    }
    if (contract.kind == "control.label" && editor.rows.any {
        it.kind == "control.label" && editor.nodeArguments(it.nodeId)?.get("name") == arguments.get("name")
    }) return false
    if (reusePosition == EditorInsertPosition.INSIDE && childSlot !in editor.childBlockNames(nodeId)) return false
    return editor.insertBlocks(listOf(contract to arguments.deepCopy()), reusePosition, nodeId, childSlot)
}
