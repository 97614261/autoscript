package com.autoscript.studio

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.client.RuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
internal fun EditorDraftRecovery(
    snapshot: ProjectSnapshot, store: ProjectStore, editors: Map<String, VisualEditorState>,
    editorRevision: Int, client: RuntimeClient, canApply: () -> Boolean,
    onRecovered: (String) -> Unit, onMessage: (String) -> Unit,
) {
    val projectId = snapshot.manifest.projectId
    val context = LocalContext.current
    val journal = remember(projectId) { EditorDraftJournal(context.filesDir) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentCanApply by rememberUpdatedState(canApply)
    var loaded by remember(projectId) { mutableStateOf(false) }
    var checkpoint by remember(projectId) { mutableStateOf<EditorDraftCheckpoint?>(null) }
    var readFailed by remember(projectId) { mutableStateOf(false) }
    var busy by remember(projectId) { mutableStateOf(false) }
    var problem by remember(projectId) { mutableStateOf<String?>(null) }
    var checkpointRevision by remember(projectId) { mutableIntStateOf(0) }
    var promptVisible by remember(projectId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    DisposableEffect(journal) { onDispose { journal.nextRevision() } }
    LaunchedEffect(journal) {
        try {
            checkpoint = withContext(Dispatchers.IO) { journal.read(projectId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { readFailed = true; problem = "恢复记录无法读取，未覆盖正式项目" }
        loaded = true
    }
    // Reserve the ticket before IO: an older cancelled writer cannot replace a newer checkpoint.
    val writeTicket = remember(journal, editorRevision, snapshot.manifest, checkpointRevision) { journal.nextRevision() }
    LaunchedEffect(loaded, checkpoint, readFailed, writeTicket) {
        if (!loaded || checkpoint != null || readFailed) return@LaunchedEffect
        try {
            delay(300)
            coroutineContext.ensureActive()
            val frozen = journal.freeze(snapshot, editors)
            withContext(Dispatchers.IO) {
                val captured = journal.capture(snapshot, frozen)
                coroutineContext.ensureActive()
                journal.writeIfCurrent(projectId, captured, writeTicket)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onMessage("恢复草稿写入失败，请主动保存；正式项目未被覆盖") }
    }
    if (promptVisible && (checkpoint != null || readFailed)) {
        val retained = checkpoint
        val compatible by produceState<List<EditorDraftEntry>?>(null, retained, snapshot) {
            value = null
            value = withContext(Dispatchers.Default) { retained?.let { journal.compatible(it, snapshot) }.orEmpty() }
        }
        val entry = compatible?.firstOrNull()
        fun defer() {
            if (busy) return
            promptVisible = false
            onMessage("恢复记录已保留，重新打开项目可继续处理；本次自动草稿记录暂停，正式保存仍可使用")
        }
        StudioConfirmDialog(
            onDismissRequest = ::defer,
            title = { Text("恢复编辑草稿") },
            text = { Text(problem ?: when {
                readFailed -> "恢复记录无效。可以明确丢弃后继续编辑，正式脚本不受影响。"
                compatible == null -> "正在核对恢复记录与正式项目…"
                entry == null -> "项目版本或插件已变化，不能应用这份旧草稿。正式脚本不会被覆盖。"
                else -> "发现未保存的插件草稿（${compatible.orEmpty().size}个）。逐个校验并恢复到编辑器，可撤销；不会直接保存或运行。"
            }) },
            compact = true,
            actions = listOf(
                StudioConfirmAction("保留并关闭", enabled = !busy, onClick = ::defer),
                StudioConfirmAction("丢弃记录", enabled = !busy, destructive = true, onClick = {
                busy = true
                val ticket = journal.nextRevision()
                scope.launch {
                    try {
                        check(withContext(Dispatchers.IO) { journal.discardIfCurrent(projectId, ticket) })
                        checkpoint = null; readFailed = false; problem = null
                        checkpointRevision++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { problem = "恢复记录清理失败，请重试" }
                    finally { busy = false }
                }
            }),
                StudioConfirmAction(if (busy) "处理中" else "校验并恢复", enabled = !busy && entry != null, onClick = restore@{
                val target = entry ?: return@restore
                val editor = editors[target.flowId] ?: return@restore
                val inspected = currentSnapshot
                val expectedDrafts = editors.mapValues { it.value.currentSource }
                val candidateDrafts = editors.filterValues { it.isDirty }
                    .mapValues { it.value.currentSource.toByteArray(Charsets.UTF_8) }
                    .plus(target.flowId to target.source.toByteArray(Charsets.UTF_8))
                if (editor.isDirty) { problem = "当前插件已有修改，未覆盖，请先处理当前草稿"; return@restore }
                busy = true
                scope.launch {
                    try {
                        val disk = withContext(Dispatchers.IO) { store.openProject(projectId) }
                        if (retained == null || target !in journal.compatible(retained, disk) ||
                            disk.manifest != inspected.manifest || disk.flowSources != inspected.flowSources) {
                            problem = "正式项目已变化，恢复已阻止"; return@launch
                        }
                        var diskStillCompatible = false
                        var validationFinished = false
                        problem = applyValidatedPluginSource(editor, editor.currentSource, target.source, {
                            currentCanApply() && currentSnapshot.manifest == inspected.manifest &&
                                editors.mapValues { it.value.currentSource } == expectedDrafts &&
                                (!validationFinished || diskStillCompatible)
                        }) {
                            val result = withContext(Dispatchers.IO) {
                                val compiled = client.validateVisualDraftSet(projectId, candidateDrafts)
                                val latest = store.openProject(projectId)
                                diskStillCompatible = target in journal.compatible(retained, latest) &&
                                    latest.manifest == disk.manifest && latest.flowSources == disk.flowSources
                                compiled
                            }
                            validationFinished = true
                            result
                        }
                        if (problem == null) {
                            val remaining = retained.entries.filterNot { it.flowId == target.flowId }
                            checkpoint = retained.copy(entries = remaining).takeIf { remaining.isNotEmpty() }
                            onRecovered(target.flowId)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { problem = "恢复失败，原记录保留，可重试" }
                    finally { busy = false }
                }
            }),
            ),
        )
    }
}
