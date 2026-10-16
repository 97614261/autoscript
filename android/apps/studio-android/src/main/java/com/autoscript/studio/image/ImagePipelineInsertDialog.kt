package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType

/** Configuration freezes a request but cannot mutate the editor or create variables. */
@Composable
internal fun ImagePipelineInsertDialog(pipeline: ImageProcessingPipeline, originalImagePath: String?,
    snapshot: ProjectSnapshot, target: ImageWorkbenchInsertionTarget, onDismiss: () -> Unit,
    onReady: (ImageBlockInsertionRequest) -> Unit, recognitionTail: ImagePipelineRecognitionTail? = null,
    recognitionIsCurrent: () -> Boolean = { false }) {
    val variables = calculationVariables(snapshot.manifest.variables, target.flowId, snapshot.manifest.flows)
    val images = variables.filter { it.type == ProjectVariableType.IMAGE }.map { it.name to it.name }
    var scratch by remember { mutableStateOf(images.firstOrNull()?.first.orEmpty()) }
    var output by remember { mutableStateOf(images.getOrNull(1)?.first.orEmpty()) }
    var assetSource by remember { mutableStateOf(originalImagePath != null) }
    var retainFinal by remember { mutableStateOf(false) }
    var outputNames by remember(recognitionTail) { mutableStateOf(recognitionTail?.outputs.orEmpty().associate {
        it.field to variables.firstOrNull { variable -> variable.name == it.suggestion && variable.type == it.type }?.name.orEmpty()
    }) }
    val configured = runCatching {
        require(recognitionTail == null || recognitionIsCurrent()) { "识别测试会话已变化，请返回重新测试" }
        val source = if (assetSource) requireNotNull(originalImagePath) else null
        if (recognitionTail == null) imageProcessingBlocks(pipeline, source, scratch, output, variables, snapshot.manifest.resources)
        else imageProcessingRecognitionBlocks(pipeline, source, scratch, output, recognitionTail, outputNames, variables, snapshot.manifest.resources, retainFinal)
    }
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text("加入处理流水线") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${pipeline.operations.size}步处理 · 预览输出${pipeline.dimensions.first}×${pipeline.dimensions.second}px", fontSize = 11.sp)
            Row {
                TextButton(onClick = { assetSource = false }) { Text(if (!assetSource) "● 运行时截图" else "运行时截图", fontSize = 11.sp) }
                if (originalImagePath != null) TextButton(onClick = { assetSource = true }) { Text(if (assetSource) "● 项目原图" else "项目原图", fontSize = 11.sp) }
            }
            if (assetSource) Text(originalImagePath.orEmpty().removePrefix("assets/images/"), fontSize = 10.sp)
            DesignerChoice("中间帧变量", scratch.ifEmpty { "请选择图像变量" }, images, true) { scratch = it }
            DesignerChoice("最终图像变量", output.ifEmpty { "请选择图像变量" }, images, true) { output = it }
            if (recognitionTail != null) {
                Text("接${recognitionTail.title} · 已测试参数", fontSize = 12.sp)
                recognitionTail.outputs.forEach { field -> DesignerChoice(field.label, outputNames[field.field].orEmpty().ifEmpty { "请选择" },
                    variables.filter { it.type == field.type }.map { it.name to it.name }, true) { outputNames = outputNames + (field.field to it) } }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(retainFinal, { retainFinal = it }); Text("识别后保留最终图像", fontSize = 11.sp)
                }
            }
            Text(if (recognitionTail != null && !retainFinal) "会覆盖所选变量；中间帧及识别后的最终帧自动释放，只留下识别输出。"
                else "会覆盖这两个帧变量的原值。中间帧自动释放；最终图像保留给后续积木，使用完请释放。", fontSize = 11.sp)
            Text("处理图坐标不能直接点击原屏。运行时截图采用当时画面；分辨率/方向变化后请重新取材，固定像素范围不自动缩放。", fontSize = 10.sp)
            TextButton(onClick = target.onManageVariables) { Text("维护变量", fontSize = 11.sp) }
            if (configured.isFailure) Text(configured.exceptionOrNull()?.message ?: "参数无效", fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(enabled = configured.isSuccess, onClick = {
        ImageBlockInsertionRequest.create(snapshot.manifest.projectId, target.flowId, target.editor,
            configured.getOrThrow(), ImageInsertionOrigin.WORKBENCH,
            eligibility = if (recognitionTail == null) { { true } } else recognitionIsCurrent)?.let(onReady)
    }) { Text("选择加入位置") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
