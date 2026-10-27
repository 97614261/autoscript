package com.autoscript.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.project.store.importedResourcePath
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val runtimeToolKinds = setOf("input.record", "input.playrecording", "input.pastetext", "audio.play", "audio.stop", "audio.getvolume", "audio.setvolume", "clipboard.gettext", "clipboard.settext", "app.info", "app.open")

@Composable
internal fun VisualRuntimeToolsDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String,
    onResourcesUpdated: ((ProjectSnapshot) -> Unit)? = null) {
    val initial = remember(contract.kind, arguments) { blockPropertyTexts(contract, arguments) }
    var inputs by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savePrompt = rememberResourceSavePrompt()
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            val oldPaths = project.resourcePaths()
            try {
                val (updated, path) = savePrompt.run { mode -> withContext(Dispatchers.IO) {
                    val store = com.autoscript.project.store.ProjectStore(requireNotNull(project.directory.parentFile))
                    val name = queryDisplayName(context.contentResolver, uri)
                    val imported = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        store.importResource(project.manifest.projectId, com.autoscript.project.store.ProjectResourceKind.AUDIO,
                            name, input, oldPaths, mode = mode)
                    }
                    imported to importedResourcePath(imported, oldPaths, mode)
                } }
                inputs = inputs + ("path" to path); onResourcesUpdated?.invoke(updated); error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "音频导入失败" }
            finally { busy = false }
        }
    }
    fun dismiss() { if (busy) return; if (inputs != initial) discard = true else onDismiss() }
    StudioPanelDialog(contract.title, ::dismiss, confirmLabel, onConfirm = {
        if (!busy) {
            error = runtimeToolArgumentError(contract.kind, inputs, variables)
            if (error == null) {
                val result = parseBlockArguments(contract, inputs, null, emptyMap(), project.manifest.resources)
                error = result.second; result.first?.let(onConfirm)
            }
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(contract.summary, fontSize = 11.sp)
            contract.properties.forEach { property ->
                if (property.path.endsWith("Variable")) {
                    val type = if (contract.kind == "audio.getvolume") ProjectVariableType.INTEGER else ProjectVariableType.STRING
                    DesignerChoice(property.label, inputs[property.path].orEmpty().ifEmpty { "请选择" },
                        (if (property.path == "textVariable") listOf("" to "使用固定文字") else emptyList()) + variables.filter { it.type == type }.map { it.name to it.name }, !busy) {
                        inputs = inputs + (property.path to it); error = null
                    }
                } else DesignerInput(property.label, inputs[property.path].orEmpty(), !busy, Modifier.fillMaxWidth(),
                    numeric = property.editor == BlockPropertyEditor.INTEGER) { inputs = inputs + (property.path to it); error = null }
            }
            if (contract.kind == "audio.play") {
                DesignerChoice("项目音频", inputs["path"].orEmpty(), project.manifest.resources.filter { it.get("kind")?.asString == "audio" }.map {
                    val path = it.get("path").asString; path to path.substringAfterLast('/')
                }, !busy) { inputs = inputs + ("path" to it); error = null }
                TextButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !busy && onResourcesUpdated != null) { Text(if (busy) "导入中…" else "导入项目音频", fontSize = 11.sp) }
                Text("项目音频随备份与独立运行包携带；旧audio/路径仍读取本机data，不自动迁移。", fontSize = 11.sp)
            }
            if (contract.kind in setOf("input.record","input.playrecording")) Text("录制和回放使用设备原像素；方向或尺寸变化拒绝回放，内部等待不随机。", fontSize = 11.sp)
            if (contract.kind=="input.pastetext") Text("会替换剪贴板；先聚焦目标输入框，仅支持可粘贴的控件，不添加或切换输入法。",fontSize=11.sp)
            if (contract.kind.startsWith("clipboard.")) Text("剪贴板受Android后台限制；只处理纯文字，不读取链接文件，也不记录内容。不是目标App的中文输入。",fontSize=11.sp)
            if (contract.kind.startsWith("app.")) Text("仅查询系统允许看见的App；启动是请求，不保证目标已进入前台。",fontSize=11.sp)
            onManageVariables?.let { TextButton(onClick = it, enabled = !busy) { Text("维护变量", fontSize = 11.sp) } }
            error?.let { Text(it, fontSize = 11.sp) }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存参数？") },
        text = { Text("不会加入积木；已明确导入的音频仍保留为项目资源。") }, actions = listOf(
            StudioConfirmAction("继续编辑", { discard = false }), StudioConfirmAction("放弃", { discard = false; onDismiss() }, destructive = true)))
}

internal fun runtimeToolArgumentError(kind: String, inputs: Map<String, String>, variables: List<CalculationVariable>): String? {
    if (kind == "input.record" && (inputs["durationMs"]?.toLongOrNull() !in 1000L..60000L || inputs["eventBudget"]?.toLongOrNull() !in 128L..512L)) return "时长为1000–60000ms，事件预算为128–512"
    if (kind == "audio.setvolume" && inputs["volume"]?.toLongOrNull() !in 0L..100L) return "音量为0–100"
    for (key in listOf("resultVariable", "recordingVariable")) if (key in inputs) {
        val expected = if (kind == "audio.getvolume") ProjectVariableType.INTEGER else ProjectVariableType.STRING
        if (variables.find { it.name == inputs[key] }?.type != expected) return "请选择类型匹配的声明变量"
    }
    if (kind in setOf("clipboard.settext","input.pastetext")) {
        val source=inputs["textVariable"].orEmpty()
        if (source.isNotEmpty() && variables.find { it.name==source }?.type!=ProjectVariableType.STRING) return "请选择字符来源变量"
        if (inputs["text"].orEmpty().toByteArray(Charsets.UTF_8).size>4096) return "剪贴板文字最多4096字节"
    }
    if (kind.startsWith("app.")) {
        val name=inputs["packageName"].orEmpty()
        if (name.length>255 || !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(name)) return "请输入规范App包名"
    }
    if (kind == "audio.play" && inputs["path"].isNullOrBlank()) return "请选择音频或填写私有数据路径"
    return null
}
