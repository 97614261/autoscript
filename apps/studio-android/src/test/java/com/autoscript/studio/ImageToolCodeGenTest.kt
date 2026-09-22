package com.autoscript.studio

import com.autoscript.studio.ImageToolCodeGen.DesignMapping
import com.autoscript.studio.ImageToolCodeGen.PickedPoint
import com.autoscript.studio.ImageToolCodeGen.Roi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToolCodeGenTest {

    private val identity = DesignMapping(720, 1280, 720, 1280)

    /** 截图 1080x1920 的物理屏，项目基准 720x1280：坐标必须缩到 2/3。 */
    private val downscale = DesignMapping(1080, 1920, 720, 1280)

    @Test
    fun mapsCapturePixelsToDesignCoordinates() {
        assertEquals(0 to 0, downscale.toDesign(0, 0))
        assertEquals(360 to 640, downscale.toDesign(540, 960))
        // 右下角最后一个像素换算后仍须落在设计范围内，不能越界到 720/1280。
        assertEquals(719 to 1279, downscale.toDesign(1079, 1919))
    }

    @Test
    fun identityMappingLeavesCoordinatesUntouched() {
        assertEquals(123 to 456, identity.toDesign(123, 456))
        assertTrue(identity.isIdentity)
    }

    /** 两轴独立缩放：宽高比不同的假想场景下 Y 不能跟着 X 的比例走。 */
    @Test
    fun scalesEachAxisIndependently() {
        val anisotropic = DesignMapping(1000, 1000, 500, 250, scaleMode = "stretch")
        assertEquals(250 to 125, anisotropic.toDesign(500, 500))
    }

    @Test
    fun letterboxMappingAccountsForBars() {
        val mapping = DesignMapping(1000, 1000, 500, 250, scaleMode = "letterbox")
        // 500x250 design occupies y=250..750 in the square capture.
        assertEquals(250 to 125, mapping.toDesign(500, 500))
    }

    /** ROI 是半开区间，右/下边界允许取到设计宽高本身。 */
    @Test
    fun roiKeepsHalfOpenUpperBound() {
        val roi = downscale.toDesignRoi(0, 0, 1080, 1920)
        assertEquals(Roi(0, 0, 720, 1280), roi)
        assertEquals(720, roi.width)
        assertEquals(1280, roi.height)
    }

    /** 退化到零宽的框要被撑成至少 1 像素，否则生成的 ROI 永远搜不到东西。 */
    @Test
    fun roiNeverCollapsesToZeroArea() {
        val roi = downscale.toDesignRoi(100, 100, 101, 101)
        assertTrue("宽必须为正，实际 ${roi.width}", roi.width >= 1)
        assertTrue("高必须为正，实际 ${roi.height}", roi.height >= 1)
    }

    @Test
    fun rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException::class.java) { DesignMapping(0, 100, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { DesignMapping(100, 100, 100, 0) }
    }

    @Test
    fun tapUsesDesignCoordinates() {
        val snippet = ImageToolCodeGen.tap(PickedPoint(540, 960, 0xFF0000), downscale)
        assertEquals("Input.tap(360, 640)", snippet.code)
        assertNull(snippet.rejection)
        // 换算过的要在说明里点出原始像素，方便用户核对。
        assertTrue(snippet.summary, snippet.summary.contains("540"))
    }

    @Test
    fun tapOnIdentityMappingOmitsRawPixelNote() {
        val snippet = ImageToolCodeGen.tap(PickedPoint(10, 20, 0), identity)
        assertEquals("Input.tap(10, 20)", snippet.code)
        assertTrue(snippet.summary, !snippet.summary.contains("截图像素"))
    }

    @Test
    fun swipeEmitsBothEndpointsAndDuration() {
        val snippet = ImageToolCodeGen.swipe(
            start = PickedPoint(0, 0, 0),
            end = PickedPoint(1079, 1919, 0),
            durationMs = 300,
            mapping = downscale,
        )
        assertEquals("Input.swipe(0, 0, 719, 1279, 300)", snippet.code)
        assertNull(snippet.rejection)
    }

    @Test
    fun swipeRejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageToolCodeGen.swipe(PickedPoint(0, 0, 0), PickedPoint(1, 1, 0), 0, identity)
        }
    }

    /** getColor 需要帧句柄，片段必须自带 capture 且配对 release，否则会漏掉帧池租约。 */
    @Test
    fun getColorCapturesAndReleasesFrame() {
        val snippet = ImageToolCodeGen.getColor(PickedPoint(100, 200, 0x3A6EFF), identity)
        assertTrue(snippet.code, snippet.code.contains("local captureId = Screen.capture()"))
        assertTrue(snippet.code, snippet.code.contains("local frame = Screen.cache(captureId)"))
        assertTrue(snippet.code, snippet.code.contains("Screen.getColor(frame, 100, 200)"))
        assertEquals(1, Regex("Screen\\.release\\(frame\\)").findAll(snippet.code).count())
        assertTrue(snippet.code, snippet.code.contains("0x3A6EFF"))
        assertNull(snippet.rejection)
    }

    @Test
    fun visualApisKeepRawCaptureCoordinates() {
        val point = PickedPoint(540, 960, 0x3A6EFF)
        val color = ImageToolCodeGen.getColor(point, downscale)
        assertTrue(color.code, color.code.contains("Screen.getColor(frame, 540, 960)"))

        val multi = ImageToolCodeGen.findMultiColor(
            anchor = point,
            samples = listOf(PickedPoint(570, 990, 0x010203)),
            tolerance = 8,
            roi = null,
            mapping = downscale,
        )
        assertTrue(multi.code, multi.code.contains("{ x = 30, y = 30, rgb = 0x010203, tolerance = 8 }"))
        assertTrue(multi.code, multi.code.contains("samples, 0, 0, 1080, 1920)"))
    }

    @Test
    fun formatsRgbAsSixDigitHexWithoutAlpha() {
        assertEquals("0x3A6EFF", ImageToolCodeGen.formatRgb(0xFF3A6EFF.toInt()))
        assertEquals("0x000000", ImageToolCodeGen.formatRgb(0))
    }

    /** 采样点是相对锚点的有符号偏移，不是绝对坐标——契约原文如此。 */
    @Test
    fun multiColorSamplesAreSignedOffsetsFromAnchor() {
        val snippet = ImageToolCodeGen.findMultiColor(
            anchor = PickedPoint(100, 100, 0xFFFFFF),
            samples = listOf(PickedPoint(90, 120, 0x000000), PickedPoint(130, 100, 0xFF0000)),
            tolerance = 8,
            roi = null,
            mapping = identity,
        )
        assertTrue(snippet.code, snippet.code.contains("{ x = -10, y = 20, rgb = 0x000000, tolerance = 8 }"))
        assertTrue(snippet.code, snippet.code.contains("{ x = 30, y = 0, rgb = 0xFF0000, tolerance = 8 }"))
        assertNull(snippet.rejection)
    }

    /** roi 为 null 时用设计分辨率兜底成全屏，而不是留空或写 0。 */
    @Test
    fun multiColorWithoutRoiSearchesFullDesignArea() {
        val snippet = ImageToolCodeGen.findMultiColor(
            anchor = PickedPoint(0, 0, 0),
            samples = emptyList(),
            tolerance = 0,
            roi = null,
            mapping = identity,
        )
        assertTrue(snippet.code, snippet.code.contains("samples, 0, 0, 720, 1280)"))
    }

    @Test
    fun multiColorEnforcesContractLimits() {
        val many = List(ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 1) { PickedPoint(it, it, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ImageToolCodeGen.findMultiColor(PickedPoint(0, 0, 0), many, 8, null, identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageToolCodeGen.findMultiColor(PickedPoint(0, 0, 0), emptyList(), 256, null, identity)
        }
    }

    @Test
    fun regionEmitsHalfOpenBounds() {
        val snippet = ImageToolCodeGen.region(Roi(10, 20, 110, 220), identity)
        assertTrue(snippet.code, snippet.code.contains("local left, top, right, bottom = 10, 20, 110, 220"))
        assertTrue(snippet.summary, snippet.summary.contains("100x200"))
    }

    /** 模板找图要成对释放两个租约：帧和模板。 */
    @Test
    fun findImageReleasesBothFrameAndTemplate() {
        val snippet = ImageToolCodeGen.findImage("assets/images/button.png", null, identity)
        assertTrue(snippet.code, snippet.code.contains("Screen.loadImage(\"assets/images/button.png\")"))
        assertTrue(snippet.code, snippet.code.contains("local frame = Screen.cache(captureId)"))
        assertTrue(snippet.code, snippet.code.contains("Screen.release(frame)"))
        assertTrue(snippet.code, snippet.code.contains("Screen.release(template)"))
        assertNull(snippet.rejection)
    }

    @Test
    fun findImageRejectsBlankPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageToolCodeGen.findImage("   ", null, identity)
        }
    }

    @Test
    fun findImageRejectsNonCanonicalProjectPaths() {
        listOf("images/button.png", "assets/images/../button.png", "assets\\images\\button.png").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                ImageToolCodeGen.findImage(path, null, identity)
            }
        }
    }

    /** 路径里的引号和反斜杠必须转义，否则生成的 Lua 语法就断了。 */
    @Test
    fun escapesLuaStringLiterals() {
        assertEquals("\"a\\\\b\"", ImageToolCodeGen.luaString("a\\b"))
        assertEquals("\"say \\\"hi\\\"\"", ImageToolCodeGen.luaString("say \"hi\""))
    }

    /** 所有模式的产出都必须过契约门禁——这是"不生成不存在的 API"的兜底。 */
    @Test
    fun everyGeneratedSnippetPassesContractGate() {
        val snippets = listOf(
            ImageToolCodeGen.tap(PickedPoint(1, 1, 0), identity),
            ImageToolCodeGen.swipe(PickedPoint(1, 1, 0), PickedPoint(2, 2, 0), 200, identity),
            ImageToolCodeGen.getColor(PickedPoint(1, 1, 0), identity),
            ImageToolCodeGen.region(Roi(0, 0, 10, 10), identity),
            ImageToolCodeGen.findMultiColor(PickedPoint(1, 1, 0), emptyList(), 8, null, identity),
            ImageToolCodeGen.findImage("assets/images/a.png", null, identity),
        )
        snippets.forEach { assertNull("${it.summary} 被门禁拦下：${it.rejection}", it.rejection) }
    }

    @Test
    fun channelDistanceUsesMaximumChannelDelta() {
        assertEquals(0, channelDistance(0x3A6EFF, 0x3A6EFF))
        assertEquals(255, channelDistance(0x000000, 0xFF0000))
        assertEquals(0x10, channelDistance(0x102030, 0x102040))
    }
}
