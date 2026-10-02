package com.autoscript.script.ui

import org.junit.Assert.*
import org.junit.Test

class ScriptUiGeometryTest {
    @Test fun compactRadioGroupsUseOneRowButTallGroupsKeepVerticalLayout() {
        assertTrue(ScriptUiGeometry.radioHorizontal(40, 20, 2))
        assertFalse(ScriptUiGeometry.radioHorizontal(96, 20, 2))
        assertFalse(ScriptUiGeometry.radioHorizontal(40, 20, 1))
    }
    @Test fun wholePageFitsWithoutDistortionAndCentersInEitherOrientation() {
        val portrait = ScriptUiGeometry.viewport(720, 960, 360f, 600f)
        assertEquals(.5f, portrait.scale, .001f)
        assertEquals(0f, portrait.left, .001f)
        assertEquals(60f, portrait.top, .001f)
        val landscape = ScriptUiGeometry.viewport(720, 960, 600f, 300f)
        assertEquals(.3125f, landscape.scale, .001f)
        assertEquals(187.5f, landscape.left, .001f)
        assertEquals(0f, landscape.top, .001f)
        assertEquals(720f / 960, landscape.width / landscape.height, .001f)
    }

    @Test fun widthModeKeepsTallContentScrollable() {
        val viewport = ScriptUiGeometry.viewport(720, 1920, 360f, 300f, UiDisplayMode.WIDTH_SCROLL)
        assertEquals(360f, viewport.width, .001f)
        assertEquals(960f, viewport.height, .001f)
        assertEquals(0f, viewport.top, .001f)
    }

    @Test fun shortPageDoesNotExpandToScreenHeight() {
        val size = ScriptUiGeometry.window(UiPage(width = 720, height = 240), UiScreenBounds(8, 24, 1000, 1600), 1f, 64)
        assertEquals(UiWindowSize(360, 184), size)
    }

    @Test fun windowIsBoundedInPortraitLandscapeAndKeyboardSpace() {
        val page = UiPage(width = 720, height = 1920)
        listOf(UiScreenBounds(8, 24, 704, 1200), UiScreenBounds(8, 24, 1200, 600), UiScreenBounds(8, 24, 704, 280)).forEach { bounds ->
            val size = ScriptUiGeometry.window(page, bounds, 2f, 128)
            assertTrue(size.width in 1..bounds.width)
            assertTrue(size.height in 1..bounds.height)
            assertTrue(size.height <= (bounds.height * .9f).toInt() + 1)
        }
    }

    @Test fun dragClampsEntireWindowNotOnlyHeader() {
        val bounds = UiScreenBounds(8, 24, 700, 1000)
        assertEquals(8 to 24, bounds.clamp(-100, -100, UiWindowSize(360, 500)))
        assertEquals(348 to 524, bounds.clamp(9999, 9999, UiWindowSize(360, 500)))
        assertEquals(178 to 274, bounds.centered(UiWindowSize(360, 500)))
        assertEquals(8 to 24, bounds.clamp(999, 999, UiWindowSize(900, 1200)))
    }

    @Test fun legacyFormAndDensityUseSameScreenLimits() {
        val bounds = UiScreenBounds(8, 24, 2000, 2000)
        val size = ScriptUiGeometry.window(UiPage(), bounds, 2f, 128, 200)
        assertEquals(720, size.width)
        assertEquals(328, size.height)
    }

    @Test fun rejectsInvalidDesignAndKeepsUnmeasuredViewportFinite() {
        assertThrows(IllegalArgumentException::class.java) { ScriptUiGeometry.viewport(0, 960, 300f, 300f) }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiGeometry.viewport(720, 960, Float.NaN, 300f) }
        assertThrows(IllegalArgumentException::class.java) { ScriptUiGeometry.window(UiPage(), UiScreenBounds(0, 0, 100, 100), 0f, 64) }
        assertTrue(ScriptUiGeometry.viewport(720, 960, 0f, 0f).scale.isFinite())
    }

    @Test fun calculationDoesNotChangeSavedDesign() {
        val model = ScriptUiDefinition(fields = emptyList(), version = 2)
        val before = model.toJson().toString()
        ScriptUiGeometry.window(model.pages.first(), UiScreenBounds(8, 24, 700, 400), 2f, 128)
        ScriptUiGeometry.viewport(720, 960, 400f, 200f)
        assertEquals(before, model.toJson().toString())
    }
}
