package com.autoscript.studio

import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PluginSourcePersistenceTest {
    @Test fun sourceEditPersistsRestoresAndReexportsWithoutChangingBlockIdentity() {
        val root = Files.createTempDirectory("source-persistence").toFile()
        try {
            val store = ProjectStore(root)
            val project = store.createProject("回归工程", StudioProjectPolicy.creationMode)
            val flow = project.manifest.flows.single()
            val editor = VisualEditorState.create(project.flowSources.getValue(flow.flowId), flow.rootBlockId)
            editor.insertBlock(requireNotNull(BlockCatalog.find("task.comment")),
                com.google.gson.JsonObject().apply { addProperty("message", "before") })
            val baseline = editor.currentSource
            val lua = pluginSourceToolchain.export(baseline, flow.rootBlockId).text
                .replace("[\"message\"] = \"before\"", "[\"message\"] = \"saved\"")
            val converted = pluginSourceToolchain.parse(lua, baseline, flow.rootBlockId, emptySet())
            assertNotEquals(baseline, converted.text)
            assertNull(editor.replaceRawSource(baseline, converted.text))
            store.saveFlow(project.manifest.projectId, flow.flowId, editor.currentSource,
                project.flowSources.getValue(flow.flowId))
            editor.markSaved(editor.currentSource)
            assertFalse(editor.isDirty)
            val reopened = ProjectStore(root).openProject(project.manifest.projectId)
            assertEquals(editor.currentSource, reopened.flowSources.getValue(flow.flowId))
            val archive = ByteArrayOutputStream().also { store.exportProjectBackup(project.manifest.projectId, it) }
            val restored = store.importProjectBackup(ByteArrayInputStream(archive.toByteArray()))
            assertEquals(ProjectSourceMode.VISUAL, restored.manifest.sourceMode)
            assertEquals(reopened.flowSources, restored.flowSources)
            val restoredSource = restored.flowSources.getValue(flow.flowId)
            assertEquals(converted.text, pluginSourceToolchain.parse(
                pluginSourceToolchain.export(restoredSource, flow.rootBlockId).text,
                restoredSource, flow.rootBlockId, emptySet()).text)
            assertTrue(editor.undo())
            assertEquals(baseline, editor.currentSource)
            assertTrue(editor.isDirty)
        } finally {
            root.deleteRecursively()
        }
    }
}
