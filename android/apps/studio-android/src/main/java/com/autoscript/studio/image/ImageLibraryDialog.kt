package com.autoscript.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectResourceKind
import com.autoscript.project.store.ProjectResourceInUseException
import com.autoscript.project.store.ProjectResourceUsageReport
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImageLibraryImport(val folder: String, val expected: Set<String>)
private data class ImageLibraryPreview(val path: String, val bitmap: Bitmap)

/** Resource browsing only. Screen capture remains the editor's existing explicit capture route. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ImageLibraryDialog(snapshot: ProjectSnapshot, store: ProjectStore, runtimeClient: RuntimeClient,
    onSnapshotChanged: (ProjectSnapshot) -> Unit, onDismiss: () -> Unit, onCapture: () -> Unit,
    insertionTarget: ImageWorkbenchInsertionTarget?,
    onFindImageUsages: suspend (String) -> ProjectResourceUsageReport,
    onDeleteImage: suspend (String, Set<String>) -> ProjectSnapshot) {
    val projectId = snapshot.manifest.projectId
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savePrompt = rememberResourceSavePrompt()
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestChanged by rememberUpdatedState(onSnapshotChanged)
    val latestDelete by rememberUpdatedState(onDeleteImage)
    val latestFindUsages by rememberUpdatedState(onFindImageUsages)
    var folder by remember(projectId) { mutableStateOf<String?>(null) }
    var search by remember(projectId) { mutableStateOf("") }
    var folderPicker by remember(projectId) { mutableStateOf<String?>(null) }
    var pendingImport by remember(projectId) { mutableStateOf<ImageLibraryImport?>(null) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var message by remember(projectId) { mutableStateOf<String?>(null) }
    var preview by remember(projectId) { mutableStateOf<ImageLibraryPreview?>(null) }
    var workbenchOpen by remember(projectId) { mutableStateOf(false) }
    var pendingDelete by remember(projectId) { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var usageReport by remember(projectId) { mutableStateOf<Pair<ProjectResourceUsageReport, Boolean>?>(null) }
    var failurePrompt by remember(projectId) { mutableStateOf<Pair<String, String>?>(null) }
    val latestPreview by rememberUpdatedState(preview)
    val images = remember(snapshot.manifest.resources) { projectImageLibraryPaths(snapshot.manifest.resources) }
    val visible = remember(images, folder, search) { imageLibraryPaths(images, folder, search) }
    DisposableEffect(projectId) { onDispose { latestPreview?.bitmap?.recycle() } }
    LaunchedEffect(workbenchOpen) {
        // Release the borrowed source only after the workbench has left composition.
        if (!workbenchOpen) {
            val released = preview
            preview = null
            released?.bitmap?.recycle()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingImport
        pendingImport = null
        if (uri != null && request != null && !busy) {
            busy = true; message = null
            scope.launch {
                try {
                    val updated = savePrompt.run { mode -> withContext(Dispatchers.IO) {
                        validateImageDocument(context.contentResolver, uri)
                        val name = queryDisplayName(context.contentResolver, uri)
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                            store.importResource(projectId, ProjectResourceKind.IMAGE, name, stream, request.expected, request.folder, mode)
                        }
                    } }
                    ensureActive()
                    latestChanged(updated)
                    folder = request.folder; search = ""; message = "图片已导入 · 原像素保存"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "图片导入失败：图片无效、超出预算或项目资源已变化" }
                finally { busy = false }
            }
        }
    }
    fun openImage(path: String) {
        if (busy || workbenchOpen) return
        busy = true; message = null
        val frozen = latestSnapshot
        scope.launch {
            var decoded: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { decoded = decodeWorkbenchBitmap(imageRecognitionFile(frozen, path)) }
                ensureActive()
                check(latestSnapshot.manifest.projectId == projectId && path in latestSnapshot.resourcePaths())
                preview?.bitmap?.recycle()
                preview = ImageLibraryPreview(path, requireNotNull(decoded)); decoded = null; workbenchOpen = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "图片不可读、已变化或超过预览预算，原文件保留" }
            finally { decoded?.recycle(); busy = false }
        }
    }
    fun findUsages(path: String) {
        if (busy) return
        busy = true; message = "正在查找使用位置…"
        scope.launch {
            try {
                val result = latestFindUsages(path)
                ensureActive()
                usageReport = result to false
                message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                message = null
                failurePrompt = "查找使用位置失败" to (failure.message ?: "无法完整检查引用，请刷新后重试。图片未改变。")
            } finally { busy = false }
        }
    }
    val enabled = !busy && pendingImport == null && pendingDelete == null && usageReport == null && failurePrompt == null
    fun requestClose() { if (enabled) onDismiss() }
    StudioDialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, dismissOnBackPress = enabled)) {
        Column(Modifier.fillMaxSize().background(Color.White).imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("图片库", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AutoScriptPalette.Accent,
                    modifier = Modifier.weight(1f))
                Text("${visible.size} / ${images.size} 张", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                Box(Modifier.size(32.dp).clickable(enabled = enabled, onClick = ::requestClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭图片库", tint = AutoScriptPalette.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PluginFileAction("截图取材", { if (enabled) { onDismiss(); onCapture() } }, enabled)
                PluginFileAction("导入图片", { folderPicker = "import" }, enabled)
                PluginFileAction("选择文件夹", { folderPicker = "browse" }, enabled)
                PluginFileAction("刷新", {
                    busy = true
                    scope.launch {
                        try { latestChanged(withContext(Dispatchers.IO) { store.openProject(projectId) }); message = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "图片列表读取失败，当前列表保留" }
                        finally { busy = false }
                    }
                }, enabled)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PluginFileInput(search, { search = it }, "图片库搜索", "搜索图片名或文件夹", enabled, Modifier.weight(1f))
                if (search.isNotEmpty()) PluginFileAction("清空", { search = "" }, enabled)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(folder?.let { "目录：${it.ifEmpty { "图片根目录" }}" } ?: "全部图片 · 包含子文件夹",
                    fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (folder != null) PluginFileAction("全部图片", { folder = null }, enabled)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = AutoScriptPalette.Accent)
            message?.let { Text(it, fontSize = 10.sp, color = AutoScriptPalette.Accent, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                if (visible.isEmpty()) item {
                    Text(when { images.isEmpty() -> "暂无项目图片，点「导入图片」或「截图取材」。"
                        search.isNotBlank() -> "没有匹配的图片，可清空搜索或切换到全部图片。"
                        else -> "此文件夹没有图片，点「全部图片」查看其他目录。" },
                        fontSize = 12.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(vertical = 20.dp))
                }
                items(visible, key = { it }) { path ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(enabled = enabled) { openImage(path) }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        ImageLibraryThumbnail(snapshot, path)
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                            Text(path.substringAfterLast('/'), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = AutoScriptPalette.TextPrimary)
                            Text(path.removePrefix("assets/images/").substringBeforeLast('/', "图片根目录"), fontSize = 10.sp,
                                color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("处理 ›", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                                    modifier = Modifier.heightIn(min = 28.dp).clickable(enabled = enabled) { openImage(path) }.wrapContentHeight())
                                Text("使用位置", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                                    modifier = Modifier.heightIn(min = 28.dp).clickable(enabled = enabled) { findUsages(path) }.wrapContentHeight())
                                Text("删除", fontSize = 11.sp, color = AutoScriptPalette.Danger,
                                    modifier = Modifier.heightIn(min = 28.dp).clickable(enabled = enabled) {
                                        pendingDelete = path to latestSnapshot.resourcePaths()
                                    }.wrapContentHeight())
                            }
                        }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                }
            }
            Text("点图片：预览 / 处理 / 识别测试 / 制作字库", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary,
                modifier = Modifier.fillMaxWidth().background(AutoScriptPalette.PageBackground).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
    pendingDelete?.let { (path, expected) ->
        StudioConfirmDialog(onDismissRequest = { if (!busy) pendingDelete = null }, compact = true,
            title = { Text("删除图片") }, text = { Text("确定删除 ${path.substringAfterLast('/')}？\n被积木、未保存草稿或图像方案引用时不会删除。") },
            actions = listOf(
                StudioConfirmAction("取消", { pendingDelete = null }, enabled = !busy),
                StudioConfirmAction(if (busy) "删除中…" else "删除", {
                    if (!busy) {
                        busy = true; message = null
                        scope.launch {
                            try {
                                val updated = latestDelete(path, expected)
                                ensureActive()
                                latestChanged(updated)
                                pendingDelete = null; message = "图片已删除"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (inUse: ProjectResourceInUseException) {
                                pendingDelete = null
                                usageReport = inUse.report to true
                            }
                            catch (failure: Exception) {
                                pendingDelete = null
                                failurePrompt = "未能删除图片" to (failure.message ?: "图片删除失败，请重试，原文件保留")
                            } finally { busy = false }
                        }
                    }
                }, enabled = !busy, destructive = true)))
    }
    usageReport?.let { (report, refused) ->
        ImageResourceUsageDialog(report, refused, onDismiss = { usageReport = null })
    }
    failurePrompt?.let { (title, detail) ->
        StudioConfirmDialog(onDismissRequest = { failurePrompt = null }, compact = true,
            title = { Text(title) }, text = { Text(detail) },
            actions = listOf(StudioConfirmAction("知道了", { failurePrompt = null })))
    }
    folderPicker?.let { purpose ->
        ImageTemplateSaveDialog(images, store, projectId, folderOnly = true, initialFolder = folder.orEmpty(),
            onDismiss = { folderPicker = null }, onSave = { chosen, _, _ ->
                folderPicker = null
                if (purpose == "browse") folder = chosen
                else {
                    pendingImport = ImageLibraryImport(chosen, latestSnapshot.resourcePaths())
                    try { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")) }
                    catch (_: Exception) { pendingImport = null; message = "无法打开系统图片选择器，请重试" }
                }
            })
    }
    if (workbenchOpen) preview?.let { image ->
        ImageProcessingWorkbench(image.bitmap, snapshot, store, runtimeClient, onSnapshotChanged,
            onDismiss = { workbenchOpen = false }, originalImagePath = image.path, insertionTarget = insertionTarget)
    }
}

@Composable
private fun ImageLibraryThumbnail(snapshot: ProjectSnapshot, path: String) {
    // Only visible LazyColumn rows decode thumbnails, capped at 128×128; never resample the resource.
    val bitmap by produceState<Bitmap?>(null, snapshot.directory, path, snapshot.manifest.resources, snapshot.resourceRevision) {
        var decoded: Bitmap? = null
        try {
            value = null
            withContext(Dispatchers.IO) {
                val file = imageRecognitionFile(snapshot, path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
                var sample = 1
                while ((bounds.outWidth + sample - 1) / sample > 128 || (bounds.outHeight + sample - 1) / sample > 128) sample *= 2
                decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false })
            }
            ensureActive()
            value = decoded
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
        finally { value = null; decoded?.recycle() }
    }
    Box(Modifier.size(46.dp).background(AutoScriptPalette.PageBackground), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text("图片", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        else Image(requireNotNull(bitmap).asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}
