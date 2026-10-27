package com.autoscript.studio

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import com.autoscript.runtime.api.RuntimeLogFiles
import com.autoscript.runtime.client.RuntimeClient
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

/** Public owned data, actual shared panels and Binder compilation; no script execution. */
internal object EditorFinalAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient,
        store: ProjectStore, initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val projectId = initial.manifest.projectId
        var snapshot = store.createFlow(projectId, "qa_second", initial.manifest.flows.map { it.flowId }.toSet())
        val mainId = requireNotNull(snapshot.manifest.entryFlowId)
        fun row(id: String, root: String, order: String, kind: String, args: String) =
            """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$root","parentId":null,"orderKey":"$order","kind":"$kind","nodeVersion":1,"args":$args}"""
        val sources = snapshot.manifest.flows.associate { flow -> flow.flowId to if (flow.flowId == mainId) listOf(
            row("1", flow.rootBlockId, "a0", "task.log", """{"message":"QA_OLD main","level":"info"}"""),
            row("2", flow.rootBlockId, "a1", "task.sleep", """{"milliseconds":100,"exactTiming":false}"""),
            row("3", flow.rootBlockId, "a2", "task.sleep", """{"milliseconds":200,"exactTiming":false}"""),
        ).joinToString("\n", postfix = "\n") else row("4", flow.rootBlockId, "a0", "task.log",
            """{"message":"QA_OLD second","level":"info"}""") + "\n" }
        snapshot = store.saveFlows(snapshot, sources)
        val editors = snapshot.manifest.flows.associate { it.flowId to VisualEditorState.create(sources.getValue(it.flowId), it.rootBlockId) }
        val edits = EditorProjectEdits()
        fun flows() = snapshot.manifest.flows.map { EditorSearchFlow(it.flowId, it.flowId,
            editors.getValue(it.flowId).currentSource, it.rootBlockId) }
        fun drafts() = editors.mapValues { it.value.currentSource }
        fun validate(text: Map<String, String>): VisualCompileResult = client.validateVisualDraftSet(projectId,
            text.mapValues { it.value.toByteArray(Charsets.UTF_8) })
        check(validate(drafts()) is VisualCompileResult.Success) { "QA_CLOSEOUT_BASELINE_INVALID" }
        var panel by mutableStateOf("")
        var compact by mutableStateOf(false)
        val session = EditorSearchSession().apply { query = "QA_OLD" }
        var frozen = flows()
        var selected: EditorProjectSearchHit? = null
        var batch: EditorBatchParameters? = null
        var applied = 0
        var rejected = false
        val live = mutableStateListOf("脚本/INFO: QA_LIVE_FIRST")
        val logId = UUID.randomUUID().toString()
        val logDir = RuntimeLogFiles.directory(test.targetContext.filesDir, logId)
        check(!logDir.exists() && logDir.mkdirs()) { "QA_CLOSEOUT_LOG_DIRECTORY" }
        try {
            Properties().apply {
                setProperty("formatVersion", "1"); setProperty("projectId", projectId)
                setProperty("startedAtMs", System.currentTimeMillis().toString()); setProperty("status", "stopped")
            }.let { properties -> File(logDir, "metadata.properties").outputStream().use { properties.store(it, null) } }
            File(logDir, "script.log").writeText((1..1024).joinToString("\n") { "脚本/INFO: QA_HISTORY_$it" })
            File(logDir, "diagnostic.log").writeText("Runner诊断 · QA_HISTORY_DIAGNOSTIC\n")
            File(logDir, "events.jsonl").writeText("")
            test.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density * if (compact) 1.5f else 1f,
                        if (compact) 1.5f else 1f)) {
                        AutoScriptTheme {
                            Text("QA_CLOSEOUT_BACKGROUND")
                            key(panel) {
                                when (panel) {
                                    "search" -> EditorProjectSearchDialog(frozen, mainId, session, { frozen = flows() },
                                        { panel = "replace" }, { panel = "" }) { hit, close ->
                                        editorSearchTarget(hit, frozen, editors[hit.flowId]).also { failure ->
                                            if (failure == null) { selected = hit; if (close) panel = "" }
                                        }
                                    }
                                    "replace" -> EditorProjectReplacementDialog(frozen, emptyList(), mainId,
                                        "QA_OLD", "all", "all", { panel = "" }) { candidate ->
                                        val before = drafts()
                                        val validation = withContext(Dispatchers.IO) { validate(before + candidate.sources) }
                                        check(validation is VisualCompileResult.Success) { "QA_CLOSEOUT_REPLACE_COMPILE" }
                                        if (rejected) "QA_PUBLIC_REJECTION_KEEP_INPUT" else
                                            edits.apply(before, candidate.sources, editors).also { if (it == null) applied++ }
                                    }
                                    "batch" -> EditorBatchParametersDialog(requireNotNull(batch), { panel = "" }) { candidate ->
                                        val request = requireNotNull(batch)
                                        applyEditorBatch(request, candidate, editors.getValue(mainId), { true }) {
                                            withContext(Dispatchers.IO) { validate(drafts() + (mainId to candidate.source)) }
                                        }.also { if (it == null) applied++ }
                                    }
                                    "logs" -> RuntimeLogDialog(live, projectId, onDismiss = { panel = "" })
                                }
                            }
                        }
                    }
                }
            }
            fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (n == null || !n.refresh()) return null
                    if (predicate(n)) return n
                    for (index in 0 until n.childCount) find(n.getChild(index))?.let { return it }
                    return null
                }
                return test.uiAutomation.rootInActiveWindow?.takeIf {
                    it.packageName?.toString() == test.targetContext.packageName
                }?.let(::find)
            }
            fun label(text: String) = node { it.text?.toString() == text || it.contentDescription?.toString() == text }
            fun await(description: String, predicate: () -> Boolean) {
                val until = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < until) {
                    if (predicate()) return
                    SystemClock.sleep(60)
                }
                val labels = mutableListOf<String>()
                fun inspect(n: AccessibilityNodeInfo?) {
                    if (n == null || !n.refresh() || labels.size >= 60) return
                    (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { labels += it.take(100) }
                    for (index in 0 until n.childCount) inspect(n.getChild(index))
                }
                inspect(test.uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == test.targetContext.packageName })
                test.sendStatus(1, Bundle().apply { putString("stream", "QA_PUBLIC_CLOSEOUT_TREE:$labels\n") })
                error("QA_CLOSEOUT_WAIT:$description")
            }
            fun click(text: String) {
                await(text) { label(text) != null }
                var n = label(text)
                n?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                repeat(8) {
                    if (n?.isClickable == true && n?.isEnabled == true) {
                        check(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "QA_CLOSEOUT_CLICK:$text" }
                        SystemClock.sleep(120); return
                    }
                    n = n?.parent
                }
                error("QA_CLOSEOUT_NOT_CLICKABLE:$text")
            }
            fun input(index: Int, value: String) {
                val fields = mutableListOf<AccessibilityNodeInfo>()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null || !n.refresh()) return
                    if (n.isEditable) fields += n
                    for (i in 0 until n.childCount) collect(n.getChild(i))
                }
                collect(test.uiAutomation.rootInActiveWindow)
                check(fields.getOrNull(index)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }) == true) { "QA_CLOSEOUT_SET_TEXT:$index" }
                SystemClock.sleep(120)
            }
            fun open(value: String) { test.runOnMainSync { frozen = flows(); panel = value } }
            record("closeout-search-next-previous-fullscreen-and-session-retained") {
                open("search")
                await("two hits") { node { it.text?.startsWith("找到2行") == true } != null }
                click("下一条"); check(selected?.flowId == mainId)
                click("下一条"); check(selected?.flowId == "qa_second")
                click("上一条"); check(selected?.flowId == mainId)
                click("最大化全屏"); await("search full") { session.fullScreen }
                click("还原窗口"); click("关闭")
                await("search closed") { label("全项目查找") == null }
                open("search"); await("search retained") { label("QA_OLD") != null && session.active != null }
                click("关闭")
            }
            record("closeout-replacement-rejection-retains-preview-then-real-compile-and-grouped-history") {
                rejected = true; open("replace"); await("replace field") { label("预览改动") != null }
                input(1, "QA_NEW"); click("预览改动")
                await("replace preview") { label("校验并替换") != null }
                click("校验并替换")
                await("retained rejected preview") { label("QA_PUBLIC_REJECTION_KEEP_INPUT") != null }
                check(drafts() == sources && applied == 0)
                rejected = false; click("校验并替换")
                await("replace committed") { label("全项目替换") == null && applied == 1 }
                check(editors.values.all { !it.currentSource.contains("QA_OLD") })
                test.runOnMainSync { check(edits.moveHistory(editors.getValue(mainId), false, editors) == null) }
                check(drafts() == sources)
                test.runOnMainSync { check(edits.moveHistory(editors.getValue(mainId), true, editors) == null) }
                check(editors.values.all { it.currentSource.contains("QA_NEW") })
            }
            record("closeout-multiple-batch-fields-real-validation-one-undo") {
                val editor = editors.getValue(mainId)
                test.runOnMainSync {
                    check(editor.selectNodes(listOf("2", "3")))
                    batch = requireNotNull(editorBatchParameters(projectId, mainId,
                        snapshot.manifest.flows.first { it.flowId == mainId }.rootBlockId, editor))
                }
                val before = editor.currentSource
                open("batch"); click(batch!!.fields.first { it.path == "milliseconds" }.label)
                input(0, "300")
                click(batch!!.fields.first { it.path == "exactTiming" }.label); click("开启")
                click("预览并确认"); await("batch confirmation") { label("确认修改2行") != null }
                click("校验并应用"); await("batch committed") { applied == 2 && label("批量参数 · 2行") == null }
                val args = variableCheckNodes(editor.variableCheckInput()).filter { it.row.nodeId in setOf("2", "3") }.map { requireNotNull(it.args) }
                check(args.all { it["milliseconds"].asInt == 300 && it["exactTiming"].asBoolean })
                test.runOnMainSync { check(editor.undo()) }
                check(editor.currentSource == before)
            }
            record("closeout-replacement-discard-confirmation-does-not-change-draft") {
                val before = drafts()
                open("replace"); await("replace reopen") { label("预览改动") != null }
                input(1, "UNSAVED_PUBLIC_TEXT"); click("取消")
                await("discard confirmation") { label("放弃本次替换？") != null }
                click("继续编辑"); await("input retained") { label("UNSAVED_PUBLIC_TEXT") != null }
                click("取消"); click("放弃"); await("discarded") { label("全项目替换") == null }
                check(before == drafts() && applied == 2)
            }
            record("closeout-search-narrow-large-font-fixed-actions-reachable") {
                test.runOnMainSync { compact = true; session.query = "QA_NEW"; session.filtersExpanded = true }
                open("search"); await("narrow results") { node { it.text?.startsWith("找到2行") == true } != null }
                for (text in listOf("上一条", "下一条", "刷新", "替换")) {
                    val n = requireNotNull(label(text)); val bounds = Rect().also(n::getBoundsInScreen)
                    val window = test.uiAutomation.windows.first { it.id == n.windowId }
                    check(Rect().also(window::getBoundsInScreen).contains(bounds) && bounds.height() > 0) { "QA_CLOSEOUT_NARROW_ACTION:$text" }
                }
                click("下一条"); click("关闭")
                test.runOnMainSync { compact = false }
            }
            record("closeout-live-log-mutable-snapshot-and-history-filter-channel-switch") {
                open("logs"); await("live line") { label("脚本/INFO: QA_LIVE_FIRST") != null }
                test.runOnMainSync { live += "脚本/ERROR: QA_LIVE_SECOND" }
                await("live update") { label("脚本/ERROR: QA_LIVE_SECOND") != null }
                click("仅错误"); await("live filter") { label("脚本/INFO: QA_LIVE_FIRST") == null }
                click("历史"); await("history loaded") { label("脚本/INFO: QA_HISTORY_1024") != null }
                input(0, "QA_HISTORY_1000"); await("history filter") { label("脚本/INFO: QA_HISTORY_1000") != null }
                check(label("脚本/INFO: QA_HISTORY_1024") == null)
                input(0, ""); click("状态")
                await("history diagnostic") { label("Runner诊断 · QA_HISTORY_DIAGNOSTIC") != null }
                click("脚本"); await("history returned") { label("脚本/INFO: QA_HISTORY_1024") != null }
                click("最大化全屏"); await("log full") { label("还原窗口") != null }
                click("关闭"); await("log closed") { label("运行日志") == null }
            }
            record("closeout-log-narrow-large-font-close-and-fullscreen-remain-reachable") {
                test.runOnMainSync { compact = true }; open("logs")
                await("compact log") { label("运行日志") != null }
                click("最大化全屏"); await("compact full") { label("还原窗口") != null }
                click("还原窗口"); click("关闭")
                await("compact log closed") { label("运行日志") == null }
            }
            check(store.openProject(projectId).flowSources == sources) { "QA_CLOSEOUT_DRAFTS_AUTO_SAVED" }
            return snapshot
        } finally {
            test.runOnMainSync { panel = ""; compact = false; activity.setContent { AutoScriptTheme { Text("QA_CLOSEOUT_DONE") } } }
            val cleanup = RuntimeLogFiles.cleanup(test.targetContext.filesDir, projectId, setOf(logId))
            check(logId in cleanup.deleted) { "QA_CLOSEOUT_OWN_LOG_CLEANUP" }
        }
    }
}
