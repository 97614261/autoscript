package com.autoscript.studio

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.script.ui.UiPage
import com.autoscript.script.ui.MAX_SCRIPT_UI_CONTROLS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun RunnerUiDesignerScreen(snapshot: ProjectSnapshot, store: ProjectStore, onSnapshotChanged: (ProjectSnapshot) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    key(snapshot.manifest.projectId) {
        RunnerUiDesignerContent(snapshot, store, onSnapshotChanged, onBack, modifier)
    }
}

@Composable
private fun RunnerUiDesignerContent(snapshot: ProjectSnapshot, store: ProjectStore, onSnapshotChanged: (ProjectSnapshot) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.inputPickerActivity() }
    val screenOrientation = remember(activity) {
        activity?.let {
            TemporaryScreenOrientation({ it.requestedOrientation }, { direction -> it.requestedOrientation = direction },
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        }
    }
    DisposableEffect(screenOrientation) {
        screenOrientation?.acquire()
        onDispose { screenOrientation?.release() }
    }
    var draft by remember(snapshot.manifest.projectId) { mutableStateOf(runnerUiDesignerDraft(snapshot.manifest.runnerUi)) }
    var saved by remember { mutableStateOf(draft) }
    var expected by remember { mutableStateOf(snapshot.manifest.runnerUi?.deepCopy()) }
    var undo by remember { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var redo by remember { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pageId by remember { mutableStateOf(draft.pages.first().id) }
    var multi by remember { mutableStateOf(false) }
    var boxSelect by remember { mutableStateOf(false) }
    var toolsVisible by remember { mutableStateOf(true) }
    var inspectorVisible by remember { mutableStateOf(true) }
    var focusedCanvas by remember { mutableStateOf(false) }
    var previousPanels by remember { mutableStateOf(true to true) }
    val narrowScreen = LocalConfiguration.current.screenWidthDp < 560
    var zoom by remember { mutableFloatStateOf(1f) }
    var snap by remember { mutableStateOf(true) }
    var fixedSizeIds by remember(snapshot.manifest.projectId) { mutableStateOf(emptySet<String>()) }
    var inspectorTab by remember { mutableStateOf("布局") }
    var preview by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var recoveryPending by remember(snapshot.manifest.projectId) { mutableStateOf(true) }
    var recoveryRequest by remember(snapshot.manifest.projectId) { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageRequest by remember { mutableIntStateOf(0) }
    val feedback = remember { SnackbarHostState() }
    var validationIssue by remember { mutableStateOf<DesignerValidationIssue?>(null) }
    val scope = rememberCoroutineScope()
    val propertyInputs = remember { DesignerPropertyInputs() }
    val journal = remember(snapshot.manifest.projectId) { UiDesignerDraftJournal(context.filesDir, snapshot.manifest.projectId) }
    fun showMessage(notice: String?) { message = notice; messageRequest++ }
    fun commit(next: RunnerUiDesignerDraft) {
        if (busy) return
        if (draft != next) { undo = (undo + draft).takeLast(100); redo = emptyList(); draft = next; propertyInputs.prune(next); message = null; validationIssue = null }
    }
    fun attempt(action: () -> RunnerUiDesignerDraft) { runCatching(action).onSuccess(::commit).onFailure { showMessage(it.message) } }
    fun leave() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (!recoveryPending) withContext(Dispatchers.IO) { journal.discard() }
                screenOrientation?.release(); onBack()
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage("设计恢复记录清理失败，未退出，请重试") }
            finally { busy = false }
        }
    }
    fun exit() { if (draft != saved || propertyInputs.firstIssue() != null) confirmExit = true else leave() }
    fun select(id: String) {
        selection = if (multi) if (id in selection) selection - id else selection + id else setOf(id)
        if (!focusedCanvas) inspectorVisible = true
    }
    fun fit(next: RunnerUiDesignerDraft, field: RunnerUiFieldDraft) {
        runCatching { val metrics = DesignerTextMetrics(field); next.fitContent(field.id, metrics::width, metrics::rowHeight) }.onSuccess {
            commit(it.draft)
            if (it.limited) showMessage("内容超出可用空间，请增大页面/容器或移开邻近控件；选项可在预览中滚动")
        }.onFailure { commit(next); showMessage("内容尺寸无法计算，请检查选项或手动调整宽高") }
    }
    fun change(field: RunnerUiFieldDraft) {
        val index = draft.fields.indexOfFirst { it.id == field.id }
        if (index < 0 || busy) return
        val next = draft.update(index, field)
        if (field.id !in fixedSizeIds && field.contentSizeChanged(draft.fields[index])) fit(next, field) else commit(next)
    }
    fun deleteSelection() {
        var next = draft
        selection.forEach { id -> next.fields.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { next = next.remove(it) } }
        commit(next); selection = emptySet()
    }
    fun duplicate() {
        val ids = selection.toMutableSet()
        repeat(draft.fields.size) { draft.fields.filter { it.ui.parentId in ids }.forEach { ids.add(it.id) } }
        if (draft.fields.size + ids.size > MAX_SCRIPT_UI_CONTROLS) { showMessage("控件数量超过${MAX_SCRIPT_UI_CONTROLS}"); return }
        val names = ids.associateWith { "copy_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val copies = draft.fields.filter { it.id in ids }.map { f ->
            val offset = if (f.ui.parentId in ids) 0 else 16
            f.copy(id = names.getValue(f.id), label = f.label.take(62) + "副本", ui = f.ui.copy(binding = null, parentId = names[f.ui.parentId] ?: f.ui.parentId, x = (f.ui.x + offset).coerceAtMost(4096), y = (f.ui.y + offset).coerceAtMost(8192)))
        }
        commit(draft.copy(fields = draft.fields + copies)); selection = copies.map { it.id }.toSet()
    }
    fun save() {
        if (busy) return
        validationIssue = propertyInputs.firstIssue() ?: draft.firstValidationIssue()
        validationIssue?.let { issue -> showMessage(issue.message); return }
        val submitted = draft
        val json = runCatching { submitted.toRunnerUiJson() }.getOrElse { showMessage(it.message); return }
        busy = true
        scope.launch {
            editorOperation({ failure ->
                    showMessage(failure.message)
                    if (failure is com.autoscript.project.store.ProjectInterfaceReferenceException) draft.fields.find { it.id == failure.controlId }?.let { field ->
                        validationIssue = DesignerValidationIssue(field.id, field.ui.pageId, "交互", failure.message ?: "引用无效")
                    }
                }, { busy = false }) {
                val updated = withContext(Dispatchers.IO) { store.updateRunnerUi(snapshot.manifest.projectId, json, expected) }
                expected = updated.manifest.runnerUi?.deepCopy(); saved = submitted
                onSnapshotChanged(updated); showMessage("界面已保存")
            }
        }
    }
    UiDesignerDraftRecovery(journal, snapshot, store, draft, saved, busy || propertyInputs.firstIssue() != null, onRestore = { recovered ->
        commit(recovered); selection = emptySet(); pageId = recovered.pages.first().id; showMessage("设计草稿已恢复，可撤销，请校验保存")
    }, onMessage = ::showMessage, reopenRequest = recoveryRequest, onPendingChanged = { recoveryPending = it })
    BackHandler(!busy) { exit() }
    val page = draft.pages.firstOrNull { it.id == pageId } ?: draft.pages.first()
    val field = draft.fields.firstOrNull { it.id in selection }
    val selectedFields = draft.fields.filter { it.id in selection }
    val canAlign = selectedFields.size >= 2 && selectedFields.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1
    val roots = draft.selectionRoots(selection)
    val canPosition = roots.isNotEmpty() && roots.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1
    fun selectPage(id: String) {
        if (busy || draft.pages.none { it.id == id }) return
        pageId = id; selection = emptySet(); inspectorTab = "布局"; zoom = 1f
    }
    fun newPage() {
        if (busy) return
        val new = UiPage(id = "page_${UUID.randomUUID().toString().take(8)}", title = "新页面")
        runCatching { draft.addPage(new) }.onSuccess { commit(it); selectPage(new.id) }.onFailure { showMessage(it.message) }
    }
    fun locateIssue() {
        val issue = validationIssue ?: return
        if (busy) return
        pageId = issue.pageId.takeIf { id -> draft.pages.any { it.id == id } } ?: draft.pages.first().id
        selection = issue.controlId?.let { setOf(it) }.orEmpty()
        inspectorTab = issue.tab; inspectorVisible = true; focusedCanvas = false; zoom = 1f
        if (narrowScreen) toolsVisible = false
    }
    LaunchedEffect(message, messageRequest) {
        val notice = message ?: return@LaunchedEffect
        val result = feedback.showSnackbar(notice, actionLabel = if (validationIssue != null) "定位" else null,
            withDismissAction = true, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) locateIssue()
    }
    Box(modifier.fillMaxSize().background(DesignerColors.Workspace).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(32.dp).background(DesignerColors.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DesignerAction("‹ 返回", enabled = !busy, onClick = ::exit)
                Text("UI 设计", color = DesignerColors.Ink, fontSize = 11.sp)
                if (recoveryPending) DesignerAction("恢复草稿", enabled = !busy) { recoveryRequest++ }
                Text(if (draft == saved && propertyInputs.firstIssue() == null) " · 已保存" else " · 未保存", color = DesignerColors.Muted, fontSize = 9.sp, modifier = Modifier.padding(end = 8.dp))
                if (message != null) DesignerAction("提示", selected = true) { messageRequest++ }
                DesignerAction("控件", selected = toolsVisible && !focusedCanvas && (!narrowScreen || !inspectorVisible)) {
                    val show = !toolsVisible || inspectorVisible && narrowScreen || focusedCanvas
                    focusedCanvas = false; toolsVisible = show
                    if (show && narrowScreen) inspectorVisible = false
                }
                DesignerAction("属性", selected = inspectorVisible && !focusedCanvas) { focusedCanvas = false; inspectorVisible = !inspectorVisible }
                DesignerAction(if (focusedCanvas) "退出专注" else "专注", selected = focusedCanvas) {
                    if (!focusedCanvas) { previousPanels = toolsVisible to inspectorVisible; toolsVisible = false; inspectorVisible = false }
                    else { toolsVisible = previousPanels.first; inspectorVisible = previousPanels.second }
                    focusedCanvas = !focusedCanvas
                }
                DesignerAction("撤销", enabled = undo.isNotEmpty() && !busy) { propertyInputs.clear(); redo = (redo + draft).takeLast(100); draft = undo.last(); undo = undo.dropLast(1); selection = emptySet() }
                DesignerAction("恢复", enabled = redo.isNotEmpty() && !busy) { propertyInputs.clear(); undo = (undo + draft).takeLast(100); draft = redo.last(); redo = redo.dropLast(1); selection = emptySet() }
                DesignerAction("预览", enabled = draft.fields.isNotEmpty() && !busy) { preview = true }
                DesignerAction(if (busy) "保存中" else "保存", enabled = !busy, selected = true, primary = true, onClick = ::save)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val layout = designerWorkspaceLayout(maxWidth.value, toolsVisible, inspectorVisible)
                Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (layout.showTools) DesignerToolbox(draft, page, selection, busy, Modifier.width(layout.toolboxWidth.dp).fillMaxHeight(), onSelect = ::select,
                        onAdd = { kind -> runCatching { draft.add(kind, page.id) }.onSuccess { commit(it); selection = setOf(it.fields.last().id); if (!narrowScreen) inspectorVisible = true }.onFailure { showMessage(it.message) } },
                    )
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().height(24.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(112.dp)) { DesignerChoice("", page.title, draft.pages.map { it.id to it.title }, !busy, ::selectPage) }
                            DesignerAction("+ 页", enabled = !busy && draft.pages.size < 16, onClick = ::newPage)
                            Text("${page.width}×${page.height}", Modifier.padding(horizontal = 4.dp), fontSize = 9.sp, color = DesignerColors.Muted, maxLines = 1)
                            DesignerAction("−", enabled = zoom > .5f) { zoom = (zoom - .25f).coerceAtLeast(.5f) }
                            DesignerAction("${(zoom * 100).toInt()}%", selected = zoom == 1f) { zoom = 1f }
                            DesignerAction("+", enabled = zoom < 4f) { zoom = (zoom + .25f).coerceAtMost(4f) }
                            DesignerAction("吸附", selected = snap) { snap = !snap }
                            DesignerAction("框选", selected = boxSelect, enabled = !busy) { boxSelect = !boxSelect; if (boxSelect) multi = true }
                        }
                        DesignerCanvas(draft, page, selection, zoom, snap, !busy, Modifier.weight(1f).fillMaxWidth(), onSelect = ::select, onClear = { selection = emptySet() },
                            onGestureStart = { id -> if (!busy && id !in selection) selection = setOf(id) }, onGestureChange = { next -> if (!busy) draft = next },
                            onGestureEnd = { before -> if (!busy && draft != before) { undo = (undo + before).takeLast(100); redo = emptyList() } }, directory = snapshot.directory,
                            boxSelect = boxSelect, onBoxSelect = { ids -> if (!busy) { selection = ids; multi = true } },
                        )
                        DesignerCanvasActions(multi, selectedFields.size, !busy, canPosition, canAlign,
                            onMulti = { multi = !multi }, onCopy = ::duplicate, onDelete = ::deleteSelection,
                            onPosition = { attempt { draft.position(selection, it) } }, onAlign = { commit(draft.align(selection, it)) },
                            onDistribute = { commit(draft.distribute(selection, it)) },
                            organize = listOf(1 to "一", 2 to "二", 3 to "三").map { (columns, label) ->
                                DesignerMenuAction("当前页${label}列排版", !busy && draft.fields.any { it.ui.pageId == page.id }) { attempt { draft.compactPage(page.id, columns) } }
                            } + listOf(
                                DesignerMenuAction("所选横向排列 · 8px间距", !busy && canPosition && roots.size > 1) { attempt { draft.arrangeSelection(selection, true) } },
                                DesignerMenuAction("所选纵向排列 · 8px间距", !busy && canPosition && roots.size > 1) { attempt { draft.arrangeSelection(selection, false) } },
                                DesignerMenuAction("统一宽度 · 首选为准", !busy && canAlign) { attempt { draft.uniformSize(selection, DesignerUniformSize.WIDTH) } },
                                DesignerMenuAction("统一高度 · 首选为准", !busy && canAlign) { attempt { draft.uniformSize(selection, DesignerUniformSize.HEIGHT) } },
                                DesignerMenuAction("统一宽高 · 首选为准", !busy && canAlign) { attempt { draft.uniformSize(selection, DesignerUniformSize.BOTH) } },
                                DesignerMenuAction("所选吸附 · 8px网格", !busy && selection.isNotEmpty()) { attempt { draft.snapSelection(selection) } },
                            ) + DesignerSizePreset.entries.map { preset ->
                                DesignerMenuAction("所选${preset.label}尺寸 · ${preset.width}×${preset.height}", !busy && selection.isNotEmpty()) { attempt { draft.withSizePreset(selection, preset) } }
                            },
                        )
                    }
                    if (layout.showInspector) CompositionLocalProvider(LocalDesignerPropertyInputs provides propertyInputs,
                        LocalDesignerInputOwner provides DesignerInputOwner(field?.ui?.pageId ?: page.id, field?.id, inspectorTab)) {
                    DesignerInspector(draft, page, field, selectedFields.size, inspectorTab, !busy, canAlign, snapshot, Modifier.width(layout.inspectorWidth.dp).fillMaxHeight(),
                        onTab = { inspectorTab = it }, onField = ::change,
                        smartSize = field?.id !in fixedSizeIds,
                        onSmartSize = { smart -> field?.let { fixedSizeIds = if (smart) fixedSizeIds - it.id else fixedSizeIds + it.id } },
                        onFitContent = { field?.let { fit(draft, it) } },
                        sizePreset = DesignerSizePreset.entries.firstOrNull { preset -> selectedFields.isNotEmpty() && selectedFields.all { preset.matches(it.ui) } },
                        onSizePreset = { preset -> if (!busy) attempt {
                            draft.withSizePreset(selection, preset).also { propertyInputs.clearSizes(selection) }
                        } },
                        onPage = { next -> commit(draft.copy(pages = draft.pages.map { if (it.id == next.id) next else it })) },
                        onDescription = { commit(draft.copy(description = it)) },
                        onReparent = { parent, target -> attempt { draft.reparent(requireNotNull(field).id, parent, target) } },
                        onAlign = { commit(draft.align(selection, it)) }, onDistribute = { commit(draft.distribute(selection, it)) },
                        onLayer = { commit(draft.layer(selection, it)) }, onNudge = { dx, dy -> commit(draft.translate(selection, dx, dy)) },
                        onDeletePage = {
                            if (draft.fields.any { it.ui.pageId == page.id } || draft.fields.any { it.ui.events.values.any { e -> e == "page:${page.id}" } }) showMessage("请先移走控件及页面事件引用")
                            else { commit(draft.copy(pages = draft.pages.filterNot { it.id == page.id })); pageId = draft.pages.first().id }
                        },
                    )
                    }
                }
            }
        }
        SnackbarHost(feedback, Modifier.align(Alignment.TopCenter).padding(top = 36.dp, start = 8.dp, end = 8.dp)) { data ->
            Surface(Modifier.widthIn(max = 320.dp), shape = RoundedCornerShape(6.dp),
                color = DesignerColors.Panel, shadowElevation = 3.dp, border = BorderStroke(1.dp, DesignerColors.Line)) {
                Row(Modifier.width(IntrinsicSize.Max).heightIn(min = 28.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(data.visuals.message, Modifier.weight(1f, fill = false).heightIn(max = 64.dp).verticalScroll(rememberScrollState()),
                        color = DesignerColors.Ink, fontSize = 11.sp, lineHeight = 14.sp)
                    data.visuals.actionLabel?.let { label ->
                        DesignerAction(label, selected = true, onClick = data::performAction)
                    }
                    DesignerAction("关闭", onClick = data::dismiss)
                }
            }
        }
    }
    if (preview) {
        val inputIssue = propertyInputs.firstIssue()
        val json = runCatching { draft.toRunnerUiJson() }.getOrNull()
        if (inputIssue != null || json == null) { preview = false; validationIssue = inputIssue; showMessage(inputIssue?.message ?: "请先修正控件属性") }
        else ProjectInterfacePreview(json, snapshot.directory) { preview = false }
    }
    if (confirmExit) StudioConfirmDialog(onDismissRequest = { confirmExit = false }, title = { Text("有未保存修改") }, text = { Text("退出会丢弃本次未保存的界面修改。") }, confirmButton = { TextButton(onClick = ::leave) { Text("丢弃并退出") } }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } })
}
