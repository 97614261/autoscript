package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.ProjectSummary
import kotlinx.coroutines.*

@Composable
internal fun CloudProjectBackupsScreen(store: ProjectStore, projects: List<ProjectSummary>, blocked: () -> Boolean,
    onRestored: suspend () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
    sessionStore: StudioAccountStore? = null, accountOnly: Boolean = false) {
    val app = LocalContext.current
    val accountStore = remember(sessionStore) { sessionStore ?: StudioAccountStore(app) }
    var session by remember { mutableStateOf<StudioAccountSession?>(null) }
    var showBackups by remember { mutableStateOf(!accountOnly) }
    var loading by remember { mutableStateOf(true) }
    var legacy by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var allowHttp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    StudioSystemLogEffect("账号", message)
    var confirmSignOut by remember { mutableStateOf(false) }
    var call by remember { mutableStateOf<StudioCloudClient?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { call?.close(); job?.cancel() } }
    LaunchedEffect(Unit) {
        try { withContext(Dispatchers.IO) { Triple(accountStore.read(), accountStore.lastOrigin(), accountStore.lastUserName()) }.let { (saved, last, lastName) ->
            origin = saved?.origin ?: last; allowHttp = saved?.allowHttp ?: false
            userName = saved?.userName ?: lastName
            if (saved != null && saved.expiresAt <= System.currentTimeMillis()) {
                withContext(Dispatchers.IO) { accountStore.clear() }
                message = "登录已过期，请重新登录。"
            } else session = saved
        } } catch (_: Exception) { message = "本机登录信息无法读取，请重新登录。旧的个人密钥备份仍可访问。" }
        finally { loading = false }
    }

    fun cancel() { call?.close(); job?.cancel() }
    fun signOut() {
        val active = session ?: return
        busy = true; message = null
        job = scope.launch {
            var remoteRevoked = false
            try {
                withContext(Dispatchers.IO) {
                    StudioCloudClient(active.origin, active.allowHttp, active).use { client -> call = client; client.logout(); remoteRevoked = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Local sign-out remains available when the server cannot be reached. */ }
            finally {
                withContext(NonCancellable) {
                    try { withContext(Dispatchers.IO) { accountStore.clear() }; session = null; password = ""; confirmation = ""
                        message = if (remoteRevoked) "已退出账号，本机项目保留。" else "已清除本机登录；服务器会话未确认撤销，将按有效期失效。"
                    } catch (_: Exception) { message = "退出信息保存失败，请重试。" }
                }
                call = null; busy = false; job = null
            }
        }
    }
    fun submit() {
        if (busy || loading) return
        if (origin.isBlank() || userName.isBlank() || password.isEmpty()) { message = "请填写服务器地址、账号和密码。"; return }
        if (register && password != confirmation) { message = "两次输入的密码不一致。"; return }
        busy = true; message = null
        val requestOrigin = origin.trim()
        val requestName = userName.trim()
        val requestPassword = password
        val requestConfirmation = confirmation
        val registering = register
        val http = allowHttp
        job = scope.launch {
            var active: StudioCloudClient? = null
            try {
                active = StudioCloudClient(requestOrigin, http); call = active
                val signedIn = withContext(Dispatchers.IO) {
                    if (registering) { active.register(requestName, requestPassword, requestConfirmation); null }
                    else active.login(requestName, requestPassword, http)
                }
                currentCoroutineContext().ensureActive()
                if (signedIn == null) { register = false; message = "注册成功，请登录。" }
                else {
                    withContext(Dispatchers.IO) { accountStore.save(signedIn) }
                    com.autoscript.runtime.api.RuntimeSystemLog.notice(app.filesDir, "账号", "登录成功")
                    session = signedIn
                }
                password = ""; confirmation = ""
            } catch (cancelled: CancellationException) { message = "已取消；若注册已提交，可直接尝试登录。"; throw cancelled }
            catch (failure: Exception) {
                message = if (failure is StudioCloudFailure) failure.userMessage else when (failure.message) {
                    "CLOUD_HTTPS_REQUIRED" -> "请使用 HTTPS 地址；本地测试 HTTP 时需明确勾选允许调试。"
                    else -> "连接未完成，请核对账号服务地址和网络后重试。"
                }
            } finally { active?.close(); call = null; busy = false; job = null }
        }
    }

    if (legacy) {
        ServerProjectBackupsScreen(store, projects, blocked, onRestored, { legacy = false }, modifier)
        return
    }
    val signedIn = session
    if (signedIn != null && !busy) {
        if (accountOnly && !showBackups) {
            StudioSignedInAccount(signedIn, onBack, { showBackups = true }, { confirmSignOut = true },
                onPasswordChanged = { expired ->
                    withContext(NonCancellable) {
                        session = null; userName = signedIn.userName; password = ""; confirmation = ""
                        message = if (expired) "登录已过期，请重新登录。" else "密码已修改，所有旧登录已失效，请重新登录。"
                        try { withContext(Dispatchers.IO) { accountStore.clear() } }
                        catch (_: Exception) { message = "旧登录已失效，本机信息清除失败，请重新登录后保存。" }
                    }
                }, modifier = modifier)
        } else {
        key(signedIn.origin, signedIn.accountId, signedIn.token) {
            ServerProjectBackupsScreen(store, projects, blocked, onRestored,
                { if (accountOnly) showBackups = false else onBack() }, modifier,
                accountSession = signedIn, onSignOut = { confirmSignOut = true }, onLegacy = { legacy = true },
                onSessionExpired = {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { accountStore.clear() }
                        userName = signedIn.userName; session = null
                        password = ""; confirmation = ""; message = "登录已过期，请重新登录。"
                    }
                })
        }
        }
        if (confirmSignOut) StudioConfirmDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("退出账号") },
            text = { Text("退出后不能访问该账号的云备份，本机项目保留。") },
            confirmButton = { TextButton({ confirmSignOut = false; signOut() }) { Text("退出") } },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text("取消") } })
        return
    }
    BackHandler { if (busy) cancel() else onBack() }
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground).imePadding()) {
        UtilityLightHeader("账号与云备份", "", { if (busy) cancel() else onBack() }) {
            TextButton({ legacy = true }, enabled = !busy && !loading) { Text("旧备份", fontSize = 11.sp) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (register) "创建智构账号" else "登录智构账号", fontSize = 17.sp, modifier = Modifier.weight(1f))
                    TextButton({ register = !register; confirmation = ""; message = null }, enabled = !busy) {
                        Text(if (register) "返回登录" else "注册账号", fontSize = 12.sp)
                    }
                }
                OutlinedTextField(origin, { origin = it; allowHttp = false }, label = { Text("账号服务器地址") },
                    placeholder = { Text("https://你的服务器") }, singleLine = true, enabled = !busy && !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(userName, { userName = it }, label = { Text("账号") }, singleLine = true,
                    enabled = !busy && !loading, modifier = Modifier.fillMaxWidth())
                StudioAccountPasswordField(password, { password = it }, "密码", !busy && !loading)
                if (register) StudioAccountPasswordField(confirmation, { confirmation = it }, "确认密码", !busy && !loading)
                if (register) Text("账号支持中文、字母、数字、_ 和 -；密码至少8字节。", fontSize = 11.sp)
                else Text("忘记密码请联系管理员重置。", fontSize = 11.sp)
                if (origin.startsWith("http://")) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowHttp, { allowHttp = it }, enabled = !busy)
                    Text("允许 HTTP 调试（账号密码会明文传输）", fontSize = 11.sp)
                }
                Text("仅云备份需要登录，本机项目、编辑与运行可直接使用。", fontSize = 11.sp)
            }
        }
        message?.let { Text(it, fontSize = 12.sp, color = AutoScriptPalette.Accent,
            modifier = Modifier.fillMaxWidth().heightIn(max = 64.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            if (busy) TextButton({ cancel() }) { Text("取消") }
            else TextButton(onBack) { Text("暂不登录") }
            Button({ submit() }, enabled = !busy && !loading) { Text(if (busy) "正在处理…" else if (register) "注册" else "登录") }
        }
    }
}
