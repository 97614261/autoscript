package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal

internal const val LOOP_CONFIG_HINT = "--@autoscript-loop-json:"
internal val positionLoopKinds = setOf("control.loopmetric", "control.loopcheck")
internal enum class LoopMode(val title: String) {
    FOREVER("无限循环"), REPEAT("限次循环"), TIMED("限时循环"), COUNT("获取循环次数"), ELAPSED("获取循环时间"),
    WAIT("空循环 / 等待"), CHECK_COUNT("循环超次"), CHECK_TIME("循环超时"),
}
internal enum class LoopTimeUnit(val key: String, val label: String, val factor: Long) {
    MILLISECONDS("milliseconds", "毫秒", 1), SECONDS("seconds", "秒", 1000), MINUTES("minutes", "分钟", 60000),
}
internal data class LoopInsertion(val kind: String, val arguments: JsonObject) {
    fun hint() = LOOP_CONFIG_HINT + JsonObject().apply { addProperty("kind", kind); add("args", arguments) } + "\n"
}
internal fun loopInsertionFromHint(hint: String): LoopInsertion? = runCatching {
    val line = hint.lineSequence().firstOrNull().orEmpty().trim()
    if (line.startsWith(FunctionCatalog.LOOP_HINT_PREFIX + "metric:")) {
        val parts = line.removePrefix(FunctionCatalog.LOOP_HINT_PREFIX).split(':')
        require(parts.size == 3 && parts[1] in setOf("count", "elapsed"))
        require(parts[2].matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
        return@runCatching LoopInsertion("control.loopmetric", JsonObject().apply {
            addProperty("metric", parts[1]); addProperty("name", parts[2]); addProperty("unit", "milliseconds")
        })
    }
    require(line.startsWith(LOOP_CONFIG_HINT) && line.length <= 8192)
    val json = JsonParser.parseString(line.removePrefix(LOOP_CONFIG_HINT)).asJsonObject
    require(json.keySet() == setOf("kind", "args"))
    val kind = json["kind"].asString
    require(kind in positionLoopKinds + setOf("control.repeat", "control.while", "task.sleep"))
    LoopInsertion(kind, json.getAsJsonObject("args").deepCopy())
}.getOrNull()

internal data class LoopDraft(
    val mode: LoopMode = LoopMode.REPEAT, val variableMode: Boolean = false,
    val count: String = "3", val time: String = "3", val unit: LoopTimeUnit = LoopTimeUnit.SECONDS,
    val countVariable: String = "", val timeVariable: String = "", val countOutput: String = "", val elapsedOutput: String = "",
    val elapsedUnit: LoopTimeUnit = LoopTimeUnit.SECONDS, val maxIterations: String = "10000",
    val delay: String = "100", val delayVariable: String = "", val checkCount: String = "3", val checkTime: String = "3",
    val checkCountVariable: String = "", val checkTimeVariable: String = "", val checkUnit: LoopTimeUnit = LoopTimeUnit.SECONDS,
)
internal data class LoopBuildResult(val insertion: LoopInsertion? = null, val error: String? = null)
internal fun loopDraftFromArguments(kind: String, args: JsonObject): LoopDraft {
    fun text(key: String, fallback: String = "") = args.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
    fun unit(key: String) = LoopTimeUnit.entries.firstOrNull { it.key == text(key) } ?: LoopTimeUnit.MILLISECONDS
    return LoopDraft(
        mode = when (kind) {
            "control.repeat" -> LoopMode.REPEAT
            "control.while" -> if (args.has("durationMs") || args.has("durationVariable")) LoopMode.TIMED else LoopMode.FOREVER
            "control.loopmetric" -> if (text("metric") == "elapsed") LoopMode.ELAPSED else LoopMode.COUNT
            "control.loopcheck" -> if (text("metric") == "elapsed") LoopMode.CHECK_TIME else LoopMode.CHECK_COUNT
            else -> LoopMode.WAIT
        },
        variableMode = listOf("timesVariable", "durationVariable", "millisecondsVariable", "limitVariable").any(args::has),
        count = text("times", "3"), countVariable = text("timesVariable"),
        time = text("durationMs", "3"), timeVariable = text("durationVariable"), unit = if (args.has("durationVariable")) unit("durationUnit") else LoopTimeUnit.MILLISECONDS,
        countOutput = text("name"), elapsedOutput = text("name"), elapsedUnit = unit("unit"),
        maxIterations = text("maxIterations", "10000"), delay = text("milliseconds", "100"), delayVariable = text("millisecondsVariable"),
        checkCount = text("limit", "3"), checkTime = text("limit", "3"), checkCountVariable = text("limitVariable"),
        checkTimeVariable = text("limitVariable"), checkUnit = unit("unit"),
    )
}

internal fun loopModeKind(mode: LoopMode): String = when (mode) {
    LoopMode.REPEAT -> "control.repeat"
    LoopMode.FOREVER, LoopMode.TIMED -> "control.while"
    LoopMode.COUNT, LoopMode.ELAPSED -> "control.loopmetric"
    LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME -> "control.loopcheck"
    LoopMode.WAIT -> "task.sleep"
}
internal fun loopMilliseconds(value: String, unit: LoopTimeUnit, allowZero: Boolean = false): Long? = runCatching {
    require(value.length in 1..64 && value.trim().matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
    val milliseconds = BigDecimal(value.trim()).multiply(BigDecimal.valueOf(unit.factor))
    require(milliseconds >= BigDecimal.valueOf(if (allowZero) 0 else 1) && milliseconds <= BigDecimal.valueOf(86400000))
    milliseconds.longValueExact()
}.getOrNull()

internal fun buildLoopInsertion(draft: LoopDraft, variables: List<CalculationVariable>): LoopBuildResult = runCatching {
    fun whole(value: String, min: Long, max: Long): Long {
        val number = value.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        require(number != null && number in min..max) { "请输入${min}～${max}之间的整数" }
        return number
    }
    fun variable(name: String, types: Set<ProjectVariableType>): String {
        require(variables.any { it.name == name && it.type in types }) { "请选择当前插件可用且类型正确的变量" }
        return name
    }
    fun duration(value: String, unit: LoopTimeUnit, allowZero: Boolean = false) =
        requireNotNull(loopMilliseconds(value, unit, allowZero)) { "时长需为${if (allowZero) "0" else "1"}～86400000毫秒；不接受非法值或不足1毫秒的精度" }
    val integers = setOf(ProjectVariableType.INTEGER)
    val numbers = integers + ProjectVariableType.NUMBER
    val args = JsonObject()
    val kind = when (draft.mode) {
        LoopMode.REPEAT -> {
            args.addProperty("times", if (draft.variableMode) 0 else whole(draft.count, 0, 1000000))
            if (draft.variableMode) args.addProperty("timesVariable", variable(draft.countVariable, integers))
            "control.repeat"
        }
        LoopMode.TIMED, LoopMode.FOREVER -> {
            args.addProperty("variable", "loopEnabled"); args.addProperty("operator", "equals"); args.addProperty("value", true)
            args.addProperty("always", true); args.addProperty("maxIterations", whole(draft.maxIterations, 1, 1000000))
            if (draft.mode == LoopMode.TIMED) {
                if (draft.variableMode) {
                    args.addProperty("durationVariable", variable(draft.timeVariable, numbers)); args.addProperty("durationUnit", draft.unit.key)
                } else args.addProperty("durationMs", duration(draft.time, draft.unit))
            }
            "control.while"
        }
        LoopMode.COUNT, LoopMode.ELAPSED -> {
            val elapsed = draft.mode == LoopMode.ELAPSED
            args.addProperty("metric", if (elapsed) "elapsed" else "count")
            args.addProperty("name", variable(if (elapsed) draft.elapsedOutput else draft.countOutput,
                if (!elapsed) integers else if (draft.elapsedUnit == LoopTimeUnit.MILLISECONDS) numbers else setOf(ProjectVariableType.NUMBER)))
            args.addProperty("unit", if (elapsed) draft.elapsedUnit.key else "milliseconds")
            "control.loopmetric"
        }
        LoopMode.WAIT -> {
            args.addProperty("milliseconds", if (draft.variableMode) 0 else duration(draft.delay, LoopTimeUnit.MILLISECONDS, true))
            if (draft.variableMode) args.addProperty("millisecondsVariable", variable(draft.delayVariable, integers))
            "task.sleep"
        }
        LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME -> {
            val elapsed = draft.mode == LoopMode.CHECK_TIME
            args.addProperty("metric", if (elapsed) "elapsed" else "count")
            args.addProperty("unit", if (elapsed) draft.checkUnit.key else "milliseconds")
            if (draft.variableMode) {
                args.addProperty("limit", 0)
                args.addProperty("limitVariable", variable(if (elapsed) draft.checkTimeVariable else draft.checkCountVariable, if (elapsed) numbers else integers))
            } else if (elapsed) {
                duration(draft.checkTime, draft.checkUnit)
                args.addProperty("limit", BigDecimal(draft.checkTime.trim()))
            } else args.addProperty("limit", whole(draft.checkCount, 1, 1000000))
            "control.loopcheck"
        }
    }
    LoopBuildResult(LoopInsertion(kind, args))
}.getOrElse { LoopBuildResult(error = it.message ?: "循环参数无效") }

/** Five reference rows are preserved; old-version extras expand within the same parameter page. */
@Composable
internal fun VisualLoopDialog(
    variables: List<ProjectVariable>, currentFlowId: String, flows: List<ProjectFlow>,
    onDismiss: () -> Unit, onInsert: (String) -> Unit, onManageVariables: (() -> Unit)? = null,
    initialDraft: LoopDraft = LoopDraft(), lockedKind: String? = null, confirmLabel: String = "加入",
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(initialDraft.mode in setOf(LoopMode.WAIT, LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME)) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pickerScope by remember { mutableStateOf("局部") }
    val choices = calculationVariables(variables, currentFlowId, flows)
    fun openPicker(key: String) {
        picker = key
        pickerScope = if (choices.any { it.scope != "全局" }) "局部" else "全局"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp).imePadding()) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = minOf(maxHeight, 650.dp)).align(Alignment.Center), color = Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 10.dp) {
                Column {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) { Text(draft.mode.title, color = AutoScriptPalette.Accent, fontSize = 17.sp) }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth().height(32.dp).background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp)) {
                            listOf(false to "固定值", true to "变量").forEach { (value, text) ->
                                LoopButton(text, { draft = draft.copy(variableMode = value); error = null }, Modifier.weight(1f).fillMaxHeight(), selected = draft.variableMode == value)
                            }
                        }
                        fun choose(mode: LoopMode) {
                            if (lockedKind != null && loopModeKind(mode) != lockedKind) error = "修改时请保留积木类型；其他类型请从循环入口新增"
                            else { draft = draft.copy(mode = mode); error = null }
                        }
                        LoopRow(LoopMode.FOREVER, draft.mode, { choose(LoopMode.FOREVER) }) { Text("可停止 · 有安全上限", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp) }
                        LoopRow(LoopMode.REPEAT, draft.mode, { choose(LoopMode.REPEAT) }) {
                            if (draft.variableMode) LoopSelect("整", draft.countVariable) { openPicker("count") }
                            else LoopValue(draft.count, { draft = draft.copy(count = it); error = null }, "预设次数") { openPicker("countPreset") }
                        }
                        LoopRow(LoopMode.TIMED, draft.mode, { choose(LoopMode.TIMED) }) {
                            if (draft.variableMode) LoopSelect("数", draft.timeVariable) { openPicker("time") }
                            else LoopValue(draft.time, { draft = draft.copy(time = it); error = null }, "预设时长") { openPicker("timePreset") }
                        }
                        if (draft.mode == LoopMode.TIMED) LoopUnits("时长单位", draft.unit) { draft = draft.copy(unit = it) }
                        LoopRow(LoopMode.COUNT, draft.mode, { choose(LoopMode.COUNT) }) { LoopSelect("整", draft.countOutput) { openPicker("countOutput") } }
                        LoopRow(LoopMode.ELAPSED, draft.mode, { choose(LoopMode.ELAPSED) }) { LoopSelect(if (draft.elapsedUnit == LoopTimeUnit.MILLISECONDS) "数" else "浮", draft.elapsedOutput) { openPicker("elapsedOutput") } }
                        if (draft.mode == LoopMode.ELAPSED) LoopUnits("读取单位", draft.elapsedUnit) { draft = draft.copy(elapsedUnit = it, elapsedOutput = "") }
                        if (draft.mode in setOf(LoopMode.FOREVER, LoopMode.TIMED)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("安全次数上限", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                LoopInput(draft.maxIterations, { draft = draft.copy(maxIterations = it); error = null }, Modifier.width(110.dp))
                            }
                            Text("达到上限会停止并报错；不会取消任务的停止/资源保护。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        if (draft.mode in setOf(LoopMode.COUNT, LoopMode.ELAPSED)) Text("加入循环体：执行到这一行读取最近循环，不新增循环。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        LoopButton(if (more) "收起更多循环操作 ▴" else "更多循环操作 ▾", { more = !more }, Modifier.fillMaxWidth().height(30.dp))
                        if (more) {
                            HorizontalDivider(color = AutoScriptPalette.Divider)
                            LoopRow(LoopMode.WAIT, draft.mode, { choose(LoopMode.WAIT) }) {
                                if (draft.variableMode) LoopSelect("整", draft.delayVariable) { openPicker("delay") }
                                else LoopValue(draft.delay, { draft = draft.copy(delay = it); error = null }, "毫秒") { openPicker("delayPreset") }
                            }
                            LoopRow(LoopMode.CHECK_COUNT, draft.mode, { choose(LoopMode.CHECK_COUNT) }) {
                                if (draft.variableMode) LoopSelect("整", draft.checkCountVariable) { openPicker("checkCount") }
                                else LoopValue(draft.checkCount, { draft = draft.copy(checkCount = it); error = null }, "预设次数") { openPicker("checkCountPreset") }
                            }
                            LoopRow(LoopMode.CHECK_TIME, draft.mode, { choose(LoopMode.CHECK_TIME) }) {
                                if (draft.variableMode) LoopSelect("数", draft.checkTimeVariable) { openPicker("checkTime") }
                                else LoopValue(draft.checkTime, { draft = draft.copy(checkTime = it); error = null }, "预设时长") { openPicker("checkTimePreset") }
                            }
                            if (draft.mode == LoopMode.CHECK_TIME) LoopUnits("检查单位", draft.checkUnit) { draft = draft.copy(checkUnit = it) }
                            if (draft.mode in setOf(LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME)) Text("放入循环体，达到此阈值就结束最近循环。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                        error?.let { Text(it, color = Color(0xFFD14949), fontSize = 11.sp) }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(42.dp)) {
                        LoopButton("取消", onDismiss, Modifier.weight(1f).fillMaxHeight(), muted = true)
                        LoopButton(confirmLabel, {
                            val result = buildLoopInsertion(draft, choices)
                            error = result.error
                            result.insertion?.let { onInsert(it.hint()); onDismiss() }
                        }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
    picker?.let { key ->
        val preset = key.endsWith("Preset")
        val allowed = when (key) {
            "count", "countOutput", "delay", "checkCount" -> setOf(ProjectVariableType.INTEGER)
            "elapsedOutput" -> if (draft.elapsedUnit == LoopTimeUnit.MILLISECONDS) setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER) else setOf(ProjectVariableType.NUMBER)
            else -> setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
        }
        val values = when (key) {
            "countPreset", "checkCountPreset" -> (1..10).map { it.toString() } + listOf("20", "30", "50", "100", "1000")
            "delayPreset" -> listOf("0", "50", "100", "200", "500", "1000", "3000")
            else -> listOf("0.5", "1", "2", "3", "5", "10", "30", "60", "300")
        }
        Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth(.92f).heightIn(max = 420.dp), color = Color.White, shape = RoundedCornerShape(3.dp)) {
                Column {
                    Text(if (preset) "选择预设" else "选择变量", color = AutoScriptPalette.Accent, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
                    if (!preset) {
                        Row(Modifier.fillMaxWidth().height(34.dp)) {
                            listOf("局部", "全局").forEach { item -> LoopButton("${item}变量", { pickerScope = item }, Modifier.weight(1f).fillMaxHeight(), selected = pickerScope == item) }
                            onManageVariables?.let { LoopButton("＋维护", it, Modifier.weight(1f).fillMaxHeight()) }
                        }
                        Text("仅显示类型匹配的变量；参数/局部优先于同名全局。", color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        val filtered = choices.filter { it.type in allowed && if (pickerScope == "全局") it.scope == "全局" else it.scope != "全局" }
                        if (!preset && filtered.isEmpty()) Text("暂无匹配变量，可点＋维护添加后返回选择。", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
                        val entries = if (preset) values.map { it to if (key.contains("Count", true) || key == "countPreset") "$it 次" else if (key == "delayPreset") "$it 毫秒" else "$it 秒" }
                            else filtered.map { it.name to "${calculationTypeLabel(it.type)} · ${it.name}  (${it.scope})" }
                        entries.forEach { (value, label) ->
                            LoopButton(label, {
                                draft = when (key) {
                                    "count" -> draft.copy(countVariable = value); "time" -> draft.copy(timeVariable = value)
                                    "countOutput" -> draft.copy(countOutput = value); "elapsedOutput" -> draft.copy(elapsedOutput = value)
                                    "delay" -> draft.copy(delayVariable = value); "checkCount" -> draft.copy(checkCountVariable = value); "checkTime" -> draft.copy(checkTimeVariable = value)
                                    "countPreset" -> draft.copy(count = value); "checkCountPreset" -> draft.copy(checkCount = value)
                                    "timePreset" -> draft.copy(time = value, unit = LoopTimeUnit.SECONDS)
                                    "checkTimePreset" -> draft.copy(checkTime = value, checkUnit = LoopTimeUnit.SECONDS)
                                    else -> draft.copy(delay = value)
                                }; picker = null; error = null
                            }, Modifier.fillMaxWidth().height(38.dp), muted = !preset)
                            HorizontalDivider(color = AutoScriptPalette.Divider)
                        }
                    }
                    LoopButton("取消", { picker = null }, Modifier.fillMaxWidth().height(40.dp), muted = true)
                }
            }
        }
    }
}

@Composable private fun LoopButton(text: String, onClick: () -> Unit, modifier: Modifier, selected: Boolean = false, muted: Boolean = false) {
    Box(modifier.background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(4.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (muted) AutoScriptPalette.TextPrimary else AutoScriptPalette.Accent, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
@Composable private fun LoopRow(mode: LoopMode, selected: LoopMode, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == mode, onSelect, Modifier.size(28.dp))
            Text(mode.title, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, maxLines = 1)
        }
        Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) { content() }
    }
}
@Composable private fun LoopInput(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    BasicTextField(value, { onChange(it.take(64)) }, singleLine = true, textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 13.sp, textAlign = TextAlign.Center, platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier.height(32.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)),
        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { field() } })
}
@Composable private fun LoopValue(value: String, onChange: (String) -> Unit, label: String, onPreset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        LoopInput(value, onChange, Modifier.weight(1f))
        LoopButton("$label ▾", onPreset, Modifier.weight(1.15f).height(32.dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)))
    }
}
@Composable private fun LoopSelect(type: String, name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp).border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)), verticalAlignment = Alignment.CenterVertically) {
        Text(type, color = AutoScriptPalette.Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 7.dp))
        Text(name.ifEmpty { "未选择" }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LoopButton("选择变量", onClick, Modifier.width(62.dp).fillMaxHeight())
    }
}
@Composable private fun LoopUnits(title: String, value: LoopTimeUnit, onChange: (LoopTimeUnit) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
        LoopTimeUnit.entries.forEach { unit -> LoopButton(unit.label, { onChange(unit) }, Modifier.width(52.dp).fillMaxHeight().background(if (unit == value) Color(0xFFEAF0FF) else Color.Transparent, RoundedCornerShape(3.dp))) }
    }
}
