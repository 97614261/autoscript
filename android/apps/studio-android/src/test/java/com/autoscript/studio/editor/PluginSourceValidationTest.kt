package com.autoscript.studio

import com.autoscript.runtime.client.VisualCompileDiagnostic
import com.autoscript.runtime.client.VisualCompileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginSourceValidationTest {
    @Test fun compilerNodeOrJsonlLocationMapsToExactSourceLineAndColumn() {
        val document = com.autoscript.studio.compiler.VisualSourceDocument("", linkedMapOf(
            "node-1" to com.autoscript.studio.compiler.SourceLocation(2, 1),
            "node-10" to com.autoscript.studio.compiler.SourceLocation(8, 3),
        ))
        // An overlapping ID must not accidentally select node-1.
        assertEquals("第 8 行，第 3 列：节点 node-10：能力未声明",
            pluginSourceProblemMessage(document, "节点 node-10：能力未声明"))
        assertEquals("第 8 行，第 3 列：JSONL 第 2 行：参数错误",
            pluginSourceProblemMessage(document, "JSONL 第 2 行：参数错误"))
        assertEquals("未连接", pluginSourceProblemMessage(document, "未连接"))
    }

    private fun editor() = VisualEditorState.create("", "root").apply { insertNoop() }
    private fun replacement(source: String) = source.replace("\"args\":{}", "\"args\":{\"note\":\"validated\"}")

    @Test fun successfulValidationCreatesExactlyOneUndoStep() = runBlocking {
        val editor = editor()
        val baseline = editor.currentSource
        val replacement = replacement(baseline)
        assertNotEquals(baseline, replacement)
        assertNull(applyValidatedPluginSource(editor, baseline, replacement, { true }) { VisualCompileResult.Success("test") })
        assertEquals(replacement, editor.currentSource)
        assertTrue(editor.undo())
        assertEquals(baseline, editor.currentSource)
        assertTrue(editor.redo())
        assertEquals(replacement, editor.currentSource)
    }

    @Test fun compilerErrorAndUnavailableServiceNeverWriteDraft() = runBlocking {
        val editor = editor()
        val baseline = editor.currentSource
        val diagnostic = VisualCompileDiagnostic("capability", "能力未声明", "main", "test-node", 1)
        val error = applyValidatedPluginSource(editor, baseline, replacement(baseline), { true }) { VisualCompileResult.Invalid(diagnostic) }
        assertTrue(error!!.contains("test-node"))
        assertTrue(error.contains("能力未声明"))
        assertEquals(baseline, editor.currentSource)
        assertEquals("未连接", applyValidatedPluginSource(editor, baseline, replacement(baseline), { true }) { VisualCompileResult.Unavailable("未连接") })
        assertEquals(baseline, editor.currentSource)
    }

    @Test fun changedDraftDuringValidationCannotBeOverwritten() = runBlocking {
        val editor = editor()
        val baseline = editor.currentSource
        var concurrent = ""
        assertNotNull(applyValidatedPluginSource(editor, baseline, replacement(baseline), { true }) {
            editor.insertNoop()
            concurrent = editor.currentSource
            VisualCompileResult.Success("test")
        })
        assertEquals(concurrent, editor.currentSource)
    }

    @Test fun switchedPluginOrRunningStateCannotApplyLateSuccess() = runBlocking {
        val editor = editor()
        val baseline = editor.currentSource
        var live = true
        assertNotNull(applyValidatedPluginSource(editor, baseline, replacement(baseline), { live }) {
            live = false
            VisualCompileResult.Success("test")
        })
        assertEquals(baseline, editor.currentSource)
    }

    @Test fun staleOrOversizedInputDoesNotEvenInvokeCompiler() = runBlocking {
        val editor = editor()
        val baseline = editor.currentSource
        val validator: suspend () -> VisualCompileResult = { error("must not validate") }
        assertNotNull(applyValidatedPluginSource(editor, "stale", "", { true }, validator))
        assertNotNull(applyValidatedPluginSource(editor, baseline, "x".repeat(1_048_577), { true }, validator))
        assertEquals(baseline, editor.currentSource)
    }

    @Test fun cancelledValidationNeverWritesEvenIfValidatorReturnsSuccess() {
        val editor = editor()
        val baseline = editor.currentSource
        try {
            runBlocking {
                applyValidatedPluginSource(editor, baseline, replacement(baseline), { true }) {
                    currentCoroutineContext().cancel()
                    VisualCompileResult.Success("late")
                }
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(baseline, editor.currentSource)
        }
    }
}
