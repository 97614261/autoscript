package com.autoscript.studio

import com.autoscript.studio.ImageToolCodeGen.Roi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐条对照参考实现 `build/erjianwan-ui/x-3d4f4b39.html` 的
 * `limitTranslation()` / `scaleImage()` / `resetView()` / `handleResizeMove()` /
 * `handleHandleClick()` / `showMagnifier()` / `updateMenuPosition()`。
 */
class ImageToolViewportTest {

    /** 720×1280 的图放进 720×1280 的视图：fitScale 正好 1，居中即原点。 */
    private fun sameSize() = ImageToolViewport.fit(720, 1280, 720f, 1280f)

    /** 1080×1920 的图放进 720×1280 的视图：需要缩到 2/3。 */
    private fun downscaled() = ImageToolViewport.fit(1080, 1920, 720f, 1280f)

    @Test
    fun fitScaleNeverEnlargesSmallImages() {
        // 参考 resetView() 的 min(w比, h比, 1)：小图保持原大小，不拉伸。
        val small = ImageToolViewport.fit(100, 100, 720f, 1280f)
        assertEquals(1f, small.scale, 1e-4f)
        // 但仍要居中
        assertEquals(310f, small.translateX, 1e-3f)
        assertEquals(590f, small.translateY, 1e-3f)
    }

    @Test
    fun fitScalesDownLargeImages() {
        val v = downscaled()
        assertEquals(720f / 1080f, v.scale, 1e-4f)
        // 等比缩放后正好铺满，平移为 0
        assertEquals(0f, v.translateX, 1e-3f)
        assertEquals(0f, v.translateY, 1e-3f)
    }

    /** minScale 不带 resetView 的 1f 上限——两者不是同一个量。 */
    @Test
    fun minScaleDiffersFromFitScaleForSmallImages() {
        val small = ImageToolViewport.fit(100, 100, 720f, 1280f)
        assertEquals(1f, small.fitScale, 1e-4f)
        assertEquals(7.2f, small.minScale, 1e-3f)
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) { ImageToolViewport.fit(0, 100, 10f, 10f) }
        assertThrows(IllegalArgumentException::class.java) { ImageToolViewport.fit(10, 10, 0f, 10f) }
    }

    /** 参考 limitTranslation()：图比容器小时强制居中，拖不动。 */
    @Test
    fun smallerThanViewportIsAlwaysCentred() {
        val v = ImageToolViewport.fit(100, 100, 720f, 1280f).panned(300f, -400f)
        assertEquals(310f, v.translateX, 1e-3f)
        assertEquals(590f, v.translateY, 1e-3f)
    }

    /** 放大后可以拖动，但边缘不许越界——不能把图拖出屏幕。 */
    @Test
    fun panIsClampedToImageEdges() {
        val zoomed = sameSize().scaledBy(2f, 360f, 640f)
        val farLeft = zoomed.panned(-99_999f, 0f)
        // 右边缘贴住视图右侧：translateX == viewWidth - scaledWidth
        assertEquals(zoomed.viewWidth - farLeft.scaledWidth, farLeft.translateX, 1e-3f)
        val farRight = zoomed.panned(99_999f, 0f)
        assertEquals(0f, farRight.translateX, 1e-3f)
    }

    /** 参考 scaleImage()：锚点在屏幕上必须保持不动。 */
    @Test
    fun zoomKeepsAnchorPointStationary() {
        val v = sameSize()
        val anchorX = 200f
        val anchorY = 500f
        val before = v.toImage(anchorX, anchorY)
        val after = v.scaledBy(2f, anchorX, anchorY).toImage(anchorX, anchorY)
        assertNotNull(before)
        assertNotNull(after)
        // 缩放前后锚点对应的图片像素一致（钳制可能带来 1px 误差）
        assertTrue("$before vs $after", kotlin.math.abs(before!!.first - after!!.first) <= 1)
        assertTrue("$before vs $after", kotlin.math.abs(before.second - after.second) <= 1)
    }

    @Test
    fun zoomOutStopsAtMinScale() {
        val v = sameSize().scaledBy(0.001f, 360f, 640f)
        assertEquals(v.minScale, v.scale, 1e-4f)
    }

    @Test
    fun zoomInStopsAtMaxScale() {
        val v = sameSize().scaledBy(9_999f, 360f, 640f)
        assertEquals(ImageToolViewport.MAX_SCALE, v.scale, 1e-4f)
    }

    @Test
    fun resetReturnsToFittedCentre() {
        val v = sameSize().scaledBy(4f, 100f, 100f).panned(-200f, -300f).reset()
        assertEquals(v.fitScale, v.scale, 1e-4f)
        assertEquals(0f, v.translateX, 1e-3f)
        assertEquals(0f, v.translateY, 1e-3f)
    }

    /** 视图↔图片互转要能往返。 */
    @Test
    fun viewAndImageCoordinatesRoundTrip() {
        val v = sameSize().scaledBy(3f, 300f, 400f)
        val (vx, vy) = v.toView(123, 456)
        assertEquals(123 to 456, v.toImage(vx, vy))
    }

    /** 参考 updateCrosshairPreview() 的「自动吸附边缘」。 */
    @Test
    fun toImageClampsOutOfBoundsWhenAsked() {
        val v = sameSize()
        assertEquals(0 to 0, v.toImage(-500f, -500f, clamp = true))
        assertEquals(719 to 1279, v.toImage(99_999f, 99_999f, clamp = true))
    }

    @Test
    fun toImageRejectsOutOfBoundsWhenClampDisabled() {
        val v = sameSize()
        assertNull(v.toImage(-1f, 10f, clamp = false))
        assertNotNull(v.toImage(10f, 10f, clamp = false))
    }

    // ---- 放大镜 ----

    @Test
    fun magnifierSamplesFormOddSquareGrid() {
        val samples = magnifierSamples({ x, y -> if (x in 0..9 && y in 0..9) x * 10 + y else null }, 5, 5)
        assertEquals(MAGNIFIER_GRID * MAGNIFIER_GRID, samples.size)
        // 中心格对应中心像素
        assertEquals(55, samples[samples.size / 2])
    }

    /** 越界格返回 null，而不是把坐标夹回来——夹回来会让边缘看起来像一片纯色。 */
    @Test
    fun magnifierLeavesOutOfBoundsCellsEmpty() {
        val samples = magnifierSamples({ x, y -> if (x in 0..9 && y in 0..9) 1 else null }, 0, 0)
        assertEquals(1, samples[samples.size / 2])
        assertNull(samples[0])
    }

    @Test
    fun magnifierGridMustBeOdd() {
        assertThrows(IllegalArgumentException::class.java) {
            magnifierSamples({ _, _ -> 0 }, 0, 0, size = 10)
        }
    }

    /** 参考 showMagnifier()：默认左上，越界翻到另一侧，最后强制钳边。 */
    @Test
    fun magnifierPrefersTopLeftThenFlips() {
        val (left, top) = magnifierPlacement(400f, 600f, 120f, 720f, 1280f, offset = 40f)
        assertEquals(240f, left, 1e-3f)
        assertEquals(440f, top, 1e-3f)

        val (flippedLeft, flippedTop) = magnifierPlacement(10f, 10f, 120f, 720f, 1280f, offset = 40f)
        assertEquals(50f, flippedLeft, 1e-3f)
        assertEquals(50f, flippedTop, 1e-3f)
    }

    @Test
    fun magnifierNeverLeavesTheViewport() {
        val (left, top) = magnifierPlacement(719f, 1279f, 200f, 720f, 1280f, offset = 40f)
        assertTrue("left=$left", left >= 0f && left + 200f <= 720f)
        assertTrue("top=$top", top >= 0f && top + 200f <= 1280f)
    }

    // ---- 菜单定位 ----

    @Test
    fun menuOpensBelowToolbarWhenThereIsRoom() {
        val (left, top) = menuPlacement(100f, 200f, 38f, 160f, 300f, 720f, 1280f, padding = 6f)
        assertEquals(100f, left, 1e-3f)
        assertEquals(244f, top, 1e-3f)
    }

    /** 下方放不下就翻到工具条上方——参考的「智能向上弹出」。 */
    @Test
    fun menuFlipsAboveWhenBelowIsTooTight() {
        val (_, top) = menuPlacement(100f, 1100f, 38f, 160f, 300f, 720f, 1280f, padding = 6f)
        assertEquals(794f, top, 1e-3f)
    }

    @Test
    fun menuIsPushedBackInsideHorizontally() {
        val (left, _) = menuPlacement(700f, 100f, 38f, 160f, 300f, 720f, 1280f)
        assertEquals(560f, left, 1e-3f)
    }

    // ---- 选框微调与拖边 ----

    /** 参考 handleHandleClick()：左/上向内收，右/下向外扩，各 1 像素。 */
    @Test
    fun nudgeMovesOneImagePixelPerTap() {
        val box = Roi(10, 20, 110, 220)
        assertEquals(Roi(11, 20, 110, 220), nudgeBox(box, BoxEdge.LEFT, 720, 1280))
        assertEquals(Roi(10, 21, 110, 220), nudgeBox(box, BoxEdge.TOP, 720, 1280))
        assertEquals(Roi(10, 20, 111, 220), nudgeBox(box, BoxEdge.RIGHT, 720, 1280))
        assertEquals(Roi(10, 20, 110, 221), nudgeBox(box, BoxEdge.BOTTOM, 720, 1280))
    }

    @Test
    fun nudgeStopsAtMinimumSize() {
        val thin = Roi(10, 10, 11, 11)
        assertEquals(thin, nudgeBox(thin, BoxEdge.LEFT, 720, 1280))
        assertEquals(thin, nudgeBox(thin, BoxEdge.TOP, 720, 1280))
    }

    @Test
    fun nudgeStopsAtImageEdge() {
        val edge = Roi(700, 1270, 720, 1280)
        assertEquals(edge, nudgeBox(edge, BoxEdge.RIGHT, 720, 1280))
        assertEquals(edge, nudgeBox(edge, BoxEdge.BOTTOM, 720, 1280))
    }

    /** 参考 handleResizeMove()：屏幕位移按 delta/scale 折算成图片整数像素。 */
    @Test
    fun resizeConvertsViewDeltaToImagePixels() {
        val box = Roi(100, 100, 200, 200)
        // scale = 2 时，屏幕上拖 40px 等于图片上 20px
        assertEquals(Roi(120, 100, 200, 200), resizeBox(box, BoxEdge.LEFT, 40f, 0f, 2f, 720, 1280))
        assertEquals(Roi(100, 100, 220, 200), resizeBox(box, BoxEdge.RIGHT, 40f, 0f, 2f, 720, 1280))
        assertEquals(Roi(100, 120, 200, 200), resizeBox(box, BoxEdge.TOP, 0f, 40f, 2f, 720, 1280))
        assertEquals(Roi(100, 100, 200, 220), resizeBox(box, BoxEdge.BOTTOM, 0f, 40f, 2f, 720, 1280))
    }

    @Test
    fun resizeNeverInvertsOrLeavesTheImage() {
        val box = Roi(100, 100, 200, 200)
        // 左边往右拖过头：最多顶到 right - minSize
        assertEquals(199, resizeBox(box, BoxEdge.LEFT, 99_999f, 0f, 1f, 720, 1280).left)
        // 右边往右拖过头：最多到图片宽度
        assertEquals(720, resizeBox(box, BoxEdge.RIGHT, 99_999f, 0f, 1f, 720, 1280).right)
        // 上边往上拖过头：最多到 0
        assertEquals(0, resizeBox(box, BoxEdge.TOP, 0f, -99_999f, 1f, 720, 1280).top)
    }

    @Test
    fun toolbarStaysInsideViewport() {
        assertEquals(0f to 0f, clampToolbar(-50f, -50f, 200f, 38f, 720f, 1280f))
        assertEquals(520f to 1242f, clampToolbar(99_999f, 99_999f, 200f, 38f, 720f, 1280f))
    }
}
