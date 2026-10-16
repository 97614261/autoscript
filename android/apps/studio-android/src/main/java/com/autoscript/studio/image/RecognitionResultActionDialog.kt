package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

@Composable
internal fun RecognitionResultActionDialog(project: ProjectSnapshot, flowId: String, visible: Boolean,
    onManageVariables: () -> Unit, onDismiss: () -> Unit, onConfirm: (List<Pair<BlockContract, JsonObject>>) -> Unit,
    initial: JsonObject) {
    val contract = requireNotNull(BlockCatalog.find("vision.resultaction"))
    val baseline = remember(initial) { blockPropertyTexts(contract, initial) }
    var inputs by remember(baseline) { mutableStateOf(baseline) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val variables = calculationVariables(project.manifest.variables,flowId,project.manifest.flows)
    fun close() { if(inputs!=baseline) discard=true else onDismiss() }
    fun update(key:String,value:String) {
        inputs=inputs+(key to value)
        if(recognitionActionClearsConfirmation(key)) inputs=inputs+("screenConfirmed" to "false")
        if(key=="selection" && value=="all" && inputs["action"]=="hold") error="逐项不支持按下不弹起，请更换动作" else error=null
    }
    if(visible) StudioPanelDialog("识别结果动作",::close,"确定",fullScreen=fullScreen,onToggleFullScreen={fullScreen=!fullScreen},onConfirm={
        val source=inputs["sourceVariable"]
        error=when {
            variables.none { it.name==source && it.type==ProjectVariableType.STRING } -> "请选择已声明字符结果变量"
            inputs["screenConfirmed"]!="true" -> "必须明确确认结果属于原屏坐标；处理图不能直接点击"
            inputs["action"]=="hold" && inputs["selection"]!="first" -> "按下不弹起只能作用首项"
            inputs["limit"]?.toIntOrNull() !in 1..64 -> "最多处理项须为1–64"
            inputs["durationMs"]?.toIntOrNull() !in 1..5000 -> "时长须为1–5000ms"
            inputs["offsetX"]?.toIntOrNull() !in -32768..32768 || inputs["offsetY"]?.toIntOrNull() !in -32768..32768 -> "偏移须为-32768–32768"
            else -> null
        }
        if(error==null) { val parsed=parseBlockArguments(contract,inputs,null,emptyMap(),project.manifest.resources);error=parsed.second;parsed.first?.let { onConfirm(listOf(contract to it)) } }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max=if(fullScreen) 900.dp else 380.dp).verticalScroll(rememberScrollState()).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            DesignerChoice("结果集合",inputs["sourceVariable"].orEmpty(),variables.filter { it.type==ProjectVariableType.STRING }.map { it.name to it.name },true) { update("sourceVariable",it) }
            val shape=RecognitionResultShape.entries.firstOrNull { it.name==inputs["shape"] }
            DesignerChoice("结果类型",shape?.label ?: "请选择",RecognitionResultShape.entries.filter { it!=RecognitionResultShape.GLYPH_SUMMARY }.map { it.name to it.label },true) { update("shape",it) }
            shape?.let { Text(recognitionResultProducerHint(it),fontSize=10.sp) }
            DesignerChoice("处理范围",inputs["selection"].orEmpty(),listOf("first" to "首项","all" to "逐项（有界）"),true) { update("selection",it) }
            DesignerChoice("动作位置",inputs["anchor"].orEmpty(),listOf("center" to "矩形中心 / 颜色点","topLeft" to "左上 / 颜色点"),true) { update("anchor",it) }
            DesignerChoice("动作",inputs["action"].orEmpty(),listOf("tap" to "点击","hold" to "按下不弹起","tapWait" to "点击后等待","pressRelease" to "按下后定时弹起"),true) { update("action",it) }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                DesignerInput("最多项",inputs["limit"].orEmpty(),true,Modifier.weight(1f),numeric=true) { update("limit",it) }
                DesignerInput("时长ms",inputs["durationMs"].orEmpty(),true,Modifier.weight(1f),numeric=true) { update("durationMs",it) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("offsetX" to "X偏移","offsetY" to "Y偏移").forEach { (key,label) -> DesignerInput(label,inputs[key].orEmpty(),true,Modifier.weight(1f),numeric=true) { update(key,it) } } }
            DesignerToggle("确认来自原屏、未裁剪/旋转/缩放",inputs["screenConfirmed"]=="true",true) { update("screenConfirmed",it.toString()) }
            Text("不会识别或截图。仅当前脚本执行到此处才操作。动作前校验全部所选坐标和当前屏幕尺寸；无结果不操作。处理图请先自行完成坐标映射，不得直接勾选确认。",fontSize=10.sp)
            Text("逐项最多64；按下不弹起仅首项，脚本需明确弹起，停止仍清理。不要沿用上次运行的结果。",fontSize=10.sp)
            DesignerAction("维护变量",onClick=onManageVariables)
            error?.let { Text(it,fontSize=11.sp,color=com.autoscript.core.designsystem.AutoScriptPalette.Danger) }
        }
    }
    if(discard && visible) EditorPromptDialog("放弃结果动作配置？","未加入的配置将丢弃。",{discard=false},{discard=false;onDismiss()},"继续编辑","放弃")
}
