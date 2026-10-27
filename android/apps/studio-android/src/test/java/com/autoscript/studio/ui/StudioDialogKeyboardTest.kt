package com.autoscript.studio

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioDialogKeyboardTest {
    @Test fun visibleImeWithZeroInsetUsesTheActualVisibleWindow() {
        assertEquals(352, studioKeyboardBottomInset(48, 672, 368, true, 0))
        assertEquals(352, studioLegacyImeOverlap(672, 352, 0, 672))
    }

    @Test fun resizedWindowDoesNotSubtractTheKeyboardAgain() {
        assertEquals(0, studioKeyboardBottomInset(48, 320, 368, true, 0))
        assertEquals(0, studioLegacyImeOverlap(320, 352, 0, 320))
    }

    @Test fun hiddenOrFloatingKeyboardDoesNotCreateAnEmptyBottomArea() {
        assertEquals(0, studioKeyboardBottomInset(48, 672, 368, false, 352))
        assertEquals(0, studioKeyboardBottomInset(48, 672, 720, true, 0))
    }

    @Test fun explicitImeInsetIsKeptWhenTheVisibleFrameIsUnchanged() {
        assertEquals(352, studioKeyboardBottomInset(48, 672, 720, true, 352))
    }

    @Test fun legacyInsetsOnlyRemoveContentStillBehindTheKeyboard() {
        assertEquals(337, studioLegacyImeOverlap(656, 337, 0, 656))
        assertEquals(0, studioLegacyImeOverlap(656, 337, 16, 303))
        assertEquals(321, studioLegacyImeOverlap(656, 337, 16, 624))
        assertEquals(0, studioLegacyImeOverlap(656, 0, 0, 656))
    }

    @Test fun invalidLegacyInsetsDoNotCollapseAWindow() {
        assertEquals(0, studioLegacyImeOverlap(0, 100, 0, 100))
        assertEquals(0, studioLegacyImeOverlap(100, -1, 0, 100))
        assertEquals(0, studioLegacyImeOverlap(100, 100, 0, 100))
        assertEquals(0, studioLegacyImeOverlap(100, 10, 0, 0))
    }

    @Test fun resizeReplacesPanWithoutChangingKeyboardVisibilityPolicy() {
        val visibility = LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        assertEquals(visibility or LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            studioDialogResizeSoftInputMode(visibility or LayoutParams.SOFT_INPUT_ADJUST_PAN))
    }

    @Test fun resizePreservesForwardNavigationAndIsIdempotent() {
        val current = LayoutParams.SOFT_INPUT_STATE_HIDDEN or LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION or
            LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val resized = studioDialogResizeSoftInputMode(current)
        assertEquals(LayoutParams.SOFT_INPUT_STATE_HIDDEN or LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION or
            LayoutParams.SOFT_INPUT_ADJUST_RESIZE, resized)
        assertEquals(resized, studioDialogResizeSoftInputMode(resized))
    }
}
