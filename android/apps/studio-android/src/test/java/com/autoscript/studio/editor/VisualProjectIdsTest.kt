package com.autoscript.studio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VisualProjectIdsTest {
    @Test fun emptyPluginRootDeclarationsAreReservedEvenWithoutSourceRows() {
        val ids = VisualProjectIds(emptyList(), listOf("block-1", "block-90"))
        assertEquals("block-91", ids.allocate("block", emptySet()))
    }
    private fun source(id: String, root: String) =
        """{"flowSchemaVersion":1,"nodeVersion":1,"nodeId":"$id","blockId":"$root","parentId":null,"orderKey":"a0","depth":0,"kind":"task.noop","args":{}}""" + "\n"
    private fun nodes(source: String) = source.lineSequence().filter(String::isNotBlank)
        .map { JsonParser.parseString(it).asJsonObject }.toList()

    @Test fun allPluginDraftsShareNodeAndBlockHighWaterMarks() {
        val first = source("node-4", "block-8")
        val second = source("node-20", "block-12")
        val ids = VisualProjectIds(listOf(first, second))
        val a = VisualEditorState.createProjectScoped(first, "block-8", ids)
        val b = VisualEditorState.createProjectScoped(second, "block-12", ids)
        assertEquals("node-21", a.insertNoop())
        assertEquals("node-22", b.insertNoop())
        assertTrue(a.undo())
        assertEquals("node-23", b.insertNoop())
        assertTrue(a.redo())
        val all = nodes(a.currentSource) + nodes(b.currentSource)
        assertEquals(all.size, all.map { it["nodeId"].asString }.toSet().size)
        val loop = requireNotNull(BlockCatalog.find("control.repeat"))
        a.insertBlock(loop, JsonObject().apply { addProperty("times", 2) })
        b.insertBlock(loop, JsonObject().apply { addProperty("times", 2) })
        val blocks = (nodes(a.currentSource) + nodes(b.currentSource))
            .flatMap { it["childBlocks"]?.asJsonObject?.entrySet()?.map { entry -> entry.value.asString }.orEmpty() }
        assertEquals(setOf("block-13", "block-14"), blocks.toSet())
    }

    @Test fun stagedBatchesShareReservationsWithoutChangingTheLiveDraft() {
        val first = source("node-1", "block-1")
        val ids = VisualProjectIds(listOf(first), listOf("block-1", "block-2"))
        val a = VisualEditorState.createProjectScoped(first, "block-1", ids)
        val b = VisualEditorState.createProjectScoped("", "block-2", ids)
        val noop = requireNotNull(BlockCatalog.find("task.noop"))
        val blocks = List(3) { noop to JsonObject() }
        val staged = requireNotNull(b.stageBlockInsertion(blocks, EditorInsertPosition.LIST_BOTTOM, null, null))
        assertEquals(listOf("node-2", "node-3", "node-4"), nodes(staged).map { it["nodeId"].asString })
        assertEquals("", b.currentSource)
        assertNull(b.selectedNodeId)
        assertFalse(b.canUndo)
        // Rejecting a candidate must not make its identities available to another plugin.
        assertEquals("node-5", a.insertNoop())
        val retried = requireNotNull(b.stageBlockInsertion(blocks, EditorInsertPosition.LIST_BOTTOM, null, null))
        assertEquals(listOf("node-6", "node-7", "node-8"), nodes(retried).map { it["nodeId"].asString })
        assertNull(b.replaceRawSource("", retried))
        assertTrue(b.undo())
        assertEquals("node-9", a.insertNoop())
        assertTrue(b.redo())
        val all = nodes(a.currentSource) + nodes(b.currentSource)
        assertEquals(all.size, all.map { it["nodeId"].asString }.toSet().size)
    }

    @Test fun stagedForestsShareNodeAndBranchReservationsAcrossPlugins() {
        val first = source("node-1", "block-1")
        val ids = VisualProjectIds(listOf(first), listOf("block-1", "block-2", "block-3"))
        val a = VisualEditorState.createProjectScoped(first, "block-1", ids)
        val b = VisualEditorState.createProjectScoped("", "block-2", ids)
        val condition = requireNotNull(BlockCatalog.find("control.if"))
        val repeat = requireNotNull(BlockCatalog.find("control.repeat"))
        val noop = requireNotNull(BlockCatalog.find("task.noop"))
        a.insertBlock(condition, JsonObject())
        val forest = listOf(VisualBlockTree(repeat, JsonObject().apply { addProperty("times", 2) },
            mapOf("body" to listOf(VisualBlockTree(condition, JsonObject(),
                mapOf("then" to listOf(VisualBlockTree(noop, JsonObject()))))))))
        val staged = requireNotNull(b.stageTreeInsertion(forest, EditorInsertPosition.LIST_BOTTOM, null, null))
        assertEquals(listOf("node-3", "node-4", "node-5"), nodes(staged).map { it["nodeId"].asString })
        val branchIds = nodes(staged).flatMap { it["childBlocks"]?.asJsonObject?.entrySet()
            ?.map { entry -> entry.value.asString }.orEmpty() }
        assertEquals(setOf("block-6", "block-7", "block-8"), branchIds.toSet())
        assertEquals("", b.currentSource)
        assertFalse(b.canUndo)
        a.insertBlock(repeat, JsonObject().apply { addProperty("times", 1) })
        assertEquals("node-6", a.selectedNodeId)
        assertEquals("block-9", nodes(a.currentSource).last()["childBlocks"].asJsonObject["body"].asString)
        assertNull(b.replaceRawSource("", staged))
        val all = nodes(a.currentSource) + nodes(b.currentSource)
        assertEquals(all.size, all.map { it["nodeId"].asString }.toSet().size)
    }

    @Test fun copyPasteAndRawRecoveryReserveIdentitiesForOtherPlugins() {
        val first = source("node-1", "block-1")
        val second = source("node-2", "block-2")
        val ids = VisualProjectIds(listOf(first, second))
        val a = VisualEditorState.createProjectScoped(first, "block-1", ids)
        val b = VisualEditorState.createProjectScoped(second, "block-2", ids)
        a.selectedNodeId = "node-1"
        val clipboard = requireNotNull(a.copySelectedSubtree())
        assertNotNull(b.pasteSubtree(clipboard))
        assertEquals(setOf("node-2", "node-3"), nodes(b.currentSource).map { it["nodeId"].asString }.toSet())
        val restored = source("node-900", "block-1")
        assertNull(a.replaceRawSource(a.currentSource, restored))
        assertTrue(a.undo())
        assertEquals("node-901", b.insertNoop())
    }

    @Test fun sourceEditorAndVisualInsertionUseTheSameReservations() {
        val first = source("node-9", "block-1")
        val second = source("node-30", "block-40")
        val ids = VisualProjectIds(listOf(first, second))
        val a = VisualEditorState.createProjectScoped(first, "block-1", ids)
        val b = VisualEditorState.createProjectScoped(second, "block-40", ids)
        val parsed = pluginSourceToolchain.parse("for _ = 1, Repeat({times = 2}) do\nTask.Noop({})\nend", first,
            "block-1", emptySet(), allocateIdentity = a::allocateProjectIdentity)
        assertEquals(setOf("node-31", "node-32"), nodes(parsed.text).map { it["nodeId"].asString }.toSet())
        assertEquals("block-41", nodes(parsed.text).first()["childBlocks"].asJsonObject["body"].asString)
        // Canceling a source preview must not recycle its allocated IDs.
        assertEquals("node-33", b.insertNoop())
    }

    @Test fun emptySourceReservesItsDeclaredRootBlockAndRejectsBadAllocator() {
        val parsed = pluginSourceToolchain.parse("for _ = 1, Repeat({times = 2}) do\nTask.Noop({})\nend", "",
            "block-1", emptySet())
        assertEquals("block-2", nodes(parsed.text).first()["childBlocks"].asJsonObject["body"].asString)
        assertThrows(com.autoscript.studio.compiler.VisualSourceException::class.java) {
            pluginSourceToolchain.parse("Task.Noop({})", source("node-1", "block-1"), "block-1", emptySet(),
                allocateIdentity = { _, _ -> "node-1" })
        }
    }

    @Test fun highWaterIsBoundedAndLegacyUuidIsNotRewritten() {
        val legacy = "node-12345678-1234-4321-1234-123456789abc"
        val source = source(legacy, "block-1")
        val ids = VisualProjectIds(listOf(source))
        val editor = VisualEditorState.createProjectScoped(source, "block-1", ids)
        assertEquals("node-1", editor.insertNoop())
        assertTrue(nodes(editor.currentSource).any { it["nodeId"].asString == legacy })
        ids.reserveIds(listOf("node-${Int.MAX_VALUE}"))
        assertThrows(IllegalArgumentException::class.java) { ids.allocate("node", emptySet()) }
    }
}
