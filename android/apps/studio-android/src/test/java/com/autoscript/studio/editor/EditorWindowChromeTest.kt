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

    @Test fun chromeUsesPhysicalScreenWithoutReservedSystemBarSpaceAndPreservesSmallWindow() {
        val screen = editorWorkArea(720, 1280)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 720f, 1280f), screen)
        assertEquals(EditorPanelPlacement(0f, 0f, 720f, 1280f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 720f, 1280f, screen))
        assertEquals(normal, editorPanelDisplayBounds(normal, EditorPanelMode.NORMAL, 720f, 1280f, screen))
        val landscape = editorWorkArea(1280, 720)
        assertEquals(EditorPanelPlacement(0f, 0f, 1280f, 720f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 1280f, 720f, landscape))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f), editorWorkArea(0, 0))
    }

    @Test fun onlyTheVisibleStatusBarRestrictsDraggingAndMaximize() {
        val area = editorWorkArea(720, 1280, 48)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 48f, 720f, 1280f), area)
        val range = editorPanelDragArea(area, normal.width, normal.height)
        assertEquals(48f, range.top, 0f)
        assertEquals(1280f - normal.height, range.bottom, 0f)
        assertEquals(EditorPanelPlacement(0f, 48f, 720f, 1232f),
            editorPanelDisplayBounds(normal, EditorPanelMode.MAXIMIZED, 720f, 1280f, area))
        assertEquals(normal, editorPanelDisplayBounds(normal, EditorPanelMode.NORMAL, 720f, 1280f, area))
        assertEquals(0f, editorPanelDragArea(editorWorkArea(720, 1280, 0), normal.width, normal.height).top, 0f)
    }

    @Test fun invalidStatusBarValuesNeverProduceAnEmptyOrOffScreenWorkArea() {
        assertEquals(editorWorkArea(720, 1280), editorWorkArea(720, 1280, -30))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 1279f, 720f, 1280f), editorWorkArea(720, 1280, Int.MAX_VALUE))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f), editorWorkArea(0, 0, 48))
    }

    @Test fun legacyVisibilityRemovesTheInsetAndLocalZeroCannotMakeItOscillate() {
        assertEquals(48, editorLegacyStatusBarTop(true, 0, 48, 0))
        assertEquals(48, editorLegacyStatusBarTop(true, 12, 48, 48))
        assertEquals(48, editorLegacyStatusBarTop(null, 0, 48, 48))
        assertEquals(0, editorLegacyStatusBarTop(false, 48, 48, 48))
        assertEquals(48, editorLegacyStatusBarTop(true, 0, 48, 0))
    }

    @Test fun legacyMeasurementsDoNotMistakeTheSmallWindowOriginForAStatusBar() {
        assertEquals(0, editorLegacyStatusBarTop(null, 300, 48, 0))
        assertEquals(0, editorLegacyStatusBarTop(null, 0, 48, 0))
        assertEquals(48, editorLegacyStatusBarTop(null, 48, 48, 0))
        assertEquals(32, editorLegacyStatusBarTop(true, 0, 32, 48))
        assertEquals(0, editorLegacyStatusBarTop(null, 300, 0, 48))
    }

    @Test fun statusBarObservationUsesDisplayMetricsAndKeepsInsetsAndCleanupOwnedByTheEditor() {
        val observer = File("src/main/java/com/autoscript/studio/editor/EditorStatusBarObserver.kt").readText()
        assertTrue(observer.contains("manager.maximumWindowMetrics.windowInsets"))
        assertTrue(observer.contains("insets.isVisible(WindowInsets.Type.statusBars())"))
        assertTrue(observer.contains("enqueue(); insets"))
        assertTrue(observer.contains("setOnSystemUiVisibilityChangeListener"))
        assertTrue(observer.contains("view.removeCallbacks(refresh)"))
        assertTrue(observer.contains("removeOnGlobalLayoutListener(layoutListener)"))
        assertFalse(observer.contains("getInsetsIgnoringVisibility"))
        assertFalse(observer.contains("manager.addView"))
        assertFalse(observer.contains("postDelayed"))
        val service = File("src/main/java/com/autoscript/studio/editor/EditorOverlayService.kt").readText()
        assertTrue(service.contains("EditorStatusBarObserver(container, manager, ::updateWindow)"))
        assertTrue(service.contains("statusBar?.configurationChanged()"))
        assertTrue(service.contains("statusBar?.close(); statusBar = null"))
        assertTrue(service.contains("editorWorkArea(point.x, point.y, statusBar?.top ?: 0)"))
        val dock = editorDockTestSources()
        assertTrue(dock.contains("Size(maxWidthPx, maxHeightPx)"))
        assertTrue(dock.contains("panelY.coerceIn(minPanelY, maxPanelY)"))
    }

    @Test fun draggingToAnyEdgeKeepsTheWholeWindowOnScreenInEitherOrientation() {
        for (screen in listOf(editorWorkArea(720, 1280), editorWorkArea(1280, 720), editorWorkArea(320, 240))) {
            val width = EDITOR_DEFAULT_WIDTH_DP.toFloat().coerceAtMost(screen.width)
            val height = EDITOR_DEFAULT_HEIGHT_DP.toFloat().coerceAtMost(screen.height)
            val range = editorPanelDragArea(screen, width, height)
            assertEquals(0f, range.top, 0f)
            assertEquals(screen.right - width, range.right, 0f)
            assertEquals(screen.bottom - height, range.bottom, 0f)
            for (x in listOf(-100f, 10000f)) for (y in listOf(-100f, 10000f)) {
                val placedX = x.coerceIn(range.left, range.right)
                val placedY = y.coerceIn(range.top, range.bottom)
                assertTrue(placedX >= 0f && placedY >= 0f)
                assertTrue(placedX + width <= screen.right)
                assertTrue(placedY + height <= screen.bottom)
            }
        }
    }

    @Test fun aScreenSizedPanelCannotBeDraggedOutsideItsViewport() {
        val screen = editorWorkArea(320, 240)
        assertEquals(androidx.compose.ui.geometry.Rect.Zero, editorPanelDragArea(screen, 320f, 240f))
        assertEquals(androidx.compose.ui.geometry.Rect.Zero, editorPanelDragArea(editorWorkArea(0, 0), 1f, 1f))
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
        val source = editorDockTestSources()
        val header = source.substringAfter(".pointerInput(projectName, headerDensity.density, \"titlebar-drag\")")
            .substringBefore("Row(Modifier.fillMaxWidth().weight(1f))")
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
