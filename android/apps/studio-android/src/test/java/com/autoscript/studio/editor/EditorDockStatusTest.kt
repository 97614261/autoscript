package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorDockStatusTest {
    @Test fun blankAndDismissedNoticesDoNotReserveSpace() {
        assertNull(editorDockStatusText(null, false))
        assertNull(editorDockStatusText(" \n ", false))
        assertNull(editorDockStatusText("saved", true))
        assertEquals("saved", editorDockStatusText(" saved ", false))
    }

    @Test fun noticeRenderingPreservesDiagnosticTextWithoutChangingExecutionState() {
        val status = "ROOT_STOP_CLEANUP_FAILED: input remains blocked"
        assertEquals(status, editorDockStatusText(status, false))
        assertNull(editorDockStatusText(status, true))
        assertEquals("new status", editorDockStatusText("new status", false))
    }

    @Test fun compactDismissibleStripSitsAfterTreeAndSidebarButBeforeBottomActions() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDockContent.kt").readText()
        val panel = source.substringAfter("internal fun EditorDockPanel(").substringBefore("internal fun editorDockStatusText")
        val tree = panel.indexOf("Row(Modifier.fillMaxWidth().weight(1f))")
        val sidebar = panel.indexOf("EditorDockAction(\"AI\"")
        val strip = panel.indexOf("EditorDockStatusStrip(status, onDismissExecutionStatus)")
        val footer = panel.indexOf("val entries = editorToolbarEntries")
        assertTrue(tree >= 0 && tree < sidebar && sidebar < strip && strip < footer)
        assertFalse(panel.substringBefore("Row(Modifier.fillMaxWidth().weight(1f))").contains("executionStatus?.let"))
        val content = source.substringAfter("internal fun EditorDockStatusStrip(").substringBefore("internal fun EditorMorePopup(")
        assertTrue(content.contains("heightIn(min = 18.dp)"))
        assertFalse(content.contains(".height(18.dp)"))
        assertTrue(content.contains("maxLines = 1"))
        assertTrue(content.contains("contentDescription = \"关闭当前提示\""))
        assertTrue(content.contains("onClick = onDismiss"))
    }

    @Test fun dismissalBelongsToParentSessionAndResetsOnlyWhenMessageOrProjectChanges() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        val owner = source.indexOf("var statusDismissed by rememberSaveable(projectName, statusText)")
        assertTrue(owner >= 0 && owner < source.indexOf("} else if (expanded)"))
        assertTrue(source.contains("executionStatus = editorDockStatusText(statusText, statusDismissed)"))
        assertTrue(source.contains("onDismissExecutionStatus = { statusDismissed = true }"))
    }

    @Test fun untouchedSizeUsesViewportPolicyAndFirstResizeStartsAtDisplayedSize() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        assertTrue(source.contains("if (panelResized) Size(panelWidthPx, panelHeightPx) else null"))
        assertTrue(source.contains("Size(normalPanelWidthPx + delta.x, normalPanelHeightPx + delta.y)"))
        assertTrue(source.contains("panelResized = true"))
        assertFalse(source.contains("panelHeightPx.coerceIn(effectiveMinPanelHeightPx"))
    }
}
