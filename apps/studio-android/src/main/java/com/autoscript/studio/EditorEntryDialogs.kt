package com.autoscript.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectDebugSettings
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

@Composable
internal fun EditorEntryDialog(
    entry: LegacyToolDialog,
    projectName: String,
    files: List<StudioProjectFile>,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenFile: (StudioProjectFile) -> Unit = {},
    onDeleteFiles: (List<StudioProjectFile>) -> Unit = {},
    onOpenImageTools: ((Long) -> Unit)? = null,
    onOpenImageLibrary: (() -> Unit)? = null,
    onRemoveCaptureOverlay: (() -> Unit)? = null,
    availableVariables: List<String> = emptyList(),
    debugSettings: ProjectDebugSettings = ProjectDebugSettings(),
    onSaveDebugSettings: (ProjectDebugSettings) -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val panelModifier = when (entry) {
            LegacyToolDialog.FILES -> Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f).widthIn(max = 520.dp)
            LegacyToolDialog.JUDGMENT -> Modifier.width(132.dp).height(132.dp)
            LegacyToolDialog.COMMON -> Modifier.fillMaxWidth(.96f).widthIn(max = 560.dp).height(390.dp)
            // 调试页是左侧导航 + 右侧配置卡的桌面式弹窗；与普通短表单不同，
            // 需要一整屏高度来保留三张参考页的结构，而不是让内容挤在 390dp 内。
            LegacyToolDialog.DEBUG -> Modifier.fillMaxWidth(.96f).widthIn(max = 600.dp).fillMaxHeight(.72f)
            LegacyToolDialog.IMAGE -> Modifier.fillMaxWidth(.96f).widthIn(max = 550.dp).height(310.dp)
            LegacyToolDialog.LOOP -> Modifier.fillMaxWidth(.96f).widthIn(max = 540.dp).height(350.dp)
            else -> Modifier.fillMaxWidth(.90f).widthIn(max = 520.dp).height(
                when (entry) {
                    LegacyToolDialog.AI -> 520.dp
                    // 工具页按单屏内容量固定，避免卡片之间留出大段无用空白。
                    LegacyToolDialog.TOOLS -> 342.dp
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
                LegacyToolDialog.FILES -> FileManagerPage(projectName, files, onDismiss, onOpenFile, onDeleteFiles)
                LegacyToolDialog.TOOLS -> ToolSettingsPage(
                    onDismiss,
                    onInsert,
                    onOpenImageTools,
                    onOpenImageLibrary,
                    onRemoveCaptureOverlay,
                )
                LegacyToolDialog.IMAGE -> ImageRecognitionPage(onDismiss, onInsert)
                LegacyToolDialog.JUDGMENT -> JudgmentPage(onDismiss, onInsert)
                LegacyToolDialog.LOOP -> LoopPage(onDismiss, onInsert, availableVariables)
                LegacyToolDialog.COMMON -> CommonPage(onDismiss, onInsert)
                LegacyToolDialog.DEBUG -> DebugPage(onDismiss, onInsert, debugSettings, onSaveDebugSettings)
                LegacyToolDialog.AI -> AiProgrammingPage(onDismiss, onInsert)
                LegacyToolDialog.DATA_BACKFILL -> DataBackfillPage(onDismiss, onInsert)
                LegacyToolDialog.VARIABLE_CHECK -> VariableCheckPage(onDismiss)
                LegacyToolDialog.RUNTIME_VARIABLES -> RuntimeVariablesPage(onDismiss)
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
                        notice = "源文件请在源文件管理中新建，图片和字库请在项目设置中导入"
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

@Composable
private fun DataBackfillPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    EditorColumnPage("数据回填", onDismiss, "回填", {
        onInsert("${LEGACY_ENTRY_COMMAND_PREFIX}DATA_BACKFILL\n")
        onDismiss()
    }) {
        Text("把调试或识别结果回填到当前节点参数", color = EntryMuted, fontSize = 10.sp)
        EntryField("数据来源", "最近一次调试结果") {}
        EntryField("目标节点", "当前选中节点") {}
        EntryField("回填字段", "自动匹配") {}
        SettingChoice("覆盖已有值", "否")
    }
}

/** `service_variable_check_dialog`：静态变量检查需要 Flow/Lua 的变量分析，尚未实现，不能显示“未发现问题”这种假通过。 */
@Composable
private fun VariableCheckPage(onDismiss: () -> Unit) {
    EditorColumnPage("变量检查", onDismiss, "关闭", onDismiss) {
        Text("检查未声明、未赋值和类型不匹配的变量", color = EntryMuted, fontSize = 10.sp)
        Text(
            "变量检查尚未接入\n需要 Rust 编译器输出变量分析结果，属于后续阶段",
            color = EntryMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** `service_runtime_variable_window`：运行时变量需要调试协议，Runtime 尚未提供。 */
@Composable
private fun RuntimeVariablesPage(onDismiss: () -> Unit) {
    EditorColumnPage("变量信息", onDismiss, "关闭", onDismiss) {
        Text("运行后在这里显示变量名、类型和当前值", color = EntryMuted, fontSize = 10.sp)
        Text(
            "运行时变量读取尚未开放\n需要 Runtime 调试协议，属于后续阶段",
            color = EntryMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private const val LEGACY_ENTRY_COMMAND_PREFIX = "--@autoscript-editor:"

@Composable
private fun ToolSettingsPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onOpenImageTools: ((Long) -> Unit)?,
    onOpenImageLibrary: (() -> Unit)?,
    onRemoveCaptureOverlay: (() -> Unit)?,
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
                ToolActionRow(R.drawable.editor_recognition_preview_24, "测试识别（待实现）", enabled = false) {
                    picker = PickerRequest("测试识别", listOf("找图", "找色", "字库找字", "ONNX OCR")) {}
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
                ToolActionRow(R.drawable.editor_annotation_24, "打开标注库（待实现）", enabled = false) {
                    if (onOpenImageLibrary != null) onOpenImageLibrary() else picker = PickerRequest("标注库", listOf("图片标注", "OCR 标注", "目标检测标注")) {}
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
private fun ImageRecognitionPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val pages = listOf("区域找图", "多点找色", "字库识字", "ONNX OCR")
    var page by remember { mutableStateOf(1) }
    var recognitionFrequency by remember { mutableStateOf("识别频率[1]") }
    var template by remember { mutableStateOf("未选择") }
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
                        onFirst = { picker = PickerRequest("选择模板图片", listOf("template.png", "button_start.png", "target.png", "从截屏中创建"), template) { template = it } },
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
        FooterButtons(listOf("取消" to onDismiss, "图像对比" to {}, "加入" to {
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
            "如果" to "${LegacyFunctionCatalog.BLOCK_HINT_PREFIX}control.if\n",
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

@Composable
private fun LoopPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    availableVariables: List<String>,
) {
    var variableMode by remember { mutableStateOf(false) }
    // 循环入口直接落在实际的参数页。限次循环是唯一不依赖额外运行时状态的
    // 默认项；用户仍可在同一页切换其它循环语义，不再需要经过一个中转选择页。
    var mode by remember { mutableStateOf(1) }
    var count by remember { mutableStateOf("3") }
    var time by remember { mutableStateOf("3") }
    var timeUnit by remember { mutableStateOf("秒") }
    var countVariable by remember { mutableStateOf<String?>(null) }
    var timeVariable by remember { mutableStateOf<String?>(null) }
    var loopCountVariable by remember { mutableStateOf<String?>(null) }
    var loopTimeVariable by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("${when (mode) { 0 -> "无限循环"; 1 -> "限次循环"; 2 -> "限时循环"; 3 -> "获取循环次数"; else -> "获取循环时间" }}", color = EntryBlue, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 9.dp))
        DividerLine()
        Box(Modifier.padding(top = 10.dp)) {
            LoopValueModeChoice(variableMode) { variableMode = it }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)) {
            DenseLoopRow("无限循环", mode == 0, { mode = 0 }) { Spacer(Modifier.fillMaxWidth()) }
            DenseLoopRow("限次循环", mode == 1, { mode = 1 }) {
                if (variableMode) DenseVariableSelector("整", countVariable ?: "未选择") {
                    picker = variablePicker(availableVariables, countVariable) { countVariable = it }
                }
                else DenseLoopValue(count, { count = it }, "$count(次)") {}
            }
            DenseLoopRow("限时循环", mode == 2, { mode = 2 }) {
                if (variableMode) DenseVariableSelector("浮", timeVariable ?: "未选择") {
                    picker = variablePicker(availableVariables, timeVariable) { timeVariable = it }
                }
                else DenseLoopValue(time, { time = it }, "$time($timeUnit)") {
                    picker = PickerRequest("时间单位", listOf("毫秒", "秒", "分钟"), timeUnit) { timeUnit = it }
                }
            }
            DenseLoopRow("获取循环次数", mode == 3, { mode = 3 }) {
                DenseVariableSelector("整", loopCountVariable ?: "未选择") {
                    picker = variablePicker(availableVariables, loopCountVariable) { loopCountVariable = it }
                }
            }
            DenseLoopRow("获取循环时间", mode == 4, { mode = 4 }) {
                DenseVariableSelector("浮", loopTimeVariable ?: "未选择") {
                    picker = variablePicker(availableVariables, loopTimeVariable) { loopTimeVariable = it }
                }
            }
        }
        validationError?.let { message ->
            Text(message, color = Color(0xFFD14949), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
        }
        FooterButtons(listOf("取消" to onDismiss, "加入" to {
            val prefix = LegacyFunctionCatalog.LOOP_HINT_PREFIX
            val snippet = when (mode) {
                0 -> "${prefix}forever"
                1 -> if (variableMode) countVariable?.let { "${prefix}repeat:variable:$it" }
                    else "${prefix}repeat:fixed:${count.toLongOrNull()?.coerceIn(0, 1_000_000) ?: 1}"
                2 -> if (variableMode) timeVariable?.let { "${prefix}timed:variable:$it" }
                    else {
                        val multiplier = when (timeUnit) { "分钟" -> 60_000L; "秒" -> 1_000L; else -> 1L }
                        val duration = ((time.toDoubleOrNull() ?: 0.0) * multiplier).toLong().coerceIn(1, 86_400_000)
                        "${prefix}timed:fixed:$duration"
                    }
                3 -> loopCountVariable?.let { "${prefix}metric:count:$it" }
                else -> loopTimeVariable?.let { "${prefix}metric:elapsed:$it" }
            }
            if (snippet == null) {
                validationError = "请先选择变量"
            } else {
                validationError = null
                onInsert(snippet)
                onDismiss()
            }
        }))
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

@Composable
private fun LoopValueModeChoice(variableMode: Boolean, onSelected: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp)
            .background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp),
    ) {
        listOf(false to "固定值", true to "变量").forEach { (value, label) ->
            Text(
                label,
                color = if (variableMode == value) EntryBlue else EntryMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(if (variableMode == value) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                    .clickable { onSelected(value) }
                    .wrapContentSize(Alignment.Center),
            )
        }
    }
}

private fun legacyLuaEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

@Composable
private fun DenseLoopRow(label: String, selected: Boolean, onSelect: () -> Unit, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(30.dp))
            Text(label, fontSize = 13.sp, color = Color(0xFF20242C), maxLines = 1)
        }
        Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) { value() }
    }
}

@Composable
private fun DenseLoopValue(value: String, onValue: (String) -> Unit, unit: String, onUnit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF20242C), textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f).height(30.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
                .border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(vertical = 7.dp),
        )
        DenseSpinner(unit, Modifier.padding(start = 6.dp).weight(1.15f), arrow = true, onClick = onUnit, centered = true)
    }
}

@Composable
private fun DenseVariableSelector(type: String, name: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
            .border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type, color = EntryAccent, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(.28f))
        DividerVertical()
        Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        DividerVertical()
        Text("选择变量", fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(.72f).fillMaxHeight().clickable(onClick = onSelect).wrapContentSize(Alignment.Center))
    }
}

private fun variablePicker(
    variables: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
): PickerRequest = PickerRequest("选择变量", variables, selected, onSelected)

@Composable
private fun CommonPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val tabs = listOf("流程控制", "设置调用参数", "获取调用参数", "设置返回参数", "获取返回参数")
    var selected by remember { mutableStateOf(0) }
    var action by remember { mutableStateOf(0) }
    var waitValue by remember { mutableStateOf("500") }
    SplitEntryPage("常用功能", tabs, selected, { selected = it }, onDismiss, {
        val snippet = when (selected) {
            1 -> "Runtime.setParameter(1, value)\n"
            2 -> "local value = Runtime.getParameter(1)\n"
            3 -> "Runtime.setReturnParameter(1, value)\n"
            4 -> "local value = Runtime.getReturnParameter(1)\n"
            else -> when (action) { 0 -> "break\n"; 1 -> "::label::\n"; 2 -> "return\n"; 3 -> "goto label\n"; else -> "Task.sleep($waitValue)\n" }
        }
        onInsert(snippet); onDismiss()
    }) {
        when (selected) {
            0 -> {
                DenseFlowActions(action) { action = it }
                if (action == 4) {
                    SegmentedChoice("固定值", "变量", false) {}
                    DenseTypedValue("毫秒", waitValue, { waitValue = it }, "500毫秒")
                } else if (action == 1) {
                    SegmentedChoice("自动标记", "自定义", false) {}
                    DenseTypedValue("名", "", {}, "")
                } else if (action == 3) {
                    DenseNotice("当前文件没有标记")
                } else {
                    DenseNotice(if (action == 0) "只能在循环体内使用。" else "结束当前调用并返回上层。", title = "使用说明")
                }
            }
            1 -> {
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置调用参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("+ 添加参数", color = EntryAccent, fontSize = 10.sp)
                }
                DenseTypedValue("序", "1", {}, "选择变量")
                DenseTypedValue("值", "未选择", {}, "选择")
            }
            2 -> {
                Text("获取调用参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "变量")
                DenseTypedValue("接", "未选择", {}, "选择")
            }
            3 -> {
                Text("设置返回参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "选择变量")
                DenseTypedValue("值", "未选择", {}, "选择")
            }
            else -> {
                Text("获取返回参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "变量")
                DenseTypedValue("接", "未选择", {}, "选择")
            }
        }
    }
}

@Composable
private fun DenseFlowActions(selected: Int, onSelected: (Int) -> Unit) {
    val actions = listOf("跳出循环", "放置标记", "返回上层", "跳转标记", "等待时间")
    Column(Modifier.fillMaxWidth().border(1.dp, EntryBorder, RoundedCornerShape(3.dp))) {
        actions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            if (rowIndex > 0) DividerLine()
            Row(Modifier.fillMaxWidth().height(35.dp)) {
                rowItems.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * 2 + columnIndex
                    if (columnIndex > 0) DividerVertical()
                    Row(Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(index == selected, { onSelected(index) }, modifier = Modifier.size(26.dp))
                        Text(label, fontSize = 10.sp)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DenseTypedValue(type: String, value: String, onValue: (String) -> Unit, action: String) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type, color = EntryAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
        DividerVertical()
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF20242C)), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (action.isNotEmpty()) {
            DividerVertical()
            Text(action, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp).fillMaxHeight().clickable { }.padding(top = 8.dp))
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

@Composable
/** 参考是 `IosSwitchView` 45×26；`enabled=false` 时标签带“（未开放）”，不做成看起来能点的假开关。 */
private fun DenseDebugSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (enabled) label else "$label（未开放）",
            fontSize = 12.sp,
            color = if (enabled) Color(0xFF202839) else EntryMuted,
            modifier = Modifier.weight(1f),
        )
        IosSwitch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
}

@Composable
private fun DenseRuntimeMode(label: String, options: List<String>, selected: Int) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Row(Modifier.weight(1f).height(28.dp).background(Color(0xFFF5F7FA), RoundedCornerShape(6.dp))) {
            options.forEachIndexed { index, option ->
                Text(
                    option,
                    color = if (index == selected) Color.White else EntryMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(if (index == selected) EntryAccent else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(top = 7.dp),
                )
            }
        }
    }
}

/** `service_tk_debug_control.xml` 的三栏内容复刻：标题区、左导航、右配置卡和固定底栏。 */
@Composable
private fun DebugPage(
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    initialSettings: ProjectDebugSettings,
    onSaveSettings: (ProjectDebugSettings) -> Unit,
) {
    val tabs = listOf("运行输出", "调试设置", "开发环境")
    var selected by remember { mutableStateOf(0) }
    var outputConsole by remember { mutableStateOf(false) }
    var outputText by remember { mutableStateOf("") }
    var runDelayMs by remember(initialSettings) { mutableStateOf(initialSettings.runDelayMs) }
    var delayPicker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        DebugHeader()
        DividerLine()
        Row(Modifier.weight(1f)) {
            DebugNavigation(tabs, selected) { selected = it }
            DividerVertical()
            Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                when (selected) {
                    0 -> DebugOutputPanel(
                        outputConsole = outputConsole,
                        onOutputConsoleChange = { outputConsole = it },
                        outputText = outputText,
                        onOutputTextChange = { outputText = it },
                    )
                    1 -> DebugSettingsPanel(runDelayMs) {
                        val choices = listOf(0, 100, 300, 500, 1_000, 3_000, 5_000, 10_000)
                        delayPicker = PickerRequest(
                            title = "运行延迟",
                            options = choices.map { "${it}毫秒" },
                            selected = "${runDelayMs}毫秒",
                        ) { choice ->
                            runDelayMs = choice.removeSuffix("毫秒").toIntOrNull()?.coerceIn(0, 60_000)
                                ?: runDelayMs
                        }
                    }
                    else -> DebugEnvironmentPanel()
                }
            }
        }
        FooterButtons(
            listOf(
                "取消" to onDismiss,
                (if (selected == 0) "加入" else "保存") to {
                    if (selected != 0) {
                        onSaveSettings(ProjectDebugSettings(runDelayMs = runDelayMs))
                    } else {
                        val level = if (outputConsole) "info" else "warn"
                        val text = outputText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        onInsert("Log.$level(\"$text\")\n")
                    }
                },
            ),
        )
    }
    delayPicker?.let { request -> PickerDialog(request) { delayPicker = null } }
}

@Composable
private fun DebugHeader() {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("调试功能", color = EntryAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(118.dp))
        Text(
            "调试/运行延迟，控制台日志开启会降低运行速度，仅开发时生效！",
            color = EntryAccent,
            // 720px 宽实机也必须保持参考图的一行说明，不能挤成两行占用标题区。
            fontSize = 6.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DebugNavigation(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Column(Modifier.width(116.dp).fillMaxHeight().background(Color.White)) {
        tabs.forEachIndexed { index, label ->
            Box(
                Modifier.fillMaxWidth().height(48.dp).clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight()
                        .background(if (selected == index) EntryAccent else Color.Transparent),
                )
                Text(
                    label,
                    color = if (selected == index) EntryAccent else Color(0xFF282E38),
                    fontSize = 13.sp,
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
    outputConsole: Boolean,
    onOutputConsoleChange: (Boolean) -> Unit,
    outputText: String,
    onOutputTextChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp),
            ) {
                listOf(false to "运行提示", true to "控制台日志").forEach { (value, label) ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(if (outputConsole == value) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                            .clickable { onOutputConsoleChange(value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (outputConsole == value) EntryAccent else EntryMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            style = CenteredDialogButtonTextStyle,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.width(64.dp).fillMaxHeight().border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("选择变量", color = EntryAccent, fontSize = 11.sp, style = CenteredDialogButtonTextStyle) }
        }
        Spacer(Modifier.height(9.dp))
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
                        if (outputText.isEmpty()) Text("请输入运行提示内容", color = Color(0xFFB3BAC7), fontSize = 13.sp)
                        field()
                    }
                },
            )
        }
    }
}

@Composable
private fun DebugSettingsPanel(runDelayMs: Int, onPickDelay: () -> Unit) {
    val rows = listOf("调试运行", "运行时隐藏编程窗口", "控制台日志", "显示按键准星", "性能信息", "无障碍音量停止", "停止后返回入口")
    Column(Modifier.fillMaxSize()) {
        DebugBorderCard(Modifier.weight(1f)) {
            rows.forEachIndexed { index, label ->
                DebugPreviewSwitchRow(label, checked = index != 1)
                if (index != rows.lastIndex) DividerLine()
            }
        }
        Spacer(Modifier.height(10.dp))
        DebugBorderCard(Modifier.height(98.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("运行延迟", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "${runDelayMs}毫秒",
                    color = EntryAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onPickDelay),
                )
            }
            Box(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp, vertical = 20.dp)) {
                Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(Color(0xFFE9ECF1)))
                Box(Modifier.size(12.dp).align(Alignment.CenterStart).background(EntryAccent, RoundedCornerShape(12.dp)))
            }
        }
    }
}

@Composable
private fun DebugEnvironmentPanel() {
    Column(Modifier.fillMaxSize()) {
        DebugBorderCard(Modifier.weight(1f)) {
            Text("开发环境", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 10.dp, vertical = 12.dp))
            DebugSegmentRow("截图服务", listOf("系统录屏", "Root"), 1)
            DividerLine()
            DebugSegmentRow("按键服务", listOf("无障碍", "Root"), 1)
            DividerLine()
            DebugSegmentRow("截屏显示", listOf("自动", "悬浮窗", "应用内"), 0)
            DividerLine()
            DebugPreviewSwitchRow("始终只显示一个弹窗", checked = false)
        }
    }
}

@Composable
private fun DebugBorderCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().border(1.dp, EntryBorder, RoundedCornerShape(4.dp)), content = { content() })
}

@Composable
private fun DebugPreviewSwitchRow(label: String, checked: Boolean) {
    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        DebugStaticSwitch(checked)
    }
}

/** 只作参考布局展示；正式接入运行配置前不把开关做成可点击的假功能。 */
@Composable
private fun DebugStaticSwitch(checked: Boolean) {
    Box(
        Modifier.size(width = 45.dp, height = 26.dp)
            .background(if (checked) Color(0xFF31C75A) else Color(0xFFD1D5DB), RoundedCornerShape(13.dp))
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(11.dp)))
    }
}

@Composable
private fun DebugSegmentRow(label: String, options: List<String>, selected: Int) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(76.dp))
        Row(Modifier.weight(1f).height(30.dp).background(Color(0xFFF4F6FA), RoundedCornerShape(6.dp)).padding(2.dp)) {
            options.forEachIndexed { index, option ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (selected == index) Color.White else Color.Transparent, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        color = if (selected == index) EntryAccent else EntryMuted,
                        fontSize = 10.sp,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        style = CenteredDialogButtonTextStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiProgrammingPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var requirement by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var sidePanel by remember { mutableStateOf<String?>(null) }
    var attachments by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 编程助手", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("服务 · 模型", fontSize = 8.sp, color = EntryMuted)
            }
            HeaderAction("会话") { sidePanel = "会话" }
            HeaderAction("配置") { sidePanel = "配置" }
            HeaderAction("×", onDismiss)
        }
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("任务要求", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SmallOutlineAction("路径") { attachments++ }
                SmallOutlineAction("附件 $attachments/999") { attachments++ }
                SmallOutlineAction("截屏") { attachments++ }
            }
            MultilineField(requirement, "描述目标、问题和期望结果", 96) { requirement = it }
            Text("可选：添加文件或截图", color = EntryMuted, fontSize = 9.sp, modifier = Modifier.padding(vertical = 7.dp))
            Text("等待输入", color = EntryMuted, fontSize = 10.sp, modifier = Modifier.height(30.dp))
            Text("结果 · 可视化节点", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.height(26.dp))
            MultilineField(result, "", 180) { result = it }
        }
        FooterButtons(listOf("复制" to {}, "插入" to { if (result.isNotBlank()) onInsert(result) }, "发送" to {
            result = if (requirement.isBlank()) "请先描述任务要求" else "-- AI 服务尚未配置\n-- $requirement"
        }))
    }
    sidePanel?.let { AiSidePanelDialog(it) { sidePanel = null } }
}

@Composable
private fun EditorColumnPage(title: String, onDismiss: () -> Unit, primary: String, onPrimary: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp, vertical = 11.dp))
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        FooterButtons(listOf("取消" to onDismiss, primary to onPrimary))
    }
}

@Composable
private fun SplitEntryPage(title: String, tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit, onAdd: () -> Unit, headerHint: String? = null, showAdd: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            headerHint?.let { Text(it, color = EntryMuted, fontSize = 8.sp) }
        }
        DividerLine()
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(70.dp).fillMaxHeight().background(EntryPanel)) {
                tabs.forEachIndexed { index, tab ->
                    Box(Modifier.fillMaxWidth().height(36.dp).clickable { onSelected(index) }) {
                        Box(Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                            .background(if (selected == index) EntryAccent else Color.Transparent))
                        Text(
                            tab,
                            fontSize = 10.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) EntryAccent else Color(0xFF283140),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(top = 10.dp),
                        )
                    }
                }
            }
            DividerVertical()
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { content() }
        }
        FooterButtons(if (showAdd) listOf("取消" to onDismiss, "加入" to onAdd) else listOf("关闭" to onDismiss))
    }
}

@Composable private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).padding(bottom = 6.dp)) {
        Text(title, color = EntryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        content()
    }
}

@Composable private fun SettingChoice(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(42.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value, color = EntryAccent, fontSize = 11.sp)
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

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Switch(checked, onChecked, modifier = Modifier.size(38.dp, 24.dp))
    }
}

@Composable private fun EntryField(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f)); Text(value, fontSize = 11.sp, color = EntryAccent)
    }
}

@Composable private fun EntryEditField(label: String, value: String, onValue: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f))
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = EntryAccent, textAlign = TextAlign.End), modifier = Modifier.width(100.dp))
    }
}

@Composable private fun CompactChoice(text: String, modifier: Modifier, onClick: () -> Unit) = Text(text, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = modifier.height(34.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(top = 9.dp))

@Composable private fun SegmentedChoice(left: String, right: String, selectedRight: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp).background(Color(0xFFEEF1F7), RoundedCornerShape(15.dp))) {
        listOf(false to left, true to right).forEach { (rightSide, label) ->
            Text(label, color = if (selectedRight == rightSide) Color.White else EntryMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().background(if (selectedRight == rightSide) EntryAccent else Color.Transparent, RoundedCornerShape(15.dp)).clickable { onSelect(rightSide) }.padding(top = 7.dp))
        }
    }
}

@Composable private fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, modifier = Modifier.size(30.dp)); Text(label, fontSize = 12.sp)
    }
}

@Composable private fun MultilineField(value: String, hint: String, minHeight: Int, onValue: (String) -> Unit) {
    BasicTextField(value, onValue, textStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF202839), fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().height(minHeight.dp).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(8.dp), decorationBox = { field -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = EntryMuted, fontSize = 10.sp); field() } })
}

@Composable private fun HeaderAction(label: String, onClick: () -> Unit) = Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(46.dp).height(30.dp).clickable(onClick = onClick).padding(top = 8.dp))
@Composable private fun SmallOutlineAction(label: String, onClick: () -> Unit) = Text(label, color = EntryAccent, fontSize = 9.sp, modifier = Modifier.padding(start = 5.dp).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp))

@Composable
private fun AiSidePanelDialog(kind: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.82f).widthIn(max = 420.dp).height(420.dp), color = Color.White, shape = RoundedCornerShape(2.dp), shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (kind == "配置") "AI 配置" else "会话记录", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    HeaderAction("×", onDismiss)
                }
                DividerLine()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == "配置") {
                        EntryField("服务", "未配置") {}
                        EntryField("模型", "未选择") {}
                        EntryField("输出格式", "可视化节点") {}
                        EntryField("文件访问", "每次询问") {}
                        EntryField("截图权限", "每次询问") {}
                        Text("服务地址、密钥和账号信息不会写死在客户端。", color = EntryMuted, fontSize = 9.sp)
                    } else {
                        EntryField("新会话", "＋") {}
                        Text("暂无历史会话", color = EntryMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 60.dp))
                    }
                }
                FooterButtons(listOf("关闭" to onDismiss, if (kind == "配置") "保存" to onDismiss else "新建会话" to {}))
            }
        }
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
