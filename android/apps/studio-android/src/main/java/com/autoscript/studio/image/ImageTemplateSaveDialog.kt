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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
    StudioSystemLogEffect("模板保存", error, failure = true)
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
    ImageWorkspaceTheme {
    StudioDialog(onDismissRequest = ::requestDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
    )) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
        val short = studioShortViewport(maxHeight.value)
        Surface(Modifier.width(studioDialogWidth(maxWidth.value, 380).dp)
            .heightIn(max = studioPanelMaxHeight(maxHeight.value, LocalConfiguration.current.screenHeightDp, if (short) 8 else 32).dp),
            shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (folderOnly) "选择文件夹" else "保存图片", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if(!folderOnly) pixelSize?.let { (width,height) ->
                        Text("${width}×${height}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SaveAction("×", !busy, ::requestDismiss)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!folderOnly) {
                        Row(Modifier.fillMaxWidth().heightIn(min=34.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp,vertical=4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("名称", fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(end=8.dp))
                            BasicTextField(imageName, { imageName = it; error = null }, enabled = enabled, singleLine = true,
                                textStyle = saveTextStyle(), modifier = Modifier.weight(1f),
                                cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { save() }))
                            Text(".png", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if(selected.isEmpty()) "图片根目录" else selected, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if('/' in selected) SaveAction("根目录",enabled,{navigate("")})
                        if(selected.isNotEmpty()) SaveAction("上级",enabled,{navigate(selected.substringBeforeLast('/', ""))})
                        SaveAction(if (creatingFolder) "收起" else "+ 文件夹", enabled, {
                            creatingFolder = !creatingFolder; error = null
                        })
                    }
                    if (creatingFolder) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(folderName, { folderName = it; error = null }, enabled = enabled, singleLine = true,
                                textStyle = saveTextStyle(), modifier = Modifier.weight(1f).focusRequester(folderFocus),
                                cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { createFolder() }),
                                decorationBox = { input ->
                                    Box(Modifier.fillMaxWidth().heightIn(min=34.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(5.dp)).padding(horizontal = 8.dp,vertical=4.dp), contentAlignment = Alignment.CenterStart) {
                                        if (folderName.isEmpty()) Text("新文件夹名称", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        input()
                                    }
                                })
                            SaveAction(if (busy) "创建中…" else "创建", enabled && folderName.isNotBlank(), ::createFolder)
                        }
                    }
                    if (loading || children.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(min = 32.dp, max = if(short) 96.dp else 144.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp))) {
                        if (children.isEmpty()) item {
                            Text("正在读取文件夹…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                        items(children, key = { it }) { folder ->
                            Row(Modifier.fillMaxWidth().heightIn(min=32.dp).clickable(enabled = enabled) { navigate(folder) }
                                .padding(horizontal = 8.dp,vertical=4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("▸", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                                Text(folder.substringAfterLast('/'), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("›", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    }
                    error?.let { ImageWorkspaceHint(it,error=true) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().height(studioCompactControlHeight(36,LocalDensity.current.fontScale).dp)) {
                    SaveAction("取消", !busy, ::requestDismiss, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.height(36.dp))
                    SaveAction(if (folderOnly) "选择此文件夹" else if (busy) "保存中…" else "保存图片", enabled && !creatingFolder,
                        ::save, Modifier.weight(1f).fillMaxHeight(),primary=true)
                }
            }
        }
        }
    }
    if (discard) EditorPromptDialog("放弃图片保存配置？", "本次名称和位置选择将丢弃，不保存新图片、不改原图；已经创建的空目录不会删除。",
        { discard=false }, { discard=false;onDismiss() }, "继续编辑", "放弃")
    }
}

@Composable
private fun saveTextStyle() = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
private fun SaveAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,primary:Boolean=false) {
    Box(modifier.heightIn(min=32.dp).then(if(primary && enabled) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp,vertical=4.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, color = if(primary && enabled) MaterialTheme.colorScheme.onPrimary else if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
