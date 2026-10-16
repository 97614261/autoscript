package com.autoscript.studio

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HomeSpaceModelTest {
    @Test fun `theme carousel wraps in either direction`() {
        assertEquals(HomeSpaceTheme.ORBITS, nextHomeTheme(HomeSpaceTheme.AURORA))
        assertEquals(HomeSpaceTheme.AURORA, nextHomeTheme(HomeSpaceTheme.PARTICLES))
        assertEquals(HomeSpaceTheme.PARTICLES, nextHomeTheme(HomeSpaceTheme.AURORA, -1))
        assertEquals(HomeSpaceTheme.ORBITS, nextHomeTheme(HomeSpaceTheme.ORBITS, 0))
        assertEquals(HomeSpaceTheme.PARTICLES, nextHomeTheme(HomeSpaceTheme.AURORA, -4))
    }
    @Test fun `horizontal background swipes select a theme without reacting to vertical or short gestures`() {
        val first = HomeSpaceTheme.AURORA
        assertEquals(HomeSpaceTheme.ORBITS, homeThemeAfterSwipe(first, -180f, 12f, 720f))
        assertEquals(HomeSpaceTheme.PARTICLES, homeThemeAfterSwipe(first, 180f, 12f, 720f))
        assertEquals(first, homeThemeAfterSwipe(first, -40f, 0f, 720f))
        assertEquals(first, homeThemeAfterSwipe(first, -180f, 200f, 720f))
        assertEquals(first, homeThemeAfterSwipe(first, Float.NaN, 0f, 720f))
        assertEquals(first, homeThemeAfterSwipe(first, -180f, 0f, 0f))
    }
    @Test fun `logo drag is bounded and rejects non finite geometry`() {
        assertEquals(.5f, homeDragFraction(25f, 50f), 0f)
        assertEquals(1f, homeDragFraction(500f, 50f), 0f)
        assertEquals(-1f, homeDragFraction(-500f, 50f), 0f)
        assertEquals(0f, homeDragFraction(50f, 0f), 0f)
        assertEquals(0f, homeDragFraction(Float.POSITIVE_INFINITY, 50f), 0f)
    }
    @Test fun `demo visits each visual node then returns to idle after one bounded shot`() {
        assertEquals(0, homeDemoFrame(0).active)
        assertEquals(0, homeDemoFrame(1_299).active)
        assertEquals(1, homeDemoFrame(1_300).active)
        assertEquals(2, homeDemoFrame(1_950).active)
        for (index in 0..4) {
            assertEquals(index, homeDemoFrame(index * 650L).activeLine)
            assertEquals(index, homeDemoFrame(index * 650L + 649).activeLine)
        }
        assertTrue(homeDemoFrame(3_250).completed)
        assertTrue(homeDemoFrame(4_399).completed)
        assertEquals(HomeDemoFrame(), homeDemoFrame(4_400))
        assertEquals(HomeDemoFrame(), homeDemoFrame(null))
        assertEquals(HomeDemoFrame(), homeDemoFrame(-1))
    }
    @Test fun `demo progress stays finite and bounded`() {
        for (time in 0L..4_500L step 17) {
            assertTrue(homeDemoFrame(time).progress in 0f..1f)
            assertTrue(homeDemoFrame(time).lineProgress in 0f..1f)
        }
    }
    @Test fun `compact playback follows the current row in a three row window`() {
        assertEquals(0, homeDemoFirstVisibleLine(0, true))
        assertEquals(0, homeDemoFirstVisibleLine(1, true))
        assertEquals(1, homeDemoFirstVisibleLine(2, true))
        assertEquals(2, homeDemoFirstVisibleLine(3, true))
        assertEquals(2, homeDemoFirstVisibleLine(4, true))
        assertEquals(0, homeDemoFirstVisibleLine(4, false))
        for (index in 0..4) assertTrue(index in homeDemoFirstVisibleLine(index, true)..homeDemoFirstVisibleLine(index, true) + 2)
    }
    @Test fun `ambient requires both enabled animations and no power saver`() {
        assertTrue(homeAmbientMotionAllowed(true, false))
        assertFalse(homeAmbientMotionAllowed(false, false))
        assertFalse(homeAmbientMotionAllowed(true, true))
        assertFalse(homeAmbientMotionAllowed(false, true))
    }
    @Test fun `layout budgets keep the logo between header and playback at small sizes and large fonts`() {
        for ((width, height) in listOf(320f to 480f, 320f to 360f, 360f to 640f, 360f to 250f, 640f to 320f)) {
            for (scale in listOf(1f, 1.4f, 2f, 3f)) {
                val layout = homeSpaceLayout(width, height, scale)
                assertTrue(layout.contentHeightDp >= height)
                assertTrue(layout.diameterDp > 0f && layout.diameterDp <= 320f)
                assertTrue(layout.centerYDp - layout.diameterDp * .35f >= layout.headerReserveDp)
                val bottom = layout.contentHeightDp - layout.footerReserveDp -
                    if (layout.landscape) 0f else layout.playbackHeightDp + 16f
                assertTrue(layout.centerYDp + layout.diameterDp * .35f <= bottom + .01f)
                if (scale > 1.3f) assertTrue(layout.compactBlocks)
            }
        }
        assertTrue(homeSpaceLayout(320f, 360f, 2f).contentHeightDp > 360f)
    }
    @Test fun `layout rejects non finite metrics and grows rows for actual font scale`() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            val layout = homeSpaceLayout(Float.NaN, Float.NEGATIVE_INFINITY, scale)
            assertTrue(layout.contentHeightDp.isFinite() && layout.diameterDp.isFinite())
            assertTrue(homeBlockRowHeight(scale, true).isFinite())
        }
        assertTrue(homeBlockRowHeight(2f, true) > homeBlockRowHeight(1f, true))
    }
    @Test fun `theme saver uses a stable name and unknown saved names fall back safely`() {
        val state = HomeSpaceState(HomeSpaceTheme.PARTICLES)
        val scope = object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(HomeSpaceState.Saver) { scope.save(state) }
        assertEquals("PARTICLES", saved)
        assertEquals(HomeSpaceTheme.PARTICLES, HomeSpaceState.Saver.restore(requireNotNull(saved))?.theme)
        assertEquals(HomeSpaceTheme.AURORA, HomeSpaceState.Saver.restore("unknown")?.theme)
        val app = File("src/main/java/com/autoscript/studio/navigation/MainActivity.kt").readText()
        assertTrue(app.contains("val homeSpaceState = rememberHomeSpaceState()"))
        assertTrue(app.contains("spaceState = homeSpaceState"))
    }
    @Test fun `home scene is lifecycle bounded and reuses unchanged brand resource without runtime access`() {
        val source = File("src/main/java/com/autoscript/studio/navigation/HomeSpaceScene.kt").readText()
        assertTrue(source.contains("BrandR.drawable.zhigou_brand_mark"))
        assertTrue(source.contains("LaunchedEffect(resumed, motion)"))
        assertTrue(source.contains("Lifecycle.Event.ON_PAUSE"))
        assertTrue(source.contains("33_000_000L"))
        for (forbidden in listOf("ProjectStore(", "RuntimeClient(", "startService(", "RootDaemon", "loadUrl("))
            assertFalse(forbidden, source.contains(forbidden))
    }
}
