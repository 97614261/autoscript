package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
internal fun AiAssistantDialog(context: AiEditorContext, identities: VisualProjectIds, isCurrent: () -> Boolean,
    onValidate: suspend (String) -> String?, onApply: suspend (AiCandidate) -> String?, onDismiss: () -> Unit,
    settingsRepository: AiSettingsRepository? = null) {
    val app = LocalContext.current
    val store = remember(settingsRepository) { settingsRepository ?: AiSettingsStore(app) }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(AiProvider()) }
    var savedProvider by remember { mutableStateOf(AiProvider()) }
    var loading by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(AiMode.GENERATE) }
    var question by remember { mutableStateOf("") }
    var lastQuestion by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf<List<AiTurn>>(emptyList()) }
    var proposal by remember { mutableStateOf<AiProposal?>(null) }
    var candidate by remember { mutableStateOf<AiCandidate?>(null) }
    var after by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var engine by remember { mutableStateOf<AiAssistantEngine?>(null) }
    var testCall by remember { mutableStateOf<AiHttpCall?>(null) }
    var discard by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var references by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf(if (context.primary == null) AiInsertPlace.END else if (context.slot != null) AiInsertPlace.INSIDE else AiInsertPlace.AFTER) }
    var slot by remember { mutableStateOf(context.slot) }
    var choosePlace by remember { mutableStateOf(false) }
    var chooseSlot by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var validationFeedback by remember { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val liveCurrent by rememberUpdatedState(isCurrent)
    val liveValidate by rememberUpdatedState(onValidate)
    val liveApply by rememberUpdatedState(onApply)
    val current = isCurrent()
    fun stop() { engine?.cancel(); testCall?.cancel(); job?.cancel() }
    fun close() {
        if (busy || question.isNotBlank() || proposal?.hasChanges == true || provider != savedProvider) discard = true else onDismiss()
    }
    DisposableEffect(Unit) { onDispose { engine?.cancel(); testCall?.cancel(); job?.cancel() } }
    LaunchedEffect(Unit) {
        try {
            val read = withContext(Dispatchers.IO) { store.read() }
            provider = read; savedProvider = read; settings = read.model.isBlank()
        } catch (_: Exception) { settings = true; error = "本机 AI 配置无法读取，请重新填写并保存" }
        finally { loading = false }
    }
    fun send(text: String) {
        if (busy || loading) return
        error = null
        try { provider.validate(); require(text.isNotBlank()) { "请先填写需求" }; require(liveCurrent()) { "项目上下文已变化，请关闭后重新打开 AI" } }
        catch (failure: Exception) { error = failure.message; return }
        val selectedMode = mode
        val activeEngine = AiAssistantEngine(provider)
        engine = activeEngine; busy = true; candidate = null; proposal = null; lastQuestion = text
        job = scope.launch {
            try {
                val requestText = validationFeedback?.let { "$text\n上次方案的完整项目校验反馈：$it" }?.take(4000) ?: text
                val response = activeEngine.answer(context, selectedMode, requestText, turns) { status = it }
                ensureActive()
                require(liveCurrent()) { "生成期间项目或运行状态已变化，答复未应用；请重新打开 AI" }
                turns = (turns + AiTurn(text, response.raw)).takeLast(4)
                proposal = response; question = ""; validationFeedback = null; status = "已生成答复，尚未修改项目"
            } catch (_: TimeoutCancellationException) { error = "生成超时，可缩短需求后重试" }
            catch (cancelled: CancellationException) { status = "已停止生成，项目未修改"; throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "生成失败，请重试" }
            finally { if (engine === activeEngine) engine = null; busy = false }
        }
    }
    StudioPanelDialog("AI 助手", ::close, showFooter = false, landscapeWidthDp = 520,
        fullScreen = fullScreen, onToggleFullScreen = { fullScreen = !fullScreen }, headerActions = {
            Text(if (settings) "返回" else "设置", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                modifier = Modifier.clickable(enabled = !busy && !loading) { settings = !settings; error = null }.padding(6.dp))
        }) {
        Column(Modifier.fillMaxWidth().heightIn(max = if (fullScreen) 900.dp else 510.dp)) {
            if (settings) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("兼容聊天补全接口 · 配置仅保存在本机", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                    AiField("接口地址（含 /v1 或完整 /chat/completions）", provider.baseUrl, !busy && !loading, true) {
                        provider = provider.copy(baseUrl = it.take(512)); error = null
                    }
                    AiField("API 密钥（本地无鉴权服务可留空）", provider.apiKey, !busy && !loading, true, secret = true) {
                        provider = provider.copy(apiKey = it.take(2048)); error = null
                    }
                    AiField("模型名称", provider.model, !busy && !loading, true) { provider = provider.copy(model = it.take(160)); error = null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(provider.completionTokens, onCheckedChange = { if (!busy) provider = provider.copy(completionTokens = it) },
                            modifier = Modifier.size(28.dp))
                        Text("新模型参数 max_completion_tokens", fontSize = 11.sp)
                    }
                    Text("未勾选时使用通用 max_tokens。地址自动补 /chat/completions。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorCompactFilter(AiMode.entries.map { it.name to it.title }, mode.name) { selected ->
                        if (!busy) { mode = AiMode.valueOf(selected); proposal = null; candidate = null; turns = emptyList(); validationFeedback = null; error = null; status = null }
                    }
                    Text(if (current) "${context.flowName} · ${context.selection.size}项选择 · 发送当前插件、变量及资源名称，不发送图片或运行变量值"
                        else "项目或运行状态已变化，请关闭后重新打开 AI", fontSize = 10.sp,
                        color = if (current) AutoScriptPalette.TextSecondary else AutoScriptPalette.Danger)
                    if (mode == AiMode.MODIFY && context.selection.isEmpty()) Text("先关闭 AI，选中要修改的积木后再打开。", fontSize = 11.sp, color = AutoScriptPalette.Danger)
                }
                val prepared = candidate
                if (prepared != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("新增${prepared.added} · 删除${prepared.removed} · 改参${prepared.changed}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(if (after) "查看原内容" else "查看新内容", fontSize = 11.sp, color = AutoScriptPalette.Accent,
                            modifier = Modifier.clickable { after = !after }.padding(6.dp))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().background(AutoScriptPalette.PageBackground), contentPadding = PaddingValues(8.dp)) {
                        itemsIndexed(if (after) prepared.afterRows else prepared.beforeRows) { index, row ->
                            SelectionContainer { Text("${index + 1}  $row", fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                    }
                } else {
                    val scroll = rememberScrollState()
                    LaunchedEffect(proposal) { scroll.scrollTo(0) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (turns.isNotEmpty()) {
                            Text(if (showHistory) "收起对话记录" else "对话记录（${turns.size}轮）", fontSize = 10.sp, color = AutoScriptPalette.Accent,
                                modifier = Modifier.clickable { showHistory = !showHistory }.padding(vertical = 3.dp))
                            if (showHistory) turns.forEachIndexed { index, turn ->
                                SelectionContainer { Text("你：${turn.question}\nAI：${runCatching { aiJsonObject(turn.response)["message"].asString }.getOrDefault("答复格式无效")}",
                                    fontSize = 11.sp, color = AutoScriptPalette.TextSecondary) }
                                if (index < turns.lastIndex) HorizontalDivider(color = AutoScriptPalette.Divider)
                            }
                        }
                        if (proposal == null) {
                            Text(if (mode == AiMode.GENERATE) "描述要做的事，例如：循环点击指定位置，每次等待500毫秒，共10次。"
                                else if (mode == AiMode.MODIFY) "说明要怎样修改所选积木，例如：失败后等待500毫秒，再重试3次。"
                                else if (mode == AiMode.EXPLAIN) "解释当前插件或所选积木，可以继续追问。"
                                else "分析当前插件和最近错误；也可以在下面粘贴报错。", fontSize = 12.sp, color = AutoScriptPalette.TextSecondary)
                        }
                        proposal?.let { response ->
                            SelectionContainer { Text(response.message, fontSize = 12.sp) }
                            if (response.missingResources.isNotEmpty()) Text("待补充：${response.missingResources.joinToString("；")}\n可通过“引用”指定已有资源，或退出后添加再重新打开。", fontSize = 11.sp, color = AutoScriptPalette.Danger)
                            if (response.hasChanges) Text("方案尚未应用；先预览并校验，再确认加入。", fontSize = 10.sp, color = AutoScriptPalette.Accent)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mode == AiMode.GENERATE) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("加入：${place.title}${slot?.takeIf { place == AiInsertPlace.INSIDE }?.let { " · ${childBlockLabel(it)}" }.orEmpty()}",
                                fontSize = 11.sp, modifier = Modifier.weight(1f).clickable(enabled = !busy) { choosePlace = true }.padding(vertical = 5.dp))
                        }
                        AiField("补充需求…", question, !busy && current, false) { question = it.take(4000) }
                    }
                }
            }
            error?.let { Text(it.take(1600), fontSize = 11.sp, color = AutoScriptPalette.Danger,
                modifier = Modifier.fillMaxWidth().heightIn(max = 54.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) }
            status?.let { Text(it, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
            HorizontalDivider(color = AutoScriptPalette.Divider)
            EditorCompactActions {
                if (busy) EditorCompactButton(onClick = ::stop) { Text("停止", fontSize = 11.sp) }
                else if (settings) {
                    EditorCompactButton(enabled = !loading, onClick = {
                        error = null
                        try { provider.validate() } catch (failure: Exception) { error = failure.message; return@EditorCompactButton }
                        busy = true; status = "测试连接…"
                        val call = AiHttpCall(provider); testCall = call
                        job = scope.launch {
                            try { withTimeout(25_000) { call.complete(listOf(AiMessage("user", "Reply OK.")), 64) }; status = "连接成功，模型可用；尚未保存配置" }
                            catch (_: TimeoutCancellationException) { error = "连接测试超时，请检查接口和模型" }
                            catch (cancelled: CancellationException) { status = "已停止连接测试"; throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "连接失败" }
                            finally { call.cancel(); testCall = null; busy = false }
                        }
                    }) { Text("测试连接", fontSize = 11.sp) }
                    EditorCompactButton(enabled = !loading, onClick = {
                        busy = true; error = null
                        val value = provider
                        job = scope.launch {
                            try { withContext(Dispatchers.IO) { store.save(value) }; savedProvider = value; settings = false; status = "AI 配置已保存" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = if (failure is IllegalArgumentException) failure.message else "配置保存失败，请重试" }
                            finally { busy = false }
                        }
                    }) { Text("保存配置", fontSize = 11.sp) }
                } else {
                    if (candidate != null) {
                        EditorCompactButton(onClick = { candidate = null; error = null }) { Text("继续调整", fontSize = 11.sp) }
                        EditorCompactButton(enabled = current, onClick = {
                            val prepared = requireNotNull(candidate)
                            busy = true; error = null; status = "应用修改…"
                            job = scope.launch {
                                try {
                                    error = liveApply(prepared)
                                    if (error == null) { candidate = null; proposal = null; question = ""; onDismiss() }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "应用失败，未确认写入；请检查当前编辑内容" }
                                finally { busy = false }
                            }
                        }) { Text("确认应用", fontSize = 11.sp) }
                    } else {
                        EditorCompactButton(onClick = { references = true }) { Text("引用", fontSize = 11.sp) }
                        EditorCompactButton(onClick = { reset = true }) { Text("新对话", fontSize = 11.sp) }
                        val response = proposal
                        if (response?.hasChanges == true && response.missingResources.isEmpty()) {
                            EditorCompactButton(enabled = current, onClick = {
                                busy = true; error = null; status = "校验完整项目…"
                                val selectedMode = mode; val selectedPlace = place; val selectedSlot = slot
                                job = scope.launch {
                                    try {
                                        require(liveCurrent()) { "项目上下文已变化，请重新打开 AI" }
                                        val prepared = withContext(Dispatchers.Default) {
                                            aiCandidate(context.source, context.root, context.selection, context.primary, response,
                                                selectedMode, selectedPlace, selectedSlot, identities)
                                        }
                                        val failure = liveValidate(prepared.source)
                                        ensureActive()
                                        if (failure != null) { error = failure; validationFeedback = failure } else {
                                            require(liveCurrent()) { "校验期间项目发生变化，请重新打开 AI" }
                                            candidate = prepared; after = true; status = "校验通过，确认后应用到草稿，可撤销"
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: "方案校验失败，未修改项目" }
                                    finally { busy = false }
                                }
                            }) { Text("预览修改", fontSize = 11.sp) }
                        }
                        EditorCompactButton(enabled = current && !loading && (mode != AiMode.MODIFY || context.selection.isNotEmpty()) &&
                            (question.isNotBlank() || (error != null && lastQuestion.isNotBlank())), onClick = { send(question.takeIf { it.isNotBlank() } ?: lastQuestion) }) {
                            Text(if (question.isBlank() && error != null) "重试" else "发送", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
    if (references) {
        val options = context.manifest.variables.filter { it.flowId == null || it.flowId == context.flowId }.map { "变量：${it.name}（${it.type.name}）" } +
            context.manifest.resources.mapNotNull { resource -> resource["path"]?.asString?.let { "资源：$it" } }
        CompactVariablePickerDialog("引用已有变量 / 资源", options.map { VariablePickerOption(it) }, null,
            { references = false }, { value -> question = (question + "\n使用$value").take(4000); references = false })
    }
    if (choosePlace) CompactVariablePickerDialog("加入位置", AiInsertPlace.entries.filter { context.primary != null || it == AiInsertPlace.END }
        .filter { it != AiInsertPlace.INSIDE || VisualEditorState.create(context.source, context.root).childBlockNames(context.primary.orEmpty()).isNotEmpty() }
        .map { VariablePickerOption(it.title) }, place.title, { choosePlace = false }, { value ->
            place = AiInsertPlace.entries.single { it.title == value }; candidate = null; choosePlace = false
            if (place == AiInsertPlace.INSIDE) chooseSlot = true
        })
    if (chooseSlot) CompactVariablePickerDialog("选择分支", VisualEditorState.create(context.source, context.root).childBlockNames(context.primary.orEmpty())
        .map { VariablePickerOption(it, childBlockLabel(it)) }, slot, { chooseSlot = false }, { value -> slot = value; chooseSlot = false })
    if (discard || reset) StudioConfirmDialog(onDismissRequest = { discard = false; reset = false }, title = { Text(if (reset) "开始新对话？" else "关闭 AI？") },
        text = { Text("当前对话、未保存配置和未应用方案会丢弃，项目内容不受影响。", fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = {
            stop()
            if (reset) { turns = emptyList(); question = ""; lastQuestion = ""; proposal = null; candidate = null; validationFeedback = null; showHistory = false; error = null; status = null; reset = false }
            else onDismiss()
        }) { Text("确定") } }, dismissButton = { TextButton(onClick = { discard = false; reset = false }) { Text("返回") } })
}

@Composable
private fun AiField(hint: String, value: String, enabled: Boolean, singleLine: Boolean, secret: Boolean = false, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, enabled = enabled, singleLine = singleLine,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, lineHeight = 16.sp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint }.heightIn(min = 34.dp, max = if (singleLine) 48.dp else 86.dp)
            .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary); inner() } })
}
