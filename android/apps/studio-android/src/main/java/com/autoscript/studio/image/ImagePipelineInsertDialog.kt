package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType

/** Configuration freezes a request but cannot mutate the editor or create variables. */
@Composable
internal fun ImagePipelineInsertDialog(pipeline: ImageProcessingPipeline, originalImagePath: String?,
    snapshot: ProjectSnapshot, target: ImageWorkbenchInsertionTarget, onDismiss: () -> Unit,
    onReady: (ImageBlockInsertionRequest) -> Unit, recognitionTail: ImagePipelineRecognitionTail? = null,
    recognitionIsCurrent: () -> Boolean = { false }, visible: Boolean = true,
    initialDraft: ImageInsertionDraft? = null, onRememberDraft: ((ImageInsertionDraft) -> Unit)? = null) {
    val variables = calculationVariables(snapshot.manifest.variables, target.flowId, snapshot.manifest.flows)
    val images = variables.filter { it.type == ProjectVariableType.IMAGE }.map { it.name to it.name }
    var scratch by remember { mutableStateOf(initialDraft?.scratch ?: images.firstOrNull()?.first.orEmpty()) }
    var output by remember { mutableStateOf(initialDraft?.output ?: images.getOrNull(1)?.first.orEmpty()) }
    var assetSource by remember { mutableStateOf(initialDraft?.assetSource?.takeIf { !it || originalImagePath != null } ?: (originalImagePath != null)) }
    var retainFinal by remember { mutableStateOf(initialDraft?.retainFinal ?: false) }
    var outputNames by remember(recognitionTail) { mutableStateOf(recognitionTail?.outputs.orEmpty().associate {
        it.field to (initialDraft?.outputs?.get(it.field) ?: variables.firstOrNull { variable -> variable.name == it.suggestion && variable.type == it.type }?.name.orEmpty())
    }) }
    SideEffect { onRememberDraft?.invoke(ImageInsertionDraft(scratch,output,assetSource,retainFinal,outputNames.toMap())) }
    val configured = runCatching {
        require(recognitionTail == null || recognitionIsCurrent()) { "识别测试会话已变化，请返回重新测试" }
        val source = if (assetSource) requireNotNull(originalImagePath) else null
        if (recognitionTail == null) imageProcessingBlocks(pipeline, source, scratch, output, variables, snapshot.manifest.resources)
        else imageProcessingRecognitionBlocks(pipeline, source, scratch, output, recognitionTail, outputNames, variables, snapshot.manifest.resources, retainFinal)
    }
    if (visible) ImageWorkspaceConfirmPanel(onDismissRequest = onDismiss, prompt = false, title = { Text("加入处理与识别") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${pipeline.operations.size}步处理 · ${pipeline.dimensions.first}×${pipeline.dimensions.second}px", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ImageToolChoice("来源",if(assetSource) "asset" else "screen",
                listOf("screen" to "运行时截图") + if(originalImagePath!=null) listOf("asset" to "项目原图") else emptyList(),true) {assetSource=it=="asset"}
            if (assetSource) ImageWorkspaceHint(originalImagePath.orEmpty().removePrefix("assets/images/"))
            ImageWorkspaceSection("图像变量") {
                CompactVariableChoice("中间帧", scratch, images, true, variables) { scratch = it }
                CompactVariableChoice("最终图像", output, images, true, variables) { output = it }
            }
            if (recognitionTail != null) {
                ImageWorkspaceSection("${recognitionTail.title}结果") {
                    recognitionTail.outputs.forEach { field -> CompactVariableChoice(field.label, outputNames[field.field].orEmpty(),
                        variables.filter { it.type == field.type }.map { it.name to it.name }, true, variables) { outputNames = outputNames + (field.field to it) } }
                    ImageToolToggle("识别后保留最终图像",retainFinal,true) {retainFinal=it}
                }
            }
            ImageWorkspaceHint(if (recognitionTail != null && !retainFinal) "会覆盖所选变量；中间帧及识别后的最终帧自动释放，只留下识别输出。"
                else "会覆盖两个帧变量的原值。中间帧自动释放；最终图像保留，使用完请释放。")
            ImageToolHelp("处理图坐标不能直接点击原屏。运行时截图采用当时画面；分辨率或方向变化后请重新取材，固定像素范围不自动缩放。")
            ImageToolCompactAction("管理变量",true,onClick=target.onManageVariables)
            if (configured.isFailure) ImageWorkspaceHint(configured.exceptionOrNull()?.message ?: "参数无效",error=true)
        }
    }, confirmButton = { ImageToolCompactAction("选择加入位置",configured.isSuccess,primary=true) {
        ImageBlockInsertionRequest.create(snapshot.manifest.projectId, target.flowId, target.editor,
            configured.getOrThrow(), ImageInsertionOrigin.WORKBENCH,
            eligibility = if (recognitionTail == null) { { true } } else recognitionIsCurrent,
            preferredChildSlot = target.preferredChildSlot)?.let(onReady)
    } }, dismissButton = { ImageToolCompactAction("返回",true,onClick=onDismiss) })
}
