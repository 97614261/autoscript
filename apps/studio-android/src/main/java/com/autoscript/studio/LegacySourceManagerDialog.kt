package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.core.designsystem.hairline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 源文件管理的所有写操作都交给宿主，宿主通过 [ProjectSourceFiles] 落到 ProjectStore。 */
internal sealed interface SourceManagerAction {
    data class CreateFile(val name: String, val group: String?) : SourceManagerAction
    data class CreateGroup(val name: String) : SourceManagerAction
    data class RenameFile(val flowId: String, val name: String) : SourceManagerAction
    data class RenameGroup(val group: String, val name: String) : SourceManagerAction
    data class SaveAs(val flowId: String, val name: String) : SourceManagerAction
    data class Delete(val flowIds: List<String>, val groups: List<String>) : SourceManagerAction
    data class AddToGroup(val flowIds: List<String>, val group: String) : SourceManagerAction
    data class RemoveFromGroup(val flowIds: List<String>) : SourceManagerAction
    data class Open(val flowId: String) : SourceManagerAction
    data class InsertCall(val flowId: String) : SourceManagerAction
}

private val SourceBlue = AutoScriptPalette.DialogTitle
private val SourceAction = AutoScriptPalette.DialogAction
private val SourceText = Color.Black
private val SourceMuted = AutoScriptPalette.Muted
private val SourceDivider = AutoScriptPalette.Divider
private val SourceActive = Color(0xFFF6F8FF)

/** ae1：源文件名与分组名输入框 `LengthFilter(15)`。 */
private const val MAX_SOURCE_NAME_INPUT = 15
private const val GROUP_KEY = "g:"
private const val FILE_KEY = "f:"

private enum class SourceNameAction(val title: String) {
    NEW("创建"),
    SAVE_AS("另存为"),
    RENAME("重命名"),
    GROUP("添加分组"),
}

private enum class SourceSortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TIME("时间"),
}

private sealed interface SourceRow {
    val key: String

    data class Up(override val key: String = "..") : SourceRow
    data class Group(val name: String, val count: Int) : SourceRow {
        override val key: String get() = GROUP_KEY + name
    }
    data class File(val entry: SourceFileEntry) : SourceRow {
        override val key: String get() = FILE_KEY + entry.flowId
    }
}

/**
 * `service_tk_ywj_xz_cz.xml` 的复刻：40dp 标题行、可隐藏的搜索行、常驻的“已选择N项 / 全选”行、
 * 45dp 的分组/文件行，以及两套底栏（取消/加入/确定；另存为/重命名/取消/删除/添加分组/移出分组）。
 * 分组是虚拟视图，进入分组只是过滤，不改变文件位置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LegacySourceManagerDialog(
    tree: SourceFileTree,
    currentFlowId: String?,
    busy: Boolean,
    message: String?,
    onAction: (SourceManagerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFlowId by rememberSaveable(currentFlowId) { mutableStateOf(currentFlowId) }
    var selectedKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var sortMenuVisible by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SourceSortMode.NAME) }
    var reverseSort by rememberSaveable { mutableStateOf(false) }
    var nameAction by rememberSaveable { mutableStateOf<SourceNameAction?>(null) }
    var nameValue by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var localNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 分组名不存在了（被宿主删掉）就退回根目录。
    if (currentGroup != null && tree.groups.none { it.name == currentGroup }) currentGroup = null

    val rows = remember(tree, currentGroup, query, sortMode, reverseSort) {
        val needle = query.trim()
        val groupRows = if (currentGroup == null) {
            tree.groups.map { group -> SourceRow.Group(group.name, tree.entriesIn(group.name).size) }
        } else {
            emptyList()
        }
        val fileRows = tree.entriesIn(currentGroup).map(SourceRow::File)
        val comparator = when (sortMode) {
            SourceSortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { row: SourceRow.File -> row.entry.name }
            SourceSortMode.SIZE -> compareBy<SourceRow.File> { it.entry.sizeBytes }
            SourceSortMode.TIME -> compareBy<SourceRow.File> { it.entry.lastModified }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.name }
        val visibleGroups = groupRows
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SourceRow.Group::name))
            .let { if (reverseSort) it.asReversed() else it }
        val visibleFiles = fileRows
            .filter { needle.isEmpty() || it.entry.name.contains(needle, ignoreCase = true) }
            .sortedWith(comparator)
            .let { if (reverseSort) it.asReversed() else it }
        buildList {
            if (currentGroup != null) add(SourceRow.Up())
            addAll(visibleGroups)
            addAll(visibleFiles)
        }
    }
    val selectableKeys = remember(rows) { rows.filterNot { it is SourceRow.Up }.map(SourceRow::key) }
    val selectionMode = selectedKeys.isNotEmpty()
    val selectedFlowIds = selectedKeys.filter { it.startsWith(FILE_KEY) }.map { it.removePrefix(FILE_KEY) }
    val selectedGroups = selectedKeys.filter { it.startsWith(GROUP_KEY) }.map { it.removePrefix(GROUP_KEY) }
    val allSelected = selectableKeys.isNotEmpty() && selectableKeys.all(selectedKeys::contains)

    fun clearSelection() {
        selectedKeys = emptyList()
    }

    fun toggle(key: String) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    fun beginName(action: SourceNameAction, suggested: String) {
        menuVisible = false
        localNotice = null
        nameValue = suggested
        nameAction = action
    }

    fun dispatch(action: SourceManagerAction) {
        localNotice = null
        nameAction = null
        confirmDelete = false
        clearSelection()
        onAction(action)
    }

    BackHandler {
        when {
            nameAction != null -> nameAction = null
            confirmDelete -> confirmDelete = false
            selectionMode -> clearSelection()
            searchVisible -> { searchVisible = false; query = "" }
            currentGroup != null -> currentGroup = null
            else -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 参考根布局是 match_parent + paddingTop/Bottom 5dp，即全屏减上下 5dp，不是居中小弹窗。
        Surface(
            modifier = Modifier.fillMaxSize().padding(vertical = 5.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // line_tk_ywj_xz_cz_1：标题 + 菜单
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        currentGroup ?: "源文件管理",
                        color = SourceBlue,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        Icon(
                            painter = painterResource(R.drawable.editor_menu_24),
                            contentDescription = "源文件菜单",
                            tint = SourceBlue,
                            modifier = Modifier.size(32.dp).clickable(enabled = !busy) { menuVisible = true }.padding(4.dp),
                        )
                        DropdownMenu(
                            expanded = menuVisible,
                            onDismissRequest = { menuVisible = false },
                            containerColor = Color.White,
                            shape = RoundedCornerShape(2.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建", fontSize = 14.sp) },
                                onClick = { beginName(SourceNameAction.NEW, nextSourceName(tree, currentGroup, "默认名称")) },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新", fontSize = 14.sp) },
                                onClick = { menuVisible = false; localNotice = "已刷新" },
                            )
                            DropdownMenuItem(
                                text = { Text("搜索", fontSize = 14.sp) },
                                onClick = { menuVisible = false; searchVisible = true },
                            )
                            DropdownMenuItem(
                                text = { Text("排序", fontSize = 14.sp) },
                                onClick = { menuVisible = false; sortMenuVisible = true },
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuVisible,
                            onDismissRequest = { sortMenuVisible = false },
                            containerColor = Color.White,
                            shape = RoundedCornerShape(2.dp),
                        ) {
                            SourceSortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text((if (sortMode == mode) "● " else "○ ") + mode.label, fontSize = 14.sp) },
                                    onClick = { sortMode = mode; sortMenuVisible = false },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text((if (reverseSort) "☑ " else "☐ ") + "倒序", fontSize = 14.sp) },
                                onClick = { reverseSort = !reverseSort; sortMenuVisible = false },
                            )
                        }
                    }
                }
                // line_tk_ywj_xz_cz_search
                if (searchVisible) {
                    SourceSearchBar(query, { query = it.take(60) }) {
                        query = ""
                        searchVisible = false
                    }
                }
                // line_tk_ywj_xz_cz_2：已选择N项 / 全选（常驻）
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已选择${selectedKeys.size}项", color = SourceBlue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("全选", color = SourceText, fontSize = 14.sp)
                    SourceCheckBox(
                        checked = allSelected,
                        enabled = selectableKeys.isNotEmpty(),
                        modifier = Modifier.padding(end = 9.dp),
                    ) {
                        selectedKeys = if (allSelected) selectedKeys - selectableKeys.toSet() else (selectedKeys + selectableKeys).distinct()
                    }
                }
                SourceDividerLine()
                if (rows.isEmpty() || rows.all { it is SourceRow.Up }) {
                    Box(Modifier.fillMaxWidth().weight(1f).padding(1.dp)) {
                        if (rows.isNotEmpty()) SourceUpRow { currentGroup = null }
                        Text(
                            if (query.isBlank()) "无内容" else "没有匹配的源文件",
                            color = SourceMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(1.dp)) {
                        items(rows, key = SourceRow::key) { row ->
                            when (row) {
                                is SourceRow.Up -> SourceUpRow { currentGroup = null }
                                is SourceRow.Group -> SourceGroupRow(
                                    name = row.name,
                                    count = row.count,
                                    checked = row.key in selectedKeys,
                                    onClick = { if (selectionMode) toggle(row.key) else currentGroup = row.name },
                                    onLongClick = { toggle(row.key) },
                                    onCheck = { toggle(row.key) },
                                )
                                is SourceRow.File -> SourceFileRow(
                                    entry = row.entry,
                                    timestamp = timestamp,
                                    active = row.entry.flowId == selectedFlowId && !selectionMode,
                                    checked = row.key in selectedKeys,
                                    onClick = { if (selectionMode) toggle(row.key) else selectedFlowId = row.entry.flowId },
                                    onLongClick = { toggle(row.key); selectedFlowId = row.entry.flowId },
                                    onCheck = { toggle(row.key) },
                                )
                            }
                        }
                    }
                }
                (localNotice ?: message)?.let {
                    Text(
                        it,
                        color = if (it == "已刷新") SourceBlue else AutoScriptPalette.Danger,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
                SourceDividerLine()
                if (selectionMode) {
                    val singleFile = selectedFlowIds.size == 1 && selectedGroups.isEmpty()
                    val singleGroup = selectedGroups.size == 1 && selectedFlowIds.isEmpty()
                    val groupedSelected = selectedFlowIds.any { tree.entry(it)?.group != null }
                    SourceSelectionActions(
                        canSaveAs = singleFile && !busy,
                        canRename = (singleFile || singleGroup) && !busy,
                        canDelete = !busy,
                        canGroup = selectedFlowIds.isNotEmpty() && selectedGroups.isEmpty() && !busy,
                        canUngroup = groupedSelected && !busy,
                        onSaveAs = {
                            val original = tree.entry(selectedFlowIds.single()) ?: return@SourceSelectionActions
                            beginName(SourceNameAction.SAVE_AS, nextSourceName(tree, null, original.name + "_副本"))
                        },
                        onRename = {
                            val suggested = if (singleFile) tree.entry(selectedFlowIds.single())?.name.orEmpty() else selectedGroups.single()
                            beginName(SourceNameAction.RENAME, suggested)
                        },
                        onCancel = ::clearSelection,
                        onDelete = { confirmDelete = true },
                        onGroup = { beginName(SourceNameAction.GROUP, currentGroup ?: nextSourceName(tree, null, "新建分组")) },
                        onUngroup = { dispatch(SourceManagerAction.RemoveFromGroup(selectedFlowIds)) },
                    )
                } else {
                    SourceNormalActions(
                        enabled = selectedFlowId != null && !busy,
                        onCancel = onDismiss,
                        onJoin = { selectedFlowId?.let { dispatch(SourceManagerAction.InsertCall(it)) } },
                        onConfirm = { selectedFlowId?.let { dispatch(SourceManagerAction.Open(it)) } },
                    )
                }
            }
        }
    }

    nameAction?.let { action ->
        val suggestions = if (action == SourceNameAction.GROUP) tree.groups.map { it.name } else emptyList()
        SourceNameDialog(
            title = action.title,
            value = nameValue,
            suggestions = suggestions,
            onValueChange = { nameValue = it.take(MAX_SOURCE_NAME_INPUT) },
            onDismiss = { nameAction = null },
            alternateLabel = if (action == SourceNameAction.NEW) "分组" else null,
            confirmLabel = if (action == SourceNameAction.NEW) "源文件" else "确定",
            onAlternate = if (action == SourceNameAction.NEW) {
                {
                    val cleanName = nameValue.trim()
                    when {
                        cleanName.isEmpty() -> localNotice = "名称不能为空"
                        tree.groups.any { it.name == cleanName } -> localNotice = "分组已存在"
                        else -> dispatch(SourceManagerAction.CreateGroup(cleanName))
                    }
                }
            } else null,
            onConfirm = {
                val cleanName = nameValue.trim()
                if (cleanName.isEmpty()) {
                    localNotice = "名称不能为空"
                    return@SourceNameDialog
                }
                val nameTaken = tree.entries.any { it.name == cleanName }
                when (action) {
                    SourceNameAction.NEW -> if (nameTaken) localNotice = "源文件已存在" else {
                        dispatch(SourceManagerAction.CreateFile(cleanName, currentGroup))
                    }
                    SourceNameAction.SAVE_AS -> if (nameTaken) localNotice = "源文件已存在" else {
                        dispatch(SourceManagerAction.SaveAs(selectedFlowIds.single(), cleanName))
                    }
                    SourceNameAction.RENAME -> when {
                        selectedGroups.size == 1 -> {
                            if (tree.groups.any { it.name == cleanName }) localNotice = "分组已存在"
                            else dispatch(SourceManagerAction.RenameGroup(selectedGroups.single(), cleanName))
                        }
                        nameTaken -> localNotice = "源文件已存在"
                        else -> dispatch(SourceManagerAction.RenameFile(selectedFlowIds.single(), cleanName))
                    }
                    SourceNameAction.GROUP -> dispatch(SourceManagerAction.AddToGroup(selectedFlowIds, cleanName))
                }
            },
        )
    }

    if (confirmDelete) {
        SourceConfirmDialog(
            text = "是否删除所选 ${selectedKeys.size} 项文件?",
            confirmLabel = "删除",
            onDismiss = { confirmDelete = false },
            onConfirm = { dispatch(SourceManagerAction.Delete(selectedFlowIds, selectedGroups)) },
        )
    }
}

@Composable
private fun SourceSearchBar(value: String, onValueChange: (String) -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = SourceText, fontSize = 12.sp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("关键字搜索", color = SourceMuted, fontSize = 12.sp)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 5.dp),
        )
        Text(
            "取消",
            color = SourceBlue,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxHeight().clickable(onClick = onCancel).padding(horizontal = 15.dp, vertical = 11.dp),
        )
    }
}

/** item_tree_list_file_3：返回上一级。 */
@Composable
private fun SourceUpRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(45.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.editor_folder_24),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.padding(horizontal = 5.dp).size(35.dp),
        )
        Text("..", color = SourceText, fontSize = 14.sp)
    }
}

/** item_tree_list_file_1：分组行（文件夹图标 + 名称 + 8sp 说明 + 复选框）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceGroupRow(
    name: String,
    count: Int,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheck: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(45.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.editor_folder_24),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.padding(horizontal = 5.dp).size(35.dp),
        )
        // 原来是 fillMaxHeight + SpaceBetween，把两行压进固定的 45dp 里，8sp 副标题的字形被齐根切掉。
        // 参考 item_tree_list_file_2.xml 的竖向 LinearLayout 是 wrap_content + layout_weight，
        // 语义是「自然高度再分剩余空间」，永远不会压到自然高度以下；这里改成让 Column 包裹内容、
        // 由外层 Row 的 CenterVertically 居中，并给 8sp 文本显式收紧行高。
        Column(Modifier.weight(1f).padding(top = 5.dp, bottom = 5.dp)) {
            Text(name, color = SourceText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 个源文件 · 文件夹", color = SourceMuted, fontSize = 8.sp, lineHeight = 10.sp)
        }
        SourceCheckBox(checked, modifier = Modifier.padding(start = 2.dp, end = 8.dp), onClick = onCheck)
    }
}

/** item_tree_list_file_2：文件行（图标 + 名称 + 8sp 修改时间与大小 + 复选框）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceFileRow(
    entry: SourceFileEntry,
    timestamp: SimpleDateFormat,
    active: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheck: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(45.dp)
            .background(if (active) SourceActive else Color.White)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 5.dp, top = 5.dp, bottom = 5.dp).size(35.dp).padding(1.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.editor_code_file_24), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
        }
        // 同 SourceFolderRow：固定高度 + SpaceBetween 会把 8sp 的时间与大小切掉下半截。
        Column(Modifier.weight(1f).padding(start = 3.dp, top = 4.dp, bottom = 5.dp)) {
            Text(
                if (entry.isEntry) "${entry.name}（入口）" else entry.name,
                color = SourceText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(timestamp.format(Date(entry.lastModified)), color = SourceMuted, fontSize = 8.sp, lineHeight = 10.sp)
                Text(
                    formatSourceSize(entry.sizeBytes),
                    color = SourceMuted,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        SourceCheckBox(checked, modifier = Modifier.padding(start = 2.dp, end = 8.dp), onClick = onCheck)
    }
}

@Composable
private fun SourceCheckBox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = SourceBlue, uncheckedColor = SourceMuted),
        modifier = modifier.size(32.dp),
    )
}

/** line_tk_ywj_xz_cz_3：取消 | 加入 | 确定。“加入”把所选源文件作为调用节点加入程序树。 */
@Composable
private fun SourceNormalActions(enabled: Boolean, onCancel: () -> Unit, onJoin: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        SourceTextAction("取消", true, onCancel, Modifier.weight(1f))
        SourceVerticalDivider()
        SourceTextAction("加入", enabled, onJoin, Modifier.weight(1f))
        SourceVerticalDivider()
        SourceTextAction("确定", enabled, onConfirm, Modifier.weight(1f), primary = true)
    }
}

/** line_tk_ywj_xz_cz_4：另存为 / 重命名 / 取消 / 删除 / 添加分组 / 移出分组。 */
@Composable
private fun SourceSelectionActions(
    canSaveAs: Boolean,
    canRename: Boolean,
    canDelete: Boolean,
    canGroup: Boolean,
    canUngroup: Boolean,
    onSaveAs: () -> Unit,
    onRename: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        SourceIconAction(R.drawable.visual_save_24, "另存为", canSaveAs, onSaveAs)
        SourceIconAction(R.drawable.editor_edit_24, "重命名", canRename, onRename)
        SourceIconAction(R.drawable.editor_close_24, "取消", true, onCancel)
        SourceIconAction(R.drawable.editor_delete_24, "删除", canDelete, onDelete)
        SourceIconAction(R.drawable.editor_folder_add_24, "添加分组", canGroup, onGroup)
        SourceIconAction(R.drawable.editor_file_move_24, "移出分组", canUngroup, onUngroup)
    }
}

@Composable
private fun SourceIconAction(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 15.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), label, tint = if (enabled) SourceBlue else SourceMuted, modifier = Modifier.size(20.dp))
        Text(label, color = if (enabled) SourceText else SourceMuted, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun SourceTextAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false,
) {
    Text(
        label,
        color = when { !enabled -> SourceMuted; primary -> SourceBlue; else -> SourceText },
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
private fun SourceVerticalDivider() {
    Box(Modifier.width(hairline()).fillMaxHeight().background(SourceDivider))
}

@Composable
private fun SourceDividerLine() {
    Box(Modifier.fillMaxWidth().height(hairline()).background(SourceDivider))
}

/** service_tk_new_file.xml：18sp 标题、15sp 输入框、左“取消”、右侧一或两个主按钮。 */
@Composable
private fun SourceNameDialog(
    title: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    alternateLabel: String?,
    confirmLabel: String,
    onAlternate: (() -> Unit)?,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(title, color = SourceText, fontSize = 18.sp, modifier = Modifier.padding(start = 25.dp, end = 25.dp))
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = TextStyle(color = SourceText, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 30.dp, top = 10.dp, end = 30.dp, bottom = 5.dp)
                            .height(42.dp)
                            .background(Color(0xFFF7F8FA), RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFFC8CED8), RoundedCornerShape(2.dp))
                            .padding(horizontal = 9.dp, vertical = 10.dp),
                    )
                    if (suggestions.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, bottom = 4.dp)) {
                            suggestions.take(4).forEach { suggestion ->
                                Text(
                                    suggestion,
                                    color = SourceBlue,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(end = 8.dp)
                                        .border(1.dp, Color(0xFFC8CED8), RoundedCornerShape(2.dp))
                                        .clickable { onValueChange(suggestion) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 15.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceNameActionText("取消", onDismiss)
                        Spacer(Modifier.weight(1f))
                        if (alternateLabel != null && onAlternate != null) {
                            SourceNameActionText(alternateLabel, onAlternate)
                        }
                        SourceNameActionText(confirmLabel, onConfirm, Modifier.padding(end = 15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceConfirmDialog(text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(text, color = SourceText, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 25.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 15.dp, top = 14.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceNameActionText("取消", onDismiss)
                        Spacer(Modifier.weight(1f))
                        SourceNameActionText(confirmLabel, onConfirm, Modifier.padding(end = 15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceNameActionText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        color = SourceAction,
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

private fun formatSourceSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
}

private fun nextSourceName(tree: SourceFileTree, group: String?, prefix: String): String {
    val names = tree.entries.map(SourceFileEntry::name).toSet() + tree.groups.map { it.name }
    if (prefix !in names && prefix.endsWith("_副本")) return prefix
    var index = 1
    while ("$prefix$index" in names) index++
    return "$prefix$index"
}
