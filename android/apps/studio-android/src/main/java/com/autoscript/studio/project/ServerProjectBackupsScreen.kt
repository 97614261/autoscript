package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerProjectBackupsScreen(
    store: ProjectStore,
    projects: List<ProjectSummary>,
    blocked: () -> Boolean,
    onRestored: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("personal-project-backup", 0) }
    val useDefaults = remember { !prefs.contains("origin") && !prefs.contains("key") }
    var savedOrigin by remember {
        mutableStateOf(if (useDefaults) BuildConfig.DEFAULT_BACKUP_SITE else prefs.getString("origin", "").orEmpty())
    }
    var savedKey by remember {
        mutableStateOf(if (useDefaults) BuildConfig.DEFAULT_BACKUP_KEY else prefs.getString("key", "").orEmpty())
    }
    var origin by remember { mutableStateOf(savedOrigin) }
    var secret by remember { mutableStateOf(savedKey) }
    var configuring by remember { mutableStateOf(savedOrigin.isEmpty() || savedKey.isEmpty()) }
    var records by remember { mutableStateOf<List<ServerBackupRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var chooseProject by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<ServerBackupRecord?>(null) }
    var delete by remember { mutableStateOf<ServerBackupRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var committing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var client by remember { mutableStateOf<ServerBackupClient?>(null) }
    val scope = rememberCoroutineScope()
    val currentBlocked by rememberUpdatedState(blocked)
    val currentRestored by rememberUpdatedState(onRestored)
    val date = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val dirty = origin != savedOrigin || secret != savedKey
    val ready = savedKey.isNotEmpty() && !dirty && progress == null
    BackHandler { if (progress == null) onBack() else if (!committing) { client?.close(); job?.cancel() } }
    DisposableEffect(Unit) { onDispose { client?.close() } }

    fun requireIdle() { check(!currentBlocked()) { "BACKUP_EDITOR_OR_RUN_ACTIVE" } }
    fun runTask(title: String, action: suspend (ServerBackupClient) -> Unit) {
        if (progress != null) return
        progress = title; message = null
        job = scope.launch {
            var active: ServerBackupClient? = null
            try {
                active = ServerBackupClient(savedOrigin, savedKey)
                client = active
                action(active)
            } catch (cancelled: CancellationException) {
                message = "已取消；若上传已在服务端提交，请刷新确认。"
                throw cancelled
            } catch (failure: Exception) {
                val code = failure.message?.takeIf { Regex("[A-Z_]{1,64}").matches(it) }
                message = when (code) {
                    "BACKUP_EDITOR_OR_RUN_ACTIVE" -> "请先保存并关闭编辑/配置窗口，停止脚本后再备份或恢复。"
                    "BACKUP_CAPACITY" -> "服务器空间或份数已满，请先删除不需要的快照。"
                    "BACKUP_TOO_LARGE" -> "备份超过 128 MiB，请先精简项目资源。"
                    "BACKUP_SIGNATURE_INVALID" -> "服务签名验证失败，请核对独立备份密钥和服务器配置。"
                    "BACKUP_STORAGE_NEEDS_ATTENTION" -> "服务器存储有异常文件，需要在服务器检查；未自动删除。"
                    else -> "操作未完成，请检查网络、备份地址和密钥${code?.let { " [$it]" }.orEmpty()}。"
                }
            } finally {
                active?.close(); client = null; progress = null; committing = false; job = null
            }
        }
    }
    fun refresh() = runTask("正在读取服务器备份…") { active ->
        records = withContext(Dispatchers.IO) { active.list() }
        message = "共 ${records.size} 份服务器备份"
    }
    fun upload() = runTask("正在加密并上传已保存项目…") { active ->
        requireIdle()
        val project = projects.firstOrNull { it.projectId == selected } ?: error("BACKUP_PROJECT_REQUIRED")
        val uploaded = withContext(Dispatchers.IO) {
            val zip = File.createTempFile("project-backup-", ".zip", context.cacheDir)
            var encrypted: File? = null
            try {
                zip.outputStream().use { output -> store.exportProjectBackup(project.projectId,
                    BackupBoundedOutput(output, ServerBackupCrypto.MAX_ZIP, active::check)) }
                active.check(); requireIdle()
                val id = ServerBackupCrypto.id()
                encrypted = File.createTempFile("project-backup-", ".zgb", context.cacheDir)
                zip.inputStream().use { source -> encrypted.outputStream().use { target ->
                    ServerBackupCrypto.encrypt(source, target, savedKey, id, active::check)
                } }
                active.upload(encrypted, id, ServerBackupCrypto.encryptName(project.name, savedKey, id))
            } finally { zip.delete(); encrypted?.delete() }
        }
        records = (listOf(uploaded) + records.filterNot { it.id == uploaded.id }).sortedByDescending { it.createdAt }
        message = "备份已上传；不包含未保存草稿和运行数据。"
    }
    fun restoreRecord(record: ServerBackupRecord) = runTask("正在下载并验证备份…") { active ->
        requireIdle()
        val zip = File.createTempFile("project-restore-", ".zip", context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                val encrypted = File.createTempFile("project-restore-", ".zgb", context.cacheDir)
                try {
                    active.download(record, encrypted)
                    encrypted.inputStream().use { source -> zip.outputStream().use { target ->
                        ServerBackupCrypto.decrypt(source, target, savedKey, record.id, active::check)
                    } }
                    active.check()
                } finally { encrypted.delete() }
            }
            currentCoroutineContext().ensureActive(); active.check(); requireIdle()
            committing = true; progress = "正在恢复为新项目，请稍候…"
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { zip.inputStream().use(store::importProjectBackup) }
                message = if (runCatching { currentRestored() }.isSuccess) "已恢复为新项目，原项目保持不变。"
                    else "已恢复为新项目，请回项目页刷新查看；不要重复恢复。"
            }
        } finally { zip.delete() }
    }

    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityLightHeader("服务器备份", "手动快照 · 恢复为新项目", { if (progress == null) onBack() }) {
            TextButton(onClick = { configuring = !configuring }, enabled = progress == null) { Text("设置", fontSize = 11.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (configuring) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(origin, { origin = it }, label = { Text("服务器地址，如 http://服务器IP") }, singleLine = true, enabled = progress == null, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it.trim().lowercase(Locale.ROOT) }, label = { Text("独立备份密钥（64位十六进制）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = progress == null, modifier = Modifier.fillMaxWidth())
                    Text("密钥需与备份服务一致，请另行保管；不要填写 APK 发布密钥。丢失密钥无法恢复。HTTP 仍会暴露传输大小和时间。", fontSize = 11.sp)
                    TextButton(onClick = {
                        if (runCatching { StudioUpdateSite.parse(origin.trim()); require(ServerBackupCrypto.validKey(secret)) }.isFailure) {
                            message = "请填写有效的 HTTP(S) 根地址及独立的 64 位备份密钥。"
                        } else {
                            savedOrigin = origin.trim(); origin = savedOrigin; savedKey = secret
                            prefs.edit().putString("origin", savedOrigin).putString("key", savedKey).apply()
                            records = emptyList(); configuring = false; message = "配置已保存，请点刷新连接服务器。"
                        }
                    }, enabled = progress == null) { Text("保存配置") }
                }
            }
            item {
                Text("只备份已经保存的脚本、界面和项目资源，不含草稿与运行数据。上传/恢复前请关闭编辑窗口并停止脚本。", fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ chooseProject = true }, enabled = ready) { Text(projects.firstOrNull { it.projectId == selected }?.name ?: "选择本机项目", fontSize = 12.sp) }
                    TextButton({ confirmUpload = true }, enabled = ready && selected != null) { Text("上传备份", fontSize = 12.sp) }
                    TextButton({ refresh() }, enabled = ready) { Text("刷新列表", fontSize = 12.sp) }
                }
                progress?.let { Text(it, fontSize = 12.sp, color = AutoScriptPalette.Accent) }
                if (progress != null && !committing) TextButton({ client?.close(); job?.cancel() }) { Text("取消") }
                message?.let { Text(it, fontSize = 12.sp) }
                if (records.isEmpty()) Text("暂无已读取的服务器备份；点刷新读取。", fontSize = 11.sp)
            }
            items(records, key = { it.id }) { record ->
                Surface(color = AutoScriptPalette.AccentSoft, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(runCatching { ServerBackupCrypto.decryptName(record.label, savedKey, record.id) }.getOrDefault("无法解密名称"), fontSize = 13.sp)
                        Text("${date.format(Date(record.createdAt))} · ${"%.2f".format(record.bytes / 1048576.0)} MiB · ${record.id.take(8)}", fontSize = 10.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ restore = record }, enabled = ready) { Text("恢复为新项目", fontSize = 12.sp) }
                            TextButton({ delete = record }, enabled = ready) { Text("删除备份", fontSize = 12.sp, color = AutoScriptPalette.Danger) }
                        }
                    }
                }
            }
        }
    }
    if (chooseProject) StudioConfirmDialog(onDismissRequest = { chooseProject = false }, title = { Text("选择已保存项目") },
        text = { Column { if (projects.isEmpty()) Text("暂无本机项目") else projects.forEach { project ->
            TextButton({ selected = project.projectId; chooseProject = false }) { Text(project.name) }
        } } }, confirmButton = { TextButton({ chooseProject = false }) { Text("取消") } })
    if (confirmUpload) StudioConfirmDialog(onDismissRequest = { confirmUpload = false }, title = { Text("上传项目备份") },
        text = { Text("将加密上传“${projects.firstOrNull { it.projectId == selected }?.name.orEmpty()}”的已保存内容，不含编辑草稿。") },
        confirmButton = { TextButton({ confirmUpload = false; upload() }) { Text("上传") } }, dismissButton = { TextButton({ confirmUpload = false }) { Text("取消") } })
    restore?.let { record -> StudioConfirmDialog(onDismissRequest = { restore = null }, title = { Text("恢复为新项目") },
        text = { Text("恢复后新增一份本机项目，不覆盖、不自动运行现有项目。") },
        confirmButton = { TextButton({ restore = null; restoreRecord(record) }) { Text("恢复") } }, dismissButton = { TextButton({ restore = null }) { Text("取消") } }) }
    delete?.let { record -> StudioConfirmDialog(onDismissRequest = { delete = null }, title = { Text("删除服务器备份") },
        text = { Text("将永久删除这份快照（${record.id.take(8)}）；不删除本机项目。") }, confirmButton = { TextButton({
            delete = null
            runTask("正在删除备份…") { active ->
                withContext(Dispatchers.IO) { active.delete(record) }
                records = records.filterNot { it.id == record.id }; message = "服务器快照已删除，本机项目不变。"
            }
        }) { Text("删除") } }, dismissButton = { TextButton({ delete = null }) { Text("取消") } }) }
}

internal class BackupBoundedOutput(output: OutputStream, private val maximum: Long, private val checkActive: () -> Unit) : FilterOutputStream(output) {
    private var bytes = 0L
    override fun write(value: Int) { checkActive(); require(bytes + 1 <= maximum) { "BACKUP_TOO_LARGE" }; out.write(value); bytes++ }
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        checkActive(); require(bytes + count <= maximum) { "BACKUP_TOO_LARGE" }; out.write(buffer, offset, count); bytes += count
    }
}
