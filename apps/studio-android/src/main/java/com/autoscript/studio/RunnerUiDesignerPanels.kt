package com.autoscript.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.script.ui.UiPage

internal object DesignerColors {
    val Accent = Color(0xFF386AFF)
    val Ink = Color(0xFF27344A)
    val Muted = Color(0xFF7F8BA0)
    val Line = Color(0xFFE2E7F0)
    val Workspace = Color(0xFFF0F3F9)
    val Panel = Color.White
    val Selected = Color(0xFFEDF2FF)
}

@Composable
internal fun DesignerAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false, primary: Boolean = false, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(4.dp))
        .background(if (selected && primary) DesignerColors.Accent else if (selected) DesignerColors.Selected else Color.Transparent)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 5.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, maxLines = 1, style = TextStyle(lineHeight = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), color = when { !enabled -> DesignerColors.Muted.copy(alpha = .45f); selected && primary -> Color.White; selected -> DesignerColors.Accent; else -> DesignerColors.Ink })
    }
}

@Composable
private fun DesignerTabs(names: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(24.dp).background(DesignerColors.Workspace, RoundedCornerShape(4.dp)).padding(2.dp)) {
        names.forEach { name ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(if (name == selected) Color.White else Color.Transparent)
                .clickable { onSelect(name) }, contentAlignment = Alignment.Center) {
                Text(name, fontSize = 10.sp, fontWeight = if (name == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = TextStyle(lineHeight = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    color = if (name == selected) DesignerColors.Accent else DesignerColors.Muted)
            }
        }
    }
}

internal data class DesignerMenuAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

@Composable
internal fun DesignerMenu(label: String, actions: List<DesignerMenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignerAction("$label ⌄", enabled = actions.any { it.enabled }) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DesignerAction(action.label, Modifier.fillMaxWidth().height(28.dp), action.enabled) {
                    expanded = false; action.onClick()
                }
            }
        }
    }
}

@Composable
internal fun DesignerToolbox(draft: RunnerUiDesignerDraft, page: UiPage, selection: Set<String>, busy: Boolean, modifier: Modifier,
    onSelect: (String) -> Unit, onPage: (String) -> Unit, onAdd: (RunnerUiControlKind) -> Unit, onNewPage: () -> Unit,
) {
    var tab by remember { mutableStateOf("添加") }
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(DesignerColors.Panel).border(1.dp, DesignerColors.Line, RoundedCornerShape(6.dp)).padding(4.dp)) {
        DesignerTabs(listOf("控件", "图层"), if (tab == "添加") "控件" else tab) { tab = if (it == "控件") "添加" else it }
        Spacer(Modifier.height(4.dp))
        DesignerChoice("页面", page.title, draft.pages.map { it.id to it.title }, !busy, onPage)
        Spacer(Modifier.height(4.dp))
        if (tab == "添加") Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RunnerUiControlKind.entries.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { kind -> Box(Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(4.dp)).background(DesignerColors.Workspace)
                    .clickable(!busy) { onAdd(kind) }.padding(2.dp), contentAlignment = Alignment.Center) {
                    Text(designerControlName(kind), fontSize = 10.sp, color = DesignerColors.Ink, maxLines = 1,
                        style = TextStyle(lineHeight = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
                } }
            } }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(draft.fields.filter { it.ui.pageId == page.id }.asReversed(), key = { it.id }) { field ->
                Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp)).background(if (field.id in selection) DesignerColors.Selected else Color.Transparent)
                    .clickable(!busy) { onSelect(field.id) }.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (field.ui.parentId != null) "↳ " else "▤ ", color = DesignerColors.Accent, fontSize = 10.sp)
                    Text(field.label, Modifier.weight(1f), fontSize = 10.sp, color = DesignerColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!field.ui.visible) Text("隐", color = DesignerColors.Muted, fontSize = 9.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${draft.fields.count { it.ui.pageId == page.id }} 控件", Modifier.weight(1f), fontSize = 10.sp, color = DesignerColors.Muted)
            DesignerAction("+ 页", enabled = !busy && draft.pages.size < 16, onClick = onNewPage)
        }
    }
}

@Composable
internal fun DesignerInspector(draft: RunnerUiDesignerDraft, page: UiPage, field: RunnerUiFieldDraft?, count: Int, tab: String, enabled: Boolean,
    canAlign: Boolean, snapshot: ProjectSnapshot, modifier: Modifier, onTab: (String) -> Unit, onField: (RunnerUiFieldDraft) -> Unit,
    onPage: (UiPage) -> Unit, onDescription: (String) -> Unit, onReparent: (String?, String) -> Unit, onAlign: (String) -> Unit,
    onDistribute: (Boolean) -> Unit, onLayer: (Boolean) -> Unit, onNudge: (Int, Int) -> Unit, onDeletePage: () -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(DesignerColors.Panel).border(1.dp, DesignerColors.Line, RoundedCornerShape(6.dp)).padding(4.dp)) {
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (field == null) "页面属性" else if (count > 1) "已选 $count 个控件" else field.kind.label, Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DesignerColors.Ink)
            Text("px", fontSize = 9.sp, color = DesignerColors.Muted)
        }
        if (field != null) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            DesignerTabs(listOf("布局", "样式", "交互"), tab, onTab)
        }
        if (field != null && field.kind !in setOf(RunnerUiControlKind.IMAGE, RunnerUiControlKind.CONTAINER, RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS)) {
            Text("文字对齐", fontSize = 9.sp, color = DesignerColors.Muted, style = TextStyle(lineHeight = 11.sp))
            Row(Modifier.fillMaxWidth().height(24.dp).background(DesignerColors.Workspace, RoundedCornerShape(4.dp))) {
                listOf("左对齐" to "left", "居中" to "center", "右对齐" to "right").forEach { (label, key) ->
                    DesignerAction(label, Modifier.weight(1f), enabled, selected = field.ui.alignment == key) { onField(field.copy(ui = field.ui.copy(alignment = key))) }
                }
            }
        }
        key(field?.id ?: page.id, tab) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (field == null) {
                    DesignerInput("名称", page.title, enabled, onChange = { onPage(page.copy(title = it)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        DesignerInput("宽 px", page.width.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 100..4096 }?.let { n -> onPage(page.copy(width = n)) } }
                        DesignerInput("高 px", page.height.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 100..8192 }?.let { n -> onPage(page.copy(height = n)) } }
                    }
                    DesignerSection("常用画布")
                    Row { listOf("紧凑" to (720 to 640), "竖屏" to (720 to 960), "横屏" to (960 to 600)).forEach { (name, size) ->
                        DesignerAction(name, Modifier.weight(1f), enabled, selected = page.width == size.first && page.height == size.second) { onPage(page.copy(width = size.first, height = size.second)) }
                    } }
                    DesignerColor("背景", page.background, enabled) { onPage(page.copy(background = it)) }
                    DesignerInput("说明", draft.description, enabled, onChange = onDescription)
                    DesignerAction("删除空页面", enabled = enabled && draft.pages.size > 1, onClick = onDeletePage)
                } else {
                    val f = field
                    fun style(next: com.autoscript.script.ui.UiPresentation) = onField(f.copy(ui = next))
                    when (tab) {
                        "布局" -> {
                            DesignerInput("标题", f.label, enabled) { onField(f.copy(label = it)) }
                            when {
                                f.kind == RunnerUiControlKind.BOOLEAN -> DesignerToggle("默认选中", f.initialText == "true", enabled) { onField(f.copy(initialText = it.toString())) }
                                f.kind == RunnerUiControlKind.IMAGE -> DesignerChoice("图片", f.initialText.ifEmpty { "选择资源" }, listOf("" to "不显示") + snapshot.manifest.resources.filter { it.get("kind")?.asString == "image" }.map { it.get("path").asString.let { path -> path to path.substringAfterLast('/') } }, enabled) { onField(f.copy(initialText = it)) }
                                f.kind.wireName == "choice" -> {
                                    DesignerInput("选项", f.optionsText, enabled) { onField(f.copy(optionsText = it)) }
                                    DesignerChoice("默认", f.initialText, f.optionsText.split(',').map(String::trim).filter(String::isNotEmpty).map { it to it }, enabled) { onField(f.copy(initialText = it)) }
                                }
                                else -> DesignerInput("内容", f.initialText, enabled, numeric = f.kind.wireName == "integer") { onField(f.copy(initialText = it)) }
                            }
                            if (f.kind.wireName == "integer") Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                DesignerInput("最小", f.minimum, enabled, Modifier.weight(1f), true) { onField(f.copy(minimum = it)) }
                                DesignerInput("最大", f.maximum, enabled, Modifier.weight(1f), true) { onField(f.copy(maximum = it)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                DesignerInput("X", f.ui.x.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 0..4096 }?.let { n -> style(f.ui.copy(x = n)) } }
                                DesignerInput("Y", f.ui.y.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 0..8192 }?.let { n -> style(f.ui.copy(y = n)) } }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                DesignerInput("宽", f.ui.width.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 20..4096 }?.let { n -> style(f.ui.copy(width = n)) } }
                                DesignerInput("高", f.ui.height.toString(), enabled, Modifier.weight(1f), true) { it.toIntOrNull()?.takeIf { n -> n in 20..8192 }?.let { n -> style(f.ui.copy(height = n)) } }
                            }
                            Row { listOf("←" to (-1 to 0), "↑" to (0 to -1), "↓" to (0 to 1), "→" to (1 to 0)).forEach { (label, move) -> DesignerAction(label, Modifier.weight(1f), enabled) { onNudge(move.first, move.second) } }
                                DesignerAction("置顶", enabled = enabled) { onLayer(true) }; DesignerAction("置底", enabled = enabled) { onLayer(false) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                DesignerToggle("可见", f.ui.visible, enabled, Modifier.weight(1f)) { style(f.ui.copy(visible = it)) }
                                DesignerToggle("启用", f.ui.enabled, enabled, Modifier.weight(1f)) { style(f.ui.copy(enabled = it)) }
                                DesignerToggle("必填", f.required, enabled, Modifier.weight(1f)) { onField(f.copy(required = it)) }
                            }
                            if (count > 1) {
                                DesignerSection("多选对齐")
                                listOf(listOf("左" to "left", "居中" to "centerX", "右" to "right"), listOf("上" to "top", "垂中" to "centerY", "下" to "bottom")).forEach { row -> Row {
                                    row.forEach { (label, operation) -> DesignerAction(label, Modifier.weight(1f), enabled && canAlign) { onAlign(operation) } }
                                } }
                                Row { DesignerAction("水平等距", Modifier.weight(1f), enabled && canAlign && count > 2) { onDistribute(true) }; DesignerAction("垂直等距", Modifier.weight(1f), enabled && canAlign && count > 2) { onDistribute(false) } }
                            }
                        }
                        "样式" -> {
                            DesignerColor("文字", f.ui.textColor, enabled) { style(f.ui.copy(textColor = it)) }
                            DesignerColor("背景", f.ui.background, enabled) { style(f.ui.copy(background = it)) }
                            DesignerInput("字号 px", f.ui.fontPx.toString(), enabled, numeric = true) { it.toIntOrNull()?.takeIf { n -> n in 8..160 }?.let { n -> style(f.ui.copy(fontPx = n)) } }
                            DesignerSection("快捷配色")
                            Row { DesignerAction("蓝白", Modifier.weight(1f), enabled) { style(f.ui.copy(background = "#FF386AFF", textColor = "#FFFFFFFF")) }
                                DesignerAction("浅底", Modifier.weight(1f), enabled) { style(f.ui.copy(background = "#FFF2F5FB", textColor = "#FF27344A")) }
                                DesignerAction("透明", Modifier.weight(1f), enabled) { style(f.ui.copy(background = "#00000000")) }
                            }
                        }
                        else -> {
                            Text("ID · ${f.id}", fontSize = 9.sp, color = DesignerColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            DesignerChoice("页面", draft.pages.firstOrNull { it.id == f.ui.pageId }?.title ?: f.ui.pageId, draft.pages.map { it.id to it.title }, enabled) { onReparent(null, it) }
                            DesignerChoice("容器", draft.fields.find { it.id == f.ui.parentId }?.label ?: "页面根层", listOf("" to "页面根层") + draft.fields.filter { it.kind == RunnerUiControlKind.CONTAINER && it.id != f.id && it.ui.pageId == f.ui.pageId }.map { it.id to it.label }, enabled) { onReparent(it.takeIf(String::isNotBlank), f.ui.pageId) }
                            val bindings = snapshot.manifest.variables.filter { v -> if (f.kind.wireName in setOf("integer", "boolean")) v.type.name in setOf("INTEGER", "NUMBER") else v.type.name == "STRING" }.map { v ->
                                val key = if (v.scope == ProjectVariableScope.GLOBAL) "global:${v.name}" else "flow:${v.flowId}:${v.name}"
                                key to (if (v.scope == ProjectVariableScope.GLOBAL) "全局 · ${v.name}" else "${v.flowId} · ${v.name}")
                            } + snapshot.manifest.flows.find { it.flowId == snapshot.manifest.entryFlowId }?.params.orEmpty().filter { p -> p.get("type").asString in when (f.kind.wireName) { "integer" -> setOf("integer", "number"); "boolean" -> setOf("boolean"); else -> setOf("string") } }.map { p -> p.get("name").asString.let { "param:$it" to "入口参数 · $it" } }
                            DesignerChoice("绑定", bindings.find { it.first == f.ui.binding }?.second ?: f.ui.binding ?: "不绑定", listOf("" to "不绑定") + bindings, enabled) { style(f.ui.copy(binding = it.takeIf(String::isNotBlank))) }
                            DesignerSection("事件动作")
                            val actions = listOf("" to "无动作", "run" to "确认运行", "close" to "关闭 / 停止", "minimize" to "最小化") + draft.pages.map { "page:${it.id}" to "切页 · ${it.title}" } + snapshot.manifest.flows.map { "flow:${it.flowId}" to "插件 · ${it.flowId}" }
                            listOf("click" to "点击", "longClick" to "长按", "change" to "值变化", "selection" to "选择").forEach { (key, label) ->
                                DesignerChoice(label, actions.find { it.first == f.ui.events[key] }?.second ?: f.ui.events[key] ?: "无动作", actions, enabled) { style(f.ui.copy(events = if (it.isBlank()) f.ui.events - key else f.ui.events + (key to it))) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignerSection(label: String) { Text(label, color = DesignerColors.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp)) }

@Composable
internal fun DesignerInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, numeric: Boolean = false, onChange: (String) -> Unit) {
    var buffer by remember(label) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, focused) { if (!focused) buffer = value }
    Row(modifier.height(24.dp).clip(RoundedCornerShape(4.dp)).background(DesignerColors.Workspace).border(1.dp, if (focused) DesignerColors.Accent else DesignerColors.Line, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DesignerColors.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        BasicTextField(buffer, { buffer = it; onChange(it) }, enabled = enabled, singleLine = true, modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(color = DesignerColors.Ink, fontSize = 10.sp), cursorBrush = SolidColor(DesignerColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
    }
}

@Composable
private fun DesignerToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    DesignerAction((if (checked) "✓ " else "○ ") + label, modifier, enabled, checked) { onChange(!checked) }
}

@Composable
private fun DesignerChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, DesignerColors.Line, RoundedCornerShape(4.dp)).clickable(enabled) { expanded = true }.padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = DesignerColors.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
            Text(value, Modifier.weight(1f), color = DesignerColors.Ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("⌄", color = DesignerColors.Accent, fontSize = 10.sp)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 224.dp)) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text, fontSize = 10.sp) }, onClick = { expanded = false; onChange(key) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) }
            if (options.isEmpty()) Text("没有可选内容", fontSize = 10.sp, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun DesignerColor(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DesignerInput("${label}色", value, enabled, Modifier.weight(1f), onChange = onChange)
        Box {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(designerColor(value)).border(1.dp, DesignerColors.Line, RoundedCornerShape(4.dp)).clickable(enabled) { focus.clearFocus(); expanded = true })
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf("#FF27344A", "#FF386AFF", "#FF21AF83", "#FFFFB84D", "#FFFF647C", "#FFFFFFFF", "#FFF2F5FB", "#00000000").chunked(4).forEach { colors ->
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        colors.forEach { color -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(designerColor(color))
                            .border(if (value.equals(color, true)) 2.dp else 1.dp, if (value.equals(color, true)) DesignerColors.Accent else DesignerColors.Line, RoundedCornerShape(4.dp))
                            .clickable { expanded = false; onChange(color) }) }
                    }
                }
            }
        }
    }
}

private fun designerControlName(kind: RunnerUiControlKind): String = when (kind) {
    RunnerUiControlKind.TEXT -> "文本"; RunnerUiControlKind.INTEGER -> "整数"
    RunnerUiControlKind.BOOLEAN -> "复选"; RunnerUiControlKind.CHOICE -> "下拉"
    RunnerUiControlKind.TEXTAREA -> "多行"; RunnerUiControlKind.RADIO -> "单选"
    RunnerUiControlKind.PROGRESS -> "进度"; RunnerUiControlKind.NAVIGATION -> "导航"
    else -> kind.label
}

internal fun designerColor(value: String, fallback: Color = Color.White): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)
