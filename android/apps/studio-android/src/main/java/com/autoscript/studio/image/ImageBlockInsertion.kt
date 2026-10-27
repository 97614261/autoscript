package com.autoscript.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient

internal enum class ImageInsertionOrigin { CONFIGURATION, PREVIEW, WORKBENCH }

/** Frozen configuration plus the exact draft/selection that requested insertion. */
internal class ImageBlockInsertionRequest private constructor(
    val blocks: List<Pair<BlockContract, JsonObject>>,
    val context: EditorPasteRequest,
    val origin: ImageInsertionOrigin,
    private val eligibility: () -> Boolean,
    private val trees: List<VisualBlockTree>? = null,
    val preferredChildSlot: String? = null,
) {
    companion object {
        fun createTrees(projectId: String, flowId: String, editor: VisualEditorState, trees: List<VisualBlockTree>,
            origin: ImageInsertionOrigin, preferredChildSlot: String? = null): ImageBlockInsertionRequest? {
            var count = 0
            fun bounded(tree: VisualBlockTree, depth: Int): Boolean {
                if (++count > 256 || depth > 32 || !tree.contract.childBlocks.containsAll(tree.children.keys)) return false
                return tree.children.values.all { children -> children.all { bounded(it, depth + 1) } }
            }
            if (trees.isEmpty() || !trees.all { bounded(it, 0) }) return null
            val flat = trees.flatMap { it.flattened() }
            if (flat.isEmpty() || flat.size > 256) return null
            return ImageBlockInsertionRequest(flat.map { it.contract to it.arguments.deepCopy() },
                EditorPasteRequest(projectId, flowId, editor.currentSource, editor.selectedNodeId, editor.selectedNodeIds.toSet()),
                origin, { true }, trees.map(VisualBlockTree::frozen), preferredChildSlot)
        }
        fun create(projectId: String, flowId: String, editor: VisualEditorState,
            blocks: List<Pair<BlockContract, JsonObject>>, origin: ImageInsertionOrigin,
            eligibility: () -> Boolean = { true }, preferredChildSlot: String? = null): ImageBlockInsertionRequest? {
            if (blocks.isEmpty() || blocks.size > 256) return null
            return ImageBlockInsertionRequest(blocks.map { it.first to it.second.deepCopy() },
                EditorPasteRequest(projectId, flowId, editor.currentSource, editor.selectedNodeId, editor.selectedNodeIds.toSet()), origin, eligibility,
                preferredChildSlot = preferredChildSlot)
        }
    }

    fun isEligible(): Boolean = runCatching(eligibility).getOrDefault(false)

    fun apply(editor: VisualEditorState, projectId: String, flowId: String, capabilities: Set<String>,
        position: EditorInsertPosition, childSlot: String? = null): Boolean {
        if (!isEligible() || !context.matches(projectId, flowId, editor) ||
            !capabilities.containsAll(blocks.flatMap { it.first.requiredCapabilities }) ||
            position == EditorInsertPosition.REPLACE) return false
        if (trees != null) {
            val source = editor.stageTreeInsertion(trees, position, context.primary, childSlot) ?: return false
            return editor.replaceRawSource(context.source, source) == null
        }
        return editor.insertBlocks(blocks, position, context.primary, childSlot)
    }

    fun stage(editor: VisualEditorState, projectId: String, flowId: String, capabilities: Set<String>,
        position: EditorInsertPosition, childSlot: String?): String? {
        if (!isEligible() || !context.matches(projectId, flowId, editor) || position == EditorInsertPosition.REPLACE ||
            !capabilities.containsAll(blocks.flatMap { it.first.requiredCapabilities })) return null
        return if (trees != null) editor.stageTreeInsertion(trees, position, context.primary, childSlot)
            else editor.stageBlockInsertion(blocks, position, context.primary, childSlot)
    }
}

/** Exact staged identities are validated before one live-draft commit. */
internal suspend fun applyValidatedImageInsertion(request: ImageBlockInsertionRequest, editor: VisualEditorState,
    projectId: String, flowId: String, capabilities: Set<String>, position: EditorInsertPosition, childSlot: String?,
    canApply: () -> Boolean, validate: suspend (String) -> VisualCompileResult): String? {
    currentCoroutineContext().ensureActive()
    if (!canApply() || !request.isEligible()) return "编辑、测试会话或运行状态已变化，未加入"
    val previousIds = editor.rows.mapTo(hashSetOf()) { it.nodeId }
    var candidate: String? = null
    // Only prepare/validate in the timed child. A timeout must never race a live-draft commit.
    val proof = try {
        withTimeout(15_000) {
            candidate = request.stage(editor, projectId, flowId, capabilities, position, childSlot)
            val source = candidate ?: return@withTimeout VisualCompileResult.Unavailable("加入位置、能力或选择已变化，未加入")
            validate(source)
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // Parent/session cancellation remains cancellation.
        return "加入校验超时，原草稿保留；请重试"
    }
    val source = candidate ?: return (proof as? VisualCompileResult.Unavailable)?.message ?: "未能准备加入积木"
    val problem = applyValidatedPluginSource(editor, request.context.source, source,
        canApply = { canApply() && request.isEligible() && request.context.matches(projectId, flowId, editor) }, validate = { proof })
    if (problem == null) editor.rows.firstOrNull { it.nodeId !in previousIds }?.let { editor.selectOnly(it.nodeId) }
    return problem
}

internal class ImageWorkbenchInsertionTarget(
    val flowId: String,
    val editor: VisualEditorState,
    val onManageVariables: () -> Unit,
    val preferredChildSlot: String? = null,
    val apply: suspend (ImageBlockInsertionRequest, EditorInsertPosition, String?) -> String?,
)

internal fun validatedWorkbenchTarget(store: ProjectStore, client: RuntimeClient, projectId: String, flowId: String,
    editor: VisualEditorState, currentSnapshot: () -> ProjectSnapshot?, drafts: () -> Map<String, String>,
    canApply: () -> Boolean, onManageVariables: () -> Unit, onCommitted: () -> Unit,
    preferredChildSlot: String? = null): ImageWorkbenchInsertionTarget =
    ImageWorkbenchInsertionTarget(flowId, editor, onManageVariables, preferredChildSlot) { request, position, slot ->
        val inspected = currentSnapshot()
        if (inspected == null || inspected.manifest.projectId != projectId) "项目已变化，未加入"
        else {
            val expectedDrafts = drafts().toMap()
            val runtime = client.runtimeStates.value
            var validated = false
            var formalStillSame = false
            val problem = applyValidatedImageInsertion(request, editor, projectId, flowId, inspected.manifest.capabilities.toSet(), position, slot,
                canApply = {
                    val current = currentSnapshot()
                    val state = client.runtimeStates.value
                    canApply() && current != null && current.manifest == inspected.manifest && current.flowSources == inspected.flowSources &&
                        drafts() == expectedDrafts && state.sessionGeneration == runtime.sessionGeneration && state.stateRevision == runtime.stateRevision &&
                        (!validated || formalStillSame)
                }, validate = { candidate ->
                    val result = withContext(Dispatchers.IO) {
                        val before = store.openProject(projectId)
                        if (before.manifest != inspected.manifest || before.flowSources != inspected.flowSources)
                            VisualCompileResult.Unavailable("正式项目已变化，未加入")
                        else {
                            val compiled = client.validateVisualDraftSet(projectId, (expectedDrafts + (flowId to candidate))
                                .mapValues { it.value.toByteArray(Charsets.UTF_8) })
                            val after = store.openProject(projectId)
                            formalStillSame = after.manifest == before.manifest && after.flowSources == before.flowSources
                            compiled
                        }
                    }
                    validated = true
                    result
                })
            if (problem == null) onCommitted()
            problem
        }
    }

@Composable
internal fun ImageInsertionPositionDialog(request: ImageBlockInsertionRequest, editor: VisualEditorState,
    onDismiss: () -> Unit, onConfirm: (EditorInsertPosition, String?) -> Unit) {
    val slots = request.context.primary?.let(editor::childBlockNames).orEmpty()
    val preferred = request.preferredChildSlot?.takeIf { it in slots }
    val previewNodes = remember(request) { editor.programDisplayNodes().toEditorTreeNodes() }
    var choosingBranch by remember(request) { mutableStateOf(false) }
    if (choosingBranch) EditorOptionDialog("选择子积木区", slots.map { it to childBlockLabel(it) },
        onDismiss = { choosingBranch = false },
        preview = { slot -> EditorInsertionPreview(previewNodes, request.context.primary, EditorInsertPosition.INSIDE, slot) },
        onConfirm = { onConfirm(EditorInsertPosition.INSIDE, it) })
    else EditorInsertPositionDialog(
        hasSelection = request.context.primary != null,
        canInsertInside = slots.isNotEmpty(), allowReplace = false, title = "图像加入位置",
        previewNodes = previewNodes, previewAnchorId = request.context.primary, previewChildSlot = preferred,
        initialPosition = if (preferred != null) EditorInsertPosition.INSIDE
            else if (request.context.primary == null) EditorInsertPosition.LIST_BOTTOM else EditorInsertPosition.BELOW,
        insideLabel = preferred?.let { "加入${childBlockLabel(it)}" } ?: "选择行里面",
        onDismiss = onDismiss, onConfirm = { position ->
            if (position == EditorInsertPosition.INSIDE && preferred != null) onConfirm(position, preferred)
            else if (position == EditorInsertPosition.INSIDE && slots.size > 1) choosingBranch = true
            else onConfirm(position, if (position == EditorInsertPosition.INSIDE) slots.singleOrNull() else null)
        },
    )
}
