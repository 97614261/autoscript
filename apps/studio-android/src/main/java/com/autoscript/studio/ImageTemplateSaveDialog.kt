package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.project.store.ProjectStore
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
    onDismiss: () -> Unit,
    onSave: (folder: String, name: String) -> Unit,
) {
    var selected by remember(projectId) { mutableStateOf(initialFolder) }
    var storedFolders by remember(projectId) { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var folderName by remember { mutableStateOf("") }
    var imageName by remember { mutableStateOf(defaultName) }
    var error by remember { mutableStateOf<String?>(null) }
    val folders = (imageResourceFolders(imagePaths) + storedFolders).distinct()
        .sortedWith(compareBy({ it.count { character -> character == '/' } }, { it }))
    LaunchedEffect(projectId) {
        val result = withContext(Dispatchers.IO) { runCatching { store.readImageDirectories(projectId) } }
        result.onSuccess { storedFolders = it }.onFailure { error = it.message ?: "图片文件夹读取失败" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f), shape = RoundedCornerShape(4.dp), color = Color.White) {
            Column {
                Text(if (folderOnly) "项目图片文件夹" else "保存截图模板", color = Color(0xFF3A6EFF), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (folderOnly) "项目图片 · 选择文件夹" else "项目图片 · 选择保存位置", fontSize = 12.sp, color = Color(0xFF727B8B))
                    folders.forEach { folder ->
                        Text("${if (folder == selected) "●" else "○"} ${folder.ifEmpty { "图片根目录" }}",
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFC7CBD1), RoundedCornerShape(3.dp))
                                .background(if (folder == selected) Color(0xFFF1F5FF) else Color.White)
                                .clickable(enabled = !busy) { selected = folder; error = null }.padding(9.dp), fontSize = 12.sp)
                    }
                    OutlinedTextField(folderName, { folderName = it }, label = { Text("在当前目录新建文件夹") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("创建并选择", color = Color(0xFF3A6EFF), fontSize = 12.sp,
                        modifier = Modifier.clickable(enabled = !busy) {
                            val name = folderName.trim()
                            val candidate = listOf(selected, name).filter(String::isNotEmpty).joinToString("/")
                            error = when {
                                name.isEmpty() || name.length > 40 || name == "." || name == ".." ||
                                    name.any { !it.isLetterOrDigit() && it !in "._-" } -> "文件夹名称只能用文字、数字、点、横线和下划线"
                                candidate.split('/').size > 4 -> "图片文件夹最多四层"
                                candidate in folders -> "图片文件夹已存在"
                                else -> null
                            }
                            if (error == null) {
                                busy = true
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { runCatching { store.createImageDirectory(projectId, candidate) } }
                                        result.onSuccess { storedFolders = it; selected = candidate; folderName = "" }
                                            .onFailure { error = it.message ?: "文件夹创建失败" }
                                    } finally { busy = false }
                                }
                            }
                        }.padding(vertical = 4.dp))
                    Text(if (busy) "正在创建…" else "文件夹立即创建，空目录也会随项目备份恢复。", fontSize = 10.sp, color = Color(0xFF727B8B))
                    if (!folderOnly) OutlinedTextField(imageName, { imageName = it }, label = { Text("图片名称") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = Color(0xFFD84949), fontSize = 11.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text("取消", textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clickable(onClick = onDismiss).padding(14.dp))
                    Text(if (folderOnly) "选择" else "保存", color = Color(0xFF3A6EFF), textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clickable(enabled = !busy) {
                            if (imageName.trim().isEmpty()) error = "请输入图片名称"
                            else onSave(selected, imageName.trim())
                        }.padding(14.dp))
                }
            }
        }
    }
}
