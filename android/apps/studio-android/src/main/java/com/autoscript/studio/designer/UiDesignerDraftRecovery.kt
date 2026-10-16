package com.autoscript.studio

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.*

internal enum class UiDesignerRecoveryAvailability(val message: String) {
    INVALID("恢复记录无法读取，已保留原记录。正式界面未修改。"),
    CHANGED("项目设置已变化，这份记录不能直接恢复。正式界面未修改。"),
    EDITING("当前设计已有修改，不能用旧记录覆盖。可保留或丢弃旧记录。"),
    READY("发现未保存的界面设计。恢复为可撤销草稿，不会直接保存或运行。"),
}

internal fun uiDesignerRecoveryAvailability(
    failed: Boolean, record: UiDesignerCheckpoint?, base: String,
    draft: RunnerUiDesignerDraft, saved: RunnerUiDesignerDraft,
): UiDesignerRecoveryAvailability = when {
    failed || record == null -> UiDesignerRecoveryAvailability.INVALID
    record.baseHash != base -> UiDesignerRecoveryAvailability.CHANGED
    draft != saved -> UiDesignerRecoveryAvailability.EDITING
    else -> UiDesignerRecoveryAvailability.READY
}

internal enum class UiDesignerRecoveryAction(val label: String) {
    KEEP("保留并关闭"), DISCARD("丢弃记录"), RESTORE("恢复草稿"),
}

internal fun uiDesignerRecoveryActions(availability: UiDesignerRecoveryAvailability): List<UiDesignerRecoveryAction> =
    listOf(UiDesignerRecoveryAction.KEEP, UiDesignerRecoveryAction.DISCARD) +
        if (availability == UiDesignerRecoveryAvailability.READY) listOf(UiDesignerRecoveryAction.RESTORE) else emptyList()

@Composable
internal fun UiDesignerDraftRecovery(
    journal: UiDesignerDraftJournal, snapshot: ProjectSnapshot, store: ProjectStore,
    draft: RunnerUiDesignerDraft, saved: RunnerUiDesignerDraft, busy: Boolean,
    onRestore: (RunnerUiDesignerDraft) -> Unit, onMessage: (String) -> Unit,
    reopenRequest: Int = 0, onPendingChanged: (Boolean) -> Unit = {},
) {
    var loaded by remember(journal) { mutableStateOf(false) }
    var record by remember(journal) { mutableStateOf<UiDesignerCheckpoint?>(null) }
    var failed by remember(journal) { mutableStateOf(false) }
    var working by remember(journal) { mutableStateOf<UiDesignerRecoveryAction?>(null) }
    var problem by remember(journal) { mutableStateOf<String?>(null) }
    var promptVisible by remember(journal) { mutableStateOf(true) }
    val currentDraft by rememberUpdatedState(draft)
    val currentSaved by rememberUpdatedState(saved)
    val currentBusy by rememberUpdatedState(busy)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val scope = rememberCoroutineScope()
    val base = remember(journal, snapshot.manifest) { journal.base(snapshot.manifest) }

    LaunchedEffect(reopenRequest) { promptVisible = true }
    LaunchedEffect(loaded, record, failed) { onPendingChanged(!loaded || record != null || failed) }
    DisposableEffect(journal) { onDispose { journal.ticket() } }
    LaunchedEffect(journal) {
        try { record = withContext(Dispatchers.IO) { journal.read() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; onMessage("设计恢复记录无效，已保留原记录") }
        loaded = true
    }
    val ticket = remember(journal, draft, base, loaded, record, failed) { journal.ticket() }
    LaunchedEffect(ticket) {
        if (!loaded || record != null || failed) return@LaunchedEffect
        delay(300)
        try { withContext(Dispatchers.IO) { journal.write(base, draft.takeIf { it != saved }, ticket) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onMessage("设计恢复记录写入失败，请主动保存") }
    }

    fun defer() {
        if (working == null) {
            promptVisible = false
            onMessage("恢复记录已保留，可点「恢复草稿」重新打开；处理前暂停自动草稿记录")
        }
    }
    fun discard() {
        if (working != null || busy) return
        working = UiDesignerRecoveryAction.DISCARD
        problem = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { journal.discard() }
                record = null
                failed = false
                promptVisible = false
                onMessage("已丢弃设计恢复记录，正式界面未修改")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                problem = "清理恢复记录失败，原记录保留"
                onMessage(problem!!)
            } finally { working = null }
        }
    }
    fun restore() {
        val candidate = record ?: return
        if (working != null || busy ||
            uiDesignerRecoveryAvailability(failed, candidate, base, draft, saved) != UiDesignerRecoveryAvailability.READY) return
        val inspected = snapshot
        val expected = draft
        working = UiDesignerRecoveryAction.RESTORE
        problem = null
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { store.openProject(inspected.manifest.projectId) }
                if (!currentBusy && currentSaved == expected && currentDraft == expected &&
                    currentSnapshot.manifest == inspected.manifest && journal.base(latest.manifest) == candidate.baseHash) {
                    onRestore(candidate.draft)
                    record = null
                    promptVisible = false
                } else {
                    problem = "设计上下文或正式项目已变化，未恢复"
                    onMessage(problem!!)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                problem = "恢复失败，原记录保留"
                onMessage(problem!!)
            } finally { working = null }
        }
    }

    if (promptVisible && (record != null || failed)) {
        val availability = uiDesignerRecoveryAvailability(failed, record, base, draft, saved)
        StudioConfirmDialog(
            onDismissRequest = ::defer,
            title = { Text("恢复界面设计") },
            text = { Text(listOfNotNull(availability.message, problem).joinToString("\n")) },
            compact = true,
            actions = uiDesignerRecoveryActions(availability).map { action ->
                StudioConfirmAction(
                    label = if (working == action) "处理中…" else action.label,
                    enabled = working == null && (action == UiDesignerRecoveryAction.KEEP || !busy),
                    destructive = action == UiDesignerRecoveryAction.DISCARD,
                    onClick = {
                        when (action) {
                            UiDesignerRecoveryAction.KEEP -> defer()
                            UiDesignerRecoveryAction.DISCARD -> discard()
                            UiDesignerRecoveryAction.RESTORE -> restore()
                        }
                    },
                )
            },
        )
    }
}
