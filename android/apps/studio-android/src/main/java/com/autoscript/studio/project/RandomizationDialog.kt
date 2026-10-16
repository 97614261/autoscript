package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.*
import com.google.gson.Gson
import com.google.gson.JsonObject

internal data class RandomizationForm(
    val touchEnabled: Boolean, val touchX: String, val touchY: String,
    val recognitionEnabled: Boolean, val recognitionX: String, val recognitionY: String,
    val waitEnabled: Boolean, val unit: String, val min: String, val max: String,
) {
    fun parse(): ProjectRandomization? = runCatching {
        ProjectRandomization(PositionRandomization(touchEnabled, requireNotNull(touchX.toIntOrNull()), requireNotNull(touchY.toIntOrNull())),
            PositionRandomization(recognitionEnabled, requireNotNull(recognitionX.toIntOrNull()), requireNotNull(recognitionY.toIntOrNull())),
            WaitRandomization(waitEnabled, unit, requireNotNull(min.toIntOrNull()), requireNotNull(max.toIntOrNull())))
            .also { require(it.valid()) }
    }.getOrNull()
    companion object {
        fun from(value: ProjectRandomization) = RandomizationForm(value.touch.enabled,value.touch.xPx.toString(),value.touch.yPx.toString(),
            value.recognition.enabled,value.recognition.xPx.toString(),value.recognition.yPx.toString(),value.wait.enabled,value.wait.unit,value.wait.min.toString(),value.wait.max.toString())
    }
}

@Composable
internal fun RandomizationDialog(initial: ProjectRandomization, busy: Boolean, onDismiss: () -> Unit,
    onSave: (ProjectRandomization, String?) -> String?, initialNodeMode: String? = null, waitNode: Boolean = false,
    externalError: String? = null, enabled: Boolean = true) {
    val baseline = remember(initial) { RandomizationForm.from(initial) }
    var form by remember(initial) { mutableStateOf(baseline) }
    var mode by remember(initialNodeMode) { mutableStateOf(initialNodeMode) }
    var message by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val parsed = form.parse()
    val dirty = form != baseline || mode != initialNodeMode
    val controlsDisabled = busy || !enabled
    fun close() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    StudioPanelDialog(if (mode == null) "项目随机化" else "本积木随机策略", ::close, showFooter = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (mode != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("follow" to "跟随项目", "custom" to "自定义", "disabled" to "关闭").forEach { (value, title) ->
                    Surface(onClick = { if (!controlsDisabled) { mode = value; message = null } }, modifier = Modifier.weight(1f),
                        color = if (mode == value) AutoScriptPalette.AccentSoft else AutoScriptPalette.PageBackground) {
                        Text(title, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 7.dp))
                    }
                }
            }
            if (mode == null || mode == "custom") {
                if (mode == null || !waitNode) RandomPositionFields(if (mode == null) "普通触摸偏移" else "动作位置偏移", form.touchEnabled, form.touchX, form.touchY, controlsDisabled,
                    { form = form.copy(touchEnabled = it) }, { form = form.copy(touchX = it) }, { form = form.copy(touchY = it) })
                if (mode == null) RandomPositionFields("图像命中动作偏移", form.recognitionEnabled, form.recognitionX, form.recognitionY, controlsDisabled,
                    { form = form.copy(recognitionEnabled = it) }, { form = form.copy(recognitionX = it) }, { form = form.copy(recognitionY = it) })
                if (mode == null || waitNode) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("普通等待浮动", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Checkbox(form.waitEnabled, { form = form.copy(waitEnabled = it) }, enabled = !controlsDisabled, modifier = Modifier.size(32.dp))
                    }
                    if (form.waitEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("milliseconds" to "毫秒增减", "percent" to "百分比增减").forEach { (unit, label) ->
                                TextButton(onClick = { form = form.copy(unit = unit, min = "0", max = "0") }, enabled = !controlsDisabled) {
                                    Text((if (form.unit == unit) "● " else "") + label, fontSize = 11.sp)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RandomNumber("最小增减",form.min,!controlsDisabled,Modifier.weight(1f)) { form = form.copy(min = it) }
                            RandomNumber("最大增减",form.max,!controlsDisabled,Modifier.weight(1f)) { form = form.copy(max = it) }
                        }
                        parsed?.wait?.let { wait ->
                            val low = if (wait.unit == "percent") 1000L * (100 + wait.min) / 100 else 1000L + wait.min
                            val high = if (wait.unit == "percent") 1000L * (100 + wait.max) / 100 else 1000L + wait.max
                            Text("等待1000ms → ${low.coerceAtLeast(0)}～${high.coerceAtLeast(0)}ms",fontSize = 10.sp,color = AutoScriptPalette.Accent)
                        }
                    }
                }
            }
            Text("偏移为实际屏幕px，不改变原始坐标和识别结果。长按、拖动、录制内部等待保持精确；零等待、启动延迟、识图轮询和超时不受影响。",fontSize = 10.sp,color = AutoScriptPalette.TextSecondary)
            if (parsed == null) Text("偏移0～4096px；毫秒增减±60000，百分比±100；最小值不能大于最大值。",fontSize = 10.sp,color = AutoScriptPalette.Danger)
            message?.let { Text(it,fontSize = 11.sp,color = AutoScriptPalette.Danger) }
            externalError?.let { Text(it,fontSize = 11.sp,color = AutoScriptPalette.Danger) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { form = RandomizationForm.from(ProjectRandomization()); if (mode != null) mode = "disabled" },enabled = !controlsDisabled) {Text(if (mode == null) "关闭全部" else "关闭本积木",fontSize = 11.sp)}
                TextButton(onClick = ::close,enabled = !busy) {Text("取消",fontSize = 11.sp)}
                TextButton(enabled = !controlsDisabled && parsed != null,onClick = {
                    val problem = onSave(requireNotNull(parsed),mode)
                    if (problem != null) message = problem
                }) {Text(if (busy) "保存中…" else "应用",fontSize = 11.sp)}
            }
        }
    }
    if (discard) EditorPromptDialog("放弃随机设置？","未应用的修改将丢弃。",onDismiss = {discard = false},onConfirm = {discard = false; onDismiss()},confirmLabel = "放弃")
}

@Composable
private fun RandomPositionFields(title:String,enabled:Boolean,x:String,y:String,busy:Boolean,onEnabled:(Boolean)->Unit,onX:(String)->Unit,onY:(String)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
        Text(title,fontSize = 12.sp,modifier = Modifier.weight(1f)); Checkbox(enabled,onEnabled,enabled = !busy,modifier = Modifier.size(32.dp))
    }
    if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RandomNumber("X ±px",x,!busy,Modifier.weight(1f),onX); RandomNumber("Y ±px",y,!busy,Modifier.weight(1f),onY)
    }
}
@Composable
private fun RandomNumber(label:String,value:String,enabled:Boolean,modifier:Modifier,onChange:(String)->Unit) {
    Column(modifier) {
        Text(label,fontSize = 10.sp,color = AutoScriptPalette.TextSecondary)
        BasicTextField(value,{onChange(it.take(8))},enabled = enabled,singleLine = true,textStyle = TextStyle(fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground).padding(horizontal = 8.dp,vertical = 7.dp))
    }
}
internal fun randomizationNodeJson(mode:String,settings:ProjectRandomization):JsonObject = JsonObject().apply {
    addProperty("mode",mode); add("position",Gson().toJsonTree(settings.touch)); add("wait",Gson().toJsonTree(settings.wait))
}

internal fun randomizationRowSuffix(value:JsonObject?,kind:String):String = when (value?.get("mode")?.asString) {
    "disabled" -> " · 随机关"
    "custom" -> if (kind == "task.sleep") value.getAsJsonObject("wait").let {
        if (it["enabled"].asBoolean) " · 浮动${it["min"].asInt}～${it["max"].asInt}${if (it["unit"].asString == "percent") "%" else "ms"}" else " · 精确等待"
    } else value.getAsJsonObject("position").let {
        if (it["enabled"].asBoolean) " · 偏移±${it["xPx"].asInt}/${it["yPx"].asInt}px" else " · 精确位置"
    }
    else -> ""
}
