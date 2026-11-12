package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorExecutionLocationTest {
    private fun node(id: String, depth: Int = 0, kind: String = "task.noop", slots: List<String> = emptyList(),
        slot: String? = null, expanded: Boolean = true) =
        EditorTreeNode(id, id, kind, depth, slots.isNotEmpty(), expanded, childSlots = slots, childSlot = slot)

    private fun tree() = listOf(
        node("找图", kind = "vision.findimage", slots = listOf("success", "failure")),
        node("条件", 1, "control.if", listOf("then", "else"), "success"),
        node("循环", 2, "control.repeat", listOf("body"), "then"),
        node("点击", 3, slot = "body"), node("否则动作", 2, slot = "else"),
        node("失败动作", 1, slot = "failure"), node("之后"),
    )

    @Test fun nestedLocationIdentifiesOnlyTheActualResultAndConditionalPath() {
        val location = requireNotNull(editorExecutionLocation(tree(), "点击"))
        assertEquals(4, location.line)
        assertEquals("点击", location.title)
        assertEquals(listOf("branch:找图:success", "branch:条件:then"), location.branches.map { it.key })
        assertEquals(listOf(1, 2), location.branches.map { it.line })
        assertEquals("第4行 · 点击  /  第1行 成功 › 第2行 满足", location.summary)
    }

    @Test fun changingBranchesOrLeavingTheContainerRemovesThePreviousPath() {
        val nodes = tree()
        assertEquals(listOf("成功", "否则"), editorExecutionLocation(nodes, "否则动作")?.branches?.map { it.label })
        assertEquals(listOf("失败"), editorExecutionLocation(nodes, "失败动作")?.branches?.map { it.label })
        assertTrue(requireNotNull(editorExecutionLocation(nodes, "之后")).branches.isEmpty())
    }

    @Test fun foldingDoesNotChangeTheRealLineOrHideTheLocationFromTheDisplayModel() {
        val nodes = tree()
        val folded = nodes.map { if (it.container) it.copy(expanded = false) else it }
        assertEquals(editorExecutionLocation(nodes, "点击"), editorExecutionLocation(folded, "点击"))
        assertEquals(listOf("找图", "之后"), editorTreeDisplayRows(folded).mapNotNull { it.nodeId })
        val failures = editorTreeDisplayRows(nodes, setOf("branch:找图:failure"))
        assertFalse(failures.any { it.nodeId == "失败动作" })
        assertEquals(6, editorExecutionLocation(nodes, "失败动作")?.line)
    }

    @Test fun ownerCheckpointNeverGuessesAnEmptyOrUntakenBranch() {
        val nodes = listOf(node("找图", kind = "vision.findimage", slots = listOf("success", "failure")))
        assertTrue(requireNotNull(editorExecutionLocation(nodes, "找图")).branches.isEmpty())
        assertNull(editorExecutionLocation(nodes, null))
        assertNull(editorExecutionLocation(nodes, "missing"))
        assertNull(editorExecutionLocation(nodes + nodes, "找图"))
    }

    @Test fun locationUsesTheDisplayTitleWithoutIncludingArgumentsOrInternalIdentities() {
        val nodes = tree().map { if (it.id == "点击") it.copy(displayRow =
            CompactProgramRowText("点击确认", "(100,200)", null, false)) else it }
        assertEquals("点击确认", editorExecutionLocation(nodes, "点击")?.title)
        assertFalse(requireNotNull(editorExecutionLocation(nodes, "点击")).summary.contains("(100,200)"))
    }

    @Test fun followingRevealsTheMarkerAndTitleWithMinimalHorizontalMovement() {
        assertNull(editorExecutionScrollOffset(0, 320, 30, 144))
        assertEquals(184, editorExecutionScrollOffset(0, 320, 360, 144))
        assertEquals(30, editorExecutionScrollOffset(250, 320, 30, 144))
        assertEquals(360, editorExecutionScrollOffset(0, 80, 360, 144))
    }
}
