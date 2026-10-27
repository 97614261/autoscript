package com.autoscript.studio

import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TextToolInsertDialog(contract: BlockContract, initial: JsonObject, snapshot: ProjectSnapshot,
    target: ImageWorkbenchInsertionTarget, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val owner = remember(initial) { target }
    val projectId = remember(initial) { snapshot.manifest.projectId }
    val context = remember(initial) { editorInsertionContext(projectId, owner.flowId, owner.editor) }
    var request by remember(initial) { mutableStateOf<ImageBlockInsertionRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    VisualUtilityArguments(contract, initial, snapshot, owner.flowId, owner.onManageVariables, onDismiss,
        onConfirm = { args ->
            if (projectId != snapshot.manifest.projectId || owner.flowId != target.flowId || owner.editor !== target.editor ||
                !context.matches(projectId, target.flowId, target.editor))
                onMessage("项目、插件或选择已变化，未加入，请重新打开")
            else request = ImageBlockInsertionRequest.create(projectId, owner.flowId, owner.editor,
                listOf(contract to args), ImageInsertionOrigin.CONFIGURATION, preferredChildSlot = owner.preferredChildSlot)
        }, confirmLabel = "选择加入位置", visible = request == null && !busy)
    request?.let { frozen -> if (!busy) ImageInsertionPositionDialog(frozen, owner.editor,
        onDismiss = { request = null }, onConfirm = { position, slot ->
            busy = true
            scope.launch {
                try {
                    val problem = owner.apply(frozen, position, slot)
                    if (problem == null) { onMessage("已加入：${contract.title}"); onDismiss() }
                    else { request = null; onMessage(problem) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { request = null; onMessage("校验或加入失败，原草稿保留") }
                finally { busy = false }
            }
        }) }
}
