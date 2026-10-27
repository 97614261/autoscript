package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class EditorProjectSearchTest {
    private fun editor(): VisualEditorState {
        var next = 0
        return VisualEditorState.create("", "root") { "node-${++next}" }
    }
    private fun flow(id: String, editor: VisualEditorState) = EditorSearchFlow(id, "插件$id", editor.currentSource, "root", editor.isReadOnly)

    @Test fun searchCoversAllFrozenDraftsAndFiltersActualVariablesAndImages() {
        val first = editor()
        first.insertBlock(requireNotNull(BlockCatalog.find("task.sleep")), JsonObject().apply {
            addProperty("milliseconds", 1234); addProperty("millisecondsVariable", "等待时间")
        })
        val second = editor()
        second.insertBlock(requireNotNull(BlockCatalog.find("vision.findimage")), JsonObject().apply {
            addProperty("imagePath", "assets/images/按钮.png"); addProperty("foundVariable", "找到")
        })
        val index = editorProjectSearchIndex(listOf(flow("a", first), flow("b", second)))
        assertEquals(2, index.hits.size)
        assertEquals("a", editorProjectSearch(index, "等待时间", "variable").single().flowId)
        assertTrue(editorProjectSearch(index, "按钮", "variable").isEmpty())
        assertEquals("b", editorProjectSearch(index, "按钮", "image").single().flowId)
        assertTrue(editorProjectSearch(index, "等待时间", "image").isEmpty())
        assertEquals("a", editorProjectSearch(index, "TASK.SLEEP", "kind").single().flowId)
        assertEquals("a", editorProjectSearch(index, "1234", "all").single().flowId)
        assertTrue(editorProjectSearch(index, "", "all").isEmpty())
        assertTrue(first.isDirty)
    }

    @Test fun aliasesAndMultipleTermsAreSearchableWithoutChangingSource() {
        val editor = editor()
        val id = requireNotNull(editor.insertNoop())
        editor.updateNodeDisplay(id, com.autoscript.studio.compiler.NodeDisplaySettings("等待大厅", false))
        val source = editor.currentSource
        val index = editorProjectSearchIndex(listOf(flow("main", editor)))
        assertEquals(id, editorProjectSearch(index, "大厅 main", "all").single().nodeId)
        assertEquals(source, editor.currentSource)
    }

    @Test fun resultNavigationRejectsChangedSourceMissingFlowOrMissingNode() {
        val editor = editor()
        editor.insertNoop()
        val flows = listOf(flow("a", editor))
        val hit = editorProjectSearchIndex(flows).hits.single()
        assertNull(editorSearchTarget(hit, flows, editor))
        assertNotNull(editorSearchTarget(hit, emptyList(), editor))
        assertNotNull(editorSearchTarget(hit, flows, null))
        assertNotNull(editorSearchTarget(hit.copy(nodeId = "missing"), flows, editor))
        editor.insertNoop()
        assertNotNull(editorSearchTarget(hit, flows, editor))
    }

    @Test fun invalidAndOversizeSourcesAreExplicitlySkippedAndCancellationPropagates() {
        val flows = listOf(EditorSearchFlow("invalid", "invalid", "not-json", "root", true),
            EditorSearchFlow("large", "large", "x".repeat(1_048_577), "root"))
        val index = editorProjectSearchIndex(flows)
        assertEquals(2, index.skipped)
        assertTrue(index.hits.isEmpty())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            editorProjectSearchIndex(flows) { throw kotlinx.coroutines.CancellationException("closed") }
        }
    }

    @Test fun identicalNodeIdsInDifferentPluginsStayDistinctResults() {
        val editor = editor()
        editor.insertNoop()
        val index = editorProjectSearchIndex(listOf(flow("a", editor), flow("b", editor)))
        assertEquals(2, editorProjectSearch(index, "task.noop", "kind").size)
        assertEquals(setOf("a", "b"), index.hits.map { it.flowId }.toSet())
    }

    @Test fun filteringCancellationPropagatesInsteadOfPublishingStaleResults() {
        val editor = editor()
        editor.insertNoop()
        val index = editorProjectSearchIndex(listOf(flow("main", editor)))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            editorProjectSearch(index, "task", "all") { throw kotlinx.coroutines.CancellationException("new query") }
        }
    }
}
