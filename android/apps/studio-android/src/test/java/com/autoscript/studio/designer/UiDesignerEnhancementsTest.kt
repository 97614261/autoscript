package com.autoscript.studio

import com.autoscript.script.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UiDesignerEnhancementsTest {
    @Test fun trustedPrivateRootAliasDoesNotDisableRecovery() {
        val root = Files.createTempDirectory("ui-designer-root-alias").toFile().canonicalFile
        try {
            val journal = UiDesignerDraftJournal(java.io.File(root, "."), "project-qa-alias")
            val draft = RunnerUiDesignerDraft().add(RunnerUiControlKind.TEXT)
            journal.write(journal.base("formal"), draft, journal.ticket())
            assertEquals(draft, journal.read()?.draft)
            journal.discard()
            assertNull(journal.read())
        } finally { root.deleteRecursively() }
    }
    @Test fun previewContentClipsAtEveryAncestorWithoutChangingEditableGeometry() {
        val draft = RunnerUiDesignerDraft(pages = listOf(UiPage(width = 720, height = 1280)), fields = listOf(
            RunnerUiFieldDraft("outer", "Outer", RunnerUiControlKind.CONTAINER, false, "",
                ui = UiPresentation(control = "CONTAINER", x = 100, y = 100, width = 100, height = 100)),
            RunnerUiFieldDraft("inner", "Inner", RunnerUiControlKind.CONTAINER, false, "",
                ui = UiPresentation(control = "CONTAINER", parentId = "outer", x = 80, y = 80, width = 100, height = 100)),
            RunnerUiFieldDraft("child", "Child", RunnerUiControlKind.LABEL, false, "Text",
                ui = UiPresentation(control = "LABEL", parentId = "inner", x = 10, y = 10, width = 80, height = 80))))
        val child = draft.fields.last()
        assertEquals(DesignerPreviewClip(0, 0, 10, 10), draft.previewClip(child))
        assertEquals(80, child.ui.width)
        assertEquals(190 to 190, draft.absolutePosition(child))
        val outside = child.copy(ui = child.ui.copy(x = 90, y = 90))
        assertEquals(DesignerPreviewClip(0, 0, 0, 0), draft.previewClip(outside))
        val pageEdge = child.copy(ui = child.ui.copy(parentId = null, x = 700, y = 1270))
        assertEquals(DesignerPreviewClip(0, 0, 20, 10), draft.previewClip(pageEdge))
    }
    @Test fun operationsAndDesignDefaultsFollowSelectedControl() {
        val ui = ScriptUiDefinition(version = 2, fields = listOf(UiField("label", "文字", "text", false, "hello",
            ui = UiPresentation(control = "LABEL", width = 160, textColor = "#FFAABBCC"))))
        assertEquals(listOf("show", "get:width"), uiApplicableOperations(ui, "label", listOf("show", "get:items", "progress", "get:width")))
        assertEquals("160", uiDesignValue(ui, "label", "width"))
        assertEquals("#FFAABBCC", uiDesignValue(ui, "label", "textColor"))
        assertEquals("hello", uiDesignValue(ui, "label", "value"))
    }
    @Test fun rectangleUsesAbsoluteContainerCoordinatesAndFindsInvalidControl() {
        val draft = RunnerUiDesignerDraft(fields = listOf(
            RunnerUiFieldDraft("container", "容器", RunnerUiControlKind.CONTAINER, false, "", ui = UiPresentation(control = "CONTAINER", x = 100, y = 100, width = 200, height = 200)),
            RunnerUiFieldDraft("child", "子控件", RunnerUiControlKind.INTEGER, false, "bad", ui = UiPresentation(control = "NUMBER", parentId = "container", x = 20, y = 20, width = 40, height = 40))))
        assertEquals(setOf("container", "child"), draft.boxSelection("main", 170f, 170f, 110f, 110f))
        assertEquals("child", draft.firstValidationIssue()?.controlId)
        assertTrue(draft.boxSelection("main", 0f, 0f, 0f, 0f).isEmpty())
    }
    @Test fun privateCheckpointRejectsLateOwnersAndNeverWritesProject() {
        val root = Files.createTempDirectory("ui-designer-journal").toFile().canonicalFile
        try {
            val old = UiDesignerDraftJournal(root, "project-test")
            val draft = RunnerUiDesignerDraft().add(RunnerUiControlKind.TEXT)
            val hash = old.base("formal")
            val ticket = old.ticket()
            old.write(hash, draft, ticket)
            assertEquals(draft, old.read()?.draft)
            val newer = UiDesignerDraftJournal(root, "project-test")
            newer.discard()
            old.write(hash, draft, ticket)
            assertNull(newer.read())
            val invalid = draft.copy(fields = draft.fields.map { it.copy(kind = RunnerUiControlKind.INTEGER, initialText = "unfinished", ui = it.ui.copy(control = "NUMBER")) })
            newer.write(hash, invalid, newer.ticket())
            assertEquals(invalid, newer.read()?.draft)
            assertFalse(java.io.File(root, "project.json").exists())
            val directory = java.io.File(root, "ui-designer-drafts")
            val main = directory.listFiles()!!.single { it.name.endsWith(".json") }
            assertTrue(main.renameTo(java.io.File(main.path + ".previous")))
            java.io.File(main.path + ".tmp").writeText("uncommitted")
            assertEquals(invalid, newer.read()?.draft)
        } finally { root.deleteRecursively() }
    }
}
