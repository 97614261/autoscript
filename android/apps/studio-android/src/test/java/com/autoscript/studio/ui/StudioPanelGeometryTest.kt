package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class StudioPanelGeometryTest {
    @Test fun entryPanelsOnlyUseExtraWidthWhenTheActualViewportCanSupportIt() {
        assertEquals(600f, studioToolPanelWidth(720f, 320f, 1f, landscapeWidthDp = 600))
        assertEquals(544f, studioToolPanelWidth(560f, 280f, 1f, landscapeWidthDp = 600))
        assertEquals(344f, studioToolPanelWidth(360f, 640f, 1f, landscapeWidthDp = 600))
        assertEquals(400f, studioToolPanelWidth(720f, 320f, 1.6f, landscapeWidthDp = 600))
        assertEquals(400f, studioToolPanelWidth(720f, 320f, 1f))
        assertEquals(400f, studioToolPanelWidth(Float.NaN, 320f, 1f, landscapeWidthDp = 600))
    }
    @Test fun compactToolGridsFitFourAcrossWithoutSqueezingPortraitOrLargeFonts() {
        assertEquals(4, studioCompactGridColumns(584f, 1f, 130, 4))
        assertEquals(3, studioCompactGridColumns(504f, 1f, 130, 4))
        assertEquals(2, studioCompactGridColumns(344f, 1f, 130, 4))
        assertEquals(2, studioCompactGridColumns(584f, 1.6f, 130, 4))
        assertEquals(1, studioCompactGridColumns(240f, 2f, 130, 4))
    }
    @Test fun sideNavigationNeedsEnoughWidthAndAShortViewport() {
        assertTrue(studioWideShortLayout(640f, 360f, 1f))
        assertTrue(studioWideShortLayout(960f, 440f, 1.5f))
        assertFalse(studioWideShortLayout(360f, 640f, 1f))
        assertFalse(studioWideShortLayout(540f, 280f, 1f))
        assertFalse(studioWideShortLayout(640f, 360f, 1.5f))
        assertFalse(studioWideShortLayout(800f, 600f, 1f))
        assertFalse(studioWideShortLayout(Float.POSITIVE_INFINITY, 280f, 1f))
        assertFalse(studioWideShortLayout(640f, Float.NaN, 1f))
        assertFalse(studioWideShortLayout(640f, 0f, 1f))
        assertTrue(studioWideShortLayout(576f, 300f, 1f, 520))
    }
    @Test fun toolSectionsTogetherPreserveAtLeastSixtyPercentForTheirList() {
        for (height in listOf(0f, 60f, 100f, 180f, 320f, 600f)) for (sections in 1..3) {
            val each = studioToolbarBudget(height, sections)
            assertTrue(each >= 0f && each <= 112f)
            assertTrue(each * sections <= height * .4f + .001f)
        }
        assertEquals(24f, studioToolbarBudget(120f, 2))
        assertEquals(0f, studioToolbarBudget(-30f))
        assertEquals(40f, studioToolbarBudget(100f, 0))
        assertEquals(112f, studioToolbarBudget(Float.NaN))
    }
    @Test fun compactFormsDoNotExpandWithLandscapeButStillFitTinyWindows() {
        for (preferred in listOf(360, 400, 420, 520)) {
            assertEquals(preferred.toFloat(), studioDialogWidth(1080f, preferred))
            assertEquals(preferred.toFloat(), studioDialogWidth(1600f, preferred))
            assertEquals(264f, studioDialogWidth(280f, preferred))
            assertEquals(1f, studioDialogWidth(0f, preferred))
            assertEquals(preferred.toFloat(), studioDialogWidth(Float.POSITIVE_INFINITY, preferred))
            for (width in listOf(120f, 240f, 360f, 480f)) {
                val actual = studioDialogWidth(width, preferred)
                assertTrue(actual > 0f && actual <= width - 16f)
                assertTrue(actual <= preferred)
            }
        }
    }
    @Test fun imageFieldsAndShortcutsWrapBeforeTheirLabelsAndValuesAreSqueezed() {
        assertEquals(4, studioCompactGridColumns(380f, 1f, 82, 4))
        assertEquals(3, studioCompactGridColumns(300f, 1f, 82, 4))
        assertEquals(2, studioCompactGridColumns(380f, 1.8f, 82, 4))
        assertEquals(1, studioCompactGridColumns(260f, 1.8f, 142, 2))
        assertEquals(1, studioCompactGridColumns(Float.NaN, 1f, 82, 4))
        assertEquals(1, studioCompactGridColumns(0f, 1f, 82, 4))
        for (width in listOf(240f, 280f, 380f, 480f)) for (font in listOf(1f, 1.5f, 2f)) {
            val columns = studioCompactGridColumns(width, font, 82, 4)
            val cell = (width - (columns - 1) * 6f) / columns
            assertTrue(cell >= 82f * font || columns == 1)
        }
    }
    @Test fun outputChoicesUseWholeLabelsAndWrapBeforeLargeFontCanClipThem() {
        assertEquals(4, debugOutputChoiceColumns(480f, 1f))
        assertEquals(4, debugOutputChoiceColumns(360f, 1f))
        assertEquals(2, debugOutputChoiceColumns(280f, 1f))
        assertEquals(2, debugOutputChoiceColumns(480f, 1.8f))
        assertEquals(1, debugOutputChoiceColumns(280f, 2f))
        assertEquals(1, debugOutputChoiceColumns(120f, 1f))
        assertEquals(1, debugOutputChoiceColumns(Float.NaN, 1f))
        assertEquals(4, debugOutputChoiceColumns(480f, Float.NaN))
    }
    @Test fun shortViewportUsesActualSpaceRatherThanOrientation() {
        assertTrue(studioShortViewport(180f))
        assertTrue(studioShortViewport(400f))
        assertFalse(studioShortViewport(640f))
        assertFalse(studioShortViewport(Float.POSITIVE_INFINITY))
        assertFalse(studioShortViewport(Float.NaN))
    }
    @Test fun landscapeWorkbenchPreservesCanvasAndAllToolsAtLargeFont() {
        for (width in listOf(560f, 640f, 800f)) for (scale in listOf(1f, 1.6f, 2f)) {
            val sidebar = imageWorkbenchSideWidth(width, 280f, scale)
            assertTrue(sidebar >= 224f)
            assertTrue(width - sidebar >= width * .58f - .001f)
        }
        assertEquals(0f, imageWorkbenchSideWidth(360f, 640f, 1f))
        assertEquals(0f, imageWorkbenchSideWidth(800f, 500f, 1f))
        assertEquals(0f, imageWorkbenchSideWidth(Float.NaN, 280f, 1f))
        assertEquals(224f, imageWorkbenchSideWidth(640f, 280f, Float.NaN))
    }
    @Test fun popupPreviewLeavesMostOfAShortKeyboardViewportForInputs() {
        assertEquals(72f, studioPopupPreviewHeight(600f))
        assertEquals(38.4f, studioPopupPreviewHeight(160f), .001f)
        assertEquals(0f, studioPopupPreviewHeight(0f))
        assertEquals(72f, studioPopupPreviewHeight(Float.NaN))
        for (height in listOf(60f, 120f, 240f, 360f)) assertTrue(studioPopupPreviewHeight(height) <= height * .24f)
    }
    @Test fun keyboardAndTinyWindowAlwaysBoundPanel() {
        assertEquals(180f, studioPanelMaxHeight(180f, 640))
        assertEquals(65f, studioPanelMaxHeight(65f, 640))
        assertEquals(1f, studioPanelMaxHeight(0f, 640))
        assertEquals(336f, studioPanelMaxHeight(Float.POSITIVE_INFINITY, 360))
    }
    @Test fun fontScaleDoesNotChangeDefaultButAllowsLargeText() {
        assertEquals(30f, studioCompactControlHeight(30, 1f))
        assertEquals(60f, studioCompactControlHeight(30, 2f))
        assertEquals(30f, studioCompactControlHeight(30, Float.NaN))
    }
    @Test fun narrowRotatedHeaderCannotConsumeTheEntireTitle() {
        for (width in listOf(0f, 120f, 240f, 360f, 540f, 1080f)) {
            val actions = studioHeaderActionsMaxWidth(width)
            assertTrue(actions >= 0f)
            assertTrue(actions <= width * .35f)
        }
        assertEquals(0f, studioHeaderActionsMaxWidth(Float.NaN))
        assertEquals(0f, studioHeaderActionsMaxWidth(Float.POSITIVE_INFINITY))
    }
    @Test fun fullscreenAndCloseStayOutsideTheScrollableActions() {
        for (width in listOf(120f, 240f, 330f, 540f, 1080f)) {
            val pinned = 64f
            val actions = studioHeaderActionsMaxWidth(width, pinned)
            assertTrue(actions >= 0f)
            assertTrue(actions + pinned <= maxOf(pinned, width * .35f) + .001f)
        }
        assertEquals(0f, studioHeaderActionsMaxWidth(120f, 64f))
        assertEquals(51.5f, studioHeaderActionsMaxWidth(330f, 64f), .001f)
    }
    @Test fun expandedSourceViewportRemovesCompactCapButStillFitsKeyboardSpace() {
        assertEquals(260f, studioPanelViewportHeight(600f, 260, false))
        assertEquals(600f, studioPanelViewportHeight(600f, 260, true))
        assertEquals(100f, studioPanelViewportHeight(100f, 260, true))
        assertEquals(100f, studioPanelViewportHeight(100f, 260, false))
        assertEquals(1f, studioPanelViewportHeight(0f, 260, true))
        assertEquals(260f, studioPanelViewportHeight(Float.POSITIVE_INFINITY, 260, true))
    }
}
