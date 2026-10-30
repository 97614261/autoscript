package com.autoscript.studio

import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The host owns the original snapshot and the validation proof; the model owns neither. */
@Composable
internal fun AiEditorHost(request: AiEditorContext?, identities: VisualProjectIds, snapshot: ProjectSnapshot,
    editors: Map<String, VisualEditorState>, flowId: String, branch: EditorBranchSelection?, client: RuntimeClient,
    store: ProjectStore, transactions: EditorProjectEdits, canMutate: () -> Boolean, onChanged: () -> Unit, onDismiss: () -> Unit,
    settingsRepository: AiSettingsRepository? = null) {
    if (request == null) return
    key(request) {
        val baseline = remember { snapshot }
        val references = remember { editors.toMap() }
        val drafts = remember { editors.mapValues { it.value.currentSource } }
        val selections = remember { editors.mapValues { it.value.selectedNodeId to it.value.selectedNodeIds.toSet() } }
        val generation = remember { client.runtimeStates.value.sessionGeneration }
        val revision = remember { client.executionStateRevision }
        val liveSnapshot by rememberUpdatedState(snapshot)
        val liveFlow by rememberUpdatedState(flowId)
        val liveBranch by rememberUpdatedState(branch)
        val liveEditors by rememberUpdatedState(editors)
        val liveCanMutate by rememberUpdatedState(canMutate)
        var validatedSource by remember { mutableStateOf<String?>(null) }
        fun current(): Boolean = liveSnapshot.manifest == baseline.manifest && liveSnapshot.flowSources == baseline.flowSources &&
            liveSnapshot.resourceRevision == baseline.resourceRevision && liveFlow == request.flowId &&
            liveEditors.keys == references.keys && references.all { (id, state) -> liveEditors[id] === state } &&
            liveEditors.mapValues { it.value.currentSource } == drafts &&
            liveEditors.mapValues { it.value.selectedNodeId to it.value.selectedNodeIds.toSet() } == selections &&
            liveBranch?.slot == request.slot && client.runtimeStates.value.sessionGeneration == generation &&
            client.executionStateRevision == revision && liveCanMutate()
        AiAssistantDialog(request, identities, ::current, onDismiss = onDismiss,
            onValidate = { source ->
                validatedSource = null
                if (!current()) "项目上下文已变化，请重新打开 AI" else {
                    val result = withContext(Dispatchers.IO) {
                        client.validateVisualDraftSet(baseline.manifest.projectId,
                            (drafts + (request.flowId to source)).mapValues { it.value.toByteArray(Charsets.UTF_8) })
                    }
                    coroutineContext.ensureActive()
                    when (result) {
                        is VisualCompileResult.Invalid -> "方案校验未通过：${result.diagnostic.message.take(1200)}。可继续对话调整，项目未修改。"
                        is VisualCompileResult.Unavailable -> "校验暂不可用：${result.message}"
                        is VisualCompileResult.Success -> if (current()) { validatedSource = source; null } else "校验期间项目上下文已变化，请重新打开 AI"
                    }
                }
            }, onApply = { candidate ->
                if (candidate.source != validatedSource) "方案尚未通过完整校验，请重新预览"
                else if (!current()) "项目上下文已变化，请重新打开 AI" else {
                    val stored = withContext(Dispatchers.IO) { store.openProject(baseline.manifest.projectId) }
                    coroutineContext.ensureActive()
                    if (!current() || stored.manifest != baseline.manifest || stored.flowSources != baseline.flowSources)
                        "正式项目或编辑上下文已变化，未应用；请重新打开 AI"
                    else transactions.apply(drafts, mapOf(request.flowId to candidate.source), liveEditors).also { failure ->
                        if (failure == null) onChanged()
                    }
                }
            }, settingsRepository = settingsRepository)
    }
}
