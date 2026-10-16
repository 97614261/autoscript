package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorExecutionAppearanceTest {
    @Test fun `only a validated current identity can receive an execution accent`() {
        for (executing in listOf(false, true)) for (recent in listOf(false, true))
            assertEquals(EditorExecutionEmphasis.NONE, editorExecutionEmphasis(false, executing, recent))
    }
    @Test fun `running recent and paused next step have distinct labels backgrounds and rails`() {
        val states = listOf(editorExecutionEmphasis(true, true, false), editorExecutionEmphasis(true, true, true),
            editorExecutionEmphasis(true, false, false))
        assertEquals(listOf("执行中", "刚执行", "下一步"), states.map { it.label })
        assertEquals(3, states.map { it.background }.toSet().size)
        assertEquals(3, states.map { it.accent }.toSet().size)
    }
}
