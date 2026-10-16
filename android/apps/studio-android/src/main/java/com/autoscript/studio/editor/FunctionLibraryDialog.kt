package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette

/** One compact visual browser, with read-only contracts in function documents. */
@Composable
internal fun FunctionLibraryDialog(
    groups: List<FunctionLibraryGroup>,
    capabilities: Set<String>,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    initialGroup: String? = null,
    documentationOnly: Boolean = false,
) {
    var apiReference by rememberSaveable { mutableStateOf(false) }
    val visibleGroups = if (documentationOnly && apiReference) FunctionCatalog.apiGroups() else groups
    var category by rememberSaveable(initialGroup) { mutableStateOf(initialGroup) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCategory = category?.takeIf { label -> visibleGroups.any { it.label == label } }
    val matched = remember(visibleGroups, query, activeCategory) { FunctionCatalog.search(visibleGroups, query, activeCategory) }
    // Search/category changes start at the first result. Opening/closing details keeps this instance.
    val resultScroll = remember(apiReference, activeCategory, query) { LazyListState() }
    val selected = visibleGroups.flatMap { it.entries }.firstOrNull { it.libraryKey() == selectedKey }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val panelHeight = functionLibraryHeightDp(screenHeight, matched.size).dp
    var fullScreen by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 680.dp).fillMaxWidth(.94f).height(panelHeight),
            color = Color.White,
            shape = RoundedCornerShape(if (fullScreen) 0.dp else 6.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (documentationOnly) "函数文档" else "函数库", color = AutoScriptPalette.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${visibleGroups.sumOf { it.entries.size }} 项", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                    StudioPanelFullScreenButton(fullScreen, { fullScreen = !fullScreen })
                    LibraryControl("×", "关闭函数库", Modifier.width(44.dp).fillMaxHeight(), onClick = onDismiss)
                }
                LibraryDivider()
                if (documentationOnly) Row(Modifier.fillMaxWidth().height(32.dp)) {
                    LibraryControl("积木功能", "查看可视化函数", Modifier.weight(1f).fillMaxHeight(), accent = !apiReference) {
                        apiReference = false; selectedKey = null
                    }
                    LibraryControl("API 契约（只读）", "查看底层API文档", Modifier.weight(1f).fillMaxHeight(), accent = apiReference) {
                        apiReference = true; selectedKey = null
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
                        .heightIn(min = 34.dp).background(Color(0xFFF5F7FB), RoundedCornerShape(4.dp))
                        .border(1.dp, AutoScriptPalette.DividerSoft, RoundedCornerShape(4.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(160) },
                        singleLine = true,
                        textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("搜索名称、用途、参数或 API", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                                field()
                            }
                        },
                    )
                    if (query.isNotEmpty()) LibraryControl("×", "清空搜索", Modifier.width(34.dp).height(34.dp)) { query = "" }
                }
                LibraryDivider()
                Row(Modifier.weight(1f)) {
                    LazyColumn(Modifier.width(FUNCTION_LIBRARY_CATEGORY_WIDTH_DP.dp).fillMaxHeight().background(Color(0xFFF7F8FA))) {
                        item {
                            LibraryCategory("全部", visibleGroups.sumOf { it.entries.size }, activeCategory == null) { category = null }
                        }
                        items(visibleGroups, key = { it.label }) { group ->
                            LibraryCategory(group.label, group.entries.size, activeCategory == group.label) { category = group.label }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.DividerSoft))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (matched.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                                Text("没有匹配函数\n试试中文用途或英文 API", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                                state = resultScroll,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(matched, key = { _, item -> item.second.libraryKey() }) { index, (group, entry) ->
                                    val heading = librarySectionHeading(group, entry.section, activeCategory)
                                    val previous = matched.getOrNull(index - 1)?.let { librarySectionHeading(it.first, it.second.section, activeCategory) }
                                    if (heading.isNotEmpty() && heading != previous) Text(heading,
                                        color = AutoScriptPalette.TextSecondary, fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 2.dp))
                                    LibraryFunctionRow(
                                        entry = entry,
                                        capabilities = capabilities,
                                        onDetail = { selectedKey = entry.libraryKey() },
                                        onInsert = { if (entry.canInsert) onInsert(entry.snippet) },
                                        documentationOnly = documentationOnly,
                                    )
                                }
                            }
                        }
                    }
                }
                LibraryDivider()
                Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
                    Text("${matched.size} 项 · " + if (documentationOnly) "点名称查看说明；不修改项目" else "点名称看详情，点加入添加",
                        color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, style = LibraryCenteredText)
                }
            }
        }
    }
    selected?.let { entry ->
        FunctionLibraryDetailDialog(entry, capabilities, onDismiss = { selectedKey = null }, documentationOnly = documentationOnly) {
            selectedKey = null
            if (entry.canInsert) onInsert(entry.snippet)
        }
    }
}

internal fun functionLibraryHeightDp(screenHeight: Int, count: Int): Int {
    val wanted = 127 + count.coerceIn(4, 7) * 36
    return wanted.coerceAtMost((screenHeight - 48).coerceAtLeast(160))
}

internal const val FUNCTION_LIBRARY_CATEGORY_MIN_HEIGHT_DP = 28
internal const val FUNCTION_LIBRARY_CATEGORY_WIDTH_DP = 76

private fun FunctionLibraryEntry.libraryKey(): String = blockKind ?: apiName ?: snippet

internal fun librarySectionHeading(group: String, section: String, activeCategory: String?): String =
    if (activeCategory == null) listOf(group, section).filter(String::isNotEmpty).joinToString(" · ") else section

private val LibraryCenteredText = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
private fun LibraryCategory(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = FUNCTION_LIBRARY_CATEGORY_MIN_HEIGHT_DP.dp)
            .background(if (selected) Color.White else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(16.dp).background(AutoScriptPalette.Accent))
        Text("$label $count", color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp), textAlign = TextAlign.Center,
            maxLines = 2, style = LibraryCenteredText)
    }
}

@Composable
private fun LibraryFunctionRow(
    entry: FunctionLibraryEntry,
    capabilities: Set<String>,
    onDetail: () -> Unit,
    onInsert: () -> Unit,
    documentationOnly: Boolean,
) {
    val missing = entry.requiredCapabilities - capabilities
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp)
            .background(Color(0xFFFAFBFD), RoundedCornerShape(4.dp))
            .border(1.dp, AutoScriptPalette.DividerSoft, RoundedCornerShape(4.dp))
            .clickable(onClickLabel = "查看${entry.title}详情", onClick = onDetail)
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.title,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            color = AutoScriptPalette.TextPrimary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LibraryCenteredText,
        )
        LibraryControl(
            "详情", "查看${entry.title}详情", Modifier.width(38.dp).height(28.dp),
            onClick = onDetail,
        )
        if (!documentationOnly && entry.canInsert) LibraryControl(
            if (missing.isEmpty()) "加入" else "未启用",
            "加入${entry.title}",
            Modifier.width(44.dp).height(28.dp)
                .background(if (missing.isEmpty()) Color(0xFFEAF1FF) else Color(0xFFF0F1F4), RoundedCornerShape(4.dp)),
            enabled = missing.isEmpty(),
            accent = true,
            onClick = onInsert,
        )
    }
}

@Composable
private fun FunctionLibraryDetailDialog(entry: FunctionLibraryEntry, capabilities: Set<String>, onDismiss: () -> Unit,
    documentationOnly: Boolean, onInsert: () -> Unit) {
    val missing = entry.requiredCapabilities - capabilities
    val height = (190 + entry.parameterInfo.size * 44 + if (entry.notes.isEmpty()) 40 else 90)
        .coerceIn(330, 540).coerceAtMost((LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160)).dp
    var fullScreen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 620.dp).fillMaxWidth(.94f).height(height),
            color = Color.White, shape = RoundedCornerShape(if (fullScreen) 0.dp else 6.dp), shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    StudioPanelFullScreenButton(fullScreen, { fullScreen = !fullScreen })
                    LibraryControl("×", "关闭说明", Modifier.width(44.dp).fillMaxHeight(), onClick = onDismiss)
                }
                LibraryDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(entry.apiName ?: entry.blockKind.orEmpty(), color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(entry.detail, color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
                            Text("${entry.since?.let { "API $it · " }.orEmpty()}${if (entry.requiredCapabilities.isEmpty()) "无需额外能力" else "能力：${entry.requiredCapabilities.joinToString("、")}"}",
                                color = AutoScriptPalette.TextSecondary, fontSize = 10.sp)
                        }
                    }
                    LibraryDocumentSection("参数 · ${entry.parameterInfo.size} 项") {
                        if (entry.parameterInfo.isEmpty()) Text("无参数", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        entry.parameterInfo.forEachIndexed { index, parameter ->
                            if (index > 0) LibraryDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${parameter.name}", fontSize = 12.sp, color = AutoScriptPalette.TextPrimary, modifier = Modifier.weight(1f))
                                    Text("${parameter.type} · ${if (parameter.required) "必填" else "可选"}", fontSize = 10.sp, color = AutoScriptPalette.Accent)
                                }
                                Text(parameter.description, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, lineHeight = 15.sp)
                            }
                        }
                    }
                    if (entry.result.isNotEmpty()) LibraryDocumentSection(if (entry.blockKind == null) "返回值" else "输出 / 作用") {
                        Text(entry.result, fontSize = 12.sp, color = AutoScriptPalette.TextPrimary, lineHeight = 17.sp, modifier = Modifier.padding(8.dp))
                    }
                    if (entry.notes.isNotEmpty()) LibraryDocumentSection("使用说明") {
                        Text(entry.notes, fontSize = 11.sp, lineHeight = 16.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(8.dp))
                    }
                    if (entry.canInsert) {
                        val aliases = FunctionApiReference.aliasesForKind(requireNotNull(entry.blockKind))
                        if (aliases.isNotEmpty()) LibraryDocumentSection("相关底层 API · 不同于插件源码") {
                            SelectionContainer { Text(aliases.joinToString("\n"), fontSize = 11.sp,
                                color = AutoScriptPalette.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp)) }
                        }
                        Text(if (documentationOnly) "在编辑器的函数库中配置并加入此积木；这里仅查看说明，不修改项目。"
                            else "加入使用现有可视化积木配置流程；变量、图像和判断会打开对应参数面板。", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                    }
                }
                if (!documentationOnly && entry.canInsert && missing.isNotEmpty()) Text("无法加入：项目未声明 ${missing.joinToString("、")}", color = AutoScriptPalette.Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                LibraryDivider()
                Row(Modifier.fillMaxWidth().height(42.dp)) {
                    LibraryControl("返回", "返回函数库", Modifier.weight(1f).fillMaxHeight(), onClick = onDismiss)
                    if (!documentationOnly && entry.canInsert) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                        LibraryControl("配置 / 加入", "加入函数", Modifier.weight(1f).fillMaxHeight(),
                            enabled = missing.isEmpty(), accent = true, onClick = onInsert)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryControl(label: String, description: String, modifier: Modifier, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val fontSize = if (label == "×") 23.sp else 13.sp
    Box(modifier.clickable(enabled = enabled, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (!enabled) AutoScriptPalette.TextSecondary else if (accent) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
            fontSize = fontSize, lineHeight = fontSize, style = LibraryCenteredText)
    }
}

@Composable
private fun LibraryDocumentSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(4.dp)).background(Color(0xFFFAFBFD), RoundedCornerShape(4.dp))) {
        Text(title, color = AutoScriptPalette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
        LibraryDivider()
        content()
    }
}

@Composable
private fun LibraryDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.DividerSoft))
