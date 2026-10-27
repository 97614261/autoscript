package com.autoscript.studio

import androidx.compose.runtime.Composable
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** Dialog rendering only. Capture ownership and atomic insertion stay with the editor session. */
@Composable
internal fun EditorImageBlockDialog(contract:BlockContract, arguments:JsonObject, snapshot:ProjectSnapshot,
    flowId:String, editor:VisualEditorState, store:ProjectStore, runtimeClient:RuntimeClient,
    visible:Boolean, captureSelection:ImageToolCodeGen.VisualSelection?, captureTemplatePath:String?,
    onManageVariables:()->Unit, onWorkflow:(List<VisualBlockTree>)->Unit,
    onInsert:(List<Pair<BlockContract,JsonObject>>)->Unit, onCapture:(ImageToolMode)->Unit, onDismiss:()->Unit) {
    if(contract.kind in extraImageEntryKinds) ImageExtraEntryDialog(contract,arguments,snapshot,flowId,
        projectStore=store, onWorkflow=onWorkflow, visible=visible, onManageVariables=onManageVariables,
        onDismiss=onDismiss, onConfirm=onInsert, captureSelection=captureSelection, onPickFromScreen=onCapture)
    else VisualImageRecognitionDialog(contract=contract, arguments=arguments, resources=snapshot.manifest.resources,
        knownVariables=visualKnownVariables(editor,snapshot.manifest.variables,flowId), snapshot=snapshot,
        confirmLabel="加入", automaticCapture=true, currentFlowId=flowId, onManageVariables=onManageVariables,
        runtimeClient=runtimeClient, onConfirmSource={ selected,configured,capture ->
            onInsert(recognitionImageEntryBlocks(selected,configured,capture))
        }, onWorkflow=onWorkflow, allowKindChange=true, visible=visible, captureSelection=captureSelection,
        captureTemplatePath=captureTemplatePath, onPickFromScreen=onCapture, onDismiss=onDismiss,
        applyInitialTemplateRegion=true,
        onConfirm={ selected,configured -> onInsert(recognitionImageEntryBlocks(selected,configured)) })
}
