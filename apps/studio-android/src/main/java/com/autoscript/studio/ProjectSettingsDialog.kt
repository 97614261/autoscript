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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.window.Dialog
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSnapshot by rememberUpdatedState(snapshot)
    var importKind by remember { mutableStateOf<ProjectResourceKind?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var runnerUiEditorVisible by remember { mutableStateOf(false) }
    var runnerUiDraft by remember { mutableStateOf("") }
    var runnerUiExpected by remember { mutableStateOf<JsonObject?>(null) }
    var selectedCapabilities by remember(snapshot.manifest.capabilities) {
        mutableStateOf(snapshot.manifest.capabilities.toSet())
    }
    val supportedCapabilities = remember {
        BlockCatalog.all.flatMap { it.requiredCapabilities }.distinct().sorted()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = importKind
        importKind = null
        if (uri == null || kind == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = latestSnapshot
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
                        )
                    }
                }
            }.onSuccess { updated ->
                onSnapshotChanged(updated)
                notice = if (kind == ProjectResourceKind.IMAGE) "图片已导入" else "字库已导入"
            }.onFailure { failure ->
                error = failure.message ?: "资源导入失败"
            }
            busy = false
        }
    }

    var activePage by remember(snapshot.manifest.projectId) { mutableStateOf(ProjectSettingsPage.RESOURCES) }
    var resourceFilter by remember { mutableStateOf<String?>(null) }
    var resourceDetailsVisible by remember { mutableStateOf<JsonObject?>(null) }
    var interfacePreviewVisible by remember { mutableStateOf(false) }
    var discardCapabilitiesVisible by remember { mutableStateOf(false) }
    val capabilitiesDirty = selectedCapabilities != snapshot.manifest.capabilities.toSet()

    fun requestClose() {
        if (!busy) {
            if (capabilitiesDirty) discardCapabilitiesVisible = true else onDismiss()
        }
    }
    fun editRunnerUi() {
        error = null
        notice = null
        runnerUiDraft = snapshot.manifest.runnerUi?.let { PRETTY_JSON.toJson(it) } ?: RUNNER_UI_TEMPLATE
        runnerUiExpected = snapshot.manifest.runnerUi?.deepCopy()
        runnerUiEditorVisible = true
    }
    fun saveCapabilities() {
        if (busy || !capabilitiesDirty) return
        val submitted = selectedCapabilities.toSet()
        val expected = snapshot.manifest.capabilities.toSet()
        busy = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.updateCapabilities(latestSnapshot.manifest.projectId, submitted, expected)
                }
            }.onSuccess { updated ->
                onSnapshotChanged(updated)
                notice = "能力声明已保存"
            }.onFailure { failure -> error = failure.message ?: "能力保存失败" }
            busy = false
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
                if (busy) "处理中…" else if (capabilitiesDirty) "保存能力 *" else "保存能力",
                enabled = !busy && capabilitiesDirty, modifier = Modifier.weight(1f), onClick = ::saveCapabilities,
            )
        },
    ) {
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
            ProjectSettingsPage.RESOURCES -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsOutlineAction("＋ 图片", !busy, Modifier.weight(1f)) {
                        importKind = ProjectResourceKind.IMAGE
                        picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"))
                    }
                    SettingsOutlineAction("＋ 字库", !busy, Modifier.weight(1f)) {
                        importKind = ProjectResourceKind.GLYPH_DICTIONARY
                        picker.launch(arrayOf("*/*"))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "全部", "image" to "图片", "glyphDictionary" to "字库").forEach { (kind, title) ->
                        val count = snapshot.manifest.resources.count { kind == null || it.get("kind").asString == kind }
                        SettingsFilterChip("$title $count", resourceFilter == kind) { resourceFilter = kind }
                    }
                }
                Text("图片 ≤32 MiB · 字库 ASGLYPH v1 ≤8 MiB · 导入后自动保存",
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
                Text("已选择 ${selectedCapabilities.size} 项 · 修改后点底部保存",
                    fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(12.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(settingsCapabilityOptions(supportedCapabilities, snapshot.manifest.capabilities).chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { capability ->
                                Row(Modifier.weight(1f).height(46.dp).selectable(
                                    selected = capability in selectedCapabilities, enabled = !busy, role = Role.Checkbox,
                                    onClick = { selectedCapabilities = selectedCapabilities.toggle(capability) },
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
                        SettingsOutlineAction("编辑配置", !busy, Modifier.weight(1f), ::editRunnerUi)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    if (draft.fields.isEmpty()) item { SettingsEmptyState("尚未配置运行界面", "可编辑现有 JSON 配置；不影响可视化插件的编辑界面") }
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
        AlertDialog(
            onDismissRequest = { resourceDetailsVisible = null },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text(File(resource.get("path").asString).name, fontSize = 16.sp, color = AutoScriptPalette.Accent, maxLines = 2) },
            text = { Text(resource.get("path").asString, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { resourceDetailsVisible = null }) { Text("关闭") } },
        )
    }
    if (discardCapabilitiesVisible) {
        AlertDialog(
            onDismissRequest = { discardCapabilitiesVisible = false },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text("能力修改尚未保存", fontSize = 16.sp, color = AutoScriptPalette.Accent) },
            text = { Text("资源导入和界面配置已经即时保存；本次未保存的能力勾选要放弃吗？", fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { discardCapabilitiesVisible = false; onDismiss() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { discardCapabilitiesVisible = false }) { Text("继续编辑") } },
        )
    }
    deletePath?.let { path ->
        AlertDialog(
            onDismissRequest = { deletePath = null },
            containerColor = Color.White, shape = RoundedCornerShape(5.dp),
            title = { Text("删除资源", fontSize = 16.sp, color = AutoScriptPalette.Accent) },
            text = { Text("确定删除 $path？被脚本或积木引用时会拒绝删除。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletePath = null
                        busy = true
                        error = null
                        notice = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val current = latestSnapshot
                                    store.deleteResource(
                                        current.manifest.projectId,
                                        path,
                                        current.resourcePaths(),
                                    )
                                }
                            }.onSuccess { updated ->
                                onSnapshotChanged(updated)
                                notice = "资源已删除"
                            }.onFailure { failure ->
                                error = failure.message ?: "资源删除失败"
                            }
                            busy = false
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
            onDismiss = { runnerUiEditorVisible = false },
            preferredHeight = 460.dp,
            footer = {
                SettingsFooterAction("取消", !busy, Modifier.weight(1f)) { runnerUiEditorVisible = false }
                Box(Modifier.width(1.dp).fillMaxHeight().background(AutoScriptPalette.Divider))
                SettingsFooterAction(if (busy) "保存中…" else "保存", !busy, Modifier.weight(1f)) {
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
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.updateRunnerUi(latestSnapshot.manifest.projectId, candidate, runnerUiExpected)
                            }
                        }.onSuccess { updated ->
                            onSnapshotChanged(updated)
                            runnerUiEditorVisible = false
                            notice = if (candidate == null) "动态配置已删除" else "动态配置已保存"
                        }.onFailure { failure -> error = failure.message ?: "动态配置保存失败" }
                        busy = false
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
                        if (kind == "image") "图片" else "字库",
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
            Text("字库", style = MaterialTheme.typography.labelMedium)
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
