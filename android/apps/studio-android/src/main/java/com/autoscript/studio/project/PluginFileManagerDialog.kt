package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI commands; writes still use ProjectSourceFiles and ProjectStore transactions. */
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
    data class SetEntry(val flowId: String) : SourceManagerAction
    data object Refresh : SourceManagerAction
}

private enum class PluginNameAction(val title: String) {
    NEW_FILE("新建插件"), NEW_GROUP("新建分组"), SAVE_AS("插件另存为"),
    RENAME("重命名"), JOIN_GROUP("加入分组"),
}

private enum class PluginSort(val label: String) { NAME("名称"), TIME("修改时间"), SIZE("大小") }

private sealed interface PluginRow {
    val key: String
    data object Up : PluginRow { override val key = ".." }
    data class Group(val name: String, val count: Int) : PluginRow { override val key = "g:" + name }
    data class File(val entry: SourceFileEntry) : PluginRow { override val key = "f:" + entry.flowId }
}

/** Click opens, checkbox/long press manages; filtering and sorting never change target identities. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginFileManagerDialog(
    tree: SourceFileTree,
    currentFlowId: String?,
    busy: Boolean,
    message: String?,
    onAction: (SourceManagerAction) -> Unit,
    onDismiss: () -> Unit,
    onManagePlugins: () -> Unit = {},
    createPluginOnOpen: Boolean = false,
    onCreatePluginRequestHandled: () -> Unit = {},
    selectCurrentPluginOnOpen: Boolean = false,
    onSelectCurrentPluginRequestHandled: () -> Unit = {},
    title: String = "项目插件",
    projectName: String = "",
) {
    var group by rememberSaveable { mutableStateOf(tree.entry(currentFlowId.orEmpty())?.group) }
    var focusedFlowId by rememberSaveable { mutableStateOf(currentFlowId) }
    var selectedKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(PluginSort.NAME) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var nameAction by rememberSaveable { mutableStateOf<PluginNameAction?>(null) }
    var nameValue by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    var nameTargets by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var nameGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteKeys by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var entryTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val fontScale = LocalDensity.current.fontScale
    // The ordinary window stays stable while selecting/searching; only its inner list resizes.
    val windowHeight = rememberSaveable {
        ((tree.entries.size + tree.groups.size).coerceIn(4, 8) * 38 * fontScale + 146 * fontScale).coerceIn(280f, 460f)
    }.dp
    val timestamp = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val selection = pluginFileSelection(tree, selectedKeys)
    val selectionMode = selectedKeys.isNotEmpty()

    LaunchedEffect(tree) {
        val valid = tree.entries.map { "f:" + it.flowId }.toSet() + tree.groups.map { "g:" + it.name }
        selectedKeys = selectedKeys.filter { it in valid }
        if (tree.entry(focusedFlowId.orEmpty()) == null) focusedFlowId = currentFlowId
        if (group != null && tree.groups.none { it.name == group }) group = null
    }
    LaunchedEffect(currentFlowId) {
        if (!selectionMode && tree.entry(currentFlowId.orEmpty()) != null) {
            focusedFlowId = currentFlowId
            group = tree.entry(currentFlowId.orEmpty())?.group
        }
    }
    fun beginName(action: PluginNameAction, suggested: String) {
        if (busy) return
        nameTargets = selectedKeys.toList()
        nameGroup = group
        nameError = null
        nameValue = suggested
        nameAction = action
        notice = null
    }
    LaunchedEffect(createPluginOnOpen) {
        if (createPluginOnOpen) {
            beginName(PluginNameAction.NEW_FILE, nextPluginFileName(tree, "新建插件"))
            onCreatePluginRequestHandled()
        }
    }
    LaunchedEffect(selectCurrentPluginOnOpen, currentFlowId) {
        if (selectCurrentPluginOnOpen) {
            tree.entry(currentFlowId.orEmpty())?.let {
                group = it.group
                focusedFlowId = it.flowId
                selectedKeys = listOf("f:" + it.flowId)
            }
            onSelectCurrentPluginRequestHandled()
        }
    }

    val rows = remember(tree, group, query, sort, descending) {
        val needle = query.trim()
        val groups = if (group == null) tree.groups.filter { needle.isEmpty() || it.name.contains(needle, true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .let { if (descending) it.asReversed() else it } else emptyList()
        val comparator = when (sort) {
            PluginSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { file: SourceFileEntry -> file.name }
            PluginSort.TIME -> compareBy<SourceFileEntry> { it.lastModified }
            PluginSort.SIZE -> compareBy<SourceFileEntry> { it.sizeBytes }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        val files = (if (group == null && needle.isNotEmpty()) tree.entries else tree.entriesIn(group))
            .filter { needle.isEmpty() || it.name.contains(needle, true) }
            .sortedWith(comparator).let { if (descending) it.asReversed() else it }
        buildList {
            if (group != null) add(PluginRow.Up)
            addAll(groups.map { PluginRow.Group(it.name, tree.entriesIn(it.name).size) })
            addAll(files.map(PluginRow::File))
        }
    }
    val visibleKeys = rows.filterNot { it is PluginRow.Up }.map { it.key }
    val allVisibleSelected = visibleKeys.isNotEmpty() && visibleKeys.all { it in selectedKeys }

    fun toggle(key: String) {
        if (busy) return
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        if (key.startsWith("f:")) focusedFlowId = key.removePrefix("f:")
    }
    fun dispatch(action: SourceManagerAction) {
        if (busy) return
        nameAction = null
        nameError = null
        deleteKeys = null
        entryTarget = null
        notice = null
        if (action is SourceManagerAction.Open || action is SourceManagerAction.InsertCall || action is SourceManagerAction.Delete) selectedKeys = emptyList()
        onAction(action)
    }
    BackHandler {
        when {
            nameAction != null -> if (!busy) nameAction = null
            deleteKeys != null -> deleteKeys = null
            entryTarget != null -> entryTarget = null
            selectionMode -> selectedKeys = emptyList()
            query.isNotEmpty() -> query = ""
            group != null -> group = null
            else -> onDismiss()
        }
    }

    StudioPanelDialog(title = title, onDismiss = onDismiss, compactHeader = true, showFooter = false,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bodyHeight = studioPanelViewportHeight(maxHeight.value, windowHeight.value.toInt(), fullScreen).dp
            val footerLimit = studioToolbarBudget(bodyHeight.value, 2).dp
            val toolbarLimit = studioToolbarBudget(bodyHeight.value, if (selectionMode) 2 else 1).dp
            Column(Modifier.fillMaxWidth().height(bodyHeight)) {
                Column(Modifier.fillMaxWidth().heightIn(max = toolbarLimit).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(projectName.ifBlank { "当前项目" } + (group?.let { " / " + it } ?: ""), fontSize = 11.sp,
                        color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("共 " + tree.entries.size + " 个插件", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                }
                if (!selectionMode) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 3.dp)) {
                        PluginFileAction("新建插件", { beginName(PluginNameAction.NEW_FILE, nextPluginFileName(tree, "新建插件")) }, !busy, icon = R.drawable.studio_add_24)
                        PluginFileAction("新建分组", { beginName(PluginNameAction.NEW_GROUP, nextPluginFileName(tree, "新建分组")) }, !busy, icon = R.drawable.editor_folder_add_24)
                        PluginFileAction("插件管理", onManagePlugins, !busy)
                        PluginFileAction("刷新", { dispatch(SourceManagerAction.Refresh) }, !busy)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    PluginFileInput(query, { query = it.take(60) }, "搜索插件",
                        if (group == null) "搜索全部插件 / 分组" else "搜索当前分组", !busy, modifier = Modifier.weight(1f))
                    if (query.isNotEmpty()) PluginFileAction("清空", { query = "" }, !busy)
                    Box {
                        PluginFileAction(sort.label + if (descending) "↓" else "↑", { sortMenu = true }, !busy)
                        DropdownMenu(sortMenu, { sortMenu = false }, containerColor = Color.White) {
                            PluginSort.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label, fontSize = 12.sp) },
                                onClick = { sort = mode; sortMenu = false }) }
                            DropdownMenuItem(text = { Text(if (descending) "改为升序" else "改为降序", fontSize = 12.sp) },
                                onClick = { descending = !descending; sortMenu = false })
                        }
                    }
                }
                }
                if (selectionMode) {
                    Row(Modifier.fillMaxWidth().background(AutoScriptPalette.AccentSoft).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 " + selectedKeys.size + " 项", fontSize = 11.sp, color = AutoScriptPalette.Accent, modifier = Modifier.weight(1f))
                        PluginFileAction(if (allVisibleSelected) "取消全选" else "全选可见", {
                            selectedKeys = pluginFileSelectedKeys(visibleKeys, selectedKeys, !allVisibleSelected)
                        }, !busy && visibleKeys.isNotEmpty())
                        PluginFileAction("取消选择", { selectedKeys = emptyList() }, !busy)
                    }
                }
                HorizontalDivider(color = AutoScriptPalette.Border)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Top) {
                    items(rows, key = PluginRow::key) { row ->
                        when (row) {
                            PluginRow.Up -> PluginFileAction("返回全部插件", { group = null; query = "" }, !busy,
                                icon = R.drawable.editor_folder_24, modifier = Modifier.fillMaxWidth())
                            is PluginRow.Group -> PluginBrowserRow(row.name, row.count.toString() + " 个插件 · 分组", R.drawable.editor_folder_24,
                                row.key in selectedKeys, active = false, enabled = !busy, checkLabel = "选择分组 " + row.name,
                                onClick = { if (selectionMode) toggle(row.key) else { group = row.name; query = "" } },
                                onLongClick = { toggle(row.key) }, onCheck = { toggle(row.key) })
                            is PluginRow.File -> PluginBrowserRow(row.entry.name + if (row.entry.isEntry) "（主插件）" else "",
                                listOf(timestamp.format(Date(row.entry.lastModified)), formatPluginSize(row.entry.sizeBytes),
                                    row.entry.group?.takeIf { group == null && query.isNotBlank() }).filterNotNull().joinToString(" · "),
                                R.drawable.editor_code_file_24, row.key in selectedKeys,
                                active = row.entry.flowId == focusedFlowId && !selectionMode, enabled = !busy, checkLabel = "选择插件 " + row.entry.name,
                                onClick = { if (selectionMode) toggle(row.key) else dispatch(SourceManagerAction.Open(row.entry.flowId)) },
                                onLongClick = { toggle(row.key) }, onCheck = { toggle(row.key) })
                        }
                    }
                    if (rows.none { it !is PluginRow.Up }) item {
                        Text(if (query.isBlank()) "暂无插件，点击上方新建" else "没有匹配项", fontSize = 11.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(14.dp))
                    }
                }
                (notice ?: message)?.let {
                    Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
                }
                HorizontalDivider(color = AutoScriptPalette.Border)
                if (selectionMode) {
                    Column(Modifier.fillMaxWidth().heightIn(max = footerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 3.dp)) {
                        FlowRow(Modifier.fillMaxWidth()) {
                            val file = selection.singleFile
                            PluginFileAction("打开", { file?.let { dispatch(SourceManagerAction.Open(it.flowId)) } }, file != null && !busy)
                            PluginFileAction("插入调用", { file?.let { dispatch(SourceManagerAction.InsertCall(it.flowId)) } }, file != null && file.flowId != currentFlowId && !busy)
                            PluginFileAction("另存为", { file?.let { beginName(PluginNameAction.SAVE_AS, nextPluginFileName(tree, it.name + "_副本")) } }, file != null && !busy)
                            PluginFileAction("重命名", { beginName(PluginNameAction.RENAME, file?.name ?: selection.singleGroup.orEmpty()) },
                                (file != null || selection.singleGroup != null) && !busy)
                            PluginFileAction("加入分组", { beginName(PluginNameAction.JOIN_GROUP, group.orEmpty()) }, selection.canGroup && !busy)
                            PluginFileAction("移出分组", { dispatch(SourceManagerAction.RemoveFromGroup(selection.groupedFlowIds)) }, selection.groupedFlowIds.isNotEmpty() && !busy)
                            PluginFileAction("删除", { deleteKeys = selectedKeys.toList() }, selection.canDelete && !busy, danger = true)
                        }
                        selection.singleFile?.let { file ->
                            Row(Modifier.fillMaxWidth().padding(start = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(file.name + " · 局部变量 " + file.localVariableCount, fontSize = 10.sp,
                                    color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (file.isEntry) Text("当前主插件", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(7.dp))
                                else PluginFileAction("设为主插件", { entryTarget = file.flowId }, !busy)
                            }
                        }
                        if (selection.files.any { it.isEntry }) Text("主插件不可删除；其他操作仍可用", fontSize = 10.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(start = 7.dp, top = 2.dp))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("点击打开 · 勾选或长按管理", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp))
                        PluginFileAction("关闭", onDismiss, true)
                    }
                }
            }
        }
    }

    nameAction?.let { action ->
        val target = pluginFileSelection(tree, nameTargets)
        fun confirmName() {
            if (busy) return
            val isGroup = action in setOf(PluginNameAction.NEW_GROUP, PluginNameAction.JOIN_GROUP) ||
                action == PluginNameAction.RENAME && target.singleGroup != null
            val original = if (action == PluginNameAction.RENAME) target.singleFile?.name ?: target.singleGroup else null
            nameError = pluginFileNameError(nameValue, if (isGroup) tree.groups.map { it.name } else tree.entries.map { it.name },
                original = original, allowExisting = action == PluginNameAction.JOIN_GROUP)
            if (nameError != null) return
            val clean = nameValue.trim()
            val validTarget = when (action) {
                PluginNameAction.SAVE_AS -> target.singleFile != null
                PluginNameAction.RENAME -> target.singleFile != null || target.singleGroup != null
                PluginNameAction.JOIN_GROUP -> target.canGroup && target.files.size == nameTargets.size
                PluginNameAction.NEW_FILE -> nameGroup == null || tree.groups.any { it.name == nameGroup }
                PluginNameAction.NEW_GROUP -> true
            }
            if (!validTarget) { nameError = "目标已变化，请关闭后重新选择"; return }
            if (action == PluginNameAction.RENAME && clean == original) { nameAction = null; return }
            val command = when (action) {
                PluginNameAction.NEW_FILE -> SourceManagerAction.CreateFile(clean, nameGroup)
                PluginNameAction.NEW_GROUP -> SourceManagerAction.CreateGroup(clean)
                PluginNameAction.SAVE_AS -> SourceManagerAction.SaveAs(requireNotNull(target.singleFile).flowId, clean)
                PluginNameAction.RENAME -> target.singleFile?.let { SourceManagerAction.RenameFile(it.flowId, clean) }
                    ?: SourceManagerAction.RenameGroup(requireNotNull(target.singleGroup), clean)
                PluginNameAction.JOIN_GROUP -> SourceManagerAction.AddToGroup(target.files.map { it.flowId }, clean)
            }
            dispatch(command)
        }
        PluginFileNameDialog(action.title, nameValue,
            if (action == PluginNameAction.JOIN_GROUP) tree.groups.map { it.name } else emptyList(), nameError, busy,
            if (action in setOf(PluginNameAction.NEW_FILE, PluginNameAction.NEW_GROUP)) "创建" else if (action == PluginNameAction.JOIN_GROUP) "加入" else "确定",
            onValueChange = { nameValue = it.take(256); nameError = null }, onDismiss = { nameAction = null }, onConfirm = ::confirmName)
    }
    deleteKeys?.let { frozen ->
        val target = pluginFileSelection(tree, frozen)
        EditorPromptDialog("删除所选项目",
            "删除 " + target.files.size + " 个插件，解散 " + target.groups.size + " 个分组？" +
                "\n删除插件会清理局部变量；被调用或被界面绑定的插件不能删除。解散分组仅移除分类，不删除其中插件。",
            onDismiss = { deleteKeys = null }, onConfirm = {
                if (!busy && target.canDelete && target.files.size + target.groups.size == frozen.size) {
                    dispatch(SourceManagerAction.Delete(target.files.map { it.flowId }, target.groups))
                } else if (!busy) { deleteKeys = null; notice = "所选目标已变化，请重新选择" }
            }, confirmLabel = "删除", confirmDanger = true)
    }
    entryTarget?.let { id ->
        val target = tree.entry(id)
        EditorPromptDialog("设为主插件", "将“" + target?.name.orEmpty() + "”设为项目运行入口？原主插件仍保留。",
            onDismiss = { entryTarget = null }, onConfirm = {
                if (!busy && target != null && !target.isEntry) dispatch(SourceManagerAction.SetEntry(id))
                else if (!busy) { entryTarget = null; notice = "目标已变化，请重新选择" }
            }, confirmLabel = "设为主插件")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginBrowserRow(title: String, detail: String, icon: Int, checked: Boolean, active: Boolean, enabled: Boolean,
    checkLabel: String, onClick: () -> Unit, onLongClick: () -> Unit, onCheck: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp)
        .background(if (checked) AutoScriptPalette.AccentSoft else if (active) AutoScriptPalette.PageBackground else Color.White)
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick).padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, tint = AutoScriptPalette.Accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, lineHeight = 16.sp, color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 9.sp, lineHeight = 12.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
            Checkbox(checked, { onCheck() }, enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = AutoScriptPalette.Accent),
                modifier = Modifier.size(28.dp).padding(end = 2.dp).semantics { contentDescription = checkLabel })
        }
    }
}

private fun formatPluginSize(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + "B"
    bytes < 1024 * 1024 -> (bytes / 1024).toString() + "KB"
    else -> "%.1fMB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
}
