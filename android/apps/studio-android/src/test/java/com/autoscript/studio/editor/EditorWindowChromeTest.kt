package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorWindowChromeTest {
    private val normal = EditorPanelPlacement(20f, 80f, 292f, 378f)

    @Test fun singleMaxModeKeepsNormalBoundsAndRestoresWithoutMutation() {
        assertEquals(normal, editorPanelDisplayBounds(normal, EditorPanelMode.NORMAL, 720f, 1280f))
        assertEquals(EditorPanelPlacement(0f, 0f, 720f, 1280f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 720f, 1280f))
        assertEquals(EditorPanelMode.MAXIMIZED, editorTogglePanelMode(EditorPanelMode.NORMAL, EditorPanelMode.MAXIMIZED))
        assertEquals(EditorPanelMode.NORMAL, editorTogglePanelMode(EditorPanelMode.MAXIMIZED, EditorPanelMode.MAXIMIZED))
        assertEquals(2, EditorPanelMode.entries.size)
        assertEquals(EditorPanelPlacement(20f, 80f, 292f, 378f), normal)
    }

    @Test fun modesAdaptToRotationAndDegenerateViewports() {
        assertEquals(EditorPanelPlacement(0f, 0f, 1280f, 720f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 1280f, 720f))
        val tiny = editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 4f, 2f)
        assertEquals(EditorPanelPlacement(0f, 0f, 4f, 2f), tiny)
        val invalid = editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, Float.NaN, -1f)
        assertEquals(EditorPanelPlacement(0f, 0f, 1f, 1f), invalid)
    }

    @Test fun maximizedChromeStaysOutsideSystemBarsAndPreservesSavedSmallWindow() {
        val safe = editorWorkArea(720, 1280, 0, 24, 0, 48)
        assertEquals(EditorPanelPlacement(0f, 24f, 720f, 1208f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 720f, 1280f, safe))
        assertEquals(normal, editorPanelDisplayBounds(normal, EditorPanelMode.NORMAL, 720f, 1280f, safe))
        val landscape = editorWorkArea(1280, 720, 48, 24, 48, 0)
        assertEquals(EditorPanelPlacement(48f, 24f, 1184f, 696f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 1280f, 720f, landscape))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f), editorWorkArea(0, 0, 500, 500, 500, 500))
    }

    @Test fun leftSettingsAndThreeRightControlsAreExcludedButTitleAndOuterGapsCanDrag() {
        assertEquals(3, EDITOR_HEADER_RIGHT_ACTION_COUNT)
        for (width in listOf(210f, 292f, 720f)) {
            val start = width - 4f - 3 * 28f
            assertFalse(editorHeaderIsButtonArea(0f, width, 28f, 4f))
            assertFalse(editorHeaderIsButtonArea(3.9f, width, 28f, 4f))
            assertTrue(editorHeaderIsButtonArea(4f, width, 28f, 4f))
            assertTrue(editorHeaderIsButtonArea(18f, width, 28f, 4f))
            assertTrue(editorHeaderIsButtonArea(31.9f, width, 28f, 4f))
            assertFalse(editorHeaderIsButtonArea(32f, width, 28f, 4f))
            assertFalse(editorHeaderIsButtonArea(start - .1f, width, 28f, 4f))
            for (button in 0..2) {
                assertTrue(editorHeaderIsButtonArea(start + button * 28f + 14f, width, 28f, 4f))
            }
            assertFalse(editorHeaderIsButtonArea(width - 4f, width, 28f, 4f))
            assertFalse(editorHeaderIsButtonArea(width - 1f, width, 28f, 4f))
        }
    }

    @Test fun draggingEnlargedWindowRestoresUnderPointerWithinBounds() {
        val displayed = EditorPanelPlacement(0f, 0f, 720f, 1280f)
        assertEquals(214f to 0f, editorRestoredDragOrigin(normal, displayed, 360f, 18f, 0f, 428f, 1244f))
        val left = editorRestoredDragOrigin(normal, displayed, -20f, 18f, 0f, 428f, 1244f)
        assertEquals(0f, left.first, .001f)
        val clipped = editorRestoredDragOrigin(normal, displayed, 900f, 18f, 0f, 428f, 1244f)
        assertEquals(428f, clipped.first, .001f)
    }

    @Test fun headerGesturesIconsAndCallbacksStaySemanticallySeparated() {
        val source = File("src/main/java/com/autoscript/studio/editor/EditorDock.kt").readText()
        val header = source.substringAfter(".pointerInput(projectName, headerDensity.density, \"titlebar-drag\")")
            .substringBefore("executionStatus?.let")
        assertTrue(header.contains("awaitFirstDown(requireUnconsumed = false)"))
        assertTrue(header.contains("editorHeaderIsButtonArea"))
        assertTrue(header.contains("currentOnDragStart(down.position)"))
        assertTrue(header.contains("awaitTouchSlopOrCancellation"))
        assertTrue(header.contains("clickable(enabled = canSelectFlow, onClick = onSelectFlow)"))
        val names = listOf("editor_window_settings_20", "editor_window_minimize_20", "editor_window_maximize_20",
            "editor_window_close_20")
        val positions = names.map { header.indexOf("R.drawable.$it") }
        assertTrue(positions.all { it >= 0 }); assertEquals(positions.sorted(), positions)
        assertTrue(positions.first() < header.indexOf(".clickable(enabled = canSelectFlow"))
        assertFalse(header.contains("onFullscreen"))
        assertEquals(1, Regex("onClick = onMaximize").findAll(header).count())
        for (name in names + listOf("editor_window_restore_20", "editor_window_resize_20")) {
            val xml = File("src/main/res/drawable/$name.xml").readText()
            assertTrue(xml.contains("android:strokeWidth=\"1.7\""))
        }
        assertTrue(source.contains("onMinimize = { setExpanded(false) }"))
        assertTrue(source.contains("if (panelMode == EditorPanelMode.NORMAL) Box"))
        assertTrue(source.contains("val minPanelX = workArea.left"))
        assertTrue(source.contains("painterResource(R.drawable.editor_window_resize_20)"))
        assertFalse(source.contains("R.drawable.editor_resize_24"))
    }
}
