package com.autoscript.studio

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
internal fun ProjectSettingsButton(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    enabled: Boolean,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
) {
    var visible by remember(snapshot.manifest.projectId) { mutableStateOf(false) }
    TextButton(
        onClick = { visible = true },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
    ) {
        Text("项目设置")
    }
    if (visible) {
        ProjectSettingsDialog(
            snapshot = snapshot,
            store = store,
            onSnapshotChanged = onSnapshotChanged,
            onDismiss = { visible = false },
        )
    }
}

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

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("项目设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            importKind = ProjectResourceKind.IMAGE
                            picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp"))
                        },
                    ) { Text("导入图片") }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            importKind = ProjectResourceKind.GLYPH_DICTIONARY
                            picker.launch(arrayOf("*/*"))
                        },
                    ) { Text("导入字库") }
                }
                Text(
                    "图片最大 32 MiB；字库须为 ASGLYPH v1，最大 8 MiB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (snapshot.manifest.resources.isEmpty()) {
                        item { Text("暂无项目资源", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(snapshot.manifest.resources, key = { it.get("path").asString }) { resource ->
                        ProjectResourceRow(
                            projectDirectory = snapshot.directory,
                            resource = resource,
                            enabled = !busy,
                            onDelete = { deletePath = resource.get("path").asString },
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Runner 动态配置", style = MaterialTheme.typography.titleSmall)
                        Text(
                            snapshot.manifest.runnerUi?.getAsJsonArray("fields")?.let {
                                "已定义 ${it.size()} 个字段"
                            } ?: "未启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            error = null
                            notice = null
                            runnerUiDraft = snapshot.manifest.runnerUi?.let { PRETTY_JSON.toJson(it) }
                                ?: RUNNER_UI_TEMPLATE
                            runnerUiEditorVisible = true
                        },
                    ) { Text("编辑") }
                }
                HorizontalDivider()
                Text("脚本能力", style = MaterialTheme.typography.titleSmall)
                supportedCapabilities.forEach { capability ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                selectedCapabilities = selectedCapabilities.toggle(capability)
                            }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = capability in selectedCapabilities,
                            enabled = !busy,
                            onCheckedChange = {
                                selectedCapabilities = selectedCapabilities.toggle(capability)
                            },
                        )
                        Text(capability, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selectedCapabilities != snapshot.manifest.capabilities.toSet(),
                onClick = {
                    busy = true
                    error = null
                    notice = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val current = latestSnapshot
                                store.updateCapabilities(
                                    current.manifest.projectId,
                                    selectedCapabilities,
                                    current.manifest.capabilities.toSet(),
                                )
                            }
                        }.onSuccess { updated ->
                            onSnapshotChanged(updated)
                            notice = "能力声明已保存"
                        }.onFailure { failure ->
                            error = failure.message ?: "能力保存失败"
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "处理中…" else "保存能力") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )

    deletePath?.let { path ->
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text("删除资源") },
            text = { Text("确定删除 $path？被脚本或积木引用时会拒绝删除。") },
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
        AlertDialog(
            onDismissRequest = { if (!busy) runnerUiEditorVisible = false },
            title = { Text("Runner 动态配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "定义最终用户启动脚本前填写的表单。留空保存会删除表单；字段 ID 将作为 RunnerConfig 的键。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = runnerUiDraft,
                        onValueChange = { runnerUiDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                        enabled = !busy,
                        label = { Text("runnerUi JSON") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val candidate = runCatching {
                            runnerUiDraft.trim().takeIf(String::isNotEmpty)?.let { source ->
                                JsonParser.parseString(source).also {
                                    require(it.isJsonObject) { "runnerUi 必须是 JSON 对象" }
                                }.asJsonObject
                            }
                        }.getOrElse { failure ->
                            error = failure.message ?: "runnerUi JSON 无效"
                            return@TextButton
                        }
                        busy = true
                        error = null
                        notice = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val current = latestSnapshot
                                    store.updateRunnerUi(
                                        current.manifest.projectId,
                                        candidate,
                                        current.manifest.runnerUi,
                                    )
                                }
                            }.onSuccess { updated ->
                                onSnapshotChanged(updated)
                                runnerUiEditorVisible = false
                                notice = if (candidate == null) "动态配置已删除" else "动态配置已保存"
                            }.onFailure { failure ->
                                error = failure.message ?: "动态配置保存失败"
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = { runnerUiEditorVisible = false },
                    enabled = !busy,
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProjectResourceRow(
    projectDirectory: File,
    resource: JsonObject,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val path = resource.get("path").asString
    val kind = resource.get("kind").asString
    val file = File(projectDirectory, path)
    val details by produceState("", file.path, file.lastModified(), kind) {
        value = withContext(Dispatchers.IO) { resourceDetails(file, kind) }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResourcePreview(file, kind)
            Column(Modifier.weight(1f).padding(horizontal = 7.dp)) {
                Text(file.name, style = MaterialTheme.typography.labelMedium)
                Text(
                    listOf(
                        if (kind == "image") "图片" else "字库",
                        details,
                        formatBytes(file.length()),
                    ).filter(String::isNotEmpty).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ResourcePreview(file: File, kind: String) {
    if (kind != "image") {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text("字库", style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    val preview by produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(file)?.asImageBitmap() }
    }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (preview == null) {
            Text("图片", style = MaterialTheme.typography.labelMedium)
        } else {
            Image(
                bitmap = requireNotNull(preview),
                contentDescription = file.name,
                modifier = Modifier.size(48.dp),
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
