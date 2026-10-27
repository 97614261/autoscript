package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RecognitionBranchesTest {
    @Test fun newSearchesExposeSelectableEmptyBranchHeadingsAndAcceptOperations() {
        for (kind in listOf("vision.findimage", "vision.findgray", "vision.findallimages")) {
            val editor = VisualEditorState.create("", "root")
            val contract = requireNotNull(BlockCatalog.find(kind))
            val owner = requireNotNull(editor.insertBlock(contract, JsonObject()))
            assertEquals(listOf("success", "failure"), editorBranchSlots(kind, editor.childBlockNames(owner)))
            val rows = editorTreeDisplayRows(editor.programDisplayNodes().toEditorTreeNodes())
            val headings = rows.filterIsInstance<EditorTreeDisplayRow.Branch>()
            assertEquals(listOf("success", "failure"), headings.map { it.slot })
            assertTrue(headings.all { it.empty && it.nodeId == null })
            assertTrue(EditorBranchSelection(owner, "failure", editor.currentSource).matches(editor))
            val child = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")),
                JsonObject(), intoChildBlockName = "failure"))
            assertEquals("failure", editor.rows.single { it.nodeId == child }.childSlot)
        }
    }

    @Test fun explicitUnchangedParameterSaveUpgradesLegacyLeafWithProjectScopedIdsInOneUndo() {
        for ((kind, version) in listOf("vision.findimage" to 2, "vision.findgray" to 1)) {
            val source = """{"flowSchemaVersion":1,"nodeId":"node-1","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":"$kind","nodeVersion":$version,"args":{}}"""
            val ids = VisualProjectIds(listOf(source), listOf("root", "block-400"))
            val editor = VisualEditorState.createProjectScoped(source, "root", ids)
            val migrated = editor.currentSource
            assertFalse(editor.isReadOnly)
            assertTrue(editor.childBlockNames("node-1").isEmpty())
            assertFalse(editor.canUndo)
            val exported = pluginSourceToolchain.export(migrated, "root")
            val parsed = pluginSourceToolchain.parse(exported.text, migrated, "root", emptySet())
            assertEquals(JsonParser.parseString(migrated), JsonParser.parseString(parsed.text))
            assertTrue(applyReflectedArguments(editor, "node-1", requireNotNull(BlockCatalog.find(kind)), JsonObject()))
            val node = JsonParser.parseString(editor.currentSource).asJsonObject
            val slots = node.getAsJsonObject("childBlocks")
            assertEquals(setOf("block-401", "block-402"), slots.entrySet().map { it.value.asString }.toSet())
            assertEquals("node-1", node["nodeId"].asString)
            assertTrue(editor.undo())
            assertEquals(migrated, editor.currentSource)
            assertFalse(editor.canUndo)
            assertTrue(editor.redo())
            assertEquals(setOf("success", "failure"), editor.childBlockNames("node-1").toSet())
        }
    }

    @Test fun parameterUpdatesPreserveBranchChildrenAndCopyAndSourceRoundTrip() {
        for (kind in listOf("vision.findimage", "vision.findgray")) {
            val editor = VisualEditorState.create("", "root")
            val owner = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find(kind)), JsonObject()))
            val child = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("task.noop")),
                JsonObject(), intoChildBlockName = "success"))
            val before = editor.currentSource
            assertTrue(editor.updateArguments(owner, JsonObject().apply { addProperty("similarityPermille", 950) }))
            assertTrue(editor.rows.any { it.nodeId == child && it.childSlot == "success" })
            val restored = VisualEditorState.create(editor.currentSource, "root")
            assertEquals(editor.currentSource, restored.currentSource)
            assertEquals(setOf("success", "failure"), restored.childBlockNames(owner).toSet())
            val exported = pluginSourceToolchain.export(editor.currentSource, "root")
            val parsed = pluginSourceToolchain.parse(exported.text, editor.currentSource, "root", emptySet())
            fun objects(source: String) = source.lineSequence().filter(String::isNotBlank).map {
                JsonParser.parseString(it)
            }.toList()
            assertEquals(objects(editor.currentSource), objects(parsed.text))
            val leaf = pluginSourceToolchain.export(JsonParser.parseString(editor.currentSource.lineSequence().first())
                .asJsonObject.apply { remove("childBlocks") }.toString(), "root")
            assertThrows(com.autoscript.studio.compiler.VisualSourceException::class.java) {
                pluginSourceToolchain.parse(leaf.text, editor.currentSource, "root", emptySet())
            }
            restored.selectOnly(owner)
            val clipboard = requireNotNull(restored.copySelectedSubtree())
            val copied = requireNotNull(restored.pasteSubtree(clipboard))
            assertNotEquals(owner, copied)
            assertEquals(setOf("success", "failure"), restored.childBlockNames(copied).toSet())
            assertEquals(2, restored.rows.count { it.kind == "task.noop" && it.childSlot == "success" })
            assertEquals(restored.rows.size, restored.rows.map { it.nodeId }.distinct().size)
            assertTrue(editor.undo())
            assertEquals(before, editor.currentSource)
        }
    }
}
