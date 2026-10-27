package com.autoscript.studio

import com.autoscript.project.store.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class VisualVariableBatchCheckTest {
    private fun snapshot(source: String) = ProjectSnapshot(File("."), ProjectManifestDocument(projectId="qa", name="qa",
        sourceMode=ProjectSourceMode.VISUAL, entryFlowId="main", flows=listOf(ProjectFlow("main","visual/flows/main.jsonl","root"))),
        null, flowSources=mapOf("main" to source))
    private fun node(id: String, args: String="{}", parent: String="null", block: String="root", extra: String="") =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":$parent,"orderKey":"a$id","kind":"task.log","nodeVersion":1,$extra"args":$args}"""

    @Test(timeout=6000) fun largePluginUsesOneDetachedProjectionAndBoundsWarnings() {
        val source=(10000..12999).joinToString("\n") { node(it.toString(), """{"valueVariable":"missing$it"}""") }
        val editor=VisualEditorState.create(source,"root")
        val input=editor.variableCheckInput()
        val nodes=variableCheckNodes(input)
        assertEquals(3000,nodes.size)
        var visits=0
        assertEquals(128,visualVariableWarnings(nodes,snapshot(source),"main") { visits++ }.size)
        assertEquals(3000,visits)
        editor.selectOnly("10000"); editor.deleteSelected()
        assertEquals(3000,variableCheckNodes(input).size)
        assertEquals(2999,variableCheckNodes(editor.variableCheckInput()).size)
    }

    @Test fun disabledParentSkipsDescendantsWithoutLosingTheirOwnState() {
        val parent=node("100", extra="\"disabled\":true,\"childBlocks\":{\"body\":\"body\"},")
        val child=node("101", """{"valueVariable":"missing"}""", "\"100\"", "body")
        val source="$parent\n$child"
        val nodes=variableCheckNodes(VisualEditorState.create(source,"root").variableCheckInput())
        assertEquals(listOf(true,true),nodes.map { it.disabled })
        assertTrue(visualVariableWarnings(nodes,snapshot(source),"main").isEmpty())
    }

    @Test fun cancellationStopsAnalysisWithoutPublishingPartialWarnings() {
        val source=node("100", """{"valueVariable":"missing"}""")
        val nodes=variableCheckNodes(VisualEditorState.create(source,"root").variableCheckInput())
        assertThrows(CancellationException::class.java) {
            visualVariableWarnings(nodes,snapshot(source),"main") { throw CancellationException() }
        }
    }

    @Test fun malformedArgumentsRemainCompilerErrorsInsteadOfCrashingMaintenanceCheck() {
        for (args in listOf("null", "[]", "42", "{\"expression\":{}}")) {
            val source=node("100",args).replace("\"task.log\"","\"variable.calculate\"")
            val editor=VisualEditorState.create(source,"root")
            val before=editor.currentSource
            assertTrue(visualVariableWarnings(editor,snapshot(source),"main").isEmpty())
            assertEquals(before,editor.currentSource)
        }
    }
}
