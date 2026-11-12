package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import kotlinx.coroutines.*

@Composable
internal fun StudioAccountScreen(store: ProjectStore, blocked: () -> Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var failure by remember { mutableStateOf(false) }
    StudioSystemLogEffect("账号", if (failure) "本机项目未能读取，暂不能上传或恢复" else null, failure = true)
    suspend fun refresh() {
        try { projects = withContext(Dispatchers.IO) { store.listProjects() }; failure = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = true }
    }
    LaunchedEffect(Unit) { refresh() }
    Box(modifier) {
        CloudProjectBackupsScreen(store, projects, { failure || blocked() }, { refresh() }, onBack,
            modifier = Modifier.fillMaxSize(), accountOnly = true)
        if (failure) Text("本机项目未能读取，请返回后重试；暂不能上传或恢复。", color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp, modifier = Modifier.padding(top = 56.dp, start = 12.dp, end = 12.dp))
    }
}

@Composable
internal fun StudioSignedInAccount(session: StudioAccountSession, onBack: () -> Unit, onBackups: () -> Unit,
    onSignOut: () -> Unit, onPasswordChanged: suspend (expired: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var old by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    StudioSystemLogEffect("账号密码", message)
    var busy by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<StudioCloudClient?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun back() { if (busy) { client?.close(); job?.cancel() } else if (editing) { editing = false; old = ""; fresh = ""; confirmation = ""; message = null } else onBack() }
    DisposableEffect(Unit) { onDispose { client?.close(); job?.cancel() } }
    BackHandler { back() }
    fun save() {
        if (busy) return
        if (!studioPasswordInputValid(old) || !studioPasswordInputValid(fresh) || fresh != confirmation || fresh == old) {
            message = "请填写原密码；新密码需为8到72字节、两次一致且与原密码不同。"; return
        }
        val before = old; val after = fresh; val confirmed = confirmation
        busy = true; message = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { StudioCloudClient(session.origin, session.allowHttp, session).use {
                    client = it; it.changePassword(before, after, confirmed)
                } }
                onPasswordChanged(false)
            } catch (cancelled: CancellationException) {
                message = "修改结果未确认，可退出后尝试用新密码登录。"; throw cancelled
            } catch (failure: Exception) {
                if (failure is StudioCloudFailure && failure.code == 401) onPasswordChanged(true)
                else message = (failure as? StudioCloudFailure)?.userMessage ?: "修改结果未确认，请检查网络；若已提交，可退出后尝试新密码。"
            } finally { old = ""; fresh = ""; confirmation = ""; client = null; job = null; busy = false }
        }
    }
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground).imePadding()) {
        UtilityLightHeader(if (editing) "修改密码" else "我的账号", "", ::back) {}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(session.userName, style = MaterialTheme.typography.titleLarge)
                Text(session.origin, fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                if (editing) {
                    listOf("原密码", "新密码", "确认新密码").forEachIndexed { index, label ->
                        StudioAccountPasswordField(when (index) { 0 -> old; 1 -> fresh; else -> confirmation },
                            { when (index) { 0 -> old = it; 1 -> fresh = it; else -> confirmation = it } },
                            label = label, enabled = !busy)
                    }
                    Text("修改后所有设备需重新登录，已有备份保留。", fontSize = 12.sp)
                } else {
                    OutlinedButton(onBackups, modifier = Modifier.fillMaxWidth()) { Text("云备份与恢复") }
                    OutlinedButton({ editing = true }, modifier = Modifier.fillMaxWidth()) { Text("修改密码") }
                    TextButton(onSignOut, modifier = Modifier.fillMaxWidth()) { Text("退出账号") }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 64.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) }
        if (editing) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(::back) { Text(if (busy) "取消请求" else "取消") }
            Button(::save, enabled = !busy) { Text(if (busy) "正在修改…" else "保存密码") }
        }
    }
}

internal fun studioPasswordInputValid(value: String): Boolean = value.toByteArray(Charsets.UTF_8).size in 8..72

@Composable
internal fun StudioAccountPasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        trailingIcon = { TextButton({ visible = !visible }, enabled = enabled) { Text(if (visible) "隐藏" else "显示", fontSize = 11.sp) } })
}
