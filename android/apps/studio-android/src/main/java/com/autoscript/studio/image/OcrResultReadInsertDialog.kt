package com.autoscript.studio

import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectSnapshot
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Reuses the workbench's full-project validator, stale guards, position picker and one undo. */
@Composable
internal fun OcrResultReadInsertDialog(initial:JsonObject,snapshot:ProjectSnapshot,target:ImageWorkbenchInsertionTarget,
    onDismiss:()->Unit,onMessage:(String)->Unit) {
    // Parent runtime observations can rebuild callback wrappers; never reset an open position picker.
    val owner=remember(initial) { target }
    val projectId=remember(initial) { snapshot.manifest.projectId }
    var request by remember(initial) { mutableStateOf<ImageBlockInsertionRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    RecognitionResultReaderDialog(snapshot,owner.flowId,visible=request==null && !busy,
        onManageVariables=owner.onManageVariables,onDismiss=onDismiss,initial=initial,onConfirm={ blocks ->
            if(projectId!=snapshot.manifest.projectId || owner.flowId!=target.flowId || owner.editor!==target.editor)
                onMessage("项目或编辑会话已变化，未加入，请重新打开")
            else request=ImageBlockInsertionRequest.create(projectId,owner.flowId,owner.editor,blocks,ImageInsertionOrigin.PREVIEW)
        })
    request?.let { frozen -> if(!busy) ImageInsertionPositionDialog(frozen,owner.editor,onDismiss={request=null},onConfirm={position,slot ->
        busy=true
        scope.launch {
            try {
                val problem=owner.apply(frozen,position,slot)
                if(problem==null) { onMessage("结果读取已加入，可一次撤销；运行前须有同类型逐字结果");onDismiss() }
                else { request=null;onMessage(problem) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { request=null;onMessage("校验或加入失败，配置与原草稿保留") }
            finally { busy=false }
        }
    }) }
}
