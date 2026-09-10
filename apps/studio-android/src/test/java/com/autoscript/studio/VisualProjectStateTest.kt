package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualProjectStateTest {
    @Test
    fun projectionUsesExplicitBlocksAndOrderKeysInsteadOfPhysicalLinesOrDepth() {
        val source = listOf(
            node("child-b", "block-body", "b0", "task.noop", parent = "loop", depth = 99),
            node("root-b", "block-root", "b0", "task.noop", depth = 7),
            node(
                "loop",
                "block-root",
                "a0",
                "control.loop",
                depth = 4,
                childBlocks = "\"childBlocks\":{\"body\":\"block-body\"},",
            ),
            node("child-a", "block-body", "a0", "task.noop", parent = "loop", depth = 0),
        ).joinToString("\n", postfix = "\n")

        val rows = parseVisualNodes(source, "block-root")

        assertEquals(listOf("loop", "child-a", "child-b", "root-b"), rows.map { it.nodeId })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
    }

    @Test
    fun corruptLineRemainsVisibleWithStableLocation() {
        val rows = parseVisualNodes("{broken\n", "block-root")

        assertEquals(1, rows.size)
        assertEquals("invalid-line-1", rows.single().nodeId)
        assertEquals(1, rows.single().sourceLine)
    }

    @Test
    fun editorInsertMoveDeleteAndUndoAreWholeTransactions() {
        val source = node("first", "block-root", "a0", "task.noop", depth = 0) + "\n"
        var id = 0
        val editor = VisualEditorState.create(source, "block-root") { "fixed-${++id}" }

        val second = editor.insertNoop("first")!!
        val third = editor.insertNoop(second)!!
        assertEquals(listOf("first", second, third), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 0, 0), editor.rows.map { it.depth })
        assertTrue(editor.moveSelected(-1))
        assertEquals(listOf("first", third, second), editor.rows.map { it.nodeId })
        assertTrue(editor.deleteSelected())
        assertEquals(listOf("first", second), editor.rows.map { it.nodeId })
        assertTrue(editor.undo())
        assertEquals(listOf("first", third, second), editor.rows.map { it.nodeId })
        assertTrue(editor.redo())
        assertEquals(listOf("first", second), editor.rows.map { it.nodeId })
    }

    @Test
    fun editorDeletesOwnedSubtreeAndUsesStableUniqueIds() {
        val source = listOf(
            node(
                "owner",
                "block-root",
                "a0",
                "control.loop",
                depth = 0,
                childBlocks = "\"childBlocks\":{\"body\":\"block-body\"},",
            ),
            node("child", "block-body", "a0", "task.noop", parent = "owner", depth = 1),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root") { "new" }
        editor.selectedNodeId = "owner"

        assertTrue(editor.deleteSelected())
        assertTrue(editor.rows.isEmpty())
        assertTrue(editor.undo())
        assertEquals(listOf("owner", "child"), editor.rows.map { it.nodeId })
        val inserted = editor.insertNoop("child")
        assertEquals("node-new", inserted)
        assertEquals(3, editor.rows.map { it.nodeId }.distinct().size)
    }

    @Test
    fun invalidSourceIsReadOnlyAndSaveSnapshotDoesNotLoseLaterEdit() {
        val invalid = VisualEditorState.create("{broken\n", "block-root")
        assertTrue(invalid.isReadOnly)
        assertEquals(null, invalid.insertNoop())

        val source = node("first", "block-root", "a0", "task.noop", depth = 0) + "\n"
        var id = 0
        val editor = VisualEditorState.create(source, "block-root") { "${++id}" }
        editor.insertNoop()
        val submitted = editor.currentSource
        editor.insertNoop()
        editor.markSaved(submitted)

        assertTrue(editor.isDirty)
        assertNotEquals(editor.savedSource, editor.currentSource)
        editor.markSaved(editor.currentSource)
        assertFalse(editor.isDirty)
    }

    @Test
    fun depthConflictIsVisibleButReadOnly() {
        val source = node("first", "block-root", "a0", "task.noop", depth = 9) + "\n"
        val editor = VisualEditorState.create(source, "block-root")

        assertTrue(editor.isReadOnly)
        assertEquals(listOf("first"), editor.rows.map { it.nodeId })
        assertEquals(null, editor.insertNoop())
    }

    @Test
    fun newerKnownNodeVersionIsReadOnly() {
        val source = node("future", "block-root", "a0", "task.noop", depth = 0)
            .replace("\"nodeVersion\":1", "\"nodeVersion\":2") + "\n"

        val editor = VisualEditorState.create(source, "block-root")

        assertTrue(editor.isReadOnly)
        assertEquals(listOf("future"), editor.rows.map { it.nodeId })
    }

    @Test
    fun editorCanInsertIntoNamedEmptyChildBlocks() {
        var id = 0
        val editor = VisualEditorState.create("", "block-root") { "${++id}" }
        val conditional = requireNotNull(BlockCatalog.find("control.if"))
        val owner = requireNotNull(
            editor.insertBlock(
                conditional,
                JsonObject().apply {
                    addProperty("variable", "ready")
                    addProperty("operator", "equals")
                    addProperty("value", true)
                },
            ),
        )
        val noop = requireNotNull(BlockCatalog.find("task.noop"))

        val thenNode = requireNotNull(
            editor.insertBlock(noop, JsonObject(), owner, intoChildBlockName = "then"),
        )
        editor.selectedNodeId = owner
        val elseNode = requireNotNull(
            editor.insertBlock(noop, JsonObject(), owner, intoChildBlockName = "else"),
        )

        assertEquals(listOf(owner, elseNode, thenNode), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 1, 1), editor.rows.map { it.depth })
        assertEquals(listOf(null, "else", "then"), editor.rows.map { it.childSlot })
        val before = editor.currentSource
        editor.selectedNodeId = owner
        assertEquals(null, editor.insertBlock(noop, JsonObject(), owner, "missing"))
        assertEquals(before, editor.currentSource)
    }

    private fun node(
        id: String,
        block: String,
        order: String,
        kind: String,
        parent: String? = null,
        depth: Int,
        childBlocks: String = "",
    ): String =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,$childBlocks"depth":$depth,"args":{}}"""
}
