package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onInsertWithCompletion: ((String, () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    var usage by remember { mutableStateOf(readFunctionUsage(context)) }
    var collection by rememberSaveable { mutableStateOf("all") }
    var usageMessage by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val prefs = functionUsagePreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FUNCTION_USAGE_KEY) usage = readFunctionUsage(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun requestInsert(entry: FunctionLibraryEntry) {
        if (!entry.canInsert) return
        val submit = onInsertWithCompletion
        if (submit == null) onInsert(entry.snippet) else {
            var recorded = false
            submit(entry.snippet) {
                if (!recorded) {
                    recorded = true
                    entry.blockKind?.let { kind -> updateFunctionUsage(context) { it.used(kind) } }
                }
            }
        }
    }
    var apiReference by rememberSaveable { mutableStateOf(false) }
    val visibleGroups = if (documentationOnly && apiReference) FunctionCatalog.apiGroups() else groups
    var category by rememberSaveable(initialGroup) { mutableStateOf(initialGroup) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCategory = category?.takeIf { label -> visibleGroups.any { it.label == label } }
    val matched = remember(visibleGroups, query, activeCategory, collection, usage) {
        val matches = FunctionCatalog.search(visibleGroups, query, activeCategory)
        val keys = when (collection) { "favorites" -> usage.favorites; "recent" -> usage.recent; else -> null }
        if (keys == null || apiReference) matches else matches.filter { it.second.blockKind in keys }
            .sortedBy { keys.indexOf(it.second.blockKind) }
    }
    // Search/category changes start at the first result. Opening/closing details keeps this instance.
    val resultScroll = remember(apiReference, activeCategory, query, collection) { LazyListState() }
    val selected = visibleGroups.flatMap { it.entries }.firstOrNull { it.libraryKey() == selectedKey }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val panelHeight = functionLibraryHeightDp(screenHeight, matched.size).dp
    var fullScreen by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
        val short = studioShortViewport(maxHeight.value)
        val availableHeight = studioPanelMaxHeight(maxHeight.value, screenHeight, if (short) 8 else 24).dp
        Surface(
            modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.width(studioDialogWidth(maxWidth.value, 520).dp)
                .height(minOf(if (short) availableHeight else panelHeight, availableHeight)),
            color = Color.White,
            shape = RoundedCornerShape(if (fullScreen) 0.dp else 6.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(if (short) 32.dp else 44.dp).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (documentationOnly) "函数文档" else "函数库", color = AutoScriptPalette.Accent, fontSize = if (short) 13.sp else 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${visibleGroups.sumOf { it.entries.size }} 项", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                    StudioPanelFullScreenButton(fullScreen, { fullScreen = !fullScreen }, compact = short)
                    LibraryControl("×", if (documentationOnly) "关闭函数文档" else "关闭函数库", Modifier.width(44.dp).fillMaxHeight(), onClick = onDismiss)
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
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (short) 3.dp else 7.dp)
                        .heightIn(min = if (short) 28.dp else 34.dp).background(Color(0xFFF5F7FB), RoundedCornerShape(4.dp))
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
                if (!apiReference) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
                        listOf("all" to "全部", "favorites" to "收藏", "recent" to "最近使用").forEach { (key, label) ->
                            LibraryControl(label, label, Modifier.weight(1f).heightIn(min = 28.dp), accent = collection == key) {
                                collection = key; category = null; usageMessage = null
                            }
                        }
                    }
                    usageMessage?.let { Text(it, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(horizontal = 10.dp)) }
                }
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
                                Text(when {
                                    collection == "favorites" && usage.favorites.isEmpty() -> "暂无收藏\n点函数旁的☆收藏常用项"
                                    collection == "recent" && usage.recent.isEmpty() -> "还没有加入记录\n实际加入积木后会显示在这里"
                                    documentationOnly -> "没有匹配条目\n试试中文用途或英文 API"
                                    else -> "没有匹配积木\n试试中文用途或英文 API"
                                }, color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
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
                                        onInsert = { requestInsert(entry) },
                                        documentationOnly = documentationOnly,
                                        favorite = entry.blockKind?.let { it in usage.favorites } == true,
                                        onFavorite = entry.blockKind?.let { kind -> {
                                            usageMessage = if (kind !in usage.favorites && usage.favorites.size >= 64) "最多收藏64项，请先取消不常用的收藏" else null
                                            updateFunctionUsage(context) { it.toggle(kind) }
                                        } },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!short) {
                LibraryDivider()
                Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
                    Text("${matched.size} 项 · " + if (documentationOnly) "点名称查看说明；不修改项目" else "点名称看详情，点加入添加",
                        color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, style = LibraryCenteredText)
                }
                }
            }
        }
        }
    }
    selected?.let { entry ->
        FunctionLibraryDetailDialog(entry, capabilities, onDismiss = { selectedKey = null }, documentationOnly = documentationOnly) {
            selectedKey = null
            requestInsert(entry)
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
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
) {
    val missing = BlockCatalog.missingCapabilities(entry.blockKind ?: entry.apiName, entry.requiredCapabilities, capabilities)
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
        if (onFavorite != null) LibraryControl(if (favorite) "★" else "☆",
            if (favorite) "取消收藏${entry.title}" else "收藏${entry.title}", Modifier.width(28.dp).height(28.dp),
            accent = favorite, onClick = onFavorite)
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
    val missing = BlockCatalog.missingCapabilities(entry.blockKind ?: entry.apiName, entry.requiredCapabilities, capabilities)
    val height = (190 + entry.parameterInfo.size * 44 + if (entry.notes.isEmpty()) 40 else 90)
        .coerceIn(330, 540).coerceAtMost((LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160)).dp
    var fullScreen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints((if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).imePadding(), contentAlignment = Alignment.Center) {
        Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.width(studioDialogWidth(maxWidth.value, 420).dp).height(minOf(height, maxHeight)),
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
                            else "加入使用现有可视化积木配置流程；变量、图像和条件会打开对应参数面板。", color = AutoScriptPalette.TextSecondary, fontSize = 11.sp)
                    }
                }
                if (!documentationOnly && entry.canInsert && missing.isNotEmpty()) Text("无法加入：项目未声明 ${missing.joinToString("、")}", color = AutoScriptPalette.Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                LibraryDivider()
                Row(Modifier.fillMaxWidth().height(42.dp)) {
                    LibraryControl("返回", if (documentationOnly) "返回函数文档" else "返回函数库", Modifier.weight(1f).fillMaxHeight(), onClick = onDismiss)
                    if (!documentationOnly && entry.canInsert) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                        LibraryControl("配置 / 加入", "加入积木", Modifier.weight(1f).fillMaxHeight(),
                            enabled = missing.isEmpty(), accent = true, onClick = onInsert)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun LibraryControl(label: String, description: String, modifier: Modifier, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val fontSize = if (label == "×") 23.sp else 13.sp
    Box(modifier.semantics { if (label == "×") contentDescription = description }
        .clickable(enabled = enabled, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
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
