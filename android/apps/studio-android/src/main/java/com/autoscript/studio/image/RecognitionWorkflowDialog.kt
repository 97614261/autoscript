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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecognitionWorkflowDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot,
    flowId: String, onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (List<VisualBlockTree>) -> Unit,
    visible: Boolean = true) {
    val regionOnly=contract.kind=="screen.compareframes"
    val initial = remember(contract.kind, arguments) { if(regionOnly) RecognitionWaitDraft(mode=RecognitionWaitMode.REGION_STABLE,
        changeTolerance=arguments["tolerance"]?.asString ?: "8") else RecognitionWaitDraft() }
    var draft by remember(initial) { mutableStateOf(initial) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var suggested by remember { mutableStateOf<RecognitionWaitDraft?>(null) }
    var showWork by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val color = contract.kind in setOf("vision.findcolor","vision.findmulticolor")
    val ocr = contract.kind.startsWith("ocr.")
    val variables = calculationVariables(project.manifest.variables, flowId, project.manifest.flows)
    val reserved = recognitionWaitReferences(arguments)
    val slots = recognitionWaitSlots(contract.kind, draft)
    val configured = slots.count { slot -> slot.value !in reserved && slots.count { it.value==slot.value }==1 && variables.any { it.name==slot.value && it.type==slot.type } }
    fun update(next: RecognitionWaitDraft) { draft=next; error=null; suggested=null }
    fun choices(slot: RecognitionVariableSlot) = variables.filter { variable -> variable.type==slot.type && variable.name !in reserved &&
        slots.none { it.key!=slot.key && it.value==variable.name } }.map { it.name to it.name }
    fun close() { if (draft != initial) discard = true else onDismiss() }
    if (visible) StudioPanelDialog(if(regionOnly) "区域变化 / 稳定等待" else "识别 · 等待与重试", ::close, "选择位置并加入", fullScreen = fullScreen,
        onToggleFullScreen = { fullScreen = !fullScreen }, onConfirm = {
            runCatching { recognitionWaitBlocks(contract, arguments, draft, variables) }
                .onSuccess(onConfirm).onFailure { error = it.message ?: "配置无效，未加入"; showWork=true }
        }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 900.dp else 440.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ImageToolChoice("方式", draft.mode.name, RecognitionWaitMode.entries.filter { !regionOnly || it in
                setOf(RecognitionWaitMode.REGION_CHANGED,RecognitionWaitMode.REGION_STABLE) }.map { it.name to it.label }, true) {
                update(draft.copy(mode = RecognitionWaitMode.valueOf(it)))
            }
            ImageToolNumberFields(listOf(Triple("timeout", "总超时ms", draft.timeout), Triple("interval", "重试间隔ms", draft.interval)), true) { key, value ->
                update(if (key == "timeout") draft.copy(timeout = value) else draft.copy(interval = value))
            }
            if (draft.mode == RecognitionWaitMode.RETRY) ImageToolCompactInput("最多尝试次数", draft.attempts, true, Modifier.fillMaxWidth(), numeric = true) { update(draft.copy(attempts = it)) }
            if (draft.mode == RecognitionWaitMode.ABSENT) ImageToolCompactInput("连续未命中 ms", draft.absentMs, true, Modifier.fillMaxWidth(), numeric = true) { update(draft.copy(absentMs = it)) }
            if(draft.mode.usesFreshFrames()) {
                val fields = mutableListOf(Triple("consecutive", "连续通过次数", draft.consecutive))
                if (draft.mode == RecognitionWaitMode.STABLE && !ocr) fields += Triple("driftPx", "最大位移px", draft.driftPx)
                if (draft.mode != RecognitionWaitMode.STABLE) fields += Triple("changePermille", "变化阈值‰", draft.changePermille)
                ImageToolNumberFields(fields, true) { key, value -> update(when (key) {
                    "consecutive" -> draft.copy(consecutive = value)
                    "driftPx" -> draft.copy(driftPx = value)
                    else -> draft.copy(changePermille = value)
                }) }
                if(draft.mode!=RecognitionWaitMode.STABLE) ImageToolCompactInput("RGB容差0–255",draft.changeTolerance,true,Modifier.fillMaxWidth(),numeric=true) {update(draft.copy(changeTolerance=it))}
                Text("每次重新截图；旧缓存不计数。当前截图和前帧均须专用图像变量，允许覆盖并按组合释放。区域等待不执行找图；稳定字库要求完整覆盖且全文相同。",fontSize=10.sp)
                ImageToolToggle("两份图像变量均专用于本组合，允许覆盖和释放",draft.confirmFrames,true) {update(draft.copy(confirmFrames=it))}
            }
            if (ocr) {
                ImageToolCompactInput("目标文字 · 留空为非空文字",draft.targetText,true,Modifier.fillMaxWidth()) { update(draft.copy(targetText=it)) }
                ImageToolToggle("完全一致（否则包含）",draft.exactText,true) { update(draft.copy(exactText=it)) }
            }
            if (color || ocr) slots.firstOrNull { it.key=="match" }?.let { slot ->
                CompactVariableChoice("条件状态 · 整数1/0",slot.value,choices(slot),true,variables) { update(recognitionWaitAssign(draft,slot.key,it)) }
            }
            Text("变量映射 $configured/${slots.size} · 整数${slots.count { it.type==ProjectVariableType.INTEGER }} / 字符${slots.count { it.type==ProjectVariableType.STRING }}",fontSize=11.sp)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                DesignerAction("快速分配",onClick={
                    suggested=recognitionVariableSuggestion(contract.kind,draft,variables,reserved)
                    if(suggested==null) { error="可用声明变量不足，请维护变量；有效选择会保留，不自动新建";showWork=true }
                })
                DesignerAction(if(showWork) "收起映射" else "查看映射",onClick={showWork=!showWork})
                onManageVariables?.let { DesignerAction("维护变量",onClick=it) }
            }
            if(showWork) slots.filter { it.key!="match" }.forEach { slot ->
                CompactVariableChoice("${slot.label} · ${if(slot.type==ProjectVariableType.INTEGER) "整" else "字"}",slot.value,choices(slot),true,variables) {
                    update(recognitionWaitAssign(draft,slot.key,it))
                }
            }
            Text(when {
                regionOnly || draft.mode in setOf(RecognitionWaitMode.REGION_CHANGED,RecognitionWaitMode.REGION_STABLE) -> "比较连续新截图的指定范围，不需要模板、字库或识别输出；末尾完成/耗尽分支可继续编辑。"
                color -> "颜色集合独立输出；原单点是否找到/X/Y不写入。"
                recognitionWaitUsesGlyphDetails(contract.kind,draft) -> "同帧逐字定位目标；目标外可有未知字，目标内未知/跨行/空白不通过。需两个字符工作变量，不覆盖原文字/得分。"
                contract.kind=="ocr.glyph" && draft.targetText.isNotEmpty() -> "完全一致要求覆盖率1000‰并比较完整文字，未知占位符不算目标。"
                contract.kind=="ocr.glyph" -> "非空须至少识别出一字，未知占位符不算有效文字。"
                ocr -> "单行字母数字，大小写敏感；非空/包含/完全一致使用已接受文字，不代表准确率保证。"
                else -> "条件读取${arguments["foundVariable"]?.asString.orEmpty()}，不附带点击动作。"
            },fontSize=10.sp)
            Text("完成1 / 耗尽0；异常和停止直接终止。总期限在识别前后检查，不是SDK硬实时抢占。",fontSize=10.sp)
            DesignerAction(if(showHelp) "收起说明" else "计时与输出说明",onClick={showHelp=!showHelp})
            if(showHelp) Text("每次采集新画面。开始/耗时/次数保留实际值；末尾如果/否则分别处理完成/耗尽。首次未命中开始连续计时，命中重置；轮询等待精确且可取消，不随机。单次调用可能超过剩余时间，到期结果不算完成。",fontSize=10.sp)
            error?.let { Text(it, fontSize = 11.sp, color = com.autoscript.core.designsystem.AutoScriptPalette.Danger) }
        }
    }
    if (discard && visible) EditorPromptDialog("放弃等待配置？", "未加入的工作变量和计时设置将丢弃。", { discard = false },
        { discard = false; onDismiss() }, "继续编辑", "放弃")
    if(visible) suggested?.let { next -> EditorPromptDialog("确认使用声明变量？", recognitionWaitSlots(contract.kind,next).joinToString("\n") { "${it.label}：${it.value}" },
        { suggested=null },{ draft=next;suggested=null;error=null },"取消","使用") }
}
