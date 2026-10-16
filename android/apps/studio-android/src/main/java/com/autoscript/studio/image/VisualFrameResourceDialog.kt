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

internal val frameResourceKinds=setOf("screen.loadimage","screen.crop","screen.series","screen.seriesframe","screen.seriesrelease")
internal fun frameResourceArgumentError(kind: String, inputs: Map<String,String>, variables: List<CalculationVariable>): String? {
    fun type(name: String)=variables.find {it.name==name}?.type
    val sources=when(kind) {"screen.crop"->listOf("frameVariable" to ProjectVariableType.IMAGE);"screen.series","screen.loadimage"->emptyList();else->listOf("sourceVariable" to ProjectVariableType.STRING)}
    for((key,expected) in sources) if(type(inputs[key].orEmpty())!=expected) return "请选择类型匹配的来源变量"
    if(kind!="screen.seriesrelease" && type(inputs["resultVariable"].orEmpty())!=(if(kind=="screen.series") ProjectVariableType.STRING else ProjectVariableType.IMAGE)) return "请选择类型匹配的结果变量"
    val bounds=when(kind) {
        "screen.crop"->mapOf("left" to 0..32768,"top" to 0..32768,"right" to 1..32768,"bottom" to 1..32768)
        "screen.series"->mapOf("maxFrames" to 1..120,"durationMs" to 1..60000,"targetFps" to 1..120)
        "screen.seriesframe"->mapOf("index" to 1..120)
        else->emptyMap()
    }
    for((key,range) in bounds) if(inputs[key]?.toIntOrNull()?.let {it in range}!=true) return "数字无效或超出范围：$key"
    if(kind=="screen.crop") {
        if(inputs["frameVariable"]==inputs["resultVariable"]) return "源帧和裁剪输出请选择不同变量"
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
            Text(contract.summary,fontSize=11.sp)
            contract.properties.forEach {property ->
                val key=property.path
                when {
                    key == "imagePath" -> DesignerChoice(property.label,inputs[key].orEmpty().ifEmpty {"请选择"},
                        project.manifest.resources.filter {it.get("kind")?.asString=="image"}.mapNotNull {it.get("path")?.asString}
                            .map {it to it.removePrefix("assets/images/")},true) {update(key,it)}
                    key.endsWith("Variable") -> {
                        val expected=if(key=="sourceVariable" || (key=="resultVariable" && contract.kind=="screen.series")) ProjectVariableType.STRING else ProjectVariableType.IMAGE
                        DesignerChoice(property.label,inputs[key].orEmpty().ifEmpty {"请选择"},variables.filter {it.type==expected}.map {it.name to it.name},true) {update(key,it)}
                    }
                    property.editor==BlockPropertyEditor.BOOLEAN -> DesignerChoice(property.label,inputs[key].orEmpty(),listOf("true" to "允许","false" to "不允许"),true) {update(key,it)}
                    else -> DesignerInput(property.label,inputs[key].orEmpty(),true,Modifier.fillMaxWidth(),numeric=true) {update(key,it)}
                }
            }
            if(contract.kind=="screen.loadimage") Text("仅加载已登记项目图片，不截图；句柄只在当前任务内有效，后续用释放帧积木释放。",fontSize=11.sp)
            else if(contract.kind=="screen.crop") Text("原像素裁剪，不换算设计比例；右、下边界不包含。新帧和源帧分别管理。",fontSize=11.sp)
            else Text("系列句柄只在采集任务内有效，不能保存到文件后跨运行读取；取出帧是借用，释放系列后失效。",fontSize=11.sp)
            onManageVariables?.let {TextButton(onClick=it) {Text("维护变量",fontSize=11.sp)}}
            error?.let {Text(it,fontSize=11.sp)}
        }
    }
    if(discard && visible) StudioConfirmDialog(onDismissRequest={discard=false},title={Text("放弃未保存参数？")},text={Text("不会加入积木。")},actions=listOf(
        StudioConfirmAction("继续编辑",{discard=false}),StudioConfirmAction("放弃",{discard=false;onDismiss()},destructive=true)))
}
