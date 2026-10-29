package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        require(parts[2].matches(com.autoscript.project.store.ProjectNames.pattern))
        return@runCatching LoopInsertion("control.loopmetric", JsonObject().apply {
            addProperty("metric", parts[1]); addProperty("name", parts[2]); addProperty("unit", "milliseconds")
        })
    }
    require(line.startsWith(LOOP_CONFIG_HINT) && line.length <= 8192)
    val json = JsonParser.parseString(line.removePrefix(LOOP_CONFIG_HINT)).asJsonObject
    require(json.keySet() == setOf("kind", "args"))
    val kind = json["kind"].asString
    require(kind in positionLoopKinds + setOf("control.repeat", "control.while", "control.cycle", "task.sleep"))
    LoopInsertion(kind, json.getAsJsonObject("args").deepCopy())
}.getOrNull()

internal data class LoopDraft(
    val mode: LoopMode = LoopMode.REPEAT, val variableMode: Boolean = false,
    val count: String = "3", val time: String = "3000", val unit: LoopTimeUnit = LoopTimeUnit.MILLISECONDS,
    val countVariable: String = "", val timeVariable: String = "", val countOutput: String = "", val elapsedOutput: String = "",
    val elapsedUnit: LoopTimeUnit = LoopTimeUnit.MILLISECONDS, val maxIterations: String = "10000",
    val cancellable: Boolean = true, val yieldMs: String = "1",
    val delay: String = "100", val delayVariable: String = "", val checkCount: String = "3", val checkTime: String = "3000",
    val checkCountVariable: String = "", val checkTimeVariable: String = "", val checkUnit: LoopTimeUnit = LoopTimeUnit.MILLISECONDS,
    val iterationOutput: String = "", val loopElapsedOutput: String = "",
)
internal data class LoopBuildResult(val insertion: LoopInsertion? = null, val error: String? = null)
internal fun loopDraftFromArguments(kind: String, args: JsonObject): LoopDraft {
    fun text(key: String, fallback: String = "") = args.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
    fun unit(key: String) = LoopTimeUnit.entries.firstOrNull { it.key == text(key) } ?: LoopTimeUnit.MILLISECONDS
    return LoopDraft(
        mode = when (kind) {
            "control.repeat" -> LoopMode.REPEAT
            "control.while", "control.cycle" -> if (args.has("durationMs") || args.has("durationVariable")) LoopMode.TIMED else LoopMode.FOREVER
            "control.loopmetric" -> if (text("metric") == "elapsed") LoopMode.ELAPSED else LoopMode.COUNT
            "control.loopcheck" -> if (text("metric") == "elapsed") LoopMode.CHECK_TIME else LoopMode.CHECK_COUNT
            else -> LoopMode.WAIT
        },
        variableMode = listOf("timesVariable", "durationVariable", "millisecondsVariable", "limitVariable").any(args::has),
        count = text("times", "3"), countVariable = text("timesVariable"),
        time = text("durationMs", "3000"), timeVariable = text("durationVariable"), unit = if (args.has("durationVariable")) unit("durationUnit") else LoopTimeUnit.MILLISECONDS,
        countOutput = text("name"), elapsedOutput = text("name"), elapsedUnit = unit("unit"),
        maxIterations = text("maxIterations", "10000"), delay = text("milliseconds", "100"), delayVariable = text("millisecondsVariable"),
        cancellable = kind != "control.while", yieldMs = text("yieldMs", "1"),
        checkCount = text("limit", "3"), checkTime = text("limit", "3000"), checkCountVariable = text("limitVariable"),
        checkTimeVariable = text("limitVariable"), checkUnit = unit("unit"),
        iterationOutput = text(if (kind == "control.repeat") "indexVariable" else "iterationVariable"),
        loopElapsedOutput = text("elapsedVariable"),
    )
}

internal fun loopModeKind(mode: LoopMode): String = when (mode) {
    LoopMode.REPEAT -> "control.repeat"
    LoopMode.FOREVER, LoopMode.TIMED -> "control.cycle"
    LoopMode.COUNT, LoopMode.ELAPSED -> "control.loopmetric"
    LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME -> "control.loopcheck"
    LoopMode.WAIT -> "task.sleep"
}
internal fun loopModeCanEdit(mode: LoopMode, lockedKind: String?, cancellable: Boolean): Boolean =
    lockedKind == null || (if (!cancellable && mode in setOf(LoopMode.FOREVER, LoopMode.TIMED))
        "control.while" else loopModeKind(mode)) == lockedKind

internal fun loopDraftForMode(draft: LoopDraft, mode: LoopMode): LoopDraft = draft.copy(mode = mode,
    variableMode = when (mode) {
        LoopMode.REPEAT -> draft.countVariable.isNotEmpty()
        LoopMode.TIMED -> draft.timeVariable.isNotEmpty()
        LoopMode.WAIT -> draft.delayVariable.isNotEmpty()
        LoopMode.CHECK_COUNT -> draft.checkCountVariable.isNotEmpty()
        LoopMode.CHECK_TIME -> draft.checkTimeVariable.isNotEmpty()
        else -> false
    })

internal fun clearLoopInputVariable(draft: LoopDraft, mode: LoopMode): LoopDraft {
    val milliseconds = when (mode) {
        LoopMode.TIMED -> loopMilliseconds(draft.time, draft.unit)
        LoopMode.CHECK_TIME -> loopMilliseconds(draft.checkTime, draft.checkUnit)
        else -> null
    }
    val literal = when (mode) {
        LoopMode.REPEAT -> draft.copy(countVariable = "")
        LoopMode.TIMED -> draft.copy(timeVariable = "", time = milliseconds?.toString() ?: draft.time,
            unit = if (milliseconds != null) LoopTimeUnit.MILLISECONDS else draft.unit)
        LoopMode.WAIT -> draft.copy(delayVariable = "")
        LoopMode.CHECK_COUNT -> draft.copy(checkCountVariable = "")
        LoopMode.CHECK_TIME -> draft.copy(checkTimeVariable = "", checkTime = milliseconds?.toString() ?: draft.checkTime,
            checkUnit = if (milliseconds != null) LoopTimeUnit.MILLISECONDS else draft.checkUnit)
        else -> draft
    }
    return loopDraftForMode(literal, mode)
}

internal fun loopDraftForDialog(draft: LoopDraft): LoopDraft {
    var normalized = draft
    // Convert only literal durations; legacy variable and output units retain their meaning.
    for (mode in listOf(LoopMode.TIMED, LoopMode.CHECK_TIME)) {
        if (!loopDraftForMode(normalized, mode).variableMode) normalized = clearLoopInputVariable(normalized, mode)
    }
    return loopDraftForMode(normalized, draft.mode)
}

internal fun loopPickerChoices(key: String, draft: LoopDraft, variables: List<CalculationVariable>): List<CalculationVariable> {
    val types = when (key) {
        "count", "countOutput", "delay", "checkCount", "iterationOutput" -> setOf(ProjectVariableType.INTEGER)
        "elapsedOutput" -> if (draft.elapsedUnit == LoopTimeUnit.MILLISECONDS)
            setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER) else setOf(ProjectVariableType.NUMBER)
        else -> setOf(ProjectVariableType.INTEGER, ProjectVariableType.NUMBER)
    }
    val output = key in setOf("countOutput", "elapsedOutput", "iterationOutput", "loopElapsedOutput")
    return variables.filter { it.type in types && (!output || it.scope != "参数") }
}

internal fun applyLoopPickerSelection(draft: LoopDraft, key: String, value: String): LoopDraft = when (key) {
    "count" -> draft.copy(mode = LoopMode.REPEAT, countVariable = value, variableMode = true)
    "time" -> draft.copy(mode = LoopMode.TIMED, timeVariable = value, variableMode = true)
    "delay" -> draft.copy(mode = LoopMode.WAIT, delayVariable = value, variableMode = true)
    "checkCount" -> draft.copy(mode = LoopMode.CHECK_COUNT, checkCountVariable = value, variableMode = true)
    "checkTime" -> draft.copy(mode = LoopMode.CHECK_TIME, checkTimeVariable = value, variableMode = true)
    "countOutput" -> draft.copy(countOutput = value)
    "elapsedOutput" -> draft.copy(elapsedOutput = value)
    "iterationOutput" -> draft.copy(iterationOutput = value)
    "loopElapsedOutput" -> draft.copy(loopElapsedOutput = value)
    "countPreset" -> draft.copy(mode = LoopMode.REPEAT, count = value, countVariable = "", variableMode = false)
    "checkCountPreset" -> draft.copy(mode = LoopMode.CHECK_COUNT, checkCount = value, checkCountVariable = "", variableMode = false)
    "timePreset" -> draft.copy(mode = LoopMode.TIMED, time = value, timeVariable = "", variableMode = false, unit = LoopTimeUnit.MILLISECONDS)
    "checkTimePreset" -> draft.copy(mode = LoopMode.CHECK_TIME, checkTime = value, checkTimeVariable = "", variableMode = false, checkUnit = LoopTimeUnit.MILLISECONDS)
    "delayPreset" -> draft.copy(mode = LoopMode.WAIT, delay = value, delayVariable = "", variableMode = false)
    else -> draft
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
            if (draft.cancellable) args.addProperty("yieldMs", whole(draft.yieldMs, 1, 1000))
            else {
                args.addProperty("variable", "loopEnabled"); args.addProperty("operator", "equals"); args.addProperty("value", true)
                args.addProperty("always", true); args.addProperty("maxIterations", whole(draft.maxIterations, 1, 1000000))
            }
            if (draft.mode == LoopMode.TIMED) {
                if (draft.variableMode) {
                    args.addProperty("durationVariable", variable(draft.timeVariable, numbers)); args.addProperty("durationUnit", draft.unit.key)
                } else args.addProperty("durationMs", duration(draft.time, draft.unit))
            }
            if (draft.cancellable) "control.cycle" else "control.while"
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
    if (draft.mode in setOf(LoopMode.REPEAT, LoopMode.FOREVER, LoopMode.TIMED)) {
        if (draft.iterationOutput.isNotBlank()) args.addProperty(if (kind == "control.repeat") "indexVariable" else "iterationVariable",
            variable(draft.iterationOutput, integers))
        if (draft.loopElapsedOutput.isNotBlank()) args.addProperty("elapsedVariable", variable(draft.loopElapsedOutput, numbers))
        require(variables.none { it.scope == "参数" && it.name in listOf(draft.iterationOutput, draft.loopElapsedOutput) }) {
            "循环输出请选择已声明变量，不写入插件参数"
        }
    }
    LoopBuildResult(LoopInsertion(kind, args))
}.getOrElse { LoopBuildResult(error = it.message ?: "循环参数无效") }

/** Keep loop creation and operations inside a loop in the same bounded editor. */
@Composable
internal fun VisualLoopDialog(
    variables: List<ProjectVariable>, currentFlowId: String, flows: List<ProjectFlow>,
    onDismiss: () -> Unit, onInsert: (String) -> Unit, onManageVariables: (() -> Unit)? = null,
    initialDraft: LoopDraft = LoopDraft(), lockedKind: String? = null, confirmLabel: String = "加入",
) {
    var draft by remember(initialDraft) { mutableStateOf(loopDraftForDialog(initialDraft)) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pickerScope by remember { mutableStateOf("局部") }
    val scroll = rememberScrollState()
    val choices = calculationVariables(variables, currentFlowId, flows)
    fun openPicker(key: String) {
        picker = key
        pickerScope = preferredCalculationScope(loopPickerChoices(key, draft, choices))
    }
    fun changeMode(mode: LoopMode) {
        if (!loopModeCanEdit(mode, lockedKind, draft.cancellable)) error = "修改时请保留积木类型；其他类型请从循环入口新增"
        else { draft = loopDraftForMode(draft, mode); error = null }
    }
    StudioPanelDialog("循环", onDismiss, showFooter = false, landscapeWidthDp = 320) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().drawWithContent {
                drawContent()
                // Keep the scroll thumb in viewport space rather than inside the moving form.
                if (scroll.maxValue > 0 && scroll.viewportSize > 0) {
                    val thumb = (size.height * scroll.viewportSize / (scroll.maxValue.toFloat() + scroll.viewportSize))
                        .coerceIn(20.dp.toPx().coerceAtMost(size.height), size.height)
                    val top = (size.height - thumb) * scroll.value / scroll.maxValue
                    drawRoundRect(AutoScriptPalette.TextSecondary.copy(alpha = .4f),
                        topLeft = Offset(size.width - 3.dp.toPx(), top), size = Size(2.dp.toPx(), thumb),
                        cornerRadius = CornerRadius(1.dp.toPx()))
                }
            }.verticalScroll(scroll).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val update: (LoopDraft) -> Unit = { draft = it; error = null }
                val canSelect: (LoopMode) -> Boolean = { loopModeCanEdit(it, lockedKind, draft.cancellable) }
                LoopLegacyRows(draft, update, canSelect, ::changeMode, ::openPicker)
                if (draft.mode in setOf(LoopMode.FOREVER, LoopMode.REPEAT, LoopMode.TIMED)) {
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Text("循环输出与设置", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = AutoScriptPalette.TextSecondary)
                    LoopExtraSettings(draft, update, ::openPicker)
                } else Text(when (draft.mode) {
                    LoopMode.COUNT, LoopMode.ELAPSED -> "放入循环体，执行到这一行时读取最近一层循环。"
                    LoopMode.CHECK_COUNT, LoopMode.CHECK_TIME -> "放入循环体，达到阈值时结束最近一层循环。"
                    else -> "只等待，不创建循环体。"
                }, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
            }
            error?.let { Text(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = AutoScriptPalette.Danger, fontSize = 11.sp) }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LoopButton("取消", onDismiss, Modifier.weight(1f).heightIn(min = 36.dp), muted = true)
                Box(Modifier.width(1.dp).height(24.dp).background(AutoScriptPalette.Divider))
                LoopButton(confirmLabel, {
                    val result = buildLoopInsertion(draft, choices)
                    error = result.error
                    result.insertion?.let { onInsert(it.hint()) }
                }, Modifier.weight(1f).heightIn(min = 36.dp))
            }
        }
    }
    picker?.let { key ->
        val preset = key.endsWith("Preset")
        val eligible = loopPickerChoices(key, draft, choices)
        val values = when (key) {
            "countPreset", "checkCountPreset" -> (1..10).map { it.toString() } + listOf("20", "30", "50", "100", "1000")
            "delayPreset" -> listOf("0", "50", "100", "200", "500", "1000", "3000")
            else -> listOf("500", "1000", "2000", "3000", "5000", "10000", "30000", "60000", "300000")
        }
        fun select(value: String) {
            draft = applyLoopPickerSelection(draft, key, value)
            picker = null; error = null
        }
        if (!preset) CompactVariablePickerDialog(options = variablePickerOptions(eligible.map { it.name }, eligible),
            selected = null, onDismiss = { picker = null }, onSelect = ::select, initialScope = pickerScope,
            onManage = onManageVariables)
        else StudioPanelDialog("常用数值", { picker = null }, showFooter = false) {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(8.dp)) {
                    StudioCompactGrid(values, cellDp = 76, maximum = 4) { value, cell ->
                        val label = when {
                            key.contains("Count", true) -> "$value 次"
                            else -> "$value 毫秒"
                        }
                        StudioToolButton(label, cell) { select(value) }
                    }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)) {
                    LoopButton("取消", { picker = null }, Modifier.weight(1f).heightIn(min = 30.dp), muted = true)
                }
            }
        }
    }
}

private fun loopModeLabel(mode: LoopMode): String = when (mode) {
    LoopMode.COUNT -> "读取次数"
    LoopMode.ELAPSED -> "读取耗时"
    LoopMode.WAIT -> "等待 / 空循环"
    LoopMode.CHECK_COUNT -> "到次跳出"
    LoopMode.CHECK_TIME -> "到时跳出"
    else -> mode.title
}

@Composable
private fun LoopLegacyRows(draft: LoopDraft, onChange: (LoopDraft) -> Unit, canSelect: (LoopMode) -> Boolean,
    onSelect: (LoopMode) -> Unit, openPicker: (String) -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth.value < 280f * androidx.compose.ui.platform.LocalDensity.current.fontScale
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LoopMode.entries.forEach { mode ->
                val active = draft.mode == mode
                val enabled = canSelect(mode)
                fun pick(key: String) { onSelect(mode); openPicker(key) }
                val parameter: @Composable () -> Unit = {
                    if (!enabled) Text("保留当前积木类型", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary.copy(alpha = .5f))
                    else when (mode) {
                        LoopMode.FOREVER -> Text(if (draft.cancellable) "直到停止或跳出" else "保留安全次数保护",
                            fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
                        LoopMode.REPEAT -> LoopVariableRow({ pick("count") }) {
                            if (draft.countVariable.isNotEmpty()) LoopSelect("", draft.countVariable,
                                onClear = { onChange(clearLoopInputVariable(draft, mode)) }, clearLabel = "数值", showIcon = false) { pick("count") }
                            else LoopValue(draft.count, { onChange(draft.copy(mode = mode, variableMode = false, count = it)) }, unitLabel = "次") { pick("countPreset") }
                        }
                        LoopMode.TIMED -> LoopVariableRow({ pick("time") }) {
                            if (draft.timeVariable.isNotEmpty()) LoopSelect(draft.unit.label, draft.timeVariable,
                                onClear = { onChange(clearLoopInputVariable(draft, mode)) }, clearLabel = "数值", showIcon = false) { pick("time") }
                            else LoopValue(draft.time, { onChange(draft.copy(mode = mode, variableMode = false, time = it)) },
                                unitLabel = draft.unit.label) { pick("timePreset") }
                        }
                        LoopMode.COUNT -> LoopVariableRow({ pick("countOutput") }) {
                            LoopSelect("整", draft.countOutput, showIcon = false) { pick("countOutput") }
                        }
                        LoopMode.ELAPSED -> LoopVariableRow({ pick("elapsedOutput") }) {
                            LoopSelect(draft.elapsedUnit.label, draft.elapsedOutput, showIcon = false) { pick("elapsedOutput") }
                        }
                        LoopMode.WAIT -> LoopVariableRow({ pick("delay") }) {
                            if (draft.delayVariable.isNotEmpty()) LoopSelect("毫秒", draft.delayVariable,
                                onClear = { onChange(clearLoopInputVariable(draft, mode)) }, clearLabel = "数值", showIcon = false) { pick("delay") }
                            else LoopValue(draft.delay, { onChange(draft.copy(mode = mode, variableMode = false, delay = it)) }, unitLabel = "毫秒") { pick("delayPreset") }
                        }
                        LoopMode.CHECK_COUNT -> LoopVariableRow({ pick("checkCount") }) {
                            if (draft.checkCountVariable.isNotEmpty()) LoopSelect("", draft.checkCountVariable,
                                onClear = { onChange(clearLoopInputVariable(draft, mode)) }, clearLabel = "数值", showIcon = false) { pick("checkCount") }
                            else LoopValue(draft.checkCount, { onChange(draft.copy(mode = mode, variableMode = false, checkCount = it)) }, unitLabel = "次") { pick("checkCountPreset") }
                        }
                        LoopMode.CHECK_TIME -> LoopVariableRow({ pick("checkTime") }) {
                            if (draft.checkTimeVariable.isNotEmpty()) LoopSelect(draft.checkUnit.label, draft.checkTimeVariable,
                                onClear = { onChange(clearLoopInputVariable(draft, mode)) }, clearLabel = "数值", showIcon = false) { pick("checkTime") }
                            else LoopValue(draft.checkTime, { onChange(draft.copy(mode = mode, variableMode = false, checkTime = it)) },
                                unitLabel = draft.checkUnit.label) { pick("checkTimePreset") }
                        }
                    }
                }
                val title: @Composable () -> Unit = {
                    Row(Modifier.heightIn(min = 30.dp).selectable(active, enabled = enabled, role = Role.RadioButton) {
                        focus.clearFocus(); onSelect(mode)
                    }.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).border(1.dp,
                            if (active) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary.copy(alpha = if (enabled) .6f else .3f),
                            RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            if (active) Box(Modifier.size(6.dp).background(AutoScriptPalette.Accent, RoundedCornerShape(50)))
                        }
                        Text(loopModeLabel(mode), fontSize = 11.sp, lineHeight = 14.sp,
                            color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary.copy(alpha = .5f))
                    }
                }
                if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    title()
                    Box(Modifier.fillMaxWidth().padding(start = 21.dp)) { parameter() }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(98.dp)) { title() }
                    Box(Modifier.weight(1f)) { parameter() }
                }
            }
        }
    }
}

@Composable
private fun LoopExtraSettings(draft: LoopDraft, onChange: (LoopDraft) -> Unit, openPicker: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LoopLabeledField("序号输出") {
            LoopSelect("整数", draft.iterationOutput, onClear = { onChange(draft.copy(iterationOutput = "")) }) { openPicker("iterationOutput") }
        }
        LoopLabeledField("耗时输出") {
            LoopSelect("数值", draft.loopElapsedOutput, onClear = { onChange(draft.copy(loopElapsedOutput = "")) }) { openPicker("loopElapsedOutput") }
        }
        if (draft.mode != LoopMode.REPEAT) LoopLabeledField(if (draft.cancellable) "让步 / 毫秒" else "安全次数上限") {
            LoopInput(if (draft.cancellable) draft.yieldMs else draft.maxIterations,
                { onChange(if (draft.cancellable) draft.copy(yieldMs = it) else draft.copy(maxIterations = it)) }, Modifier.fillMaxWidth())
        }
        Text("输出可留空；序号从1起，耗时单位为毫秒。", fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
        if (draft.mode != LoopMode.REPEAT) Text(if (draft.cancellable) "每轮让步1～1000毫秒，可停止或跳出。" else "保留旧节点安全次数保护，不自动改变原行为。",
            fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun LoopLabeledField(label: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth.value < 260f * androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (stacked) StudioFormGroup(label, content = content)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, Modifier.width(66.dp), fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun LoopVariableRow(onVariable: () -> Unit, value: @Composable () -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.weight(1f)) { value() }
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.width(36.dp).heightIn(min = 30.dp).clip(shape)
            .background(AutoScriptPalette.AccentSoft, shape)
            .border(1.dp, AutoScriptPalette.VisualEditor.Border, shape)
            .clickable(role = Role.Button) { focus.clearFocus(); onVariable() }
            .padding(horizontal = 4.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
            Text("变量", fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.Accent)
        }
    }
}

@Composable private fun LoopButton(text: String, onClick: () -> Unit, modifier: Modifier, muted: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(5.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 5.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (muted) AutoScriptPalette.TextSecondary else AutoScriptPalette.Accent,
            fontSize = 12.sp, fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
@Composable private fun LoopInput(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    ImageToolCompactInput("", value, true, modifier.heightIn(min = 30.dp), numeric = true, dense = true) { onChange(it.take(64)) }
}
@Composable private fun LoopValue(value: String, onChange: (String) -> Unit,
    unitLabel: String? = null, onPreset: () -> Unit) {
    ImageToolCompactInput("", value, true, Modifier.fillMaxWidth().heightIn(min = 30.dp), numeric = true, dense = true, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            unitLabel?.let { Text(it, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary) }
            LoopInlineAction("常用", onPreset)
        }
    }) { onChange(it.take(64)) }
}
@Composable private fun LoopSelect(type: String, name: String, onClear: (() -> Unit)? = null,
    clearLabel: String = "清空", showIcon: Boolean = true,
    onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, AutoScriptPalette.VisualEditor.Border, shape), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (type.isNotEmpty()) Text(type, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
            Text(name.ifEmpty { if (showIcon) "选择变量" else "未选择" }, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (showIcon) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_list_24), "选择变量",
                Modifier.size(16.dp), tint = AutoScriptPalette.Accent)
        }
        if (name.isNotEmpty() && onClear != null) LoopInlineAction(clearLabel, onClear)
    }
}

@Composable private fun LoopInlineAction(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 24.dp).clickable(onClick = onClick).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = AutoScriptPalette.Accent)
    }
}
