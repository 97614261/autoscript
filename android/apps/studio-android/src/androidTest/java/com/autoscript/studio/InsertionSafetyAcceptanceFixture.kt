package com.autoscript.studio

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.autoscript.core.designsystem.AutoScriptTheme
import com.google.gson.JsonObject

/** Simulate external draft/selection changes in a public in-memory editor, never private data. */
internal object InsertionSafetyAcceptanceFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, record: (String, () -> Unit) -> Unit) {
        val contract = requireNotNull(BlockCatalog.find("task.sleep"))
        val editor = VisualEditorState.create("", "qa-insertion-root")
        repeat(2) { editor.insertBlock(contract, JsonObject().apply { addProperty("milliseconds", 10) }) }
        var revision by mutableIntStateOf(0)
        var submitted by mutableIntStateOf(0)
        val message = "PUBLIC_QA_STALE_INSERTION"
        fun node(label: String): AccessibilityNodeInfo? {
            fun visit(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (current == null) return null
                if (!current.refresh()) { current.recycle(); return null }
                if (current.packageName?.toString() == test.targetContext.packageName && current.isVisibleToUser &&
                    (current.text?.toString() == label || current.contentDescription?.toString() == label ||
                        (label == "QA_EDITABLE" && current.isEditable))) return current
                for (index in 0 until current.childCount.coerceAtMost(128)) visit(current.getChild(index))?.let { current.recycle(); return it }
                current.recycle(); return null
            }
            for (window in test.uiAutomation.windows.sortedByDescending { it.layer }.take(32)) visit(window.root)?.let { return it }
            return null
        }
        fun exists(label: String) = node(label)?.let { it.recycle(); true } ?: false
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(condition()) { "QA_INSERT_SAFETY_TIMEOUT" }
        }
        fun click(label: String) {
            await { exists(label) }
            val target = requireNotNull(node(label))
            val rect = android.graphics.Rect().also(target::getBoundsInScreen); target.recycle()
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(test.uiAutomation.injectInputEvent(event, true)) { "QA_INSERT_SAFETY_TOUCH" } }
                finally { event.recycle() }
            }
        }
        fun fill() {
            val field = requireNotNull(node("QA_EDITABLE"))
            try { check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            })) } finally { field.recycle() }
        }
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            val nodes = remember(revision) { editor.programDisplayNodes() }
            EditorDock("PUBLIC_QA_INSERT_SAFETY", {}, {}, initiallyExpanded = true,
                currentFlowId = "main", sourceName = "main", programSource = editor.currentSource,
                programNodes = nodes, programSelectedNodeId = editor.selectedNodeId, programSelectedNodeIds = editor.selectedNodeIds,
                capabilities = setOf("core.task"), modifier = Modifier.fillMaxSize(), onOpenSettings = {},
                onInsertPositioned = { hint, position, committed ->
                    val kind = requireNotNull(BlockCatalog.find("task.runprompt"))
                    val args = legacyDockBlockArguments(kind, hint, JsonObject().apply { addProperty("message", "") })
                    check(applyConfiguredEditorInsertion(editorInsertionContext("project-qa-memory", "main", editor),
                        "project-qa-memory", "main", editor, kind, args, position, null, setOf("core.task"), true) == null)
                    submitted++; revision++; committed()
                })
        } } }
        try {
            record("stale-position-selection-rejected-without-losing-debug-input") {
                click("输出"); await { exists("QA_EDITABLE") }; fill()
                click("加入"); await { exists("加入位置") }
                test.runOnMainSync { editor.selectOnly(editor.rows.first().nodeId); revision++ }
                click("确定"); await { exists("未加入积木") }; click("确定")
                await { exists(message) }
                check(submitted == 0 && editor.rows.size == 2) { "QA_STALE_SELECTION_INSERTED" }
            }
            record("stale-position-source-rejected-without-undo-or-selection-side-effects") {
                click("加入"); await { exists("加入位置") }
                test.runOnMainSync { editor.insertBlock(contract, JsonObject().apply { addProperty("milliseconds", 20) }); revision++ }
                val source = editor.currentSource; val primary = editor.selectedNodeId
                click("确定"); await { exists("未加入积木") }; click("确定")
                await { exists(message) }
                check(submitted == 0 && editor.currentSource == source && editor.selectedNodeId == primary) { "QA_STALE_SOURCE_INSERTED" }
            }
            record("retry-after-stale-rejection-commits-once-and-closes-only-original-form") {
                val before = editor.currentSource
                click("加入"); await { exists("加入位置") }; click("确定")
                await { !exists("弹窗样式") && submitted == 1 }
                val inserted = requireNotNull(editor.selectedNodeId)
                check(editor.nodeArguments(inserted)?.get("message")?.asString == message) { "QA_RETRY_PARAMETERS_LOST" }
                test.runOnMainSync { check(editor.undo()); revision++ }
                check(editor.currentSource == before) { "QA_RETRY_NOT_ONE_UNDO" }
            }
        } finally { test.runOnMainSync { activity.setContent {} } }
    }
}
