package com.autoscript.studio

import com.autoscript.runtime.client.VisualCompileDiagnostic
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditorProblemPresentationTest {
    private fun flow(id: String, nodeId: String = "node") = EditorSearchFlow(id, "Plugin $id",
        """{"flowSchemaVersion":1,"nodeId":"$nodeId","blockId":"root","parentId":null,"orderKey":"a0","kind":"task.sleep","nodeVersion":1,"args":{"milliseconds":100}}""", "root")
    private fun diagnostic(flow: String? = "main", node: String? = "node", line: Int? = 1) =
        VisualCompileDiagnostic("INVALID_NODE_ARGUMENTS", "Original error details", flow, node, line)

    @Test fun knownCodesHavePlainAdviceWithoutLosingOriginalDetails() {
        val input = "SCREEN_CAPTURE_FAILED: backend rejected [CAPTURE_BACKEND_FAILED]"
        assertTrue(requireNotNull(editorProblemAdvice(input)).summary.contains("截图后端"))
        assertTrue(requireNotNull(editorProblemAdvice("details", "ROOT_STOP_CLEANUP_FAILED")).suggestion.contains("Root"))
        assertTrue(requireNotNull(editorProblemAdvice("details", "INVALID_NODE_ARGUMENTS")).summary.contains("参数"))
        assertEquals("Original error details", diagnostic().message)
    }

    @Test fun unknownAndPartialCodesDoNotInventACause() {
        assertNull(editorProblemAdvice("Something unknown", "NEW_ERROR"))
        assertNull(editorProblemAdvice("XCAPTURE_BACKEND_FAILED_SUFFIX"))
        assertNull(editorProblemAdvice("SCREEN_CAPTURE_FAILED_EXTRA"))
    }

    @Test fun targetUsesExactPluginAndNodeRatherThanConflictingLine() {
        val main = flow("main"); val other = flow("other")
        val target = requireNotNull(editorProblemTarget(diagnostic(line = 99), listOf(main, other)))
        assertEquals("main", target.flowId)
        assertEquals(main.source, target.source)
        assertEquals("node", target.nodeId)
        assertEquals(1, target.line)
        assertEquals("Plugin main", target.flowName)
    }

    @Test fun lineFallbackRequiresExplicitPluginAndMissingNodeNeverFallsBack() {
        assertNotNull(editorProblemTarget(diagnostic(node = null), listOf(flow("main"))))
        assertNull(editorProblemTarget(diagnostic(node = "missing"), listOf(flow("main"))))
        assertNull(editorProblemTarget(diagnostic(flow = null, node = null), listOf(flow("main"))))
        assertNull(editorProblemTarget(diagnostic(flow = "deleted"), listOf(flow("main"))))
    }

    @Test fun ambiguousNodeAcrossPluginsHasNoLocationButUniqueNodeCanResolve() {
        assertNull(editorProblemTarget(diagnostic(flow = null), listOf(flow("main"), flow("other"))))
        assertEquals("main", editorProblemTarget(diagnostic(flow = null), listOf(flow("main"), flow("other", "different")))?.flowId)
    }

    @Test fun productionUsesPromptLayerOriginalDetailsAndGuardedParameterNavigation() {
        val base = File("src/main/java/com/autoscript/studio")
        val dialog = File(base, "editor/EditorProblemDialog.kt").readText()
        assertTrue(dialog.contains("StudioConfirmDialog("))
        assertTrue(dialog.contains("SelectionContainer"))
        assertTrue(dialog.contains("查看原始详情"))
        val screen = File(base, "editor/VisualProjectScreen.kt").readText()
        assertTrue(screen.contains("targetEditor?.currentSource != target.source"))
        assertTrue(screen.contains("if (edit) parameterNavigation = target"))
        assertTrue(screen.contains("openNodeParameters(target.flowId, target.nodeId)"))
        assertTrue(screen.contains("currentInputSnapshot.flowSources == baseline.flowSources"))
        assertTrue(screen.contains("editors.mapValues { it.value.currentSource } == frozenDrafts"))
        assertTrue(screen.contains("validateVisualDraftSet(projectId,"))
    }
}
