package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EditorCloseoutTest {
    private fun row(id: String, kind: String, args: String, extra: String = "", order: String = "a0",
        block: String = "root", parent: String? = null) =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,${extra}"args":$args}"""
    private fun editor(text: String = "old") = VisualEditorState.create(
        row("n", "task.log", """{"message":"$text","level":"info"}"""), "root")
    private fun flow(id: String, editor: VisualEditorState) = EditorSearchFlow(id, id, editor.currentSource, "root")
    private fun preview(flows: List<EditorSearchFlow>) = editorReplacementPreview(flows, "text", "old", "new", emptySet())

    @Test fun textReplacementChangesOnlySelectedTypedFieldsAndKeepsEnvelope() {
        val source = row("n", "task.log", """{"message":"old old","level":"info","valueVariable":"old"}""",
            """"display":{"alias":"old alias","showParameters":true},"disabled":true,""")
        val editor = VisualEditorState.create(source, "root")
        val flows = listOf(flow("main", editor))
        val prepared = preview(flows)
        assertEquals(listOf("message"), prepared.changes.map { it.path })
        val candidate = editorReplacementCandidate(flows, prepared, prepared.changes.map { it.key }.toSet())
        val expected = JsonParser.parseString(source).asJsonObject.apply { getAsJsonObject("args").addProperty("message", "new new") }
        assertEquals(expected, JsonParser.parseString(candidate.sources.getValue("main")))
        assertEquals(1, candidate.nodes)
        assertEquals(1, candidate.fields)
        assertEquals(source, editor.currentSource)
    }

    @Test fun variableBindingsAndScalarTypesCannotBeOverwritten() {
        val bound = VisualEditorState.create(row("n", "task.runprompt", """{"message":"old","messageVariable":"文字"}"""), "root")
        assertTrue(preview(listOf(flow("main", bound))).changes.isEmpty())
        val numeric = VisualEditorState.create(row("n", "variable.set", """{"name":"old","value":10}"""), "root")
        assertTrue(preview(listOf(flow("main", numeric))).changes.isEmpty())
        val literal = VisualEditorState.create(row("n", "variable.set", """{"name":"old","value":"old"}"""), "root")
        assertEquals(listOf("value"), preview(listOf(flow("main", literal))).changes.map { it.path })
    }

    @Test fun imageReplacementRequiresDeclaredResourcesAndDoesNotChangeOtherText() {
        val images = setOf("assets/images/old.png", "assets/images/new.png")
        val editor = VisualEditorState.create(row("n", "vision.findimage", """{"imagePath":"assets/images/old.png","frameVariable":"old"}"""), "root")
        val flows = listOf(flow("main", editor))
        val prepared = editorReplacementPreview(flows, "image", images.first(), images.last(), images)
        assertEquals(listOf("imagePath"), prepared.changes.map { it.path })
        val candidate = editorReplacementCandidate(flows, prepared, prepared.changes.map { it.key }.toSet())
        val args = JsonParser.parseString(candidate.sources.getValue("main")).asJsonObject.getAsJsonObject("args")
        assertEquals("assets/images/new.png", args["imagePath"].asString)
        assertEquals("old", args["frameVariable"].asString)
        assertThrows(IllegalArgumentException::class.java) {
            editorReplacementPreview(flows, "image", images.first(), "assets/images/missing.png", images)
        }
    }

    @Test fun disabledSubtreesAreSearchableAndReplacementRespectsFilterAndFlow() {
        val source = listOf(
            row("loop", "control.repeat", """{"times":3}""", """"childBlocks":{"body":"body"},"disabled":true,"""),
            row("child", "task.log", """{"message":"old","level":"info"}""", block = "body", parent = "loop"),
            row("live", "task.log", """{"message":"old","level":"info"}""", order = "a1")
        ).joinToString("\n")
        val editor = VisualEditorState.create(source, "root")
        assertFalse(editor.isReadOnly)
        val flows = listOf(flow("a", editor), flow("b", editor))
        val hits = editorProjectSearch(editorProjectSearchIndex(flows), "old", "all")
        assertTrue(hits.first { it.nodeId == "child" }.disabled)
        assertFalse(hits.first { it.nodeId == "live" }.disabled)
        assertTrue(hits.first { it.nodeId == "child" }.parameters.any { it.second == "old" })
        val disabled = editorReplacementPreview(flows, "text", "old", "new", emptySet(), "a", "disabled")
        assertEquals(listOf("a" to "child"), disabled.changes.map { it.flowId to it.nodeId })
        val enabled = editorReplacementPreview(flows, "text", "old", "new", emptySet(), "b", "enabled")
        assertEquals(listOf("b" to "live"), enabled.changes.map { it.flowId to it.nodeId })
    }

    @Test fun previewCancellationAndUnknownSelectionNeverProducePartialCandidate() {
        val flows = listOf(flow("a", editor()))
        assertThrows(CancellationException::class.java) { previewCancelled(flows) }
        val prepared = preview(flows)
        assertThrows(IllegalArgumentException::class.java) { editorReplacementCandidate(flows, prepared, setOf("unknown")) }
        assertThrows(CancellationException::class.java) {
            editorReplacementCandidate(flows, prepared, prepared.changes.map { it.key }.toSet()) { throw CancellationException("closed") }
        }
    }

    private fun previewCancelled(flows: List<EditorSearchFlow>) =
        editorReplacementPreview(flows, "text", "old", "new", emptySet()) { throw CancellationException("closed") }

    @Test fun multiPluginReplacementUndoRedoPreservesEarlierHistoryAndNeverMovesPartially() {
        val a = editor(); val b = editor()
        a.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject())
        b.insertBlock(requireNotNull(BlockCatalog.find("task.noop")), JsonObject())
        val editors = mapOf("a" to a, "b" to b)
        val before = editors.mapValues { it.value.currentSource }
        val flows = editors.map { flow(it.key, it.value) }
        val prepared = preview(flows)
        val after = editorReplacementCandidate(flows, prepared, prepared.changes.map { it.key }.toSet()).sources
        val edits = EditorProjectEdits()
        assertNull(edits.apply(before, after, editors))
        assertFalse(a.undo())
        assertNull(edits.moveHistory(a, false, editors))
        assertEquals(before, editors.mapValues { it.value.currentSource })
        assertNull(edits.moveHistory(b, true, editors))
        assertEquals(after, editors.mapValues { it.value.currentSource })
        b.insertNoop()
        val changed = editors.mapValues { it.value.currentSource }
        assertNotNull(edits.moveHistory(a, false, editors))
        assertEquals(changed, editors.mapValues { it.value.currentSource })
        assertTrue(b.undo())
        assertNull(edits.moveHistory(a, false, editors))
        assertEquals(before, editors.mapValues { it.value.currentSource })
        assertTrue(a.undo())
        assertTrue(b.undo())
        assertEquals(1, a.rows.size)
        assertEquals(1, b.rows.size)
    }

    @Test fun replacedEditorOrInvalidOneOfSeveralCandidatesLeavesEveryDraftUntouched() {
        val a = editor(); val b = editor()
        val editors = mapOf("a" to a, "b" to b)
        val before = editors.mapValues { it.value.currentSource }
        val edits = EditorProjectEdits()
        assertNotNull(edits.apply(before, mapOf("a" to editor("new").currentSource, "b" to "invalid"), editors))
        assertEquals(before, editors.mapValues { it.value.currentSource })
        assertTrue(editors.values.none { it.canUndo })
        assertNull(edits.apply(before, editors.mapValues { editor("new").currentSource }, editors))
        val applied = a.currentSource
        assertNotNull(edits.moveHistory(a, false, mapOf("a" to a, "b" to editor("new"))))
        assertEquals(applied, a.currentSource)
    }

    @Test fun booleanAndNumericBatchFieldsApplyTogetherWithoutTouchingOtherArguments() {
        val source = listOf(row("a", "task.sleep", """{"milliseconds":100,"exactTiming":false}"""),
            row("b", "task.sleep", """{"milliseconds":200,"exactTiming":false}""", order = "a1")).joinToString("\n")
        val editor = VisualEditorState.create(source, "root")
        editor.selectNodes(listOf("a", "b"))
        val model = requireNotNull(editorBatchParameters("project", "main", "root", editor))
        assertTrue(model.fields.map { it.path }.containsAll(listOf("milliseconds", "exactTiming")))
        val candidate = editorBatchCandidate(model, mapOf("milliseconds" to "300", "exactTiming" to "true"))
        assertEquals(2, candidate.changed)
        candidate.source.lineSequence().forEach {
            val args = JsonParser.parseString(it).asJsonObject.getAsJsonObject("args")
            assertEquals(300, args["milliseconds"].asInt)
            assertTrue(args["exactTiming"].asBoolean)
        }
        assertEquals(source, editor.currentSource)
        assertThrows(IllegalArgumentException::class.java) { editorBatchCandidate(model, mapOf("exactTiming" to "yes")) }
    }
}
