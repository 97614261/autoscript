package com.autoscript.studio

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun ProjectSettingsDialog(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onDismiss: () -> Unit,
) {
    key(snapshot.manifest.projectId) {
        ProjectSettingsContent(snapshot, store, onSnapshotChanged, onDismiss)
    }
}

@Composable
private fun ProjectSettingsContent(snapshot: ProjectSnapshot, store: ProjectStore,
    onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSnapshot by rememberUpdatedState(snapshot)
    var importKind by remember { mutableStateOf<ProjectResourceKind?>(null) }
    var choosingImportFolder by remember { mutableStateOf(false) }
    var importImageDirectory by remember { mutableStateOf("") }
    var deletePath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var runnerUiEditorVisible by remember { mutableStateOf(false) }
    var runnerUiDraft by remember { mutableStateOf("") }
    var runnerUiInitial by remember { mutableStateOf("") }
    var discardRunnerUiVisible by remember { mutableStateOf(false) }
    var runnerUiExpected by remember { mutableStateOf<JsonObject?>(null) }
    var executionDraft by remember { mutableStateOf(ProjectExecutionSettingsDraft.from(snapshot.manifest)) }
    val selectedCapabilities = executionDraft.capabilities
    val runDraft = executionDraft.run
    val randomDraft = executionDraft.random
    val observedExecution = ProjectExecutionBaseline.from(snapshot.manifest)
    androidx.compose.runtime.LaunchedEffect(observedExecution, busy) {
        if (!busy) executionDraft = executionDraft.observe(snapshot.manifest)
    }
    val supportedCapabilities = remember {
        BlockCatalog.all.flatMap { it.requiredCapabilities }.distinct().sorted()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = importKind
        importKind = null
        if (uri == null || kind == null || busy) return@rememberLauncherForActivityResult
        val current = latestSnapshot
        val folder = importImageDirectory
        busy = true
        error = null
        notice = null
        scope.launch {
            editorOperation({ failure -> error = failure.message ?: "资源导入失败" }, { busy = false }) {
                val updated = withContext(Dispatchers.IO) {
                    val sourceName = queryDisplayName(context.contentResolver, uri)
                    if (kind == ProjectResourceKind.IMAGE) {
                        validateImageDocument(context.contentResolver, uri)
                    }
                    val stream = requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "无法读取所选文件"
                    }
                    stream.use {
                        store.importResource(
                            current.manifest.projectId,
                            kind,
                            sourceName,
                            it,
                            current.resourcePaths(),
                            imageDirectory = if (kind == ProjectResourceKind.IMAGE) folder else "",
                        )
                    }
                }
                onSnapshotChanged(updated)
                notice = when(kind) { ProjectResourceKind.IMAGE -> "图片已导入"; ProjectResourceKind.AUDIO -> "音频已导入"; else -> "字库已导入" }
            }
        }
    }

    if (choosingImportFolder) ImageTemplateSaveDialog(
        imagePaths = snapshot.manifest.resources.mapNotNull { it.get("path")?.asString },
        store = store, projectId = snapshot.manifest.projectId, folderOnly = true,
        initialFolder = importImageDirectory,
        onDismiss = { choosingImportFolder = false },
        onSave = { folder, _ ->
            choosingImportFolder = false
            importImageDirectory = folder
            importKind = ProjectResourceKind.IMAGE
            picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"))
        },
    )
    var activePage by remember(snapshot.manifest.projectId) { mutableStateOf(ProjectSettingsPage.RESOURCES) }
    var resourceFilter by remember { mutableStateOf<String?>(null) }
    var resourceDetailsVisible by remember { mutableStateOf<JsonObject?>(null) }
    var interfacePreviewVisible by remember { mutableStateOf(false) }
    var discardCapabilitiesVisible by remember { mutableStateOf(false) }
    var reloadVisible by remember { mutableStateOf(false) }
    val settingsDirty = executionDraft.isDirty
    val settingsConflict = executionDraft.conflictsWith(snapshot.manifest)
    fun reloadSettings() {
        if (busy) return
        reloadVisible = false; busy = true; error = null
        scope.launch {
            editorOperation({ error = it.message ?: "读取项目设置失败" }, { busy = false }) {
                val updated = withContext(Dispatchers.IO) { store.openProject(snapshot.manifest.projectId) }
                executionDraft = ProjectExecutionSettingsDraft.from(updated.manifest)
                onSnapshotChanged(updated); notice = "已重新读取项目设置"
            }
        }
    }

    fun requestClose() {
        if (!busy) {
            if (settingsDirty) discardCapabilitiesVisible = true else onDismiss()
        }
    }
    fun editRunnerUi() {
        error = null
        notice = null
        runnerUiDraft = snapshot.manifest.runnerUi?.let { PRETTY_JSON.toJson(it) } ?: RUNNER_UI_TEMPLATE
        runnerUiInitial = runnerUiDraft
        runnerUiExpected = snapshot.manifest.runnerUi?.deepCopy()
        runnerUiEditorVisible = true
    }
    fun closeRunnerUi() {
        if (busy) return
        if (runnerUiDraft != runnerUiInitial) discardRunnerUiVisible = true else runnerUiEditorVisible = false
    }
    fun saveSettings() {
        if (busy || !settingsDirty) return
        if (settingsConflict) { error = "正式设置已变化，草稿保留；请重新读取后再修改保存"; return }
        val debug = runDraft.parse() ?: run { error = "运行设置无效，请检查延迟、尺寸和颜色格式"; activePage = ProjectSettingsPage.RUN; return }
        val random = randomDraft.parse() ?: run { error = "随机策略无效，请检查范围与最小/最大值"; activePage = ProjectSettingsPage.RUN; return }
        val submitted = selectedCapabilities.toSet()
        val expected = executionDraft.baseline
        val projectId = snapshot.manifest.projectId
        busy = true
        error = null
        notice = null
        scope.launch {
            editorOperation({ failure -> error = failure.message ?: "设置保存失败" }, { busy = false }) {
                val updated = withContext(Dispatchers.IO) {
                    store.updateExecutionSettings(projectId, submitted, debug, random,
                        expected.capabilities, expected.debug, expected.randomization)
                }
                executionDraft = ProjectExecutionSettingsDraft.from(updated.manifest)
                onSnapshotChanged(updated)
                notice = "项目设置已保存"
            }
        }
    }

    ProjectSettingsWindow(
        title = "项目设置",
        subtitle = snapshot.manifest.name,
        busy = busy,
        onDismiss = ::requestClose,
        footer = {
            SettingsFooterAction("关闭", enabled = !busy, modifier = Modifier.weight(1f), onClick = ::requestClose)
            Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
            SettingsFooterAction(
                if (busy) "处理中…" else if (settingsDirty) "保存设置 *" else "保存设置",
                enabled = !busy && settingsDirty, modifier = Modifier.weight(1f), onClick = ::saveSettings,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (settingsConflict) "正式设置已变化 · 未覆盖草稿" else "能力与运行设置统一保存", fontSize = 10.sp,
                color = if (settingsConflict) AutoScriptPalette.Danger else AutoScriptPalette.TextSecondary, modifier = Modifier.weight(1f))
            SettingsOutlineAction("重新读取", !busy, Modifier.width(76.dp)) { if (settingsDirty) reloadVisible = true else reloadSettings() }
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(AutoScriptPalette.PageBackground)) {
            ProjectSettingsPage.entries.forEach { page ->
                Column(Modifier.weight(1f).fillMaxHeight().selectable(
                    selected = activePage == page, onClick = { activePage = page }, role = Role.Tab,
                ), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(page.title, fontSize = 13.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            fontWeight = if (activePage == page) FontWeight.Bold else FontWeight.Normal,
                            color = if (activePage == page) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(
                        if (activePage == page) AutoScriptPalette.Accent else Color.Transparent,
                    ))
                }
            }
        }
        error?.let { SettingsStatus(it, AutoScriptPalette.Danger) }
        notice?.let { SettingsStatus(it, AutoScriptPalette.Accent) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = AutoScriptPalette.Accent)
        when (activePage) {
            ProjectSettingsPage.RUN -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                ProjectRunSettingsForm(runDraft, { if (!busy) { executionDraft = executionDraft.copy(run = it); error = null } }, randomDraft,
                    { if (!busy) { executionDraft = executionDraft.copy(random = it); error = null } }, snapshot.manifest.variables, !busy,
                    snapshot.manifest.flows)
            }
            ProjectSettingsPage.RESOURCES -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsOutlineAction("＋ 图片", !busy, Modifier.weight(1f)) {
                        choosingImportFolder = true
                    }
                    SettingsOutlineAction("＋ 字库", !busy, Modifier.weight(1f)) {
                        importKind = ProjectResourceKind.GLYPH_DICTIONARY
                        picker.launch(arrayOf("*/*"))
                    }
                    SettingsOutlineAction("＋ 音频", !busy, Modifier.weight(1f)) {
                        importKind = ProjectResourceKind.AUDIO
                        picker.launch(arrayOf("audio/*"))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "全部", "image" to "图片", "glyphDictionary" to "字库", "audio" to "音频").forEach { (kind, title) ->
                        val count = snapshot.manifest.resources.count { kind == null || it.get("kind").asString == kind }
                        SettingsFilterChip("$title $count", resourceFilter == kind) { resourceFilter = kind }
                    }
                }
                Text("图片 ≤32MiB · 字库 ≤8MiB · 音频 ≤20MiB/总64MiB · 自动保存",
                    fontSize = 10.sp, color = AutoScriptPalette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                val resources = filterSettingsResources(snapshot.manifest.resources, resourceFilter)
                key(resourceFilter) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                        if (resources.isEmpty()) item {
                            SettingsEmptyState(if (resourceFilter == null) "暂无项目资源" else "此分类暂无资源", "点击上方按钮导入")
                        }
                        items(resources, key = { it.get("path").asString }) { resource ->
                            ProjectResourceRow(snapshot.directory, resource, !busy,
                                onOpen = { resourceDetailsVisible = resource },
                                onDelete = { deletePath = resource.get("path").asString })
                            HorizontalDivider(color = AutoScriptPalette.Border)
                        }
                    }
                }
            }
            ProjectSettingsPage.CAPABILITIES -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selectedCapabilities.size} 项", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.weight(1f))
                    SettingsOutlineAction("全选", !busy, Modifier.width(54.dp)) { if (!busy) executionDraft = executionDraft.copy(capabilities = settingsCapabilityOptions(supportedCapabilities, snapshot.manifest.capabilities).toSet()) }
                    Spacer(Modifier.width(6.dp))
                    SettingsOutlineAction("清空", !busy, Modifier.width(54.dp)) { if (!busy) executionDraft = executionDraft.copy(capabilities = emptySet()) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(settingsCapabilityOptions(supportedCapabilities, snapshot.manifest.capabilities).chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { capability ->
                                Row(Modifier.weight(1f).height(46.dp).selectable(
                                    selected = capability in selectedCapabilities, enabled = !busy, role = Role.Checkbox,
                                    onClick = { if (!busy) executionDraft = executionDraft.copy(capabilities = executionDraft.capabilities.toggle(capability)) },
                                ), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = capability in selectedCapabilities, onCheckedChange = null,
                                        enabled = !busy, modifier = Modifier.size(22.dp),
                                        colors = CheckboxDefaults.colors(checkedColor = AutoScriptPalette.Accent))
                                    Column(Modifier.weight(1f).padding(start = 7.dp)) {
                                        Text(settingsCapabilityTitle(capability), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(capability, fontSize = 9.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        HorizontalDivider(color = AutoScriptPalette.Border)
                    }
                    item { Text("仅声明脚本允许使用的能力，不代表已获得 Root 权限。已声明的未知能力会保留。",
                        fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(vertical = 10.dp)) }
                }
            }
            ProjectSettingsPage.INTERFACE -> {
                val draft = runnerUiDesignerDraft(snapshot.manifest.runnerUi)
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("运行前配置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AutoScriptPalette.Accent)
                    Text("用户启动脚本前填写的表单 · ${draft.fields.size} 个字段", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsOutlineAction("预览界面", !busy && snapshot.manifest.runnerUi != null, Modifier.weight(1f)) { interfacePreviewVisible = true }
                        SettingsOutlineAction("高级 JSON", !busy, Modifier.weight(1f), ::editRunnerUi)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    if (draft.fields.isEmpty()) item { SettingsEmptyState("尚未配置运行界面", "常规排版请从编辑器右侧打开界面设计器；高级 JSON 仅用于配置数据") }
                    if (draft.description.isNotBlank()) item {
                        Text(draft.description, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(bottom = 8.dp),
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    items(draft.fields, key = { it.id }) { field ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(field.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(field.id, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(field.kind.label + if (field.required) " · 必填" else " · 可选", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        }
                        HorizontalDivider(color = AutoScriptPalette.Border)
                    }
                }
            }
        }
    }

    if (interfacePreviewVisible) {
        ProjectInterfacePreview(snapshot.manifest.runnerUi, snapshot.directory) { interfacePreviewVisible = false }
    }
    resourceDetailsVisible?.let { resource ->
        StudioConfirmDialog(
            onDismissRequest = { resourceDetailsVisible = null },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text(File(resource.get("path").asString).name, fontSize = 16.sp, color = AutoScriptPalette.Accent, maxLines = 2) },
            text = { Column {
                Text(resource.get("path").asString, fontSize = 12.sp)
                if (resource.get("kind").asString == "audio") ProjectAudioPreview(File(snapshot.directory, resource.get("path").asString))
            } },
            confirmButton = { TextButton(onClick = { resourceDetailsVisible = null }) { Text("关闭") } },
        )
    }
    if (discardCapabilitiesVisible) {
        StudioConfirmDialog(
            onDismissRequest = { discardCapabilitiesVisible = false },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text("项目设置尚未保存", fontSize = 16.sp, color = AutoScriptPalette.Accent) },
            text = { Text("放弃本次能力和运行设置修改？资源导入与界面配置已单独保存。", fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { discardCapabilitiesVisible = false; onDismiss() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { discardCapabilitiesVisible = false }) { Text("继续编辑") } },
        )
    }
    if (reloadVisible) StudioConfirmDialog(onDismissRequest = { reloadVisible = false }, title = { Text("重新读取项目设置？") },
        text = { Text("将丢弃本窗未保存的能力、运行设置和随机策略，读取正式项目；不删除项目或资源。") },
        confirmButton = { TextButton(enabled = !busy, onClick = ::reloadSettings) { Text("丢弃并读取") } },
        dismissButton = { TextButton(onClick = { reloadVisible = false }) { Text("保留草稿") } })
    deletePath?.let { path ->
        StudioConfirmDialog(
            onDismissRequest = { deletePath = null },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text("删除资源", fontSize = 16.sp, color = AutoScriptPalette.Accent) },
            text = { Text("确定删除 $path？被脚本或积木引用时会拒绝删除。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        deletePath = null
                        busy = true
                        error = null
                        notice = null
                        scope.launch {
                            val current = latestSnapshot
                            editorOperation({ failure -> error = failure.message ?: "资源删除失败" }, { busy = false }) {
                                val updated = withContext(Dispatchers.IO) {
                                    store.deleteResource(
                                        current.manifest.projectId,
                                        path,
                                        current.resourcePaths(),
                                    )
                                }
                                onSnapshotChanged(updated)
                                notice = "资源已删除"
                            }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletePath = null }) { Text("取消") }
            },
        )
    }

    if (runnerUiEditorVisible) {
        ProjectSettingsWindow(
            title = "运行界面 · 编辑配置",
            subtitle = "runnerUi JSON · 留空保存可删除表单",
            busy = busy,
            onDismiss = ::closeRunnerUi,
            preferredHeight = 460.dp,
            footer = {
                SettingsFooterAction("取消", !busy, Modifier.weight(1f), ::closeRunnerUi)
                Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                SettingsFooterAction(if (busy) "保存中…" else "保存", !busy, Modifier.weight(1f)) {
                    if (busy) return@SettingsFooterAction
                    val candidate = runCatching {
                        runnerUiDraft.trim().takeIf(String::isNotEmpty)?.let { source ->
                            JsonParser.parseString(source).also {
                                require(it.isJsonObject) { "runnerUi 必须是 JSON 对象" }
                            }.asJsonObject
                        }
                    }.getOrElse { failure ->
                        error = failure.message ?: "runnerUi JSON 无效"
                        return@SettingsFooterAction
                    }
                    busy = true
                    error = null
                    notice = null
                    val expectedUi = runnerUiExpected
                    val projectId = snapshot.manifest.projectId
                    scope.launch {
                        editorOperation({ failure -> error = failure.message ?: "动态配置保存失败" }, { busy = false }) {
                            val updated = withContext(Dispatchers.IO) {
                                store.updateRunnerUi(projectId, candidate, expectedUi)
                            }
                            onSnapshotChanged(updated)
                            runnerUiEditorVisible = false
                            notice = if (candidate == null) "动态配置已删除" else "动态配置已保存"
                        }
                    }
                }
            },
        ) {
            Text("定义脚本运行前的用户表单，字段 ID 是 RunnerConfig 的键。",
                fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(12.dp))
            OutlinedTextField(
                value = runnerUiDraft,
                onValueChange = {
                    if (it.length <= 262144) runnerUiDraft = it else error = "配置文本过长"
                },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                enabled = !busy,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AutoScriptPalette.Accent, unfocusedBorderColor = AutoScriptPalette.Border,
                ),
            )
            error?.let { SettingsStatus(it, AutoScriptPalette.Danger) }
            Spacer(Modifier.height(6.dp))
        }
    }
    if (discardRunnerUiVisible) StudioConfirmDialog(onDismissRequest = { discardRunnerUiVisible = false },
        title = { Text("放弃未保存的界面配置？") }, text = { Text("只丢弃当前 JSON 输入，不修改已保存界面。") },
        confirmButton = { TextButton(onClick = { discardRunnerUiVisible = false; runnerUiEditorVisible = false }) { Text("放弃并关闭") } },
        dismissButton = { TextButton(onClick = { discardRunnerUiVisible = false }) { Text("继续编辑") } })
}

@Composable
private fun ProjectSettingsWindow(
    title: String,
    subtitle: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    footer: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    preferredHeight: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp).imePadding()) {
            val height = minOf(maxHeight * if (maxHeight < 400.dp) .94f else .84f, preferredHeight)
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(height).align(Alignment.Center),
                color = Color.White, shape = RoundedCornerShape(5.dp), shadowElevation = 10.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = AutoScriptPalette.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(subtitle, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(36.dp).clickable(enabled = !busy, onClick = onDismiss), contentAlignment = Alignment.Center) {
                            Text("×", fontSize = 24.sp, color = AutoScriptPalette.TextSecondary)
                        }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(42.dp), content = footer)
                }
            }
        }
    }
}

@Composable
private fun SettingsFooterAction(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun SettingsOutlineAction(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(34.dp).border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun SettingsFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(26.dp).background(if (selected) AutoScriptPalette.AccentSoft else AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp))
        .selectable(selected = selected, onClick = onClick, role = Role.Tab).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun SettingsStatus(message: String, color: Color) {
    Text(message, fontSize = 11.sp, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SettingsEmptyState(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, color = AutoScriptPalette.TextPrimary)
        Text(hint, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
    }
}

@Composable
private fun ProjectResourceRow(
    projectDirectory: File,
    resource: JsonObject,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val path = resource.get("path").asString
    val kind = resource.get("kind").asString
    val file = File(projectDirectory, path)
    val details by produceState("", file.path, file.lastModified(), kind) {
        value = withContext(Dispatchers.IO) { resourceDetails(file, kind) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            ResourcePreview(file, kind)
            Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(horizontal = 8.dp)) {
                Text(file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        when(kind) { "image" -> "图片"; "audio" -> "音频"; else -> "字库" },
                        details,
                        formatBytes(file.length()),
                    ).filter(String::isNotEmpty).joinToString(" · "),
                    fontSize = 10.sp,
                    color = AutoScriptPalette.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
            ) { Text("删除", fontSize = 11.sp, color = AutoScriptPalette.Danger) }
    }
}

@Composable
private fun ResourcePreview(file: File, kind: String) {
    if (kind != "image") {
        Box(Modifier.size(34.dp).background(AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Text(if (kind == "audio") "音频" else "字库", style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    val preview by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(file)?.asImageBitmap() }
    }
    Box(Modifier.size(34.dp).background(AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        if (preview == null) {
            Text("图片", style = MaterialTheme.typography.labelMedium)
        } else {
            Image(
                bitmap = requireNotNull(preview),
                contentDescription = file.name,
                modifier = Modifier.size(34.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun decodeThumbnail(file: File) = BitmapFactory.Options().let { bounds ->
    bounds.inJustDecodeBounds = true
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@let null
    var sample = 1
    while (bounds.outWidth / sample > 192 || bounds.outHeight / sample > 192) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun validateImageDocument(resolver: android.content.ContentResolver, uri: Uri) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) {
        "图片无法解码或尺寸超过 4096"
    }
    require(pixels in 1..4_194_304) { "图片像素数不能超过 4194304" }
}

private fun resourceDetails(file: File, kind: String): String {
    if (kind == "image") {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            "${bounds.outWidth}×${bounds.outHeight}"
        } else {
            "无法预览"
        }
    }
    val header = runCatching { readFilePrefix(file, 16) }.getOrNull()
        ?: return "无法读取"
    if (header.size < 16 || !header.copyOfRange(0, 8).contentEquals("ASGLYPH\u0000".toByteArray())) {
        return "格式异常"
    }
    val version = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
    val count = (header[12].toLong() and 0xFF) or
        ((header[13].toLong() and 0xFF) shl 8) or
        ((header[14].toLong() and 0xFF) shl 16) or
        ((header[15].toLong() and 0xFF) shl 24)
    return "ASGLYPH v$version · $count 字形"
}

private fun readFilePrefix(file: File, maximumBytes: Int): ByteArray = file.inputStream().use { input ->
    val bytes = ByteArray(maximumBytes)
    var offset = 0
    while (offset < bytes.size) {
        val count = input.read(bytes, offset, bytes.size - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    bytes.copyOf(offset)
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String {
    val queried = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return queried?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "resource"
}

private fun ProjectSnapshot.resourcePaths(): Set<String> =
    manifest.resources.map { it.get("path").asString }.toSet()

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private val PRETTY_JSON = GsonBuilder()
    .setPrettyPrinting()
    .disableHtmlEscaping()
    .serializeNulls()
    .create()

private val RUNNER_UI_TEMPLATE = """
{
  "description": "运行前配置",
  "fields": [
    {
      "id": "delayMs",
      "label": "等待毫秒",
      "kind": "integer",
      "required": true,
      "initialValue": 1000,
      "minimum": 0,
      "maximum": 60000,
      "options": []
    }
  ]
}
""".trimIndent()
