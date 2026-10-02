package com.autoscript.studio

import com.autoscript.runtime.api.RuntimeProtocol
import org.junit.Assert.*
import org.junit.Test

class VisualInputActionDialogTest {
    @Test fun unknownOrUnavailableBackendDoesNotEnableFakeActions() {
        VisualInputAction.entries.forEach { action ->
            assertNotNull(inputActionUnavailableReason(action, true, null))
            assertNotNull(inputActionUnavailableReason(action, true, 0))
            assertNotNull(inputActionUnavailableReason(action, false, 3))
            assertNotNull(inputActionUnavailableReason(action, true, RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER))
        }
    }

    @Test fun basicBackendOnlyEnablesTapAndSwipe() {
        val allowed = VisualInputAction.entries.filter {
            inputActionUnavailableReason(it, true, RuntimeProtocol.INPUT_FEATURE_BASIC) == null
        }
        assertEquals(listOf(VisualInputAction.TAP, VisualInputAction.SWIPE), allowed)
    }

    @Test fun pointerBackendEnablesAllSevenExistingPickerRoutes() {
        val features = RuntimeProtocol.INPUT_FEATURE_BASIC or RuntimeProtocol.INPUT_FEATURE_SINGLE_POINTER
        assertEquals(listOf(ImageToolMode.TAP, ImageToolMode.SWIPE, ImageToolMode.LONG_PRESS, ImageToolMode.DRAG,
            ImageToolMode.POINTER_DOWN, ImageToolMode.POINTER_MOVE, ImageToolMode.POINTER_UP), VisualInputAction.entries.map { it.mode })
        VisualInputAction.entries.forEach { assertNull(inputActionUnavailableReason(it, true, features)) }
    }

    @Test fun unavailableReasonPrioritizesMissingEditorOverBackendClaims() {
        assertEquals("当前没有可用定位入口", inputActionUnavailableReason(VisualInputAction.TAP, false, null))
        assertEquals("后端不支持持续触点", inputActionUnavailableReason(VisualInputAction.DRAG, true, RuntimeProtocol.INPUT_FEATURE_BASIC))
    }
}
