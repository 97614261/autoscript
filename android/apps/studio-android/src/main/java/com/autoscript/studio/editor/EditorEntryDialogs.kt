package com.autoscript.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EntryBlue = Color(0xFF3D5AFE)
private val EntryAccent = Color(0xFF3A6EFF)
private val EntryBorder = Color(0xFFC7CBD1)
private val EntryMuted = Color(0xFF727B8B)
private val EntryPanel = Color(0xFFF8F9FB)

private data class PickerRequest(
    val title: String,
    val options: List<String>,
    val selected: String? = null,
    val onSelected: (String) -> Unit,
)

/** Editing and insertion reuse the same loop/jump/debug panels, with typed initial values. */
@Composable
internal fun ConfiguredEntryArgumentsDialog(
    contract: com.autoscript.studio.generated.BlockContract,
    arguments: com.google.gson.JsonObject,
    variables: List<ProjectVariable>, flows: List<ProjectFlow>, currentFlowId: String,
    knownVariables: List<String>, debugSettings: ProjectDebugSettings,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (com.google.gson.JsonObject) -> Unit,
    confirmLabel: String,
    knownLabels: List<String> = emptyList(),
): Boolean {
    val kind = contract.kind
    if (kind in setOf("control.repeat", "control.cycle", "control.loopmetric", "control.loopcheck", "task.sleep") ||
        (kind == "control.while" && arguments.get("always")?.asBoolean == true)) {
        val initial = remember(kind, arguments) { loopDraftFromArguments(kind, arguments) }
        VisualLoopDialog(variables, currentFlowId, flows, onDismiss, { hint ->
            loopInsertionFromHint(hint)?.takeIf { it.kind == kind }?.let { onConfirm(it.arguments) }
        }, onManageVariables, initial, kind, confirmLabel)
        return true
    }
    // Calls always use the real typed argument editor, even when their current argument map is empty.
    // A newly selected target may require parameters; the compact target-only form cannot supply them.
    val jump = kind in setOf("control.break", "control.continue", "control.label", "control.goto", "flow.return", "flow.status.return",
        "flow.argument.set", "flow.argument.get", "flow.return.set", "flow.return.get") &&
        (kind != "flow.return" || arguments.size() == 0)
    val debug = kind in setOf("task.prompt", "task.runprompt", "task.log", "task.message", "task.comment")
    if (!jump && !debug) return false
    val accept: (String) -> Unit = { hint ->
        val defaults = arguments.deepCopy()
        if (debug) defaults.remove("valueVariable")
        val updated = legacyDockBlockArguments(contract, hint, defaults)
        onConfirm(updated)
    }
    if (debug) {
        // The owner of the draft must own every close path, including system Back and outside taps.
        DebugPage(onDismiss, accept, knownVariables, true, debugSettings, {}, kind, arguments, confirmLabel, standalone = true)
        return true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).heightIn(max = 540.dp).fillMaxHeight(.68f),
            color = Color.White, shape = RoundedCornerShape(3.dp), shadowElevation = 9.dp) {
            CommonPage(onDismiss, accept, true, knownVariables, knownLabels, currentFlowId, flows,
                variables, onManageVariables, kind, arguments, confirmLabel)
        }
    }
    return true
}

@Composable
internal fun EditorEntryDialog(
    entry: EditorToolPanel,
    projectName: String,
    files: List<StudioProjectFile>,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit = {},
    onDeleteFiles: (List<StudioProjectFile>) -> Unit = {},
    onOpenImageTools: ((Long) -> Unit)? = null,
    onOpenImageToolMode: ((ImageToolMode, Long) -> Unit)? = null,
    onCreateImageBlock: ((String) -> Unit)? = null,
    onOpenImageLibrary: (() -> Unit)? = null,
    onTestRecognition: (() -> Unit)? = null,
    onRemoveCaptureOverlay: (() -> Unit)? = null,
    availableVariables: List<String> = emptyList(),
    availableLabels: List<String> = emptyList(),
    projectVariables: List<ProjectVariable> = emptyList(),
    currentFlowId: String = "",
    projectFlows: List<ProjectFlow> = emptyList(),
    onManageVariables: (() -> Unit)? = null,
    /** The visual editor exposes Flow variables through the generated `__vars` table. */
    visualMode: Boolean = false,
    debugSettings: ProjectDebugSettings = ProjectDebugSettings(),
    onSaveDebugSettings: (ProjectDebugSettings) -> Unit = {},
) {
    if (entry == EditorToolPanel.LOOP) {
        VisualLoopDialog(projectVariables, currentFlowId, projectFlows, onDismiss, onInsert, onManageVariables)
        return
    }
    if (entry in setOf(EditorToolPanel.DATA_BACKFILL, EditorToolPanel.VARIABLE_CHECK, EditorToolPanel.RUNTIME_VARIABLES)) {
        // Live inspectors require a project/runtime owner and are routed before this shared form.
        StudioPanelDialog(title = "需要项目上下文", onDismiss = onDismiss, showFooter = false) {
            Text("请从项目的可视化编辑器打开此功能。", fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        return
    }
    if (entry == EditorToolPanel.IMAGE && onCreateImageBlock != null) {
        VisualImageHomeDialog(onDismiss, onCreateImageBlock,
            onCapture = onOpenImageTools?.let { open -> { open(0L) } },
            onLibrary = onOpenImageLibrary,
            onTest = onTestRecognition)
        return
    }
    if (entry == EditorToolPanel.DEBUG) {
        DebugPage(onDismiss, onInsert, availableVariables, visualMode,
            debugSettings, onSaveDebugSettings, standalone = true)
        return
    }
    if (entry == EditorToolPanel.AI) {
        StudioPanelDialog("AI · 尚未开放", onDismiss, compactHeader = true) {
            Text("AI 功能已延期，当前不提供发送、附件、账号或服务配置；不会生成或插入模拟结果。",
                fontSize = 12.sp, color = EntryMuted, modifier = Modifier.padding(12.dp))
        }
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val panelModifier = when (entry) {
            EditorToolPanel.FILES -> Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f).widthIn(max = 520.dp)
            EditorToolPanel.JUDGMENT -> Modifier.width(132.dp).height(132.dp)
            EditorToolPanel.COMMON -> Modifier.fillMaxWidth(.96f).widthIn(max = 560.dp).height(390.dp)
            EditorToolPanel.IMAGE -> Modifier.fillMaxWidth(.96f).widthIn(max = 550.dp).height(310.dp)
            else -> Modifier.fillMaxWidth(.90f).widthIn(max = 520.dp).height(
                when (entry) {
                    // 工具页按单屏内容量固定，避免卡片之间留出大段无用空白。
                    EditorToolPanel.TOOLS -> 342.dp
                    else -> 390.dp
                },
            )
        }
        Surface(
            modifier = panelModifier,
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 8.dp,
        ) {
            when (entry) {
                EditorToolPanel.FILES -> FileManagerPage(projectName, files, onDismiss, onOpenFile, onDeleteFiles)
                EditorToolPanel.TOOLS -> ToolSettingsPage(
                    onDismiss,
                    onInsert,
                    onOpenImageTools,
                    onOpenImageLibrary,
                    onRemoveCaptureOverlay,
                    onTestRecognition,
                )
                EditorToolPanel.IMAGE -> ImageRecognitionPage(onDismiss, onInsert, files, onOpenImageTools)
                EditorToolPanel.JUDGMENT -> JudgmentPage(onDismiss, onInsert)
                EditorToolPanel.LOOP -> Unit // Shared adaptive dialog is handled above.
                EditorToolPanel.COMMON -> CommonPage(onDismiss, onInsert, visualMode, availableVariables,
                    availableLabels, currentFlowId, projectFlows, projectVariables, onManageVariables)
                else -> Unit
            }
        }
    }
}

private sealed interface FileRow {
    val key: String

    data class Up(override val key: String = "..") : FileRow
    data class Folder(val name: String, val path: String, val count: Int) : FileRow {
        override val key: String get() = "d:$path"
    }
    data class Entry(val file: StudioProjectFile) : FileRow {
        override val key: String get() = "f:${file.path}"
    }
}

/**
 * `service_tk_xt_wj_gl_layout.xml`：40dp 路径行 + 菜单、可隐藏的搜索行、常驻“已选择N项 / 全选”、
 * 45dp 的文件夹/文件/返回行，以及“取消”与多选图标行两套底栏。
 * 数据来自项目清单（[projectFileCatalog]），目录按路径推导，`.studio`、`generated` 不会出现。
 * 清单里的源文件、Lua 入口和 project.json 由各自的管理入口负责，这里只能删除图片/字库资源。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileManagerPage(
    projectName: String,
    files: List<StudioProjectFile>,
    onDismiss: () -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit,
    onDeleteFiles: (List<StudioProjectFile>) -> Unit,
) {
    var currentDir by remember(projectName) { mutableStateOf("") }
    var selectedKeys by remember(projectName) { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuVisible by remember { mutableStateOf(false) }
    var reverseSort by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val rows = remember(files, currentDir, query, reverseSort) {
        val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
        val inScope = files.filter { it.path.startsWith(prefix) }
        val folders = inScope
            .mapNotNull { file -> file.path.removePrefix(prefix).substringBefore('/', "").takeIf(String::isNotEmpty) }
            .distinct()
            .map { name -> FileRow.Folder(name, prefix + name, inScope.count { it.path.startsWith("$prefix$name/") }) }
        val entries = inScope.filter { !it.path.removePrefix(prefix).contains('/') }.map(FileRow::Entry)
        val needle = query.trim()
        val visibleFolders = folders
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val visibleEntries = entries
            .filter { needle.isEmpty() || it.file.path.substringAfterLast('/').contains(needle, ignoreCase = true) }
            .sortedBy { it.file.path.lowercase(Locale.ROOT) }
        buildList {
            if (currentDir.isNotEmpty()) add(FileRow.Up())
            addAll(if (reverseSort) visibleFolders.asReversed() else visibleFolders)
            addAll(if (reverseSort) visibleEntries.asReversed() else visibleEntries)
        }
    }
    val selectableKeys = rows.filterNot { it is FileRow.Up }.map(FileRow::key)
    val selectionMode = selectedKeys.isNotEmpty()
    val selectedPaths = selectedKeys.filter { it.startsWith("f:") }.map { it.removePrefix("f:") }.toSet()
    val selectedFiles = files.filter { it.path in selectedPaths }
    val selectedFolders = selectedKeys.count { it.startsWith("d:") }
    val allSelected = selectableKeys.isNotEmpty() && selectableKeys.all(selectedKeys::contains)
    val deletable = selectedFolders == 0 && selectedFiles.isNotEmpty() && selectedFiles.all {
        it.kind == StudioProjectFileKind.IMAGE || it.kind == StudioProjectFileKind.GLYPH_DICTIONARY
    }

    fun toggle(key: String) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    Column(Modifier.fillMaxSize()) {
        // line_tk_xt_wj_gl_layout_1：路径 + 菜单
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "/$projectName" + (if (currentDir.isEmpty()) "" else "/$currentDir"),
                color = EntryBlue,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                HeaderIconAction(R.drawable.editor_menu_24, "文件菜单") { menuVisible = true }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }, containerColor = Color.White) {
                    DropdownMenuItem(text = { Text("新建", fontSize = 13.sp) }, onClick = {
                        menuVisible = false
                        notice = "插件请点击标题进入插件列表新建；图片和字库请在项目设置中导入"
                    })
                    DropdownMenuItem(text = { Text("刷新", fontSize = 13.sp) }, onClick = { menuVisible = false; notice = "已刷新" })
                    DropdownMenuItem(text = { Text("搜索", fontSize = 13.sp) }, onClick = { menuVisible = false; searching = true })
                    DropdownMenuItem(text = { Text("排序", fontSize = 13.sp) }, onClick = { menuVisible = false; reverseSort = !reverseSort })
                }
            }
        }
        // line_tk_xt_wj_gl_search
        if (searching) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFF202839)),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (query.isEmpty()) Text("关键字搜索", color = EntryMuted, fontSize = 12.sp); inner() } },
                    modifier = Modifier.weight(1f).padding(end = 5.dp),
                )
                Text(
                    "取消",
                    color = EntryBlue,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxHeight().clickable { searching = false; query = "" }.padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
        }
        // line_tk_xt_wj_gl_layout_2：已选择N项 / 全选（常驻）
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选择${selectedKeys.size}项", color = EntryBlue, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("全选", fontSize = 14.sp, color = Color.Black)
            Checkbox(
                checked = allSelected,
                onCheckedChange = {
                    selectedKeys = if (allSelected) selectedKeys - selectableKeys.toSet() else (selectedKeys + selectableKeys).distinct()
                },
                enabled = selectableKeys.isNotEmpty(),
                colors = CheckboxDefaults.colors(checkedColor = EntryBlue, uncheckedColor = EntryMuted),
                modifier = Modifier.padding(end = 9.dp).size(32.dp),
            )
        }
        DividerLine()
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "无内容" else "没有匹配的文件", color = EntryMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(1.dp)) {
                items(rows, key = FileRow::key) { row ->
                    when (row) {
                        is FileRow.Up -> FileUpRow { currentDir = currentDir.substringBeforeLast('/', "") }
                        is FileRow.Folder -> FileFolderRow(
                            name = row.name,
                            count = row.count,
                            checked = row.key in selectedKeys,
                            onClick = { if (selectionMode) toggle(row.key) else currentDir = row.path },
                            onLongClick = { toggle(row.key) },
                            onCheck = { toggle(row.key) },
                        )
                        is FileRow.Entry -> FileEntryRow(
                            file = row.file,
                            timestamp = timestamp,
                            checked = row.key in selectedKeys,
                            onClick = { if (selectionMode) toggle(row.key) else onOpenFile(row.file) },
                            onLongClick = { toggle(row.key) },
                            onCheck = { toggle(row.key) },
                        )
                    }
                }
            }
        }
        notice?.let { Text(it, color = EntryBlue, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) }
        DividerLine()
        if (selectionMode) {
            // line_tk_xt_wj_gl_layout_4，七项顺序照抄：移动 · 粘贴 · 重命名 · 删除 · 取消 · 复制 · 移动。
            // 参考里首尾两个“移动”是同一图标（yidong2 / yidong），分别对应剪切式移动和移动到目录。
            // 清单文件由 Store 管理，移动/粘贴/重命名/复制在这里没有安全语义，明确禁用；只有资源可删除。
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                FileIconAction(R.drawable.editor_file_move_24, "移动", false) {}
                FileIconAction(R.drawable.editor_file_paste_24, "粘贴", false) {}
                FileIconAction(R.drawable.editor_edit_24, "重命名", false) {}
                FileIconAction(R.drawable.editor_delete_24, "删除", deletable) { confirmDelete = true }
                FileIconAction(R.drawable.editor_close_24, "取消", true) { selectedKeys = emptyList() }
                FileIconAction(R.drawable.editor_file_copy_24, "复制", false) {}
                FileIconAction(R.drawable.editor_file_move_24, "移动", false) {}
            }
        } else {
            FooterButtons(listOf("取消" to onDismiss), fontSize = 13)
        }
    }

    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.fillMaxWidth(.82f).widthIn(max = 420.dp),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text("是否删除所选 ${selectedFiles.size} 项文件?", color = Color(0xFF202839), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 25.dp))
                    Text("仍被积木或脚本引用的资源会被拒绝删除。", color = EntryMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 25.dp, end = 25.dp, top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("取消", color = Color(0xFF3F51B5), fontSize = 14.sp, modifier = Modifier.clickable { confirmDelete = false }.padding(horizontal = 15.dp, vertical = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "删除",
                            color = Color(0xFF3F51B5),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 15.dp).clickable {
                                confirmDelete = false
                                val targets = selectedFiles
                                selectedKeys = emptyList()
                                onDeleteFiles(targets)
                            }.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** item_tree_list_file_3 */
@Composable
private fun FileUpRow(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(45.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.editor_folder_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.padding(horizontal = 5.dp).size(35.dp))
        Text("..", fontSize = 14.sp, color = Color.Black)
    }
}

/** item_tree_list_file_1 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileFolderRow(name: String, count: Int, checked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onCheck: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(45.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.editor_folder_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.padding(horizontal = 5.dp).size(35.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(name, fontSize = 14.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 项 · 文件夹", color = EntryMuted, fontSize = 8.sp)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheck() },
            colors = CheckboxDefaults.colors(checkedColor = EntryBlue, uncheckedColor = EntryMuted),
            modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(32.dp),
        )
    }
}

/** item_tree_list_file_2 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    file: StudioProjectFile,
    timestamp: SimpleDateFormat,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheck: () -> Unit,
) {
    val icon = when (file.kind) {
        StudioProjectFileKind.IMAGE -> R.drawable.editor_recognition_preview_24
        else -> R.drawable.editor_code_file_24
    }
    Row(
        Modifier.fillMaxWidth().height(45.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 5.dp, top = 5.dp, bottom = 5.dp).size(35.dp).padding(1.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 3.dp, top = 4.dp, bottom = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(file.path.substringAfterLast('/'), fontSize = 14.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    file.lastModified?.let { timestamp.format(Date(it)) } ?: file.kind.label,
                    color = EntryMuted,
                    fontSize = 8.sp,
                )
                Text(formatEntrySize(file.sizeBytes), color = EntryMuted, fontSize = 8.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheck() },
            colors = CheckboxDefaults.colors(checkedColor = EntryBlue, uncheckedColor = EntryMuted),
            modifier = Modifier.padding(start = 2.dp, end = 8.dp).size(32.dp),
        )
    }
}

@Composable
private fun FileIconAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = if (enabled) EntryBlue else EntryMuted, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 8.sp, color = if (enabled) Color.Black else EntryMuted)
    }
}

private fun formatEntrySize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
}

@Composable
private fun HeaderIconAction(icon: Int, description: String, onClick: () -> Unit) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = Color.Unspecified,
        modifier = Modifier.size(36.dp).clickable(onClick = onClick).padding(6.dp),
    )
}


private const val LEGACY_ENTRY_COMMAND_PREFIX = "--@autoscript-editor:"

@Composable
private fun ToolSettingsPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenImageTools: ((Long) -> Unit)?,
    onOpenImageLibrary: (() -> Unit)?,
    onRemoveCaptureOverlay: (() -> Unit)?,
    onTestRecognition: (() -> Unit)?,
) {
    var captureDelay by remember { mutableStateOf("0秒") }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("工具", color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(31.dp).padding(horizontal = 14.dp, vertical = 6.dp))
        DividerLine()
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 3.dp, end = 8.dp, bottom = 3.dp)) {
            ToolPanel("截屏工具") {
                ToolActionRow(R.drawable.editor_capture_camera_24, "截屏延迟", trailing = captureDelay) {
                    picker = PickerRequest("截屏延迟", listOf("0秒", "1秒", "2秒", "3秒", "5秒"), captureDelay) { captureDelay = it }
                }
            }
            Spacer(Modifier.height(2.dp))
            ToolPanel("识别工具") {
                ToolActionRow(R.drawable.editor_recognition_preview_24, if (onTestRecognition != null) "测试识别 · 模板找图" else "测试识别（未接入）", enabled = onTestRecognition != null) {
                    onTestRecognition?.invoke()
                }
            }
            Spacer(Modifier.height(2.dp))
            // 「标注截屏」「图像处理」进图像工具悬浮窗（取图/取色/多点/坐标）；
            // 「打开标注库」进项目图片列表页，两者职责不同，不再指向同一个回调。
            // 宿主没提供对应入口时退回选项预览。
            ToolPanel("图像工具") {
                ToolActionRow(R.drawable.editor_recognition_preview_24, "标注截屏") {
                    if (onOpenImageTools != null) onOpenImageTools(captureDelay.filter(Char::isDigit).toLongOrNull()?.times(1_000) ?: 0L) else picker = PickerRequest("标注截屏", listOf("立即截屏", "延迟截屏", "导入图片")) {}
                }
                DividerLine()
                ToolActionRow(R.drawable.editor_annotation_24, if (onOpenImageLibrary != null) "打开标注库" else "打开标注库（未接入）", enabled = onOpenImageLibrary != null) {
                    onOpenImageLibrary?.invoke()
                }
                DividerLine()
                ToolActionRow(R.drawable.editor_image_processing_24, "图像处理") {
                    if (onOpenImageTools != null) onOpenImageTools(captureDelay.filter(Char::isDigit).toLongOrNull()?.times(1_000) ?: 0L) else picker = PickerRequest("图像处理", listOf("裁剪", "缩放", "灰度", "二值化", "颜色过滤")) {}
                }
            }
        }
        // 参考的“屏幕截图”是打开截屏工具；本项目对应的真实 API 是 Screen.capture()。
        ToolSettingsFooter(
            dismiss = { onRemoveCaptureOverlay?.invoke() ?: onDismiss() },
            onCapture = {
                val delayMillis = captureDelay.filter(Char::isDigit).toLongOrNull()?.times(1_000) ?: 0L
                if (onOpenImageTools != null) onOpenImageTools(delayMillis) else {
                    onInsert("local captureId = Screen.capture()\n")
                    onDismiss()
                }
            },
        )
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

@Composable
private fun ToolPanel(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp))) {
        Text(title, color = EntryAccent, fontSize = 9.sp, modifier = Modifier.fillMaxWidth().height(20.dp).padding(start = 10.dp, top = 4.dp))
        content()
    }
}

@Composable
private fun ToolActionRow(
    icon: Int,
    label: String,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val rowColor = if (enabled) AutoScriptPalette.TextPrimary else EntryMuted
    val iconColor = if (enabled) EntryAccent else EntryBorder
    Row(Modifier.fillMaxWidth().height(31.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(19.dp).background(iconColor, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(14.dp))
        }
        Text(label, color = rowColor, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            DenseSpinner(trailing, Modifier.width(92.dp), arrow = false, onClick = onClick)
        } else {
            Text(if (enabled) "›" else "—", color = EntryMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ToolSettingsFooter(dismiss: () -> Unit, onCapture: () -> Unit) {
    DividerLine()
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        CenteredFooterAction("移除悬浮球", AutoScriptPalette.Danger, dismiss, Modifier.weight(1f))
        DividerVertical()
        CenteredFooterAction("屏幕截图", EntryAccent, onCapture, Modifier.weight(1f), bold = true)
    }
}

@Composable
private fun ImageRecognitionPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    files: List<StudioProjectFile>,
    onOpenImageTools: ((Long) -> Unit)?,
) {
    val pages = listOf("区域找图", "多点找色", "字库识字", "ONNX OCR")
    var page by remember { mutableStateOf(0) }
    var recognitionFrequency by remember { mutableStateOf("识别频率[1]") }
    var captureDelaySeconds by remember { mutableStateOf(0) }
    val imageFiles = files.filter { it.kind == StudioProjectFileKind.IMAGE && it.sizeBytes > 0 }
    var template by remember(imageFiles) {
        mutableStateOf(imageFiles.maxByOrNull { it.lastModified ?: 0L }?.path ?: "未选择")
    }
    var imageMode by remember { mutableStateOf("普通找图") }
    var colorMode by remember { mutableStateOf("多点找色") }
    var filterMode by remember { mutableStateOf("未加载") }
    var onnxModel by remember { mutableStateOf("未开放") }
    var ocrLanguage by remember { mutableStateOf("未开放") }
    var similarity by remember { mutableStateOf("0.8") }
    var direction by remember { mutableStateOf("左上到右下") }
    var successAction by remember { mutableStateOf("不执行") }
    var returnType by remember { mutableStateOf("无") }
    var range by remember { mutableStateOf("全屏  0,0,-1,-1") }
    var colorData by remember { mutableStateOf("请选择多点数据") }
    var glyphLibrary by remember { mutableStateOf("未选择") }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DenseSpinner(
                value = recognitionFrequency,
                modifier = Modifier.weight(1f),
                arrow = false,
                onClick = {
                    picker = PickerRequest(
                        "识别频率",
                        (1..12).map { "循环${it}次，识别1次" },
                        "循环${recognitionFrequency.substringAfter('[').substringBefore(']')}次，识别1次",
                    ) { selected ->
                        recognitionFrequency = "识别频率[${selected.substringAfter("循环").substringBefore("次")}]"
                    }
                },
                centered = true,
            )
            Text("图像识别", color = EntryBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.72f))
            DenseSpinner(
                value = pages[page],
                modifier = Modifier.weight(1f),
                onClick = {
                picker = PickerRequest("识别页面", pages, pages[page]) { page = pages.indexOf(it).coerceAtLeast(0) }
                },
            )
        }
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 6.dp, top = 2.dp, end = 6.dp, bottom = 4.dp)) {
            when (page) {
                0 -> {
                    DenseImageSourceBlock(
                        firstLabel = "模版选择:", firstValue = template, secondLabel = "识别方式:", secondValue = imageMode, preview = "预览",
                        onFirst = {
                            picker = PickerRequest("选择模板图片", imageFiles.map { it.path } + "从截屏中创建", template) {
                                if (it == "从截屏中创建") onOpenImageTools?.invoke(captureDelaySeconds * 1_000L) else template = it
                            }
                        },
                        onSecond = { picker = PickerRequest("识别方式", listOf("普通找图", "透明图", "灰度图", "特征匹配"), imageMode) { imageMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量", "跟随上次结果"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseSuccessRow(successAction, returnType,
                        onAction = { picker = PickerRequest("识别成功", successActions, successAction) { successAction = it } },
                        onReturnType = { picker = PickerRequest("返回值", imageReturnTypes, returnType) { returnType = it } },
                    )
                    if (successAction == "找到后点击") DenseSingleVariableRow("随机偏移:", "5")
                    when (returnType) {
                        "图像" -> DenseSingleVariableRow("图像变量:", "选择图像变量")
                        "布尔值" -> DenseSingleVariableRow("结果变量:", "选择布尔变量")
                        else -> DenseVariableRow("坐标变量:", "选择X变量", "选择Y变量")
                    }
                }
                1 -> {
                    DenseImageSourceBlock(
                        firstLabel = "多点数据:", firstValue = colorData, secondLabel = "识别方式:", secondValue = colorMode, preview = "预览",
                        onFirst = { picker = PickerRequest("颜色数据", listOf("请选择多点数据", "从屏幕取色", "编辑多点颜色", "从文件导入"), colorData) { colorData = it } },
                        onSecond = { picker = PickerRequest("识别方式", listOf("单点找色", "多点找色", "多点比色", "区域颜色统计"), colorMode) { colorMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseSuccessRow(successAction, returnType,
                        onAction = { picker = PickerRequest("识别成功", successActions, successAction) { successAction = it } },
                        onReturnType = { picker = PickerRequest("返回值", imageReturnTypes, returnType) { returnType = it } },
                    )
                    if (successAction == "找到后点击") DenseSingleVariableRow("随机偏移:", "5")
                    when (returnType) {
                        "图像" -> DenseSingleVariableRow("图像变量:", "选择图像变量")
                        "布尔值" -> DenseSingleVariableRow("结果变量:", "选择布尔变量")
                        else -> DenseVariableRow("坐标变量:", "选择X变量", "选择Y变量")
                    }
                }
                2 -> {
                    DenseImageSourceBlock(
                        firstLabel = "字库选择:", firstValue = if (glyphLibrary == "未选择") "请选择字库" else glyphLibrary,
                        secondLabel = "滤色数组:", secondValue = filterMode, preview = "首字",
                        onFirst = { picker = PickerRequest("选择字库", listOf("default.txt", "数字字库.txt", "新建字库", "导入字库"), glyphLibrary) { glyphLibrary = it } },
                        onSecond = { picker = PickerRequest("滤色数组", listOf("未加载", "不处理", "二值化", "指定颜色", "灰度", "反色"), filterMode) { filterMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseTextReturnRow()
                }
                else -> {
                    Text(
                        "ONNX 高级 OCR 属于后续阶段（基础字库 OCR 请用“字库识字”页），下方选项仅为版式预留，不会插入任何调用。",
                        color = AutoScriptPalette.Danger,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                    DenseImageSourceBlock(
                        firstLabel = "模型选择:", firstValue = onnxModel,
                        secondLabel = "识别语言:", secondValue = ocrLanguage, preview = "OCR",
                        onFirst = { picker = PickerRequest("ONNX 模型", listOf("未开放"), onnxModel) { onnxModel = it } },
                        onSecond = { picker = PickerRequest("识别语言", listOf("未开放"), ocrLanguage) { ocrLanguage = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseTextReturnRow()
                }
            }
        }
        // 片段只用契约里真实存在的 Screen.* / Ocr.* 签名；相似度按 API 口径换算成千分位。
        // ONNX OCR 是后续阶段，没有对应 API，这里不生成任何调用。
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("截图延迟：${captureDelaySeconds}秒  ▾", color = EntryAccent, fontSize = 11.sp,
                modifier = Modifier.clickable {
                    picker = PickerRequest("截图延迟", listOf("0秒", "1秒", "3秒", "5秒"), "${captureDelaySeconds}秒") {
                        captureDelaySeconds = it.removeSuffix("秒").toInt()
                    }
                }.padding(horizontal = 6.dp))
        }
        FooterButtons(listOf("取消" to onDismiss, "截图取图" to {
            onOpenImageTools?.invoke(captureDelaySeconds * 1_000L)
        }, "加入" to {
            val permille = ((similarity.toDoubleOrNull() ?: 0.8) * 1000).toInt().coerceIn(1, 1000)
            val code = when (page) {
                0 -> "local template = Screen.loadImage(\"assets/images/${legacyLuaEscape(template.takeUnless { it == "未选择" } ?: "template.png")}\")\n" +
                    "local hit = Screen.findImage(frame, template, 16, $permille, 0, 0, -1, -1)\n"
                1 -> "local hit = Screen.findMultiColor(frame, \"${legacyLuaEscape(colorData.substringBefore('-').trim())}\", 16, samples, 0, 0, -1, -1)\n"
                2 -> "local dictionary = Ocr.loadDictionary(\"dictionaries/${legacyLuaEscape(glyphLibrary.takeUnless { it == "未选择" } ?: "main.asglyph")}\")\n" +
                    "local text = Ocr.glyph(frame, dictionary, \"FFFFFF\", 16, $permille, 0, 0, -1, -1, 2)\n"
                else -> "-- ONNX OCR 属于后续阶段，当前契约没有对应 API\n"
            }
            onInsert(code); onDismiss()
        }), fontSize = 10)
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

private val imageDirections = listOf("左上到右下", "右上到左下", "左下到右上", "右下到左上", "中心向四周")
private val similarities = listOf("1.0", "0.98", "0.95", "0.9", "0.85", "0.8")
private val successActions = listOf("不执行", "找到后点击", "找到后停止脚本", "找到后继续")
private val imageReturnTypes = listOf("无", "坐标", "图像", "布尔值")

@Composable
private fun DenseImageSourceBlock(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
    preview: String,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DenseLabeledRow(firstLabel, firstValue, onFirst)
            Spacer(Modifier.height(6.dp))
            DenseLabeledRow(secondLabel, secondValue, onSecond, arrow = true)
        }
        Box(
            Modifier.padding(start = 8.dp).size(70.dp)
                .background(Color(0xFFF4F6F9), RoundedCornerShape(2.dp))
                .border(1.dp, EntryBorder, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(preview, fontSize = 10.sp, color = EntryMuted)
        }
    }
}

@Composable
private fun DenseLabeledRow(label: String, value: String, onClick: () -> Unit, arrow: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(value, Modifier.weight(1f), arrow, onClick)
    }
}

@Composable
private fun DenseRangeRow(range: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("选取范围:")
        val values = if (range.startsWith("全屏")) listOf("x:0", "y:0", "w:0", "h:0") else listOf("x:--", "y:--", "w:--", "h:--")
        values.forEachIndexed { index, value ->
            DenseSpinner(
                value,
                Modifier.weight(1f).padding(start = if (index == 0) 0.dp else 2.dp, end = if (index == values.lastIndex) 0.dp else 2.dp),
                arrow = false,
                onClick = onClick,
                centered = true,
            )
        }
    }
}

@Composable
private fun DenseDirectionRow(
    direction: String,
    similarity: String,
    onDirection: () -> Unit,
    onSimilarity: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("查找方向:")
        DenseSpinner(direction, Modifier.weight(1f), arrow = true, onClick = onDirection)
        DenseLabel("相似度:", Modifier.padding(start = 8.dp))
        DenseSpinner(similarity, Modifier.weight(.55f), arrow = true, onClick = onSimilarity, centered = true)
    }
}

@Composable
private fun DenseSuccessRow(action: String, returnType: String, onAction: () -> Unit, onReturnType: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("识别成功:")
        DenseSpinner(action, Modifier.weight(1.3f), arrow = true, onClick = onAction)
        DenseLabel("返回值:", Modifier.padding(start = 8.dp))
        DenseSpinner(returnType, Modifier.weight(1f), arrow = true, onClick = onReturnType)
    }
}

@Composable
private fun DenseSingleVariableRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(value, Modifier.weight(1f), arrow = false, onClick = {})
    }
}

@Composable
private fun DenseTextReturnRow() {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("字符变量:")
        DenseSpinner("选择字符变量", Modifier.weight(1.2f), arrow = false, onClick = {})
        DenseLabel("返回值:", Modifier.padding(start = 8.dp))
        DenseSpinner("文本", Modifier.weight(1f), arrow = true, onClick = {})
    }
}

@Composable
private fun DenseVariableRow(label: String, first: String, second: String) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(first, Modifier.weight(1f), onClick = {})
        DenseSpinner(second, Modifier.weight(1f).padding(start = 6.dp), onClick = {})
    }
}

@Composable
private fun DenseLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF20242C), fontSize = 10.sp, modifier = modifier.padding(end = 5.dp))
}

@Composable
private fun DenseSpinner(
    value: String,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
    onClick: () -> Unit,
    centered: Boolean = false,
) {
    Box(
        modifier = modifier.height(30.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
            .border(1.dp, EntryBorder, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = if (arrow) "$value  ▾" else value,
            color = Color(0xFF20242C),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun JudgmentPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        listOf(
            // The host resolves the latter two actions against the selected `control.if`.
            // This prevents detached Lua fragments from entering a visual Flow.
            "如果" to "${FunctionCatalog.BLOCK_HINT_PREFIX}control.if\n",
            "否则如果" to "--@autoscript-control:elseIf\n",
            "否则" to "--@autoscript-control:else\n",
        ).forEach { (label, snippet) ->
            Text(
                label,
                color = Color(0xFF171B25),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().weight(1f).clickable {
                    onInsert(snippet)
                    onDismiss()
                }.padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}


private fun legacyLuaEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")


private fun variablePicker(
    variables: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
): PickerRequest = PickerRequest("选择变量", variables, selected, onSelected)

@Composable
private fun CommonPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    visualMode: Boolean,
    availableVariables: List<String>,
    availableLabels: List<String>,
    currentFlowId: String,
    projectFlows: List<ProjectFlow>,
    projectVariables: List<ProjectVariable>,
    onManageVariables: (() -> Unit)?,
    initialKind: String? = null,
    initialArguments: com.google.gson.JsonObject? = null,
    confirmLabel: String = "加入",
) {
    val tabs = if (visualMode) listOf("流程操作", "调用插件", "设置调用参数", "读取调用参数", "设置返回参数", "读取返回参数")
        else listOf("流程操作")
    val targets = projectFlows.filter { it.flowId != currentFlowId }
    fun text(key: String, fallback: String = "") = initialArguments?.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
    val initialTab = when (initialKind) { "flow.call", "flow.call.branch" -> 1; "flow.argument.set" -> 2; "flow.argument.get" -> 3; "flow.return.set" -> 4; "flow.return.get" -> 5; else -> 0 }
    val initialAction = when (initialKind) { "control.label" -> 1; "flow.return" -> 2; "control.goto" -> 3; "task.sleep" -> 4; "flow.status.return" -> if (text("success") == "false") 6 else 5; "control.continue" -> 7; else -> 0 }
    var selected by remember { mutableStateOf(initialTab) }
    var action by remember { mutableStateOf(initialAction) }
    var callBranches by remember { mutableStateOf(initialKind == "flow.call.branch") }
    var waitValue by remember { mutableStateOf(text("millisecondsVariable", text("milliseconds", "500"))) }
    var waitIsVariable by remember { mutableStateOf(initialArguments?.has("millisecondsVariable") == true) }
    val autoLabel = remember(availableLabels, visualMode) {
        if (visualMode) JumpPanelCode.nextLabel(availableLabels) else "mark_${System.currentTimeMillis()}"
    }
    var labelName by remember(autoLabel) { mutableStateOf(text("name", autoLabel)) }
    var customLabel by remember { mutableStateOf(initialKind == "control.label") }
    var jumpError by remember { mutableStateOf<String?>(null) }
    var parameterIndex by remember { mutableStateOf(text("index", "1")) }
    var parameterValue by remember { mutableStateOf(initialArguments?.get("value")?.toString() ?: "0") }
    var parameterVariable by remember { mutableStateOf(text("valueVariable", text("targetVariable"))) }
    var useParameterVariable by remember { mutableStateOf(initialArguments?.has("valueVariable") == true) }
    var targetFlowId by remember(currentFlowId, projectFlows) { mutableStateOf(text("targetFlowId").ifBlank { targets.firstOrNull()?.flowId }) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    val resolvedVariables = calculationVariables(projectVariables, currentFlowId, projectFlows).associateBy { it.name }
    fun variableType(name: String): ProjectVariableType? = resolvedVariables[name]?.type
    val scalarVariables = availableVariables.filter { variableType(it) != ProjectVariableType.IMAGE }
    val numericVariables = availableVariables.filter {
        variableType(it) == ProjectVariableType.INTEGER
    }
    fun pickVariable(options: List<String>, update: (String) -> Unit) {
        if (options.isEmpty()) jumpError = "没有符合类型的变量，请先维护变量"
        else picker = variablePicker(options, parameterVariable) { update(it); jumpError = null }
    }
    SplitEntryPage("跳转 · 插件调用", tabs, selected, { if (initialKind == null || it == initialTab) { selected = it; jumpError = null } else jumpError = "修改时请保留原积木类型" }, onDismiss, {
        val index = parameterIndex.toIntOrNull()
        val snippet = when (selected) {
            0 -> when (action) {
                0 -> "break\n"
                1 -> {
                    val name = if (customLabel) labelName else autoLabel
                    when {
                        !JumpPanelCode.validName(name) -> jumpError = "标记名须以英文字母或下划线开头，最多 64 字符"
                        visualMode && name in availableLabels && name != text("name") -> jumpError = "当前插件已有同名标记"
                    }
                    "::$name::\n"
                }
                2 -> "return\n"
                5 -> JumpPanelCode.statusSnippet(true)
                6 -> JumpPanelCode.statusSnippet(false)
                7 -> "${FunctionCatalog.BLOCK_HINT_PREFIX}control.continue\n{}\n"
                3 -> {
                    if (!JumpPanelCode.validName(labelName)) jumpError = "请输入有效的标记名"
                    else if (visualMode && labelName !in availableLabels) jumpError = "请先在当前插件放置该标记"
                    "goto $labelName\n"
                }
                else -> {
                    if (waitIsVariable) {
                        if (!JumpPanelCode.validName(waitValue)) jumpError = "请输入有效的毫秒变量名"
                        else if (variableType(waitValue) != ProjectVariableType.INTEGER)
                            jumpError = "等待毫秒请选择当前插件可用的整数变量"
                        "${FunctionCatalog.BLOCK_HINT_PREFIX}task.sleep\nvariable:$waitValue"
                    } else {
                        if (waitValue.toLongOrNull()?.takeIf { it >= 0 } == null) jumpError = "等待时间须为非负整数毫秒"
                        "Task.sleep($waitValue)\n"
                    }
                }
            }
            1 -> JumpPanelCode.callSnippet(targetFlowId?.takeIf { id -> targets.any { it.flowId == id } }.orEmpty(), callBranches).also {
                if (it == null) jumpError = "请先创建并选择目标插件"
            }
            2, 4 -> {
                if (index == null || index !in 1..99) jumpError = "参数序号须为 1～99"
                else if (useParameterVariable && !JumpPanelCode.validName(parameterVariable)) jumpError = "请输入有效的来源变量名"
                else if (useParameterVariable && variableType(parameterVariable) == ProjectVariableType.IMAGE)
                    jumpError = "调用和返回参数不能使用图像变量"
                else if (!useParameterVariable && JumpPanelCode.fixedValue(parameterValue) == null) {
                    jumpError = "固定值须为非空标量；数字样式的文字请加引号"
                }
                if (jumpError == null) JumpPanelCode.setSnippet(
                    if (selected == 2) "flow.argument.set" else "flow.return.set",
                    requireNotNull(index), parameterValue, parameterVariable.takeIf { useParameterVariable },
                ) else null
            }
            else -> {
                if (index == null || index !in 1..99) jumpError = "参数序号须为 1～99"
                else if (!JumpPanelCode.validName(parameterVariable)) jumpError = "请输入有效的接收变量名"
                else if (variableType(parameterVariable) == ProjectVariableType.IMAGE)
                    jumpError = "调用和返回参数不能写入图像变量"
                if (jumpError == null) JumpPanelCode.getSnippet(
                    if (selected == 3) "flow.argument.get" else "flow.return.get",
                    requireNotNull(index), parameterVariable,
                ) else null
            }
        }
        if (jumpError == null && snippet != null) { onInsert(snippet); onDismiss() }
    }, headerHint = if (visualMode) "当前插件内操作" else null, confirmLabel = confirmLabel) {
        when (selected) {
            0 -> {
                DenseFlowActions(action, visualMode) {
                    if (initialKind != null && it != initialAction) { jumpError = "修改时请保留原积木类型"; return@DenseFlowActions }
                    action = it
                    jumpError = null
                    if (it == 3) labelName = availableLabels.firstOrNull().orEmpty()
                    else if (it == 1 && !customLabel) labelName = autoLabel
                }
                when (action) {
                    0 -> DenseNotice("仅能加入循环体；执行后跳出最近一层循环。", "跳出循环")
                    1 -> {
                        SegmentedChoice("自动命名", "自定义", customLabel) {
                            customLabel = it; jumpError = null
                            if (!it) labelName = autoLabel
                        }
                        if (customLabel) DenseTypedValue("标记", labelName, { labelName = it; jumpError = null }, "")
                        else DenseNotice("将创建 $autoLabel；只在当前插件内有效。")
                    }
                    2 -> DenseNotice("结束当前插件，向调用者返回；此前设置的返回参数会一起传回。", "返回上层")
                    5, 6 -> DenseNotice("结束当前插件，独立返回${if (action == 5) "成功" else "失败"}状态；已设置的返回参数不变。异常、取消不当作失败。", "返回运行状态")
                    7 -> DenseNotice("跳过最近一层循环的剩余积木，开始下一轮；停止和让步仍然生效。", "结束本次循环")
                    3 -> {
                        DenseTypedValue("标记", labelName, { labelName = it; jumpError = null },
                            if (visualMode) "选择标记" else "") {
                            if (availableLabels.isEmpty()) jumpError = "当前插件没有标记，请先放置标记"
                            else picker = PickerRequest("选择当前插件标记", availableLabels, labelName) {
                                labelName = it; jumpError = null
                            }
                        }
                        DenseNotice("跳到当前插件根层级的标记；不可跨插件或跳入循环体。")
                    }
                    else -> {
                        SegmentedChoice("固定毫秒", "毫秒变量", waitIsVariable) {
                            waitIsVariable = it; waitValue = if (it) "" else "500"; jumpError = null
                        }
                        DenseTypedValue("毫秒", waitValue, { waitValue = it; jumpError = null },
                            if (waitIsVariable) "选择变量" else "") {
                            if (numericVariables.isEmpty()) jumpError = "没有数值变量，请先维护变量"
                            else picker = variablePicker(numericVariables, waitValue) { waitValue = it; jumpError = null }
                        }
                        if (waitIsVariable && visualMode && onManageVariables != null) {
                            Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                                modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 6.dp))
                        }
                    }
                }
            }
            1 -> {
                SegmentedChoice("仅调用", "成功 / 失败分支", callBranches) {
                    if (initialKind == null) callBranches = it
                    else if (it != callBranches) jumpError = "修改时保留原子区；请新建分支调用积木"
                }
                DenseNotice("先设置调用参数，再加入调用；调用后可读取返回参数。加入调用积木后仍可编辑参数。", "调用顺序")
                val selectedTarget = targets.firstOrNull { it.flowId == targetFlowId }
                DenseLabeledRow("目标插件", selectedTarget?.displayName() ?: "未创建", {
                    if (targets.isEmpty()) jumpError = "请先创建另一个插件"
                    else {
                        val labels = targets.mapIndexed { index, flow -> "${index + 1}. ${flow.displayName()} (${flow.flowId.take(8)})" }
                        picker = PickerRequest("选择目标插件", labels,
                            labels.getOrNull(targets.indexOfFirst { it.flowId == targetFlowId })) { choice ->
                            targetFlowId = targets[labels.indexOf(choice)].flowId; jumpError = null
                        }
                    }
                }, arrow = true)
                if (targets.isEmpty()) DenseNotice("当前只有一个插件；请先在标题的插件管理中创建目标插件。")
            }
            2, 4 -> {
                Text(if (selected == 2) "设置下一次调用的参数" else "设置当前插件的返回参数",
                    color = EntryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                DenseTypedValue("序号", parameterIndex, { parameterIndex = it; jumpError = null }, "")
                SegmentedChoice("固定值", "变量", useParameterVariable) { useParameterVariable = it; jumpError = null }
                if (useParameterVariable) DenseTypedValue("来源", parameterVariable, { parameterVariable = it; jumpError = null }, "选择变量") {
                    pickVariable(scalarVariables) { parameterVariable = it }
                } else {
                    DenseTypedValue("数值", parameterValue, { parameterValue = it; jumpError = null }, "")
                    DenseNotice("数字、true/false 自动识别；普通文字直接输入。数字样式的文字请用双引号包住。")
                }
                if (useParameterVariable && onManageVariables != null) {
                    Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                        modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 6.dp))
                }
            }
            else -> {
                Text(if (selected == 3) "读取当前插件收到的调用参数" else "读取上一次插件调用的返回参数",
                    color = EntryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                DenseTypedValue("序号", parameterIndex, { parameterIndex = it; jumpError = null }, "")
                DenseTypedValue("接收", parameterVariable, { parameterVariable = it; jumpError = null }, "选择变量") {
                    pickVariable(scalarVariables) { parameterVariable = it }
                }
                if (onManageVariables != null) Text("维护变量", color = EntryAccent, fontSize = 11.sp,
                    modifier = Modifier.clickable { onDismiss(); onManageVariables() }.padding(vertical = 8.dp))
            }
        }
        jumpError?.let { Text(it, color = Color(0xFFD84949), fontSize = 11.sp) }
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

@Composable
private fun DenseFlowActions(selected: Int, visualMode: Boolean, onSelected: (Int) -> Unit) {
    val actions = listOf("跳出循环", "放置标记", "返回上层", "跳转标记", "等待时间") + if (visualMode) listOf("返回成功", "返回失败", "结束本次循环") else emptyList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        actions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rowItems.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * 2 + columnIndex
                    Box(Modifier.weight(1f).height(34.dp)
                        .background(if (index == selected) Color(0xFFEAF1FF) else Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, if (index == selected) EntryAccent else EntryBorder, RoundedCornerShape(4.dp))
                        .clickable { onSelected(index) }, contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 11.sp,
                            color = if (index == selected) EntryAccent else Color(0xFF202839),
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                            style = CenteredDialogButtonTextStyle)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DenseTypedValue(
    type: String,
    value: String,
    onValue: (String) -> Unit,
    action: String,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                type,
                color = EntryAccent,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = CenteredDialogButtonTextStyle,
            )
        }
        DividerVertical()
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF20242C)), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (action.isNotEmpty()) {
            DividerVertical()
            Box(
                Modifier.width(76.dp).fillMaxHeight().clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    action,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    textAlign = TextAlign.Center,
                    style = CenteredDialogButtonTextStyle,
                )
            }
        }
    }
}

@Composable
private fun DenseNotice(text: String, title: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        title?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Text(text, color = EntryMuted, fontSize = 10.sp, modifier = Modifier.padding(top = if (title == null) 0.dp else 4.dp))
    }
}

/** Shared debug panel: insert a typed output block or edit settings that really affect execution. */
@Composable
private fun DebugPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    availableVariables: List<String>,
    visualMode: Boolean,
    initialSettings: ProjectDebugSettings,
    onSaveSettings: (ProjectDebugSettings) -> Unit,
    initialKind: String? = null,
    initialArguments: com.google.gson.JsonObject? = null,
    confirmLabel: String = "加入",
    standalone: Boolean = false,
) {
    val pages = if (initialKind != null) listOf(0) else if (visualMode) listOf(0, 1, 2) else listOf(0, 2)
    val tabs = pages.map { when (it) { 1 -> "对话框"; 2 -> "弹窗样式"; else -> "运行提示" } }
    var selected by remember { mutableStateOf(0) }
    val selectedPage = pages[selected]
    var fullScreen by remember { mutableStateOf(false) }
    val initialOutputMode = when (initialKind) { "task.prompt" -> DebugOutputCode.TOAST; "task.log" -> DebugOutputCode.LOG; "task.comment" -> DebugOutputCode.COMMENT; "task.message" -> DebugOutputCode.expressionOutputMode(initialArguments); else -> DebugOutputCode.RUN_PROMPT }
    var outputMode by remember { mutableStateOf(initialOutputMode) }
    var outputText by remember { mutableStateOf(initialArguments?.get(if (initialKind == "task.message") "expression" else "message")?.asString.orEmpty()) }
    var expressionMode by remember { mutableStateOf(initialKind == "task.message") }
    var outputVariable by remember { mutableStateOf(initialArguments?.get("valueVariable")?.asString) }
    val initialLogLevel = initialArguments?.get("level")?.asString ?: "info"
    var logLevel by remember { mutableStateOf(initialLogLevel) }
    var outputError by remember { mutableStateOf<String?>(null) }
    // Log settings may change while this window is open; they must not erase unsubmitted style text.
    var popupDraft by remember(initialSettings.popupStyle) { mutableStateOf(PopupStyleDraft.from(initialSettings.popupStyle)) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var variablePicker by remember { mutableStateOf<PickerRequest?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingDialogKind by remember { mutableStateOf<String?>(null) }
    val settingsDirty = popupDraft != PopupStyleDraft.from(initialSettings.popupStyle)
    val outputDirty = outputMode != initialOutputMode ||
        outputText != initialArguments?.get(if (initialKind == "task.message") "expression" else "message")?.asString.orEmpty() ||
        outputVariable != initialArguments?.get("valueVariable")?.asString || expressionMode != (initialKind == "task.message") ||
        logLevel != initialLogLevel
    fun requestClose() {
        pendingDialogKind = null
        if (settingsDirty || outputDirty) confirmDiscard = true else onDismiss()
    }
    fun chooseDialog(kind: String) {
        val hint = scriptDialogInsertionHint(kind) ?: return
        if (settingsDirty || outputDirty) {
            pendingDialogKind = kind
            confirmDiscard = true
        } else onInsert(hint)
    }
    val page: @Composable () -> Unit = {
    Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 460.dp))) {
        DebugNavigation(tabs, selected) { selected = it; outputError = null }
        if (selectedPage != 2) Text(when (selectedPage) {
            0 -> "加入输出积木，不修改项目配置"
            1 -> "选择对话框功能 · 配置后加入，不在这里执行"
            else -> "项目配置 · 保存后用于后续运行"
        },
            color = EntryMuted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                when (selectedPage) {
                    0 -> DebugOutputPanel(
                        outputMode = outputMode,
                        onOutputModeChange = {
                            if (initialKind == null || it == initialOutputMode || initialKind == "task.message" && it != DebugOutputCode.COMMENT) {
                                outputMode = it; outputError = null
                                if (it == DebugOutputCode.COMMENT) expressionMode = false
                            }
                            else outputError = "修改时请保留输出类型；其他类型请从调试入口新增"
                        },
                        outputText = outputText,
                        expressionMode = expressionMode,
                        expressionAvailable = visualMode && initialKind == null,
                        onExpressionModeChange = { enabled ->
                            if (initialKind == null) { expressionMode = enabled; outputVariable = null; outputError = null }
                        },
                        onOutputTextChange = { outputText = it; outputError = null },
                        selectedVariable = outputVariable,
                        logLevel = logLevel,
                        onLogLevelChange = { logLevel = it; outputError = null },
                        error = outputError,
                        onClearVariable = { outputVariable = null; outputError = null },
                        onPickVariable = {
                            if (availableVariables.isNotEmpty()) {
                                variablePicker = PickerRequest(
                                    title = "选择变量",
                                    options = availableVariables,
                                    selected = outputVariable,
                                ) {
                                    if (expressionMode) {
                                        val appended = DebugOutputCode.appendExpressionVariable(outputText, it)
                                        if (appended == null) outputError = "表达式超过1024字节或变量无效，原内容保留"
                                        else { outputText = appended; outputError = null }
                                    } else outputVariable = it
                                    if (!expressionMode) outputError = null
                                }
                            } else {
                                outputError = "当前没有可用变量，请先创建变量"
                            }
                        },
                    )
                    1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SCRIPT_DIALOG_KINDS.forEach { kind ->
                            BlockCatalog.find(kind)?.let { contract ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .border(1.dp, EntryBorder, RoundedCornerShape(4.dp))
                                    .clickable(onClickLabel = "选择${contract.title}") { chooseDialog(kind) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(contract.title, fontSize = 12.sp, color = EntryAccent, fontWeight = FontWeight.Bold)
                                        Text(contract.summary, fontSize = 10.sp, color = EntryMuted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(if (contract.properties.isEmpty()) "加入" else "配置", fontSize = 11.sp,
                                        color = EntryAccent, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        Text("确认框返回用户选择；布局框使用已保存页面。关闭对话框不会停止脚本。",
                            fontSize = 10.sp, color = EntryMuted)
                    }
                    2 -> DebugPopupStyleEditor(popupDraft, initialSettings.popupStyle) {
                        popupDraft = it; settingsError = null
                    }
                    else -> Unit
                }
            }
        }
        if (selectedPage == 2) settingsError?.let {
            Text(it, color = AutoScriptPalette.Danger, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        }
        if (selectedPage == 1) FooterButtons(listOf("关闭" to ::requestClose)) else FooterButtons(
            listOf(
                "关闭" to ::requestClose,
                (if (selectedPage == 0) confirmLabel else "保存配置") to {
                    if (selectedPage != 0) {
                        val parsed = popupDraft.parse()
                        if (parsed == null) settingsError = "请完成红框内的尺寸、坐标或颜色输入后再保存"
                        else onSaveSettings(initialSettings.copy(popupStyle = parsed))
                    } else {
                        outputError = if (expressionMode) DebugOutputCode.expressionError(outputMode, outputText, visualMode)
                            else DebugOutputCode.error(outputMode, outputText, outputVariable, logLevel)
                        if (outputError == null) {
                            if (expressionMode) DebugOutputCode.expressionSnippet(outputMode, outputText, visualMode)?.let(onInsert)
                            else DebugOutputCode.snippet(outputMode, outputText, outputVariable, visualMode, logLevel)?.let(onInsert)
                        }
                    }
                },
            ),
        )
    }
    }
    if (standalone) StudioPanelDialog("调试",
        ::requestClose, showFooter = false, fullScreen = fullScreen,
        onToggleFullScreen = { fullScreen = !fullScreen }, content = page)
    else page()
    if (confirmDiscard) StudioConfirmDialog(
        onDismissRequest = { confirmDiscard = false; pendingDialogKind = null }, title = { Text("放弃未保存内容？") },
        text = { Text("尚未加入的输出及未保存的调试配置将被丢弃，现有积木和项目配置不会改变。") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            val hint = pendingDialogKind?.let(::scriptDialogInsertionHint)
            confirmDiscard = false; pendingDialogKind = null
            if (hint == null) onDismiss() else onInsert(hint)
        }) { Text(if (pendingDialogKind == null) "放弃并关闭" else "放弃并继续") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDiscard = false; pendingDialogKind = null }) { Text("继续编辑") } },
    )
    variablePicker?.let { request -> PickerDialog(request) { variablePicker = null } }
}

@Composable
private fun DebugNavigation(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(EntryPanel)) {
        tabs.forEachIndexed { index, label ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter).height(2.dp).fillMaxWidth()
                        .background(if (selected == index) EntryAccent else Color.Transparent),
                )
                Text(
                    label,
                    color = if (selected == index) EntryAccent else Color(0xFF282E38),
                    fontSize = 12.sp,
                    fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                    style = CenteredDialogButtonTextStyle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DebugOutputPanel(
    outputMode: Int,
    onOutputModeChange: (Int) -> Unit,
    outputText: String,
    expressionMode: Boolean,
    expressionAvailable: Boolean,
    onExpressionModeChange: (Boolean) -> Unit,
    onOutputTextChange: (String) -> Unit,
    selectedVariable: String?,
    logLevel: String,
    onLogLevelChange: (String) -> Unit,
    error: String?,
    onClearVariable: () -> Unit,
    onPickVariable: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val channels = listOf("运行提示", "弹出提示", "开发日志", "添加说明")
        Column(Modifier.fillMaxWidth().background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp)) {
            repeat(2) { row ->
                Row(Modifier.fillMaxWidth().height(34.dp)) {
                    repeat(2) { column ->
                        val value = row * 2 + column
                        val label = channels[value]
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (outputMode == value) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                                .clickable { onOutputModeChange(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (outputMode == value) EntryAccent else EntryMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                style = CenteredDialogButtonTextStyle,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(when (outputMode) {
            0 -> "运行状态持续显示给脚本使用者；不进入开发日志。"
            1 -> "按弹窗样式显示，到时自动消失。"
            2 -> "写入 Runner 开发日志，不弹出给脚本使用者。"
            else -> "仅添加文字说明，不禁用其他积木；停止本行执行请用编辑→注释执行。"
        }, color = EntryMuted, fontSize = 10.sp)
        if (outputMode == DebugOutputCode.LOG && !expressionMode) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("级别", fontSize = 10.sp, color = EntryMuted, modifier = Modifier.width(40.dp))
                listOf("info" to "信息", "warn" to "警告", "error" to "错误").forEach { (value, label) ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onLogLevelChange(value) }, contentAlignment = Alignment.Center) {
                        Text(if (logLevel == value) "● $label" else label, fontSize = 11.sp,
                            color = if (logLevel == value) EntryAccent else EntryMuted, style = CenteredDialogButtonTextStyle)
                    }
                }
            }
        }
        if (outputMode != DebugOutputCode.COMMENT) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("内容来源", fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f))
                if (expressionAvailable) {
                    Text(if (expressionMode) "表达式 ✓" else "表达式", color = EntryAccent, fontSize = 11.sp,
                        modifier = Modifier.clickable { onExpressionModeChange(!expressionMode) }.padding(horizontal = 6.dp))
                }
                if (selectedVariable != null) {
                    Text("改用文字", color = EntryAccent, fontSize = 11.sp,
                        modifier = Modifier.clickable(onClick = onClearVariable).padding(horizontal = 8.dp))
                }
                Box(
                    Modifier.width(96.dp).fillMaxHeight().border(1.dp, EntryBorder, RoundedCornerShape(3.dp))
                        .clickable(onClick = onPickVariable),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        selectedVariable ?: "选择变量",
                        color = EntryAccent,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = CenteredDialogButtonTextStyle,
                    )
                }
            }
        }
        if (expressionMode) Text("字符用 .. 连接；数值用 text()。如：\"进度：\" .. text(count)。保存后由编译器验证。", color = EntryMuted, fontSize = 10.sp)
        if (selectedVariable != null && outputMode != DebugOutputCode.COMMENT) {
            Text("将输出变量当前值；下方文字仅用作编辑器中的说明。", color = EntryMuted, fontSize = 10.sp)
        }
        if (error != null) Text(error, color = Color(0xFFD13232), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).border(1.dp, EntryBorder, RoundedCornerShape(4.dp)).padding(10.dp),
        ) {
            BasicTextField(
                value = outputText,
                onValueChange = onOutputTextChange,
                textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF202839)),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { field ->
                    Box(Modifier.fillMaxSize()) {
                        if (outputText.isEmpty()) Text(
                            when (outputMode) {
                                0 -> "输入持续显示的运行提示"
                                1 -> "输入到时自动消失的弹窗内容"
                                2 -> "输入供开发调试查看的脚本日志"
                                else -> "输入说明内容"
                            },
                            color = Color(0xFFB3BAC7),
                            fontSize = 13.sp,
                        )
                        field()
                    }
                },
            )
        }
    }
}




@Composable
private fun SplitEntryPage(title: String, tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit, onAdd: () -> Unit, headerHint: String? = null, showAdd: Boolean = true, confirmLabel: String = "加入", content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            headerHint?.let { Text(it, color = EntryMuted, fontSize = 10.sp) }
        }
        DividerLine()
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(96.dp).fillMaxHeight().background(EntryPanel).verticalScroll(rememberScrollState())) {
                tabs.forEachIndexed { index, tab ->
                    Box(Modifier.fillMaxWidth().height(38.dp).clickable { onSelected(index) }) {
                        Box(Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                            .background(if (selected == index) EntryAccent else Color.Transparent))
                        Text(
                            tab,
                            fontSize = 11.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) EntryAccent else Color(0xFF283140),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
                        )
                    }
                }
            }
            DividerVertical()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
        FooterButtons(if (showAdd) listOf("取消" to onDismiss, confirmLabel to onAdd) else listOf("关闭" to onDismiss))
    }
}


@Composable
private fun PickerDialog(request: PickerRequest, onDismiss: () -> Unit) {
    var selected by remember(request.title, request.selected) { mutableStateOf(request.selected) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.78f).widthIn(max = 390.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Text(request.title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp, vertical = 10.dp))
                DividerLine()
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height((request.options.size.coerceIn(1, 6) * 40).dp)) {
                    if (request.options.isEmpty()) {
                        Text("暂无可选变量，请先在“变量”中添加。", color = EntryMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center))
                    } else {
                        request.options.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().height(40.dp)
                                    .background(if (selected == option) Color(0xFFEAF0FF) else Color.White)
                                    .clickable { selected = option }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(option, color = Color(0xFF202839), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (selected == option) Text("✓", color = EntryAccent, fontSize = 15.sp)
                            }
                            DividerLine()
                        }
                    }
                }
                FooterButtons(listOf("取消" to onDismiss, "确定" to {
                    selected?.let(request.onSelected)
                    onDismiss()
                }))
            }
        }
    }
}


@Composable private fun EntryField(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f)); Text(value, fontSize = 11.sp, color = EntryAccent)
    }
}



@Composable
private fun SegmentedChoice(
    left: String,
    right: String,
    selectedRight: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp).background(Color(0xFFEEF1F7), RoundedCornerShape(15.dp))) {
        listOf(false to left, true to right).forEach { (rightSide, label) ->
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selectedRight == rightSide) EntryAccent else Color.Transparent, RoundedCornerShape(15.dp))
                    .clickable { onSelect(rightSide) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selectedRight == rightSide) Color.White else EntryMuted,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = CenteredDialogButtonTextStyle,
                )
            }
        }
    }
}


@Composable private fun HeaderAction(label: String, onClick: () -> Unit) {
    Box(Modifier.width(46.dp).height(30.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 10.sp, textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
@Composable private fun FooterButtons(buttons: List<Pair<String, () -> Unit>>, fontSize: Int = 12) {
    DividerLine()
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        buttons.forEachIndexed { index, (label, action) ->
            if (index > 0) DividerVertical()
            CenteredFooterAction(
                label = label,
                color = if (index == buttons.lastIndex) EntryAccent else Color(0xFF202839),
                onClick = action,
                modifier = Modifier.weight(1f),
                fontSize = fontSize,
                bold = index == buttons.lastIndex,
            )
        }
    }
}

/** Shared dialog-action base: center by layout, never by a font-specific top padding. */
@Composable
private fun CenteredFooterAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    fontSize: Int = 12,
    bold: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color,
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
            style = CenteredDialogButtonTextStyle,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

private val CenteredDialogButtonTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

// 文件弹窗（service_tk_xt_wj_gl_layout）的分割线是 1.0dp 的 hs，不是 px 发线。
@Composable private fun DividerLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(AutoScriptPalette.Divider))
@Composable private fun DividerVertical() = Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
