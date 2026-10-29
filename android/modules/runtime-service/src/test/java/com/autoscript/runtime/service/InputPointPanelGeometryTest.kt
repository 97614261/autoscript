package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPointPanelGeometryTest {
    @Test fun draggingClampsAllEdgesWithoutMovingAnInteriorPosition() {
        assertEquals(20 to 30, inputPointPanelPosition(20, 30, 340, 44, 1280, 720))
        assertEquals(0 to 0, inputPointPanelPosition(-50, -10, 340, 44, 1280, 720))
        assertEquals(940 to 676, inputPointPanelPosition(Int.MAX_VALUE, Int.MAX_VALUE, 340, 44, 1280, 720))
    }
    @Test fun openingSettingsKeepsTheAnchorUnlessTheNewHeightWouldLeaveTheScreen() {
        assertEquals(120 to 100, inputPointPanelPosition(120, 100, 340, 200, 1280, 720))
        assertEquals(120 to 520, inputPointPanelPosition(120, 660, 340, 200, 1280, 720))
        assertEquals(0 to 0, inputPointPanelPosition(120, 660, 340, 200, 240, 100))
    }
    @Test fun longScreenUsesNaturalSettingsHeight() {
        assertEquals(160, inputPointPanelBodyHeight(720, 16, 76, 160))
    }

    @Test fun landscapeKeepsHeaderAndFooterOutsideScrollingBody() {
        assertEquals(88, inputPointPanelBodyHeight(180, 16, 76, 160))
    }

    @Test fun noSpaceNeverCreatesNegativeLayoutHeight() {
        assertEquals(0, inputPointPanelBodyHeight(60, 16, 76, 160))
    }

    @Test fun largeValuesDoNotOverflowBudgetArithmetic() {
        assertEquals(0, inputPointPanelBodyHeight(100, Int.MAX_VALUE, Int.MAX_VALUE, 160))
    }
}
