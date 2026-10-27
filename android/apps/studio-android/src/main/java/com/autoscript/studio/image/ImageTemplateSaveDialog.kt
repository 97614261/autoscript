package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ResourceImportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun imageResourceFolders(paths: List<String>): List<String> = buildSet {
    add("")
    paths.filter { it.startsWith("assets/images/") }.forEach { path ->
        val parts = path.removePrefix("assets/images/").split('/').dropLast(1)
        parts.indices.forEach { index -> add(parts.take(index + 1).joinToString("/")) }
    }
}.sortedWith(compareBy({ it.count { character -> character == '/' } }, { it }))

@Composable
internal fun ImageTemplateSaveDialog(
    imagePaths: List<String>,
    store: ProjectStore,
    projectId: String,
    folderOnly: Boolean = false,
    initialFolder: String = "",
    defaultName: String = "template-${System.currentTimeMillis()}.png",
    pixelSize: Pair<Int, Int>? = null,
    onDismiss: () -> Unit,
    onSave: suspend (folder: String, name: String, mode: ResourceImportMode) -> Unit,
) {
    var selected by remember(projectId, initialFolder) { mutableStateOf(initialFolder) }
    var storedFolders by remember(projectId) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(projectId) { mutableStateOf(true) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var creatingFolder by remember(projectId) { mutableStateOf(false) }
    var folderName by remember(projectId) { mutableStateOf("") }
    // Capture the suggestion once: recomposition must not regenerate the timestamp and erase a rename.
    val initialName = remember(projectId) { imageSaveStem(defaultName) }
    var imageName by remember(projectId) {
        mutableStateOf(initialName.let { TextFieldValue(it, TextRange(0, it.length)) })
    }
    var discard by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savePrompt = rememberResourceSavePrompt()
    val folderFocus = remember { FocusRequester() }
    val folders = remember(imagePaths, storedFolders) { (imageResourceFolders(imagePaths) + storedFolders).distinct() }
    val children = imageChildFolders(folders, selected)
    val enabled = !busy && !loading
    fun requestDismiss() {
        if (busy) return
        if (!folderOnly && (imageName.text != initialName || selected != initialFolder || folderName.isNotBlank())) discard = true
        else onDismiss()
    }
    fun navigate(folder: String) { selected = folder; creatingFolder = false; folderName = ""; error = null }
    fun createFolder() {
        if (!enabled) return
        val candidate = try { newImageFolderPath(selected, folderName, folders) }
        catch (failure: IllegalArgumentException) { error = failure.message; return }
        busy = true
        scope.launch {
            editorOperation({ error = it.message ?: "文件夹创建失败" }, { busy = false }) {
                storedFolders = withContext(Dispatchers.IO) { store.createImageDirectory(projectId, candidate) }
                navigate(candidate)
            }
        }
    }
    fun save() {
        if (!enabled || creatingFolder) return
        val name = if (folderOnly) "" else try { imageSaveFileName(imageName.text) }
        catch (failure: IllegalArgumentException) { error = failure.message; return }
        val folder = selected
        busy = true
        error = null
        scope.launch {
            try { savePrompt.run { mode -> onSave(folder, name, mode) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "保存图片失败，名称和位置已保留" }
            finally { busy = false }
        }
    }
    LaunchedEffect(projectId) {
        try {
            storedFolders = withContext(Dispatchers.IO) { store.readImageDirectories(projectId) }
            if (selected !in imageResourceFolders(imagePaths) && selected !in storedFolders) selected = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "图片文件夹读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(creatingFolder) { if (creatingFolder) folderFocus.requestFocus() }
    StudioDialog(onDismissRequest = ::requestDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
    )) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
        val short = studioShortViewport(maxHeight.value)
        Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth(.92f)
            .heightIn(max = studioPanelMaxHeight(maxHeight.value, LocalConfiguration.current.screenHeightDp, if (short) 8 else 32).dp),
            shape = RoundedCornerShape(4.dp), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = if (short) 32.dp else 44.dp).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (folderOnly) "选择图片文件夹" else "保存图片", color = AutoScriptPalette.Accent,
                        fontSize = if (short) 13.sp else 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SaveAction("×", !busy, ::requestDismiss)
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(if (short) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (short) 5.dp else 8.dp)) {
                    if (!folderOnly) {
                        Text("图片名称", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                        Row(Modifier.fillMaxWidth().height(40.dp)
                            .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp))
                            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(imageName, { imageName = it; error = null }, enabled = enabled, singleLine = true,
                                textStyle = saveTextStyle(), modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }))
                            Text(".png", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (folderOnly) "当前位置" else "保存位置", fontSize = 12.sp,
                            color = AutoScriptPalette.TextSecondary, modifier = Modifier.weight(1f))
                        SaveAction(if (creatingFolder) "收起" else "+ 新建文件夹", enabled, {
                            creatingFolder = !creatingFolder; error = null
                        })
                    }
                    Row(Modifier.fillMaxWidth().background(AutoScriptPalette.AccentSoft, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SaveAction("图片根目录", enabled && selected.isNotEmpty(), { navigate("") })
                        Text(if (selected.isEmpty()) "/" else "/ $selected", fontSize = 12.sp,
                            color = AutoScriptPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        if (selected.isNotEmpty()) SaveAction("上一级", enabled, { navigate(selected.substringBeforeLast('/', "")) })
                    }
                    if (creatingFolder) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(folderName, { folderName = it; error = null }, enabled = enabled, singleLine = true,
                                textStyle = saveTextStyle(), modifier = Modifier.weight(1f).focusRequester(folderFocus),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { createFolder() }),
                                decorationBox = { input ->
                                    Box(Modifier.fillMaxWidth().height(40.dp).border(1.dp, AutoScriptPalette.Divider,
                                        RoundedCornerShape(5.dp)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                        if (folderName.isEmpty()) Text("新文件夹名称", color = AutoScriptPalette.TextSecondary, fontSize = 12.sp)
                                        input()
                                    }
                                })
                            SaveAction(if (busy) "创建中…" else "创建", enabled && folderName.isNotBlank(), ::createFolder)
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 160.dp)
                        .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp))) {
                        if (children.isEmpty()) item {
                            Text(if (loading) "正在读取文件夹…" else "暂无子文件夹，可选当前目录或新建。",
                                color = AutoScriptPalette.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                        }
                        items(children, key = { it }) { folder ->
                            Row(Modifier.fillMaxWidth().height(40.dp).clickable(enabled = enabled) { navigate(folder) }
                                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("▸", color = AutoScriptPalette.Accent, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                                Text(folder.substringAfterLast('/'), fontSize = 13.sp, color = AutoScriptPalette.TextPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("进入 ›", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                            }
                        }
                    }
                    val fileName = runCatching { imageSaveFileName(imageName.text) }.getOrNull()
                    Text(if (folderOnly) "选中：${selected.ifEmpty { "图片根目录" }}" else
                        "目标：assets/images/${if (selected.isEmpty()) "" else "$selected/"}${fileName ?: "待填写名称.png"}",
                        fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    Text(if (folderOnly) "新建目录立即生效，空目录也随项目备份。" else
                        "PNG 原像素保存 · 同名可覆盖或编号另存", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                    if (!folderOnly) pixelSize?.let { (width,height) ->
                        Text("保存尺寸 ${width}×${height}px · 超预算明确拒绝，不缩小图片",fontSize=10.sp,color=AutoScriptPalette.TextSecondary)
                    }
                    error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFD84949), fontSize = 11.sp) }
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Row(Modifier.fillMaxWidth().height(if (short) 36.dp else 44.dp)) {
                    SaveAction("取消", !busy, ::requestDismiss, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = AutoScriptPalette.Divider)
                    SaveAction(if (folderOnly) "选择此文件夹" else if (busy) "保存中…" else "保存图片", enabled && !creatingFolder,
                        ::save, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        }
    }
    if (discard) EditorPromptDialog("放弃图片保存配置？", "本次名称和位置选择将丢弃，不保存新图片、不改原图；已经创建的空目录不会删除。",
        { discard=false }, { discard=false;onDismiss() }, "继续编辑", "放弃")
}

private fun saveTextStyle() = TextStyle(fontSize = 13.sp, color = AutoScriptPalette.TextPrimary,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
private fun SaveAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(40.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
