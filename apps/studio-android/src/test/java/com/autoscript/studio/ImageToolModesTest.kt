package com.autoscript.studio

import com.autoscript.studio.ImageToolCodeGen.PickedPoint
import com.autoscript.studio.ImageToolCodeGen.Roi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToolModesTest {

    private fun point(x: Int) = PickedPoint(x, x, 0)

    /** 禁用的模式必须说明原因——否则界面上就是个点不动又不解释的按钮。 */
    @Test
    fun disabledModesAlwaysExplainWhy() {
        ImageToolMode.entries.filterNot { it.enabled }.forEach { mode ->
            assertNotNull("${mode.label} 被禁用却没给原因", mode.blockedReason)
            assertTrue("${mode.label} 的原因是空串", mode.blockedReason!!.isNotBlank())
        }
    }

    /** 反过来，能用的模式不该挂着"禁用原因"，避免文案自相矛盾。 */
    @Test
    fun enabledModesCarryNoBlockedReason() {
        ImageToolMode.entries.filter { it.enabled }.forEach { mode ->
            assertNull("${mode.label} 可用却带着禁用原因", mode.blockedReason)
        }
    }

    @Test
    fun implementedImageAndInputModesAreEnabled() {
        val enabled = ImageToolMode.entries.filter { it.enabled }.map { it.label }.toSet()
        assertEquals(setOf("裁剪", "范围", "取色", "多点", "单击", "长按", "滑动", "拖动", "按下", "移动", "弹起"), enabled)
    }

    /** OCR / 节点 / 录制是本轮明确不做的三项。 */
    @Test
    fun blockedModesAreTheThreeKnownGaps() {
        val blocked = ImageToolMode.entries.filterNot { it.enabled }.map { it.label }.toSet()
        assertEquals(setOf("取字", "节点", "录制"), blocked)
    }

    /** 菜单里能用的排在禁用的前面，别让用户先点到点不动的。 */
    @Test
    fun menuListsEnabledModesFirst() {
        val order = ImageToolMode.menuOrder
        assertEquals(ImageToolMode.entries.size, order.size)
        val firstDisabled = order.indexOfFirst { !it.enabled }
        assertTrue("禁用项之后不应再出现可用项", order.drop(firstDisabled).none { it.enabled })
    }

    @Test
    fun onlyBoxModesUseDragBox() {
        assertTrue(ImageToolMode.CROP.usesDragBox)
        assertTrue(ImageToolMode.REGION.usesDragBox)
        assertTrue(!ImageToolMode.TAP.usesDragBox)
        assertTrue(!ImageToolMode.MULTI_COLOR.usesDragBox)
    }

    /** 取色和单击只保留最后一个点，重复点击是"改选"而不是"多选"。 */
    @Test
    fun singlePointModesKeepOnlyTheLatestPick() {
        var selection = ImageToolSelection()
        selection = selection.withPoint(ImageToolMode.TAP, point(1))
        selection = selection.withPoint(ImageToolMode.TAP, point(2))
        assertEquals(listOf(point(2)), selection.points)
    }

    /** 滑动保留最近两个点：第三次点击应该成为新的终点，起点顺移。 */
    @Test
    fun swipeKeepsLastTwoPoints() {
        var selection = ImageToolSelection()
        listOf(1, 2, 3).forEach { selection = selection.withPoint(ImageToolMode.SWIPE, point(it)) }
        assertEquals(listOf(point(2), point(3)), selection.points)
    }

    @Test
    fun gestureModesExposeTheCorrectFloatingPickControls() {
        assertTrue(ImageToolMode.LONG_PRESS.usesCrosshair)
        assertTrue(ImageToolMode.DRAG.usesSlideMarkers)
        assertTrue(ImageToolMode.POINTER_DOWN.usesCrosshair)
        assertTrue(ImageToolMode.POINTER_MOVE.usesCrosshair)
        assertTrue(!ImageToolMode.POINTER_UP.usesCrosshair)
        assertTrue(listOf(ImageToolMode.POINTER_DOWN, ImageToolMode.POINTER_MOVE, ImageToolMode.POINTER_UP).all { it.needsPointerInput })
        assertEquals(
            listOf(point(2), point(3)),
            listOf(1, 2, 3).fold(ImageToolSelection()) { state, x ->
                state.withPoint(ImageToolMode.DRAG, point(x))
            }.points,
        )
    }

    /** 多点：锚点 + 64 个采样点是契约上限，再点不应继续增长。 */
    @Test
    fun multiColorStopsAtContractLimit() {
        var selection = ImageToolSelection()
        repeat(ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 5) {
            selection = selection.withPoint(ImageToolMode.MULTI_COLOR, point(it))
        }
        assertEquals(ImageToolCodeGen.MAX_MULTI_COLOR_SAMPLES + 1, selection.points.size)
    }

    /** 切到拖框模式时清点位、切到点选模式时清框，两种选取不互相残留。 */
    @Test
    fun switchingModeClearsTheIrrelevantSelection() {
        val full = ImageToolSelection(box = Roi(0, 0, 10, 10), points = listOf(point(1)))
        assertEquals(emptyList<PickedPoint>(), full.clearedFor(ImageToolMode.CROP).points)
        assertEquals(Roi(0, 0, 10, 10), full.clearedFor(ImageToolMode.CROP).box)
        assertNull(full.clearedFor(ImageToolMode.TAP).box)
        assertEquals(listOf(point(1)), full.clearedFor(ImageToolMode.TAP).points)
    }
}
