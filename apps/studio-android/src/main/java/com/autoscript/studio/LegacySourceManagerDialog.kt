package com.autoscript.studio

import androidx.activity.compose.BackHandler
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

private const val SOURCE_FOLDER_SUFFIX = "/"
private val SourceBlue = Color(0xFF2864F0)
private val SourceText = Color(0xFF161C2B)
private val SourceMuted = Color(0xFF9CA5B5)
private val SourceDivider = Color(0xFFE3E7EF)

private enum class SourceNameAction(val title: String) {
    NEW_FILE("创建"),
    SAVE_AS("另存为"),
    RENAME("重命名"),
    GROUP("添加分组"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LegacySourceManagerDialog(
    entries: List<String>,
    currentSource: String?,
    onEntriesChanged: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onConfirmSource: (path: String, newlyCreated: Boolean) -> Unit,
) {
    var currentFolder by rememberSaveable { mutableStateOf("") }
    var selectedPath by rememberSaveable(currentSource) { mutableStateOf(currentSource) }
    var selectedPaths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reverseSort by rememberSaveable { mutableStateOf(false) }
    var nameAction by rememberSaveable { mutableStateOf<SourceNameAction?>(null) }
    var nameValue by rememberSaveable { mutableStateOf("") }
    var newFiles by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionMode = selectedPaths.isNotEmpty()
    val shownEntries = remember(entries, currentFolder, query, reverseSort) {
        entries.asSequence()
            .filter { sourceParent(it) == currentFolder.trimEnd('/') }
            .filter { query.isBlank() || sourceDisplayName(it).contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy<String> { !it.isSourceFolder() }.thenBy(String.CASE_INSENSITIVE_ORDER) { sourceDisplayName(it) })
            .let { if (reverseSort) it.toList().asReversed() else it.toList() }
    }
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) }

    fun beginNameAction(action: SourceNameAction, suggested: String) {
        menuVisible = false
        nameValue = suggested
        nameAction = action
    }

    fun replaceEntries(updated: List<String>) {
        onEntriesChanged(updated.distinct().sorted())
    }

    fun leaveSelectionMode() {
        selectedPaths = emptyList()
        notice = null
    }

    BackHandler {
        when {
            nameAction != null -> nameAction = null
            selectionMode -> leaveSelectionMode()
            searchVisible -> { searchVisible = false; query = "" }
            currentFolder.isNotEmpty() -> currentFolder = sourceParentFolder(currentFolder)
            else -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.90f).widthIn(max = 520.dp),
            color = Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (selectionMode) {
                    SourceSelectionHeader(
                        selectedCount = selectedPaths.size,
                        allSelected = shownEntries.isNotEmpty() && shownEntries.all(selectedPaths::contains),
                        onSelectAll = {
                            selectedPaths = if (shownEntries.isNotEmpty() && shownEntries.all(selectedPaths::contains)) {
                                selectedPaths - shownEntries.toSet()
                            } else {
                                (selectedPaths + shownEntries).distinct()
                            }
                        },
                    )
                } else {
                    SourceManagerHeader(
                        currentFolder = currentFolder,
                        menuVisible = menuVisible,
                        onMenuVisibleChanged = { menuVisible = it },
                        onBackFolder = { currentFolder = sourceParentFolder(currentFolder) },
                        onNewFile = {
                            beginNameAction(SourceNameAction.NEW_FILE, nextSourceName(entries, currentFolder, "默认名称"))
                        },
                        onRefresh = { notice = "已刷新" },
                        onSearch = { menuVisible = false; searchVisible = true },
                        onSort = { menuVisible = false; reverseSort = !reverseSort },
                    )
                }
                if (searchVisible && !selectionMode) {
                    SourceSearchBar(query, { query = it.take(60) }) {
                        query = ""
                        searchVisible = false
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SourceDivider))
                if (shownEntries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        Text(
                            if (query.isBlank()) "暂无源文件，点击右上角新建" else "没有匹配的源文件",
                            color = SourceMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 38.dp),
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(shownEntries, key = { it }) { path ->
                            SourceEntryRow(
                                path = path,
                                timestamp = timestamp,
                                checked = path in selectedPaths,
                                active = path == selectedPath,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                                    } else if (path.isSourceFolder()) {
                                        currentFolder = path
                                        selectedPath = null
                                    } else {
                                        selectedPath = path
                                    }
                                },
                                onLongClick = {
                                    selectedPaths = (selectedPaths + path).distinct()
                                    selectedPath = path.takeUnless { it.isSourceFolder() }
                                },
                            )
                        }
                    }
                }
                notice?.let {
                    Text(it, color = SourceBlue, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SourceDivider))
                if (selectionMode) {
                    SourceSelectionActions(
                        canSaveAs = selectedPaths.size == 1 && !selectedPaths.first().isSourceFolder(),
                        canRename = selectedPaths.size == 1,
                        canGroup = selectedPaths.size > 1 && selectedPaths.none { it.isSourceFolder() },
                        onSaveAs = {
                            val original = selectedPaths.single()
                            beginNameAction(SourceNameAction.SAVE_AS, sourceDisplayName(original) + "_副本")
                        },
                        onRename = {
                            beginNameAction(SourceNameAction.RENAME, sourceDisplayName(selectedPaths.single()))
                        },
                        onCancel = ::leaveSelectionMode,
                        onDelete = {
                            val deleted = selectedPaths.toSet()
                            replaceEntries(entries.filterNot { candidate ->
                                deleted.any { target -> candidate == target || (target.isSourceFolder() && candidate.startsWith(target)) }
                            })
                            if (selectedPath?.let { active -> deleted.any { active == it || active.startsWith(it) } } == true) {
                                selectedPath = null
                            }
                            leaveSelectionMode()
                        },
                        onGroup = {
                            beginNameAction(SourceNameAction.GROUP, nextSourceName(entries, currentFolder, "新建分组"))
                        },
                    )
                } else {
                    SourceNormalActions(
                        enabled = selectedPath != null,
                        onCancel = onDismiss,
                        onJoin = { selectedPath?.let { notice = "已加入 ${sourceDisplayName(it)}" } },
                        onConfirm = {
                            selectedPath?.let { onConfirmSource(it, it in newFiles) }
                        },
                    )
                }
            }
        }
    }

    nameAction?.let { action ->
        SourceNameDialog(
            title = action.title,
            value = nameValue,
            onValueChange = { nameValue = it.take(40) },
            onDismiss = { nameAction = null },
            alternateLabel = if (action == SourceNameAction.NEW_FILE) "分组" else null,
            confirmLabel = if (action == SourceNameAction.NEW_FILE) "源文件" else "确定",
            onAlternate = if (action == SourceNameAction.NEW_FILE) {
                {
                    val cleanName = sourceCleanName(nameValue)
                    val folderName = if (cleanName.startsWith("默认名称")) {
                        nextSourceName(entries, currentFolder, "新建文件夹")
                    } else cleanName
                    val path = currentFolder + folderName + SOURCE_FOLDER_SUFFIX
                    when {
                        folderName.isBlank() -> notice = "名称不能为空"
                        path in entries || path.dropLast(1) in entries -> notice = "名称已存在"
                        else -> {
                            replaceEntries(entries + path)
                            notice = "已新建文件夹 $folderName"
                            nameAction = null
                        }
                    }
                }
            } else null,
            onConfirm = {
                val cleanName = sourceCleanName(nameValue)
                if (cleanName.isBlank()) {
                    notice = "名称不能为空"
                } else {
                    when (action) {
                        SourceNameAction.NEW_FILE -> {
                            val path = currentFolder + cleanName
                            if (path in entries || "$path/" in entries) notice = "名称已存在" else {
                                replaceEntries(entries + path)
                                selectedPath = path
                                newFiles = (newFiles + path).distinct()
                                notice = "已新建 $cleanName"
                            }
                        }
                        SourceNameAction.SAVE_AS -> {
                            val original = selectedPaths.single()
                            val path = currentFolder + cleanName
                            if (path in entries || "$path/" in entries) notice = "名称已存在" else {
                                replaceEntries(entries + path)
                                newFiles = (newFiles + path).distinct()
                                selectedPath = path
                                leaveSelectionMode()
                                notice = "已另存为 $cleanName"
                            }
                        }
                        SourceNameAction.RENAME -> {
                            val original = selectedPaths.single()
                            val renamed = sourceParentPrefix(original) + cleanName + if (original.isSourceFolder()) "/" else ""
                            if (renamed != original && (renamed in entries || renamed.removeSuffix("/") in entries)) {
                                notice = "名称已存在"
                            } else {
                                replaceEntries(entries.map { candidate ->
                                    when {
                                        candidate == original -> renamed
                                        original.isSourceFolder() && candidate.startsWith(original) -> renamed + candidate.removePrefix(original)
                                        else -> candidate
                                    }
                                })
                                if (selectedPath == original) selectedPath = renamed
                                newFiles = newFiles.map { if (it == original) renamed else it }
                                leaveSelectionMode()
                                notice = "已重命名为 $cleanName"
                            }
                        }
                        SourceNameAction.GROUP -> {
                            val folder = currentFolder + cleanName + SOURCE_FOLDER_SUFFIX
                            if (folder in entries || folder.dropLast(1) in entries) notice = "名称已存在" else {
                                val moving = selectedPaths.toSet()
                                replaceEntries(entries.map { candidate ->
                                    if (candidate in moving) folder + sourceDisplayName(candidate) else candidate
                                } + folder)
                                selectedPath = selectedPath?.let { active ->
                                    if (active in moving) folder + sourceDisplayName(active) else active
                                }
                                leaveSelectionMode()
                                notice = "已添加到分组 $cleanName"
                            }
                        }
                    }
                    if (notice != "名称不能为空" && notice != "名称已存在") nameAction = null
                }
            },
        )
    }
}

@Composable
private fun SourceManagerHeader(
    currentFolder: String,
    menuVisible: Boolean,
    onMenuVisibleChanged: (Boolean) -> Unit,
    onBackFolder: () -> Unit,
    onNewFile: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onSort: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(start = 15.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (currentFolder.isEmpty()) "源文件管理" else "‹  ${sourceDisplayName(currentFolder)}",
            color = SourceBlue,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(enabled = currentFolder.isNotEmpty(), onClick = onBackFolder),
        )
        Box {
            Icon(
                painter = painterResource(R.drawable.editor_menu_24),
                contentDescription = "源文件菜单",
                tint = SourceBlue,
                modifier = Modifier.size(34.dp).clickable { onMenuVisibleChanged(true) }.padding(5.dp),
            )
            DropdownMenu(
                expanded = menuVisible,
                onDismissRequest = { onMenuVisibleChanged(false) },
                containerColor = Color.White,
                shape = RoundedCornerShape(2.dp),
            ) {
                DropdownMenuItem(text = { Text("新建", fontSize = 14.sp) }, onClick = onNewFile)
                DropdownMenuItem(text = { Text("刷新", fontSize = 14.sp) }, onClick = onRefresh)
                DropdownMenuItem(text = { Text("搜索", fontSize = 14.sp) }, onClick = onSearch)
                DropdownMenuItem(text = { Text("排序", fontSize = 14.sp) }, onClick = onSort)
            }
        }
    }
}

@Composable
private fun SourceSelectionHeader(selectedCount: Int, allSelected: Boolean, onSelectAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(start = 15.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选择${selectedCount}项", color = SourceBlue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("全选", color = SourceText, fontSize = 14.sp)
        SourceCheckBox(allSelected, Modifier.padding(start = 9.dp), onSelectAll)
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
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text("关键字搜索", color = SourceMuted, fontSize = 12.sp)
                inner()
            } },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Text("取消", color = SourceBlue, fontSize = 13.sp, modifier = Modifier.fillMaxHeight().clickable(onClick = onCancel).padding(horizontal = 15.dp, vertical = 11.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceEntryRow(
    path: String,
    timestamp: String,
    checked: Boolean,
    active: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (active && !selectionMode) Color(0xFFF6F8FF) else Color.White)
            .padding(start = 15.dp, end = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                (if (path.isSourceFolder()) "▸  " else "") + sourceDisplayName(path),
                color = SourceText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (path.isSourceFolder()) "$timestamp    文件夹" else "$timestamp    0B",
                color = SourceMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (selectionMode) SourceCheckBox(checked, onClick = onClick)
    }
}

@Composable
private fun SourceCheckBox(checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(24.dp).border(2.dp, SourceBlue, RoundedCornerShape(2.dp))
            .background(if (checked) SourceBlue else Color.White, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SourceNormalActions(enabled: Boolean, onCancel: () -> Unit, onJoin: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(45.dp)) {
        SourceTextAction("取消", true, onCancel, Modifier.weight(1f))
        SourceVerticalDivider()
        SourceTextAction("加入", enabled, onJoin, Modifier.weight(1f))
        SourceVerticalDivider()
        SourceTextAction("确定", enabled, onConfirm, Modifier.weight(1f), primary = true)
    }
}

@Composable
private fun SourceSelectionActions(
    canSaveAs: Boolean,
    canRename: Boolean,
    canGroup: Boolean,
    onSaveAs: () -> Unit,
    onRename: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onGroup: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        SourceIconAction(R.drawable.visual_save_24, "另存为", canSaveAs, onSaveAs)
        SourceIconAction(R.drawable.editor_edit_24, "重命名", canRename, onRename)
        SourceIconAction(R.drawable.editor_close_24, "取消", true, onCancel)
        SourceIconAction(R.drawable.editor_delete_24, "删除", true, onDelete)
        SourceIconAction(R.drawable.editor_folder_add_24, "添加分组", canGroup, onGroup)
    }
}

@Composable
private fun SourceIconAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(62.dp).fillMaxHeight().clickable(enabled = enabled, onClick = onClick).padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), label, tint = if (enabled) SourceBlue else SourceMuted, modifier = Modifier.size(24.dp))
        Text(label, color = if (enabled) SourceText else SourceMuted, fontSize = 9.sp, maxLines = 1)
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
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
private fun SourceVerticalDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(SourceDivider))
}

@Composable
private fun SourceNameDialog(
    title: String,
    value: String,
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
                    Text(
                        title,
                        color = SourceText,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 25.dp, end = 25.dp),
                    )
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
private fun SourceNameActionText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Color(0xFF3F51B5),
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

private fun String.isSourceFolder(): Boolean = endsWith(SOURCE_FOLDER_SUFFIX)

private fun sourceDisplayName(path: String): String = path.trimEnd('/').substringAfterLast('/')

private fun sourceParent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

private fun sourceParentPrefix(path: String): String = sourceParent(path).let { if (it.isEmpty()) "" else "$it/" }

private fun sourceParentFolder(folder: String): String = sourceParent(folder).let { if (it.isEmpty()) "" else "$it/" }

private fun sourceCleanName(value: String): String = value.trim().replace('/', '_').replace('\\', '_')

private fun nextSourceName(entries: List<String>, folder: String, prefix: String): String {
    var index = 1
    val names = entries.filter { sourceParent(it) == folder.trimEnd('/') }.map(::sourceDisplayName).toSet()
    while ("$prefix$index" in names) index++
    return "$prefix$index"
}
