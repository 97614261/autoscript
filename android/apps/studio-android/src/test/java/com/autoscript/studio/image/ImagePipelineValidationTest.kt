package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ImagePipelineValidationTest {
    private val blocks = imageProcessingBlocks(ImageProcessingPipeline(2, 3).append(ImagePixelOperation("gray")), null,
        "scratch", "result", listOf(CalculationVariable("scratch", ProjectVariableType.IMAGE, "局部"),
            CalculationVariable("result", ProjectVariableType.IMAGE, "局部")), emptyList())
    private val capabilities = blocks.flatMap { it.first.requiredCapabilities }.toSet()
    private fun request(editor: VisualEditorState) = requireNotNull(ImageBlockInsertionRequest.create("project", "main", editor, blocks, ImageInsertionOrigin.WORKBENCH))

    @Test fun numericRecognitionInsertionDoesNotReuseAnotherPluginsNodeIds() = runBlocking {
        for (kind in listOf("ocr.alphanumeric", "ocr.alphanumericv5", "ocr.glyph")) {
            val first = """{"flowSchemaVersion":1,"nodeVersion":1,"nodeId":"node-1","blockId":"block-1","parentId":null,"orderKey":"a0","depth":0,"kind":"task.noop","args":{}}""" + "\n"
            val ids = VisualProjectIds(listOf(first), listOf("block-1", "block-2"))
            val editor = VisualEditorState.createProjectScoped("", "block-2", ids)
            val recognition = recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find(kind)), JsonObject().apply {
                addProperty("frameVariable", "frame")
                addProperty("resultVariable", "digits")
            })
            val pending = requireNotNull(ImageBlockInsertionRequest.create("project", "digits", editor, recognition,
                ImageInsertionOrigin.CONFIGURATION))
            var validated = ""
            assertNull(kind, applyValidatedImageInsertion(pending, editor, "project", "digits",
                recognition.flatMap { it.first.requiredCapabilities }.toSet(), EditorInsertPosition.LIST_BOTTOM, null, { true }) { candidate ->
                assertEquals("", editor.currentSource)
                val rows = parseVisualNodes(first, "block-1") + parseVisualNodes(candidate, "block-2")
                assertEquals(kind, rows.size, rows.map { it.nodeId }.toSet().size)
                validated = candidate
                VisualCompileResult.Success("synthetic-identity-check")
            })
            assertEquals(validated, editor.currentSource)
            assertEquals(recognition.map { it.first.kind }, editor.rows.map { it.kind })
            assertEquals("node-2", editor.selectedNodeId)
            assertTrue(editor.undo())
            assertEquals("", editor.currentSource)
            assertFalse(editor.canUndo)
        }
    }

    @Test fun allPositionsCommitOnlyExactValidatedCandidateAsOneUndo() = runBlocking {
        for (position in listOf(EditorInsertPosition.ABOVE, EditorInsertPosition.BELOW, EditorInsertPosition.LIST_BOTTOM, EditorInsertPosition.INSIDE)) {
            val editor = VisualEditorState.create("", "root")
            val parent = editor.insertBlock(requireNotNull(BlockCatalog.find("control.if")), com.google.gson.JsonObject())
            editor.selectOnly(parent)
            val before = editor.currentSource
            var validated = ""
            assertNull(applyValidatedImageInsertion(request(editor), editor, "project", "main", capabilities, position,
                if (position == EditorInsertPosition.INSIDE) "then" else null, { true }) { candidate ->
                assertEquals(before, editor.currentSource)
                validated = candidate
                VisualCompileResult.Success("synthetic")
            })
            assertEquals(validated, editor.currentSource)
            assertNotEquals(parent, editor.selectedNodeId)
            assertEquals("screen.capture", editor.rows.first { it.nodeId == editor.selectedNodeId }.kind)
            assertTrue(editor.undo()); assertEquals(before, editor.currentSource)
        }
    }

    @Test fun compilerTimeoutReturnsFailureWithoutCommittingOrDroppingSelection() = runBlocking {
        val editor = VisualEditorState.create("", "root").apply { insertNoop() }
        val before = editor.currentSource; val selection = editor.selectedNodeId
        val problem = applyValidatedImageInsertion(request(editor), editor, "project", "main", capabilities,
            EditorInsertPosition.BELOW, null, { true }) {
            withTimeout(1) { delay(100); VisualCompileResult.Success("too-late") }
        }
        assertTrue(problem.orEmpty().contains("超时"))
        assertEquals(before, editor.currentSource); assertEquals(selection, editor.selectedNodeId)
        assertTrue(editor.undo()); assertEquals("", editor.currentSource)
    }

    @Test fun cancelledOwnerIsNotReportedAsOrdinaryTimeout() {
        val editor = VisualEditorState.create("", "root")
        try {
            runBlocking {
                val owner = currentCoroutineContext()
                applyValidatedImageInsertion(request(editor), editor, "project", "main", capabilities,
                    EditorInsertPosition.LIST_BOTTOM, null, { true }) {
                    owner.cancel()
                    withTimeout(1) { delay(100); VisualCompileResult.Success("cancelled") }
                }
            }
            fail("owner cancellation must propagate")
        } catch (_: CancellationException) { assertEquals("", editor.currentSource) }
    }

    @Test fun invalidUnavailableAndStaleRepliesNeverMutateLiveDraft() = runBlocking {
        val editor = VisualEditorState.create("", "root").apply { insertNoop() }
        val before = editor.currentSource
        val pending = request(editor)
        assertNotNull(applyValidatedImageInsertion(pending, editor, "project", "main", capabilities, EditorInsertPosition.BELOW, null, { true }) {
            VisualCompileResult.Unavailable("offline")
        })
        assertEquals(before, editor.currentSource)
        assertNotNull(applyValidatedImageInsertion(pending, editor, "project", "main", capabilities, EditorInsertPosition.BELOW, null, { true }) {
            editor.selectOnly(null)
            VisualCompileResult.Success("stale")
        })
        assertEquals(before, editor.currentSource)
        assertNotNull(applyValidatedImageInsertion(pending, editor, "project", "main", capabilities, EditorInsertPosition.BELOW, null, { true }) {
            error("stale selection must not validate")
        })
    }

    @Test fun cancellationAfterCompilerSuccessStillCannotCommit() {
        val editor = VisualEditorState.create("", "root")
        try {
            runBlocking {
                applyValidatedImageInsertion(request(editor), editor, "project", "main", capabilities, EditorInsertPosition.LIST_BOTTOM, null, { true }) {
                    currentCoroutineContext().cancel()
                    VisualCompileResult.Success("cancelled")
                }
            }
            fail("must cancel")
        } catch (_: CancellationException) { assertEquals("", editor.currentSource) }
    }

    @Test fun recognitionInvalidatedBeforeOrDuringValidationCannotCommit() = runBlocking {
        val editor = VisualEditorState.create("", "root")
        var current = false
        val pending = requireNotNull(ImageBlockInsertionRequest.create("project", "main", editor, blocks,
            ImageInsertionOrigin.WORKBENCH, eligibility = { current }))
        assertNull(pending.stage(editor, "project", "main", capabilities, EditorInsertPosition.LIST_BOTTOM, null))
        assertFalse(pending.apply(editor, "project", "main", capabilities, EditorInsertPosition.LIST_BOTTOM))
        assertNotNull(applyValidatedImageInsertion(pending, editor, "project", "main", capabilities,
            EditorInsertPosition.LIST_BOTTOM, null, { true }) { error("stale recognition must not validate") })
        current = true
        assertNotNull(applyValidatedImageInsertion(pending, editor, "project", "main", capabilities,
            EditorInsertPosition.LIST_BOTTOM, null, { true }) {
            current = false
            VisualCompileResult.Success("stale recognition")
        })
        assertEquals("", editor.currentSource)
        assertFalse(editor.canUndo)
        val failedGuard = requireNotNull(ImageBlockInsertionRequest.create("project", "main", editor, blocks,
            ImageInsertionOrigin.WORKBENCH, eligibility = { error("expired bitmap") }))
        assertFalse(failedGuard.isEligible())
        assertNull(failedGuard.stage(editor, "project", "main", capabilities, EditorInsertPosition.LIST_BOTTOM, null))
    }
}
