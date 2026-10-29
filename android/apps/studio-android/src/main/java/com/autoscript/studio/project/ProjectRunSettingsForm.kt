package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.*

internal data class ProjectRunSettingsDraft(
    val settings: ProjectDebugSettings,
    val numbers: Map<String, String>,
) {
    fun popupStyleDraft(): PopupStyleDraft = PopupStyleDraft.from(settings.popupStyle).let {
        it.copy(inputs = it.inputs + numbers.filterKeys { key -> key in POPUP_NUMBER_RANGES })
    }

    fun withPopupPosition(positioned: PopupStyleDraft): ProjectRunSettingsDraft = copy(
        numbers = numbers + ("x" to positioned.inputs.getValue("x")) + ("y" to positioned.inputs.getValue("y")),
    )

    fun parse(): ProjectDebugSettings? = runCatching {
        fun number(key: String) = requireNotNull(numbers[key]?.toIntOrNull())
        val style = settings.popupStyle.copy(widthPx = number("width"), heightPx = number("height"),
            xPx = number("x"), yPx = number("y"), fontPx = number("font"), cornerPx = number("corner"), durationMs = number("duration"))
        require(number("delay") in 0..60000)
        require(style.widthPx in 1..2160 && style.heightPx in 1..1200 && style.xPx in -1..10000 && style.yPx in -1..10000 &&
            style.fontPx in 10..160 && style.cornerPx in 0..200 && style.durationMs in 500..30000)
        require(listOf(style.backgroundColor, style.textColor).all { it.matches(Regex("#[0-9A-Fa-f]{8}")) })
        require(style.textAlign in setOf("left", "center", "right"))
        settings.copy(runDelayMs = number("delay"), popupStyle = style)
    }.getOrNull()

    companion object {
        fun from(settings: ProjectDebugSettings) = settings.popupStyle.let {
            ProjectRunSettingsDraft(settings, mapOf("delay" to settings.runDelayMs.toString(), "width" to it.widthPx.toString(),
                "height" to it.heightPx.toString(), "x" to it.xPx.toString(), "y" to it.yPx.toString(), "font" to it.fontPx.toString(),
                "corner" to it.cornerPx.toString(), "duration" to it.durationMs.toString()))
        }
    }
}

@Composable
internal fun ProjectRunSettingsForm(
    draft: ProjectRunSettingsDraft, onChange: (ProjectRunSettingsDraft) -> Unit,
    random: RandomizationForm, onRandomChange: (RandomizationForm) -> Unit,
    variables: List<ProjectVariable>, enabled: Boolean, flows: List<ProjectFlow> = emptyList(),
) {
    fun settings(value: ProjectDebugSettings) = onChange(draft.copy(settings = value))
    fun number(key: String, value: String) = onChange(draft.copy(numbers = draft.numbers + (key to value)))
    val filters = draft.settings.runPromptFilters
    fun filter(value: RunPromptFilters) = settings(draft.settings.copy(runPromptFilters = value))
    RunSection("启动与提示")
    RunField("启动延迟 ms · 0～60000", draft.numbers.getValue("delay"), enabled) { number("delay", it) }
    RunToggle("显示用户提示（总开关）", draft.settings.showRunPrompts, enabled) { settings(draft.settings.copy(showRunPrompts = it)) }
    Row(Modifier.fillMaxWidth()) {
        RunToggle("循环", filters.loops, enabled, Modifier.weight(1f)) { filter(filters.copy(loops = it)) }
        RunToggle("判断 / 跳转", filters.jumps, enabled, Modifier.weight(1f)) { filter(filters.copy(jumps = it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        RunToggle("插件开始", filters.flowStart, enabled, Modifier.weight(1f)) { filter(filters.copy(flowStart = it)) }
        RunToggle("插件返回", filters.flowReturn, enabled, Modifier.weight(1f)) { filter(filters.copy(flowReturn = it)) }
    }
    Row(Modifier.fillMaxWidth()) {
        RunToggle("找图", filters.imageSearch, enabled, Modifier.weight(1f)) { filter(filters.copy(imageSearch = it)) }
        RunToggle("变量", filters.variables, enabled, Modifier.weight(1f)) { filter(filters.copy(variables = it)) }
    }
    if (filters.variables) {
        RunChoices("范围", filters.variableScope, listOf("all" to "全部", "global" to "全局") +
            variables.mapNotNull { it.flowId }.distinct().map { id -> "flow:$id" to "插件 · ${flows.find { it.flowId == id }?.displayName() ?: id}" }, enabled) { filter(filters.copy(variableScope = it, variableName = "all")) }
        RunChoices("类型", filters.variableType, listOf("all" to "全部", "integer" to "整数", "number" to "浮点", "string" to "字符", "image" to "图像"), enabled) {
            filter(filters.copy(variableType = it, variableName = "all"))
        }
        val names = variables.filter { (filters.variableScope == "all" || filters.variableScope == "global" && it.scope == ProjectVariableScope.GLOBAL || filters.variableScope == "flow:${it.flowId}") &&
            (filters.variableType == "all" || filters.variableType == it.type.name.lowercase()) }.map { it.name }.distinct().sorted()
        RunChoices("名称", filters.variableName, listOf("all" to "全部") + names.map { it to it }, enabled) { filter(filters.copy(variableName = it)) }
    }
    RunHint("分类只筛选自动提示；手动运行提示和弹窗不受分类限制。日志记录设置仍在「日志」中。")
    RunSection("触摸与等待随机化")
    RunToggle("普通触摸偏移", random.touchEnabled, enabled) { onRandomChange(random.copy(touchEnabled = it)) }
    if (random.touchEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RunField("X ±px", random.touchX, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(touchX = it)) }
        RunField("Y ±px", random.touchY, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(touchY = it)) }
    }
    RunToggle("图像命中动作偏移", random.recognitionEnabled, enabled) { onRandomChange(random.copy(recognitionEnabled = it)) }
    if (random.recognitionEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RunField("X ±px", random.recognitionX, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(recognitionX = it)) }
        RunField("Y ±px", random.recognitionY, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(recognitionY = it)) }
    }
    RunToggle("普通等待浮动", random.waitEnabled, enabled) { onRandomChange(random.copy(waitEnabled = it)) }
    if (random.waitEnabled) {
        RunChoices("单位", random.unit, listOf("milliseconds" to "毫秒增减", "percent" to "百分比增减"), enabled) { onRandomChange(random.copy(unit = it, min = "0", max = "0")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RunField("最小增减", random.min, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(min = it)) }
            RunField("最大增减", random.max, enabled, Modifier.weight(1f)) { onRandomChange(random.copy(max = it)) }
        }
    }
    RunHint("偏移0～4096px；等待±60000ms或±100%，最小≤最大。长按、拖动、录制内部等待及识图超时保持精确。")
    RunSection("用户提示 · 共用弹窗样式")
    listOf(listOf("width" to "宽 px", "height" to "高 px"), listOf("x" to "X px · -1居中", "y" to "Y px · -1居中"),
        listOf("font" to "字体 px", "corner" to "圆角 px"), listOf("duration" to "短时弹窗 ms")).forEach { fields ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.forEach { (key, label) -> RunField(label, draft.numbers.getValue(key), enabled, Modifier.weight(1f)) { number(key, it) } }
        }
        if (fields.any { it.first == "x" }) {
            PopupPositionShortcuts(draft.popupStyleDraft(), enabled) { onChange(draft.withPopupPosition(it)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RunField("背景 #AARRGGBB", draft.settings.popupStyle.backgroundColor, enabled, Modifier.weight(1f)) { settings(draft.settings.copy(popupStyle = draft.settings.popupStyle.copy(backgroundColor = it))) }
        RunField("文字 #AARRGGBB", draft.settings.popupStyle.textColor, enabled, Modifier.weight(1f)) { settings(draft.settings.copy(popupStyle = draft.settings.popupStyle.copy(textColor = it))) }
    }
    RunChoices("文字", draft.settings.popupStyle.textAlign, listOf("left" to "居左", "center" to "居中", "right" to "居右"), enabled) { settings(draft.settings.copy(popupStyle = draft.settings.popupStyle.copy(textAlign = it))) }
    RunHint("宽1～2160、高1～1200、字号10～160、圆角0～200px；坐标-1～10000；短时弹窗500～30000ms。停止时关闭提示。")
}

@Composable private fun RunSection(title: String) {
    HorizontalDivider(color = AutoScriptPalette.Divider, modifier = Modifier.padding(top = 5.dp))
    Text(title, fontSize = 12.sp, color = AutoScriptPalette.Accent, modifier = Modifier.padding(vertical = 5.dp))
}
@Composable private fun RunHint(text: String) { Text(text, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(vertical = 4.dp)) }
@Composable private fun RunToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Checkbox(checked, null, enabled = enabled, modifier = Modifier.size(28.dp))
    }
}
@Composable private fun RunField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        BasicTextField(value, { onChange(it.take(64)) }, enabled = enabled, singleLine = true, textStyle = TextStyle(fontSize = 12.sp, color = AutoScriptPalette.TextPrimary),
            modifier = Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground).padding(horizontal = 8.dp, vertical = 7.dp))
    }
}
@Composable private fun RunChoices(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        StudioCompactChoice(label, selected, options, enabled, onChange)
    }
}
