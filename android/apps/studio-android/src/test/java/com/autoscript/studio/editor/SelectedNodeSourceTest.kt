package com.autoscript.studio

import com.autoscript.studio.compiler.SourceLocation
import com.autoscript.studio.compiler.VisualSourceException
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import com.autoscript.runtime.client.VisualCompileResult
import org.junit.Assert.*
import org.junit.Test

class SelectedNodeSourceTest {
    private fun node(kind: String, id: String, args: String = "{}", block: String = "root",
        parent: String? = null, depth: Int = 0, children: Map<String, String> = emptyMap()) = JsonObject().apply {
        addProperty("flowSchemaVersion", 1)
        addProperty("nodeId", id)
        addProperty("blockId", block)
        add("parentId", parent?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("orderKey", "A")
        addProperty("kind", kind)
        addProperty("nodeVersion", requireNotNull(BlockCatalog.find(kind)).nodeVersion)
        addProperty("depth", depth)
        add("args", JsonParser.parseString(args))
        if (children.isNotEmpty()) add("childBlocks", JsonObject().apply { children.forEach { (k, v) -> addProperty(k, v) } })
    }

    @Test fun allRegisteredKindsOpenWithoutExposingIdentityOrChangingData() {
        BlockCatalog.all.forEach { contract ->
            val original = node(contract.kind, "node", children = contract.childBlocks.associateWith { "child-$it" })
                .apply { addProperty("disabled", true) }
            val source = "$original\n"
            val descriptor = SelectedNodeSource.create(source, "node")
            assertFalse(contract.kind, descriptor.text.contains("--@node"))
            assertEquals(contract.kind, source, descriptor.parse(descriptor.text, emptySet()).text)
        }
    }

    @Test fun editingNestedRowPreservesEveryOtherLineAndStructuralIdentity() {
        val parent = node("control.repeat", "owner", "{\"times\":3}", children = mapOf("body" to "body"))
        val child = node("input.tap", "child", "{\"x\":10,\"y\":20}", "body", "owner", 1)
        val other = node("task.comment", "other", "{\"message\":\"unchanged\"}").apply { addProperty("orderKey", "B") }
        val source = "\n$parent\n$child\n\n$other\n"
        val descriptor = SelectedNodeSource.create(source, "child")
        val edited = descriptor.parse(descriptor.text.replace("[\"x\"] = 10", "[\"x\"] = 42"), emptySet()).text
        val originalLines = source.split('\n')
        val editedLines = edited.split('\n')
        assertEquals(originalLines.size, editedLines.size)
        originalLines.indices.filter { it != 2 }.forEach { assertEquals(originalLines[it], editedLines[it]) }
        val result = JsonParser.parseString(editedLines[2]).asJsonObject
        assertEquals(42, result["args"].asJsonObject["x"].asInt)
        result.remove("args")
        child.remove("args")
        assertEquals(child, result)
    }

    @Test fun containerParameterEditKeepsOriginalDescendantsAndEmptySkeleton() {
        val container = node("control.repeat", "loop", "{\"times\":3}", children = mapOf("body" to "body"))
        val child = node("task.comment", "inside", "{\"message\":\"keep\"}", "body", "loop", 1)
        val source = "$container\n$child\n"
        val descriptor = SelectedNodeSource.create(source, "loop")
        assertFalse(descriptor.text.contains("keep"))
        val result = descriptor.parse(descriptor.text.replace("[\"times\"] = 3", "[\"times\"] = 7"), emptySet()).text
        assertEquals(child.toString(), result.lines()[1])
        assertEquals(7, JsonParser.parseString(result.lines()[0]).asJsonObject["args"].asJsonObject["times"].asInt)
    }

    @Test fun deletingAddingChangingKindIdentityOrChildrenCannotEscapeSingleRowScope() {
        val source = "${node("input.tap", "tap", "{\"x\":1,\"y\":2}")}\n"
        val descriptor = SelectedNodeSource.create(source, "tap")
        listOf("", "Task.Comment({message=\"replace\"})", descriptor.text + "\nTask.Noop({})",
            "--@node {\"id\":\"foreign\",\"disabled\":false}\n" + descriptor.text).forEach { text ->
            assertThrows(VisualSourceException::class.java) { descriptor.parse(text, emptySet()) }
        }
        val loopSource = "${node("control.repeat", "loop", "{\"times\":3}", children = mapOf("body" to "body"))}\n"
        val loop = SelectedNodeSource.create(loopSource, "loop")
        assertThrows(VisualSourceException::class.java) { loop.parse(loop.text.replace("\nend", "\n Task.Noop({})\nend"), emptySet()) }
        assertThrows(VisualSourceException::class.java) { loop.parse(loop.text.replace("for _ = 1, Repeat", "while Condition"), emptySet()) }
    }

    @Test fun localErrorLocationsExcludeInjectedIdentityAndCancellationPropagates() {
        val source = "${node("input.tap", "tap")}\n"
        val descriptor = SelectedNodeSource.create(source, "tap")
        val error = assertThrows(VisualSourceException::class.java) { descriptor.parse("os.execute({})", emptySet()) }
        assertEquals(1, error.location.line)
        assertThrows(CancellationException::class.java) { descriptor.parse(descriptor.text, emptySet()) { throw CancellationException() } }
        assertThrows(IllegalArgumentException::class.java) { SelectedNodeSource.create(source, "missing") }
    }

    @Test fun quickRowListAndPluginSourceOffsetPointAtActualSelectedStatement() {
        val source = "\n${node("task.comment", "a", "{\"message\":\"first\"}")}\n${node("input.tap", "b").apply { addProperty("orderKey", "B") }}\n"
        val rows = pluginSourceRows(source)
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(listOf(0, 1), rows.map { it.index })
        val exported = pluginSourceToolchain.export(source, "root")
        val at = sourceRowOffset(exported.text, exported.nodes.getValue("b"))
        assertTrue(exported.text.substring(at).startsWith("Input.Tap("))
        assertEquals(0, sourceRowOffset("", SourceLocation(1, 1)))
    }

    @Test fun selectedRowUsesFullDraftValidationAndExactlyOneUndoableMutation() = runBlocking {
        val a = node("task.comment", "a", "{\"message\":\"first\"}")
        val b = node("task.comment", "b", "{\"message\":\"second\"}").apply { addProperty("orderKey", "B") }
        val source = "$a\n$b\n"
        val editor = VisualEditorState.create(source, "root")
        editor.selectOnly("b")
        val descriptor = SelectedNodeSource.create(source, "b")
        val replacement = descriptor.parse(descriptor.text.replace("second", "edited"), emptySet()).text
        var validated = false
        assertNull(applyValidatedPluginSource(editor, source, replacement, { true }) {
            assertEquals(2, pluginSourceRows(replacement).size)
            assertEquals(source.lines()[0], replacement.lines()[0])
            validated = true
            VisualCompileResult.Success("test")
        })
        assertTrue(validated)
        assertEquals("b", editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertTrue(editor.redo())
        assertEquals(replacement, editor.currentSource)
    }

    @Test fun fullDraftErrorsOnlyMapToSelectedRowsThatHaveLocalLocations() {
        val source = "${node("task.comment", "a")}\n${node("input.tap", "b")}\n"
        val descriptor = SelectedNodeSource.create(source, "b")
        val document = descriptor.parse(descriptor.text, emptySet())
        assertEquals("JSONL 第 1 行：参数错误", pluginSourceProblemMessage(document, "JSONL 第 1 行：参数错误"))
        assertEquals("第 1 行，第 1 列：JSONL 第 2 行：参数错误",
            pluginSourceProblemMessage(document, "JSONL 第 2 行：参数错误"))
        assertEquals("JSONL 第 0 行：参数错误", pluginSourceProblemMessage(document, "JSONL 第 0 行：参数错误"))
    }

    @Test fun quickSelectionFollowsProgramTreeInsteadOfPhysicalJsonlOrder() {
        val source = "${node("task.comment", "b", "{\"message\":\"second\"}")}\n${node("task.comment", "a", "{\"message\":\"first\"}")}\n"
        val rows = pluginSourceRows(source, listOf("a", "b"))
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(listOf(0, 1), rows.map { it.index })
        assertTrue(rows.first().label.contains("first"))
    }
}
