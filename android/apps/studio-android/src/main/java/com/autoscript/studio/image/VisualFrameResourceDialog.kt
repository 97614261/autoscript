package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

internal val frameResourceKinds=setOf("screen.capture","screen.release","screen.captureregion","screen.loadimage","screen.crop","screen.series","screen.seriesframe","screen.seriesrelease","screen.frameinfo")
internal fun frameResourceArgumentError(kind: String, inputs: Map<String,String>, variables: List<CalculationVariable>): String? {
    fun type(name: String)=variables.find {it.name==name}?.type
    val sources=when(kind) {"screen.crop","screen.frameinfo","screen.release"->listOf("frameVariable" to ProjectVariableType.IMAGE);"screen.capture","screen.series","screen.loadimage","screen.captureregion"->emptyList();else->listOf("sourceVariable" to ProjectVariableType.STRING)}
    for((key,expected) in sources) if(type(inputs[key].orEmpty())!=expected) return "请选择类型匹配的来源变量"
    if(kind !in setOf("screen.release","screen.seriesrelease") && type(inputs["resultVariable"].orEmpty())!=(if(kind in setOf("screen.series","screen.frameinfo")) ProjectVariableType.STRING else ProjectVariableType.IMAGE)) return "请选择类型匹配的结果变量"
    val bounds=when(kind) {
        "screen.captureregion"->mapOf("left" to 0..16383,"top" to 0..16383,"right" to 1..16384,"bottom" to 1..16384)
        "screen.crop"->mapOf("left" to 0..32768,"top" to 0..32768,"right" to 1..32768,"bottom" to 1..32768)
        "screen.series"->mapOf("maxFrames" to 1..120,"durationMs" to 1..60000,"targetFps" to 1..120)
        "screen.seriesframe"->mapOf("index" to 1..120)
        else->emptyMap()
    }
    for((key,range) in bounds) if(inputs[key]?.toIntOrNull()?.let {it in range}!=true) return "数字无效或超出范围：$key"
    if(kind in setOf("screen.crop","screen.captureregion")) {
        if(kind=="screen.crop" && inputs["frameVariable"]==inputs["resultVariable"]) return "源帧和裁剪输出请选择不同变量"
        if(requireNotNull(inputs["left"]?.toIntOrNull())>=requireNotNull(inputs["right"]?.toIntOrNull()) ||
            requireNotNull(inputs["top"]?.toIntOrNull())>=requireNotNull(inputs["bottom"]?.toIntOrNull())) return "裁剪区域不能为空"
    }
    if(kind=="screen.series" && inputs["allowPartial"] !in setOf("true","false")) return "请选择是否允许部分结果"
    return null
}
@Composable
internal fun VisualFrameResourceDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?,onDismiss: () -> Unit,onConfirm: (JsonObject)->Unit,confirmLabel: String, visible: Boolean = true) {
    val initial=remember(contract.kind,arguments) {blockPropertyTexts(contract,arguments)}
    var inputs by remember(initial) {mutableStateOf(initial)}
    var error by remember {mutableStateOf<String?>(null)}
    var discard by remember {mutableStateOf(false)}
    var fullScreen by remember {mutableStateOf(false)}
    val variables=calculationVariables(project.manifest.variables,flowId,project.manifest.flows)
    fun update(key: String,value: String) {inputs=inputs+(key to value); error=null}
    if (visible) StudioPanelDialog(contract.title,{if(inputs!=initial) discard=true else onDismiss()},confirmLabel,
        fullScreen=fullScreen,onToggleFullScreen={fullScreen=!fullScreen},onConfirm={
        error=frameResourceArgumentError(contract.kind,inputs,variables)
        if(error==null) {
            val parsed=parseBlockArguments(contract,inputs,null,emptyMap(),project.manifest.resources)
            error=parsed.second; parsed.first?.let(onConfirm)
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max=if(fullScreen) 900.dp else 360.dp).verticalScroll(rememberScrollState()).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            val numericProperties = contract.properties.filter { it.editor == BlockPropertyEditor.INTEGER && !it.path.endsWith("Variable") }
            contract.properties.filter { it !in numericProperties }.forEach {property ->
                val key=property.path
                when {
                    key == "imagePath" -> ImageToolChoice(property.label,inputs[key].orEmpty().ifEmpty {"请选择"},
                        project.manifest.resources.filter {it.get("kind")?.asString=="image"}.mapNotNull {it.get("path")?.asString}
                            .map {it to it.removePrefix("assets/images/")},true) {update(key,it)}
                    key.endsWith("Variable") -> {
                        val expected=if(key=="sourceVariable" || (key=="resultVariable" && contract.kind in setOf("screen.series","screen.frameinfo"))) ProjectVariableType.STRING else ProjectVariableType.IMAGE
                        CompactVariableChoice(property.label,inputs[key].orEmpty(),variables.filter {it.type==expected}.map {it.name to it.name},true,variables) {update(key,it)}
                    }
                    property.editor==BlockPropertyEditor.BOOLEAN -> ImageToolChoice(property.label,inputs[key].orEmpty(),listOf("true" to "允许","false" to "不允许"),true) {update(key,it)}
                    else -> ImageToolCompactInput(property.label,inputs[key].orEmpty(),true,Modifier.fillMaxWidth(),numeric=true) {update(key,it)}
                }
            }
            ImageToolNumberFields(numericProperties.map { Triple(it.path, it.label, inputs[it.path].orEmpty()) }, true, ::update)
            ImageToolHelp(contract.summary + "\n" + if(contract.kind=="screen.capture") "获取一帧到已声明的图像变量；后续找图、找色、识字选择已有图像，共用这帧。全部完成后释放；点击或等待画面变化后应重新采集。"
            else if(contract.kind=="screen.release") "仅释放本任务持有的普通图像帧；释放后不要继续识别。系列借用帧请释放所属帧系列。"
            else if(contract.kind=="screen.loadimage") "仅加载已登记项目图片，不截图；句柄只在当前任务内有效，后续用释放帧积木释放。"
            else if(contract.kind=="screen.captureregion") "原屏像素范围，不使用设计比例；识别返回区域局部坐标，用映射原屏坐标转换。使用后释放图像。"
            else if(contract.kind=="screen.crop") "原像素裁剪，不换算设计比例；右、下边界不包含。新帧和源帧分别管理。"
            else if(contract.kind=="screen.frameinfo") "读取已有图像，不自动截图或释放。图片资源先用加载项目图片获得图像变量；读取后按需要释放。"
            else "系列句柄只在采集任务内有效，不能保存到文件后跨运行读取；取出帧是借用，释放系列后失效。")
            onManageVariables?.let { ImageToolCompactAction("维护变量", true, onClick = it) }
            error?.let {Text(it,fontSize=11.sp)}
        }
    }
    if(discard && visible) StudioConfirmDialog(onDismissRequest={discard=false},title={Text("放弃未保存参数？")},text={Text("不会加入积木。")},actions=listOf(
        StudioConfirmAction("继续编辑",{discard=false}),StudioConfirmAction("放弃",{discard=false;onDismiss()},destructive=true)))
}
