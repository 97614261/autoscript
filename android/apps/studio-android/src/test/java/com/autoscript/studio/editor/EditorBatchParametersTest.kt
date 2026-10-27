package com.autoscript.studio

import com.autoscript.runtime.client.VisualCompileDiagnostic
import com.autoscript.runtime.client.VisualCompileResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorBatchParametersTest {
    private fun node(id: String, kind: String = "task.sleep", args: String = """{"milliseconds":100}""",
        block: String = "root", parent: String? = null, order: String = "a0", extra: String = "") =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,${extra}"args":$args}"""

    private fun editor(firstArgs: String = """{"milliseconds":100}"""): VisualEditorState =
        VisualEditorState.create(listOf(node("a", args = firstArgs), node("b", order = "a1")).joinToString("\n"), "root")
            .also { assertTrue(it.selectNodes(listOf("a", "b"))) }

    private fun request(editor: VisualEditorState) = requireNotNull(editorBatchParameters("project", "main", "root", editor))
    private val valid = VisualCompileResult.Success("generation")

    @Test fun sameKindSelectedRowsExposeOnlyApprovedLiteralNumericAndBooleanFields() {
        val editor = editor()
        val model = request(editor)
        assertEquals("task.sleep", model.kind)
        assertEquals(listOf(1, 2), model.rows)
        assertEquals(listOf("milliseconds", "exactTiming"), model.fields.map { it.path })
        assertEquals(listOf("100", "100"), model.fields.single { it.path == "milliseconds" }.values)
        editor.selectOnly("a")
        assertNull(editorBatchParameters("project", "main", "root", editor))
    }

    @Test fun mixedKindsAndVariableBindingsAreNotOffered() {
        val mixed = VisualEditorState.create(node("a") + "\n" + node("b", "task.noop", "{}", order = "a1"), "root")
        mixed.selectNodes(listOf("a", "b"))
        assertNull(editorBatchParameters("project", "main", "root", mixed))
        listOf("\"delay\"", "{}", "[]", "false", "12").forEach { binding ->
            val bound = editor("""{"milliseconds":100,"millisecondsVariable":$binding}""")
            assertTrue("Binding $binding must not be overwritten", editorBatchParameters("project", "main", "root", bound)
                ?.fields.orEmpty().none { it.path == "milliseconds" })
        }
        assertNotNull(editorBatchParameters("project", "main", "root", editor("""{"milliseconds":100,"millisecondsVariable":null}""")))
    }

    @Test fun numericInputRejectsFractionNegativeOverflowAndThresholdOverflow() {
        listOf("", "no", "1.5", "-1", "2147483648", "NaN", "9".repeat(33)).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { editorBatchValue("milliseconds", input) }
        }
        assertThrows(IllegalArgumentException::class.java) { editorBatchValue("similarityPermille", "1001") }
        assertThrows(IllegalArgumentException::class.java) { editorBatchValue("tolerance", "256") }
        assertEquals(1000, editorBatchValue("similarityPermille", "1000").toInt())
        assertEquals(255, editorBatchValue("tolerance", "255").toInt())
    }

    @Test fun candidateCountsActualChangesAndDoesNotMutateEditor() {
        val editor = editor("""{"milliseconds":200}""")
        val source = editor.currentSource
        val candidate = editorBatchCandidate(request(editor), "milliseconds", "200")
        assertEquals(1, candidate.changed)
        assertEquals(source, editor.currentSource)
        assertThrows(IllegalArgumentException::class.java) { editorBatchCandidate(request(editor), "resultVariable", "1") }
        assertThrows(IllegalArgumentException::class.java) { editorBatchCandidate(request(editor()), "milliseconds", "100") }
    }

    @Test fun selectedContainersDoNotImplicitlyChangeTheirChildrenOrEnvelope() {
        val source = listOf(
            node("outer", "control.repeat", """{"times":2,"indexVariable":"index"}""", extra = """"childBlocks":{"body":"body"},"disabled":true,"display":{"alias":"Loop","showParameters":true},"""),
            node("child", "control.repeat", """{"times":3}""", "body", "outer", extra = """"childBlocks":{"body":"inner"},"""),
            node("other", "control.repeat", """{"times":4}""", order = "a1", extra = """"childBlocks":{"body":"other-body"},"""),
        ).joinToString("\n")
        val editor = VisualEditorState.create(source, "root")
        assertFalse(editor.isReadOnly)
        editor.selectNodes(listOf("outer", "other"))
        val candidate = editorBatchCandidate(request(editor), "times", "9")
        val before = editor.currentSource.lineSequence().map { JsonParser.parseString(it).asJsonObject }.associateBy { it["nodeId"].asString }
        val after = candidate.source.lineSequence().map { JsonParser.parseString(it).asJsonObject }.associateBy { it["nodeId"].asString }
        assertEquals(before["child"], after["child"])
        listOf("outer", "other").forEach { id ->
            val expected = requireNotNull(before[id]).deepCopy().apply { getAsJsonObject("args").addProperty("times", 9) }
            assertEquals(expected, after[id])
        }
        assertEquals(2, candidate.changed)
    }

    @Test fun successfulValidationCommitsOnceAndPreservesSelection() = runBlocking {
        val editor = editor()
        val old = editor.currentSource
        val model = request(editor)
        val selected = editor.selectedNodeIds
        val candidate = editorBatchCandidate(model, "milliseconds", "250")
        assertNull(applyEditorBatch(model, candidate, editor, { true }) { valid })
        assertEquals(250, editor.nodeArguments("a")?.get("milliseconds")?.asInt)
        assertEquals(selected, editor.selectedNodeIds)
        assertTrue(editor.isDirty)
        assertTrue(editor.undo())
        assertEquals(old, editor.currentSource)
        assertFalse(editor.undo())
    }

    @Test fun failedOrUnavailableWholeProjectValidationDoesNotChangeAnyRow() = runBlocking {
        listOf(VisualCompileResult.Invalid(VisualCompileDiagnostic("INVALID_NODE_ARGUMENTS", "bad other plugin", "other", "node", 1)),
            VisualCompileResult.Unavailable("disconnected")).forEach { failure ->
            val editor = editor(); val old = editor.currentSource; val model = request(editor)
            assertNotNull(applyEditorBatch(model, editorBatchCandidate(model, "milliseconds", "250"), editor, { true }) { failure })
            assertEquals(old, editor.currentSource)
            assertFalse(editor.undo())
        }
    }

    @Test fun changingSelectionOrOtherContextDuringValidationRejectsCommit() = runBlocking {
        val editor = editor(); val old = editor.currentSource; val model = request(editor)
        val candidate = editorBatchCandidate(model, "milliseconds", "250")
        assertNotNull(applyEditorBatch(model, candidate, editor, { true }) { editor.selectOnly("b"); valid })
        assertEquals(old, editor.currentSource)
        editor.selectNodes(listOf("a", "b"))
        var current = true
        assertNotNull(applyEditorBatch(request(editor), candidate, editor, { current }) { current = false; valid })
        assertEquals(old, editor.currentSource)
    }

    @Test fun staleSourceRejectsBeforeValidationAndCancellationPropagatesWithoutChanges() = runBlocking {
        val editor = editor(); val model = request(editor)
        val candidate = editorBatchCandidate(model, "milliseconds", "250")
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject())
        val changed = editor.currentSource
        assertNotNull(applyEditorBatch(model, candidate, editor, { true }) { fail("Stale drafts must not compile"); valid })
        assertEquals(changed, editor.currentSource)
        val fresh = editor()
        try {
            applyEditorBatch(request(fresh), candidate, fresh, { true }) { throw CancellationException("closed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(model.context.source, fresh.currentSource) }
    }
}
