package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ImageWorkspaceNavigationTest {
    @Test fun toolSwitchWaitsForCurrentEditorToAcceptClose() {
        val navigation = ImageWorkspaceNavigation("filter")
        var requests = 0
        navigation.open("selection") { requests++ }
        assertEquals(1, requests)
        assertEquals("filter", navigation.panel)
        navigation.finish()
        assertEquals("selection", navigation.panel)
    }

    @Test fun keepingDraftCancelsPendingScreenshotRatherThanTakingItLater() {
        val navigation = ImageWorkspaceNavigation("selection")
        var captures = 0
        navigation.afterClose({}) { captures++ }
        navigation.cancelPending()
        navigation.finish()
        assertEquals(0, captures)
        assertNull(navigation.panel)
    }

    @Test fun screenshotContinuesExactlyOnceAfterConfirmedDiscard() {
        val navigation = ImageWorkspaceNavigation("mask")
        var captures = 0
        navigation.afterClose({}) { captures++ }
        navigation.finish()
        navigation.finish()
        assertEquals(1, captures)
    }

    @Test fun latestRequestedToolReplacesEarlierPendingNavigation() {
        val navigation = ImageWorkspaceNavigation("filter")
        navigation.open("glyph") {}
        navigation.open("selection") {}
        navigation.finish()
        assertEquals("selection", navigation.panel)
    }

    @Test fun reselectingCurrentToolPreservesItsDraft() {
        val navigation = ImageWorkspaceNavigation("filter")
        navigation.open("filter") { fail("The current editor should stay open") }
        assertEquals("filter", navigation.panel)
    }

    @Test fun actionsOutsideEditorAndPanelsWithoutCloseGuardContinueImmediately() {
        val navigation = ImageWorkspaceNavigation()
        var captures = 0
        navigation.afterClose(null) { captures++ }
        navigation.open("tools", null)
        navigation.open("filter", null)
        assertEquals(1, captures)
        assertEquals("filter", navigation.panel)
    }
}
