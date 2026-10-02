package com.autoscript.studio

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun RunnerUiDesignerScreen(snapshot: ProjectSnapshot, store: ProjectStore, onSnapshotChanged: (ProjectSnapshot) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    var draft by remember(snapshot.manifest.projectId) { mutableStateOf(runnerUiDesignerDraft(snapshot.manifest.runnerUi)) }
    var saved by remember { mutableStateOf(draft) }
    var expected by remember { mutableStateOf(snapshot.manifest.runnerUi?.deepCopy()) }
    var undo by remember { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var redo by remember { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pageId by remember { mutableStateOf(draft.pages.first().id) }
    var multi by remember { mutableStateOf(false) }
    var toolsVisible by remember { mutableStateOf(true) }
    var inspectorVisible by remember { mutableStateOf(true) }
    var focusedCanvas by remember { mutableStateOf(false) }
    var previousPanels by remember { mutableStateOf(true to true) }
    val narrowScreen = LocalConfiguration.current.screenWidthDp < 560
    var zoom by remember { mutableFloatStateOf(1f) }
    var snap by remember { mutableStateOf(true) }
    var inspectorTab by remember { mutableStateOf("布局") }
    var preview by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun commit(next: RunnerUiDesignerDraft) {
        if (draft != next) { undo = (undo + draft).takeLast(100); redo = emptyList(); draft = next; message = null }
    }
    fun attempt(action: () -> RunnerUiDesignerDraft) { runCatching(action).onSuccess(::commit).onFailure { message = it.message } }
    fun exit() { if (draft != saved) confirmExit = true else onBack() }
    fun select(id: String) {
        selection = if (multi) if (id in selection) selection - id else selection + id else setOf(id)
        if (!focusedCanvas) inspectorVisible = true
    }
    fun change(field: RunnerUiFieldDraft) { val index = draft.fields.indexOfFirst { it.id == field.id }; if (index >= 0) commit(draft.update(index, field)) }
    fun deleteSelection() {
        var next = draft
        selection.forEach { id -> next.fields.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { next = next.remove(it) } }
        commit(next); selection = emptySet()
    }
    fun duplicate() {
        val ids = selection.toMutableSet()
        repeat(draft.fields.size) { draft.fields.filter { it.ui.parentId in ids }.forEach { ids.add(it.id) } }
        if (draft.fields.size + ids.size > 128) { message = "控件数量超过128"; return }
        val names = ids.associateWith { "copy_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val copies = draft.fields.filter { it.id in ids }.map { f ->
            val offset = if (f.ui.parentId in ids) 0 else 16
            f.copy(id = names.getValue(f.id), label = f.label.take(62) + "副本", ui = f.ui.copy(binding = null, parentId = names[f.ui.parentId] ?: f.ui.parentId, x = (f.ui.x + offset).coerceAtMost(4096), y = (f.ui.y + offset).coerceAtMost(8192)))
        }
        commit(draft.copy(fields = draft.fields + copies)); selection = copies.map { it.id }.toSet()
    }
    fun save() {
        if (busy) return
        val json = runCatching { draft.toRunnerUiJson() }.getOrElse { message = it.message; return }
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.updateRunnerUi(snapshot.manifest.projectId, json, expected) } }
                .onSuccess { expected = it.manifest.runnerUi?.deepCopy(); saved = draft; onSnapshotChanged(it); message = "界面已保存" }
                .onFailure { message = it.message }
            busy = false
        }
    }
    BackHandler(!busy) { exit() }
    val page = draft.pages.firstOrNull { it.id == pageId } ?: draft.pages.first()
    val field = draft.fields.firstOrNull { it.id in selection }
    val selectedFields = draft.fields.filter { it.id in selection }
    val canAlign = selectedFields.size >= 2 && selectedFields.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1
    val roots = draft.selectionRoots(selection)
    val canPosition = roots.isNotEmpty() && roots.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1
    Column(modifier.fillMaxSize().background(DesignerColors.Workspace).imePadding()) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(DesignerColors.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DesignerAction("‹ 返回", enabled = !busy, onClick = ::exit)
            Text("UI 设计", color = DesignerColors.Ink, fontSize = 11.sp)
            Text(if (draft == saved) " · 已保存" else " · 未保存", color = DesignerColors.Muted, fontSize = 9.sp, modifier = Modifier.padding(end = 8.dp))
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
            DesignerAction("撤销", enabled = undo.isNotEmpty() && !busy) { redo = (redo + draft).takeLast(100); draft = undo.last(); undo = undo.dropLast(1); selection = emptySet() }
            DesignerAction("恢复", enabled = redo.isNotEmpty() && !busy) { undo = (undo + draft).takeLast(100); draft = redo.last(); redo = redo.dropLast(1); selection = emptySet() }
            DesignerAction("预览", enabled = draft.fields.isNotEmpty() && !busy) { preview = true }
            DesignerAction(if (busy) "保存中" else "保存", enabled = !busy, selected = true, primary = true, onClick = ::save)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val layout = designerWorkspaceLayout(maxWidth.value, toolsVisible, inspectorVisible)
            Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (layout.showTools) DesignerToolbox(draft, page, selection, busy, Modifier.width(layout.toolboxWidth.dp).fillMaxHeight(), onSelect = ::select,
                    onPage = { pageId = it; selection = emptySet(); inspectorTab = "布局"; zoom = 1f },
                    onAdd = { kind -> runCatching { draft.add(kind, page.id) }.onSuccess { commit(it); selection = setOf(it.fields.last().id); if (!narrowScreen) inspectorVisible = true }.onFailure { message = it.message } },
                    onNewPage = { if (draft.pages.size < 16) { val new = UiPage(id = "page_${UUID.randomUUID().toString().take(8)}", title = "新页面"); commit(draft.copy(pages = draft.pages + new)); pageId = new.id; selection = emptySet(); zoom = 1f } },
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().height(24.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Text("${page.title} · ${page.width}×${page.height}", Modifier.weight(1f), fontSize = 10.sp, color = DesignerColors.Muted, maxLines = 1)
                        DesignerAction("−", enabled = zoom > .5f) { zoom = (zoom - .25f).coerceAtLeast(.5f) }
                        DesignerAction(if (zoom == 1f) "整页" else "${(zoom * 100).toInt()}%", selected = zoom == 1f) { zoom = 1f }
                        DesignerAction("+", enabled = zoom < 4f) { zoom = (zoom + .25f).coerceAtMost(4f) }
                        DesignerAction("吸附", selected = snap) { snap = !snap }
                    }
                    DesignerCanvas(draft, page, selection, zoom, snap, !busy, Modifier.weight(1f).fillMaxWidth(), onSelect = ::select, onClear = { selection = emptySet() },
                        onGestureStart = { id -> if (id !in selection) selection = setOf(id) }, onGestureChange = { next -> draft = next },
                        onGestureEnd = { before -> if (draft != before) { undo = (undo + before).takeLast(100); redo = emptyList() } }, directory = snapshot.directory,
                    )
                    DesignerCanvasActions(multi, selectedFields.size, !busy, canPosition, canAlign,
                        onMulti = { multi = !multi }, onCopy = ::duplicate, onDelete = ::deleteSelection,
                        onPosition = { attempt { draft.position(selection, it) } }, onAlign = { commit(draft.align(selection, it)) },
                        onDistribute = { commit(draft.distribute(selection, it)) },
                        organize = listOf(
                            DesignerMenuAction("缩小全部 · 75%", !busy && draft.fields.isNotEmpty()) { commit(draft.shrinkAll()) },
                            DesignerMenuAction("当前页三列排版", !busy && draft.fields.any { it.ui.pageId == page.id }) { attempt { draft.compactPage(page.id) } },
                        ),
                    )
                }
                if (layout.showInspector) DesignerInspector(draft, page, field, selectedFields.size, inspectorTab, !busy, canAlign, snapshot, Modifier.width(layout.inspectorWidth.dp).fillMaxHeight(),
                    onTab = { inspectorTab = it }, onField = ::change,
                    onPage = { next -> commit(draft.copy(pages = draft.pages.map { if (it.id == next.id) next else it })) },
                    onDescription = { commit(draft.copy(description = it)) },
                    onReparent = { parent, target -> attempt { draft.reparent(requireNotNull(field).id, parent, target) } },
                    onAlign = { commit(draft.align(selection, it)) }, onDistribute = { commit(draft.distribute(selection, it)) },
                    onLayer = { commit(draft.layer(selection, it)) }, onNudge = { dx, dy -> commit(draft.translate(selection, dx, dy)) },
                    onDeletePage = {
                        if (draft.fields.any { it.ui.pageId == page.id } || draft.fields.any { it.ui.events.values.any { e -> e == "page:${page.id}" } }) message = "请先移走控件及页面事件引用"
                        else { commit(draft.copy(pages = draft.pages.filterNot { it.id == page.id })); pageId = draft.pages.first().id }
                    },
                )
            }
        }
        Text(message ?: if (selection.isEmpty()) "点击左侧添加控件 · 拖动移动 · 蓝色角点缩放 · 默认整页适配" else "已选 ${selection.size} 个 · ${field?.id.orEmpty()} · 拖动移动，方向键微调", Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 8.dp), fontSize = 9.sp, color = if (message != null) DesignerColors.Accent else DesignerColors.Muted, maxLines = 1)
    }
    if (preview) {
        val json = runCatching { draft.toRunnerUiJson() }.getOrNull()
        if (json == null) { preview = false; message = "请先修正控件属性" } else ProjectInterfacePreview(json, snapshot.directory) { preview = false }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("有未保存修改") }, text = { Text("退出会丢弃本次未保存的界面修改。") }, confirmButton = { TextButton(onClick = onBack) { Text("丢弃并退出") } }, dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续编辑") } })
}
