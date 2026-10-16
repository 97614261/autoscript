package com.autoscript.brand.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BrandViewWiringTest {
    @Test fun hiddenAndDetachedViewsCancelTheirAnimator() {
        val text = File("src/main/java/com/autoscript/brand/ui/ZhigouMarkView.kt").readText()
        assertTrue(text.contains("override fun onDetachedFromWindow() { stopAnimation()"))
        assertTrue(text.contains("!isAttachedToWindow || !visibleToUser || windowVisibility != VISIBLE || !isShown"))
        assertTrue(text.contains("!ValueAnimator.areAnimatorsEnabled()"))
        assertTrue(text.contains("animator?.removeAllUpdateListeners()"))
        assertFalse(text.contains("canvas.rotate"))
        assertFalse(text.contains("scaleX ="))
        assertFalse(text.contains("Handler("))
    }
    @Test fun monochromeKeepsSameCanvasAndAlphaFormat() {
        val bytes = File("src/main/res/drawable-nodpi/zhigou_brand_monochrome.png").readBytes()
        assertEquals(1024, java.nio.ByteBuffer.wrap(bytes, 16, 4).int)
        assertEquals(1024, java.nio.ByteBuffer.wrap(bytes, 20, 4).int)
        assertEquals(6, bytes[25].toInt())
    }
    @Test fun floatingTintPreservesSourceAlphaAndOnlyFloatingSurfaceChangesColor() {
        val text = File("src/main/java/com/autoscript/brand/ui/ZhigouMarkView.kt").readText()
        assertTrue(text.contains("0f, 0f, 0f, 1f, 0f"))
        assertTrue(text.contains("if (surface == BrandSurface.FLOATING) floatingTint else null"))
        assertTrue(text.contains("paint.colorFilter = null"))
        assertTrue(text.contains("now - lastFrameAt >= 30L"))
        assertTrue(text.contains("side * .5f"))
        assertTrue(BrandMotion.FLOATING_GREEN_BIAS >= 180f)
    }
}
