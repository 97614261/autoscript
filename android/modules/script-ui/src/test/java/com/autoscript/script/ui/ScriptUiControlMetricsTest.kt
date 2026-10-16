package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiControlMetricsTest {
    @Test fun compactPartsFitDefaultHeightAndScaleWithTheCanvas() {
        listOf(.25f, .5f, 1f, 2f).forEach { scale ->
            val height = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.DEFAULT_HEIGHT, scale)
            listOf(ScriptUiControlMetrics.INDICATOR, ScriptUiControlMetrics.SWITCH_HEIGHT,
                ScriptUiControlMetrics.SLIDER_THUMB, ScriptUiControlMetrics.TRACK_HEIGHT).forEach {
                assertTrue(ScriptUiControlMetrics.pixels(it, scale) <= height)
            }
            assertEquals((28 * scale).toInt(), ScriptUiControlMetrics.switchWidth(scale))
            assertEquals((8 * scale).toInt(), ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.TRACK_HEIGHT, scale))
        }
        assertEquals(40, ScriptUiControlMetrics.DEFAULT_HEIGHT)
        assertEquals(20, ScriptUiControlMetrics.DEFAULT_FONT)
    }

    @Test fun tinySwitchKeepsTwoThumbPositionsAndInvalidScaleIsRejected() {
        val scale = .01f
        assertTrue(ScriptUiControlMetrics.switchWidth(scale) >= 2 * ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.SWITCH_THUMB, scale))
        assertThrows(IllegalArgumentException::class.java) { ScriptUiControlMetrics.pixels(18, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiControlMetrics.pixels(18, 0f) }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiControlMetrics.pixels(0, 1f) }
    }
}
