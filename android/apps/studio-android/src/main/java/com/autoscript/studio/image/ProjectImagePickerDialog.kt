package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot

@Composable
internal fun ProjectImagePickerDialog(
    snapshot: ProjectSnapshot,
    paths: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    title: String = "选择模板",
) {
    var search by remember(snapshot.manifest.projectId) { mutableStateOf("") }
    val filtered = remember(paths, search) { paths.filter { it.contains(search, ignoreCase = true) } }
    CompositionLocalProvider(LocalImageWorkspace provides null) {
        ImageWorkspacePanel(title, onDismiss, compactHeader = true, showFooter = false, landscapeWidthDp = 440) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImageToolCompactInput("搜索", search, true, Modifier.fillMaxWidth(), dense = true) { search = it.take(128) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    if (filtered.isEmpty()) item(key = "empty") {
                        ImageWorkspaceHint(if (paths.isEmpty()) "暂无图片，请先截图并保存" else "没有匹配的图片")
                    }
                    items(filtered, key = { it }) { path ->
                        ProjectImageChoiceRow(snapshot, path, path == current, onSelect = { onSelect(path) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectImageChoiceRow(
    snapshot: ProjectSnapshot?,
    path: String,
    selected: Boolean,
    enabled: Boolean = true,
    multiple: Boolean = false,
    selectionLabel: String = "已选",
    onSelect: () -> Unit,
) {
    val action = if (multiple) Modifier.toggleable(selected, enabled, Role.Checkbox) { onSelect() }
        else Modifier.selectable(selected, enabled, Role.RadioButton, onSelect)
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .then(action).padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snapshot != null) ImageLibraryThumbnail(snapshot, path)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(path.substringAfterLast('/'), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val directory = path.removePrefix("assets/images/").substringBeforeLast('/', "")
            if (directory.isNotEmpty()) Text(directory, fontSize = 10.sp, lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected || multiple) Text(if (selected) selectionLabel else "○", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.widthIn(min = 24.dp))
    }
}
