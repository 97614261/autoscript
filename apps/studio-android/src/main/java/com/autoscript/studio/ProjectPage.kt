package com.autoscript.studio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectPage(
    store: ProjectStore,
    runtimeClient: RuntimeClient,
    runtimeState: RuntimeConnectionState,
    active: Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var opened by remember { mutableStateOf<ProjectSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    val scope = rememberCoroutineScope()

    fun runStoreAction(action: () -> ProjectSnapshot?) {
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = action()
                    snapshot to store.listProjects()
                }
            }.onSuccess { (snapshot, refreshed) ->
                if (snapshot != null) opened = snapshot
                projects = refreshed
            }.onFailure { failure ->
                error = failure.message ?: "项目操作失败"
            }
            loading = false
        }
    }

    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.listProjects() } }
            .onSuccess { projects = it }
            .onFailure { error = it.message ?: "读取项目失败" }
        loading = false
    }

    val luaProject = opened?.takeIf { it.manifest.sourceMode == ProjectSourceMode.LUA }
    if (luaProject != null) {
        LuaEditorScreen(
            snapshot = luaProject,
            store = store,
            runtimeClient = runtimeClient,
            runtimeState = runtimeState,
            active = active,
            modifier = modifier,
            onSnapshotChanged = { opened = it },
            onExit = {
                opened = null
                runStoreAction { null }
            },
        )
        return
    }

    val visualProject = opened?.takeIf { it.manifest.sourceMode == ProjectSourceMode.VISUAL }
    if (visualProject != null) {
        VisualProjectScreen(
            snapshot = visualProject,
            store = store,
            runtimeClient = runtimeClient,
            runtimeState = runtimeState,
            active = active,
            modifier = modifier,
            onSnapshotChanged = { opened = it },
            onExit = {
                opened = null
                runStoreAction { null }
            },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("本地项目", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (loading) "读取中…" else "${projects.size} 个项目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { showCreate = true }, enabled = !loading) { Text("新建项目") }
            }
        }

        error?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }

        opened?.let { snapshot ->
            item {
                OpenedProjectCard(snapshot = snapshot, onClose = { opened = null })
            }
        }

        if (!loading && projects.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("还没有项目", style = MaterialTheme.typography.titleSmall)
                        Text("可新建手写 Lua 或可视化积木项目。")
                    }
                }
            }
        }

        items(projects, key = ProjectSummary::projectId) { project ->
            ProjectRow(
                project = project,
                selected = opened?.manifest?.projectId == project.projectId,
                enabled = !loading,
                onOpen = { runStoreAction { store.openProject(project.projectId) } },
                onRename = { renameTarget = project },
                onDelete = { deleteTarget = project },
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 3.dp)) }
        item { footer() }
    }

    if (showCreate) {
        ProjectEditorDialog(
            title = "新建项目",
            initialName = "",
            allowModeSelection = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, mode ->
                showCreate = false
                runStoreAction { store.createProject(name, mode) }
            },
        )
    }
    renameTarget?.let { project ->
        ProjectEditorDialog(
            title = "重命名项目",
            initialName = project.name,
            allowModeSelection = false,
            onDismiss = { renameTarget = null },
            onConfirm = { name, _ ->
                renameTarget = null
                runStoreAction { store.renameProject(project.projectId, name) }
            },
        )
    }
    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确定删除“${project.name}”？本地脚本和资源也会删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        if (opened?.manifest?.projectId == project.projectId) opened = null
                        runStoreAction {
                            store.deleteProject(project.projectId)
                            null
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    selected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${project.sourceMode.displayName()} · ${formatTime(project.lastOpenedAt ?: project.updatedAt)}" +
                            if (selected) " · 已打开" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    CompactAction("打开", enabled, onOpen)
                    CompactAction("改名", enabled, onRename)
                    CompactAction("删除", enabled, onDelete)
                }
            }
        }
    }
}

@Composable
private fun OpenedProjectCard(snapshot: ProjectSnapshot, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前：${snapshot.manifest.name}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (snapshot.manifest.sourceMode) {
                        ProjectSourceMode.LUA -> "入口 main.lua · ${snapshot.luaSource?.length ?: 0} 字符"
                        ProjectSourceMode.VISUAL -> "入口 ${snapshot.manifest.entryFlowId} · ${snapshot.flowSources.size} 个 Flow"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompactAction("关闭", true, onClose)
        }
    }
}

@Composable
private fun CompactAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun ProjectEditorDialog(
    title: String,
    initialName: String,
    allowModeSelection: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, ProjectSourceMode) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var mode by remember { mutableStateOf(ProjectSourceMode.LUA) }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 128
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("项目名") },
                    supportingText = { Text("1–128 个字符") },
                )
                if (allowModeSelection) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ModeOption(ProjectSourceMode.LUA, mode) { mode = it }
                        ModeOption(ProjectSourceMode.VISUAL, mode) { mode = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, mode) }, enabled = valid) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ModeOption(
    value: ProjectSourceMode,
    selected: ProjectSourceMode,
    onSelected: (ProjectSourceMode) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = value == selected, onClick = { onSelected(value) })
        Column {
            Text(value.displayName())
            Text(
                if (value == ProjectSourceMode.LUA) "直接编辑 main.lua" else "使用积木生成 Lua",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ProjectSourceMode.displayName(): String =
    if (this == ProjectSourceMode.LUA) "手写 Lua" else "可视化积木"

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
