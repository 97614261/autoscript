package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioReferenceDesignTest {
    @Test fun normalCardsUseReferenceFourColumnGrid() {
        assertEquals(4, projectActionColumns(312f, 1f))
        assertEquals(4, projectActionColumns(272f, 1f))
    }

    @Test fun narrowAndLargeTypeReduceColumnsInsteadOfClipping() {
        assertEquals(2, projectActionColumns(272f, 1.6f))
        assertEquals(2, projectActionColumns(230f, 1f))
        assertEquals(1, projectActionColumns(100f, 2f))
        assertEquals(4, projectActionColumns(312f, Float.NaN))
        assertEquals(1, projectActionColumns(60f, 1f))
    }
}
