package com.autoscript.studio

import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EditorDraftJournalTest {
    @Test fun staleOrDisposedDiscardCannotDeleteNewCheckpoint() = temporary { root ->
        val old = EditorDraftJournal(root)
        val discarded = old.nextRevision()
        val checkpoint = EditorDraftCheckpoint(EditorDraftJournal.hash("manifest"), listOf(
            EditorDraftEntry("main", "root", EditorDraftJournal.hash("base"), "new draft")))
        old.writeIfCurrent("project", checkpoint, old.nextRevision())
        assertFalse(old.discardIfCurrent("project", discarded))
        assertEquals(checkpoint, old.read("project"))
        val oldTicket = old.nextRevision()
        val replacement = EditorDraftJournal(root)
        replacement.writeIfCurrent("project", checkpoint, replacement.nextRevision())
        assertFalse(old.discardIfCurrent("project", oldTicket))
        assertEquals(checkpoint, replacement.read("project"))
        assertTrue(replacement.discardIfCurrent("project", replacement.nextRevision()))
        assertNull(replacement.read("project"))
    }

    @Test fun frozenTextSurvivesLaterEditorChangesWithoutReadingLiveState() = temporary { root ->
        val store = ProjectStore(File(root, "projects"))
        val project = store.createProject("Frozen public draft", ProjectSourceMode.VISUAL)
        val flow = project.manifest.flows.single()
        val editor = VisualEditorState.create(project.flowSources.getValue(flow.flowId), flow.rootBlockId)
        editor.insertNoop()
        val first = editor.currentSource
        val journal = EditorDraftJournal(root)
        val frozen = journal.freeze(project, mapOf(flow.flowId to editor))
        editor.insertNoop()
        val checkpoint = journal.capture(project, frozen)
        assertEquals(first, checkpoint.entries.single().source)
        assertNotEquals(first, editor.currentSource)
        assertEquals(EditorDraftJournal.hash(editor.savedSource), checkpoint.entries.single().baseHash)
    }

    @Test fun replacedSessionCannotOverwriteOrRecreateNewSessionCheckpoint() = temporary { root ->
        val oldSession = EditorDraftJournal(root)
        val oldTicket = oldSession.nextRevision()
        val baseline = EditorDraftCheckpoint(EditorDraftJournal.hash("manifest"), listOf(
            EditorDraftEntry("main", "root", EditorDraftJournal.hash("base"), "old")))
        val newSession = EditorDraftJournal(root)
        val current = newSession.nextRevision()
        assertEquals(-1L, oldSession.nextRevision())
        val latest = baseline.copy(entries = baseline.entries.map { it.copy(source = "new") })
        newSession.writeIfCurrent("project", latest, current)
        oldSession.writeIfCurrent("project", baseline, oldTicket)
        assertEquals(latest, newSession.read("project"))
        newSession.writeIfCurrent("project", latest.copy(entries = emptyList()), newSession.nextRevision())
        oldSession.writeIfCurrent("project", baseline, oldTicket)
        assertNull(newSession.read("project"))
    }

    @Test fun multiplePluginCheckpointsKeepIndependentBaselinesAndUndo() = temporary { root ->
        val store = ProjectStore(File(root, "projects"))
        val initial = store.createProject("Multiple recovery QA", ProjectSourceMode.VISUAL)
        val project = store.createFlow(initial.manifest.projectId, "helper",
            initial.manifest.flows.map { it.flowId }.toSet())
        val editors = project.manifest.flows.associate { flow ->
            flow.flowId to VisualEditorState.create(project.flowSources.getValue(flow.flowId), flow.rootBlockId)
                .apply { insertNoop() }
        }
        val journal = EditorDraftJournal(root)
        val checkpoint = journal.capture(project, editors)
        assertEquals(2, checkpoint.entries.size)
        journal.write(project.manifest.projectId, checkpoint)
        assertEquals(checkpoint, journal.read(project.manifest.projectId))
        val first = checkpoint.entries.first()
        val changed = project.copy(flowSources = project.flowSources + (first.flowId to first.source))
        assertEquals(checkpoint.entries.drop(1), journal.compatible(checkpoint, changed))
        val remaining = checkpoint.copy(entries = checkpoint.entries.drop(1))
        journal.write(project.manifest.projectId, remaining)
        assertEquals(remaining, journal.read(project.manifest.projectId))
        editors.values.forEach { assertTrue(it.undo()) }
        assertTrue(journal.capture(project, editors).entries.isEmpty())
        assertEquals(project.flowSources, store.openProject(project.manifest.projectId).flowSources)
    }

    @Test fun checkpointRoundtripDoesNotSaveProjectAndRejectsChangedBaseline() = temporary { root ->
        val store = ProjectStore(File(root, "projects"))
        val project = store.createProject("Recovery QA", ProjectSourceMode.VISUAL)
        val flow = project.manifest.flows.single()
        val source = project.flowSources.getValue(flow.flowId)
        val editor = VisualEditorState.create(source, flow.rootBlockId)
        editor.insertBlock(requireNotNull(BlockCatalog.find("task.comment")), JsonObject().apply { addProperty("message", "draft") })
        val journal = EditorDraftJournal(root)
        val checkpoint = journal.capture(project, mapOf(flow.flowId to editor))
        journal.write(project.manifest.projectId, checkpoint)
        assertEquals(checkpoint, journal.read(project.manifest.projectId))
        assertEquals(source, store.openProject(project.manifest.projectId).flowSources.getValue(flow.flowId))
        assertEquals(checkpoint.entries, journal.compatible(checkpoint, project))
        assertTrue(journal.compatible(checkpoint, project.copy(manifest = project.manifest.copy(name = "changed"))).isEmpty())
        assertTrue(journal.compatible(checkpoint, project.copy(flowSources = mapOf(flow.flowId to editor.currentSource))).isEmpty())
        assertNull(editor.replaceRawSource(editor.currentSource, source))
        assertNull(editor.replaceRawSource(source, checkpoint.entries.single().source))
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
    }

    @Test fun supersededWritesAndSavedDraftsCannotRecreateOldRecovery() = temporary { root ->
        val journal = EditorDraftJournal(root)
        val entry = EditorDraftEntry("main", "root", EditorDraftJournal.hash("base"), "draft")
        val checkpoint = EditorDraftCheckpoint(EditorDraftJournal.hash("manifest"), listOf(entry))
        val old = journal.nextRevision()
        val current = journal.nextRevision()
        journal.writeIfCurrent("project", checkpoint, old)
        assertNull(journal.read("project"))
        journal.writeIfCurrent("project", checkpoint, current)
        assertNotNull(journal.read("project"))
        val saved = journal.nextRevision()
        journal.writeIfCurrent("project", checkpoint.copy(entries = emptyList()), saved)
        journal.writeIfCurrent("project", checkpoint, current)
        assertNull(journal.read("project"))
    }

    @Test fun interruptedSidecarUsesKnownPreviousButNeverUncommittedTemporary() = temporary { root ->
        val journal = EditorDraftJournal(root)
        val checkpoint = EditorDraftCheckpoint(EditorDraftJournal.hash("manifest"), listOf(
            EditorDraftEntry("main", "root", EditorDraftJournal.hash("base"), "draft")))
        journal.write("project", checkpoint)
        val file = File(root, "editor-drafts").listFiles()!!.single()
        assertTrue(file.renameTo(File(file.parentFile, file.name + ".previous")))
        File(file.parentFile, file.name + ".tmp").writeText("uncommitted")
        assertEquals(checkpoint, journal.read("project"))
        journal.discard("project")
        assertNull(journal.read("project"))
    }

    @Test fun boundsAndDuplicatePluginIdentityAreRejected() = temporary { root ->
        val journal = EditorDraftJournal(root)
        val entry = EditorDraftEntry("main", "root", EditorDraftJournal.hash("base"), "draft")
        val checkpoint = EditorDraftCheckpoint(EditorDraftJournal.hash("manifest"), listOf(entry))
        assertTrue(runCatching { journal.write("project", checkpoint.copy(entries = listOf(entry, entry))) }.isFailure)
        assertTrue(runCatching { journal.write("project", checkpoint.copy(entries = listOf(entry.copy(source = "x".repeat(1_048_577))))) }.isFailure)
        assertNull(journal.read("project"))
    }

    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("editor-draft-qa").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
