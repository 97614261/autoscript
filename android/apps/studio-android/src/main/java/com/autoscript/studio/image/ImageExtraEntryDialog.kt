package com.autoscript.studio

import androidx.compose.runtime.Composable
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal val extraImageEntryKinds = visionExtensionKinds + frameResourceKinds + setOf("vision.readresult","vision.resultaction")

@Composable
internal fun ImageExtraEntryDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    visible: Boolean, onManageVariables: () -> Unit, onDismiss: () -> Unit,
    onConfirm: (List<Pair<BlockContract, JsonObject>>) -> Unit,
    captureSelection: ImageToolCodeGen.VisualSelection? = null, onPickFromScreen: ((ImageToolMode) -> Unit)? = null) {
    if (contract.kind == "vision.readresult") RecognitionResultReaderDialog(project,flowId,visible,onManageVariables,onDismiss,onConfirm,arguments)
    else if(contract.kind == "vision.resultaction") RecognitionResultActionDialog(project,flowId,visible,onManageVariables,onDismiss,onConfirm,arguments)
    else if (contract.kind in frameResourceKinds) VisualFrameResourceDialog(contract, arguments, project, flowId,
        onManageVariables, onDismiss, { onConfirm(listOf(contract to it)) }, "选择加入位置", visible)
    else VisualVisionExtensionDialog(contract, arguments, project, flowId, onManageVariables, onDismiss,
        { onConfirm(listOf(contract to it)) }, "选择加入位置", visible, allowCaptureSource = true,
        onConfirmSource = { configured, capture -> onConfirm(recognitionImageEntryBlocks(contract, configured, capture)) },
        captureSelection = captureSelection, onPickFromScreen = onPickFromScreen)
}
