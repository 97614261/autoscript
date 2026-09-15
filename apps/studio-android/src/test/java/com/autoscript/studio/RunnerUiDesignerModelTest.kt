package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerUiDesignerModelTest {
    @Test
    fun `empty designer removes runner ui`() {
        assertNull(RunnerUiDesignerDraft().toRunnerUiJson())
    }

    @Test
    fun `designer round trips supported controls`() {
        val draft = RunnerUiDesignerDraft(description = "启动配置")
            .add(RunnerUiControlKind.TEXT)
            .add(RunnerUiControlKind.BOOLEAN)
            .add(RunnerUiControlKind.CHOICE)

        val json = requireNotNull(draft.toRunnerUiJson())
        val restored = runnerUiDesignerDraft(json)

        assertEquals("启动配置", restored.description)
        assertEquals(draft.fields.map { it.kind }, restored.fields.map { it.kind })
        assertEquals(3, restored.fields.size)
    }

    @Test
    fun `move keeps stable field identity`() {
        val draft = RunnerUiDesignerDraft()
            .add(RunnerUiControlKind.TEXT)
            .add(RunnerUiControlKind.INTEGER)

        val moved = draft.move(1, -1)

        assertEquals(RunnerUiControlKind.INTEGER, moved.fields.first().kind)
        assertTrue(moved.fields.map { it.id }.toSet() == draft.fields.map { it.id }.toSet())
    }
}
