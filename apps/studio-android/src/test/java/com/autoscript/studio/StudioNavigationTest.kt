package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StudioNavigationTest {
    @Test
    fun `workspace is the local-first default`() {
        assertEquals(StudioDestination.WORKSPACE, StudioNavigationState().destination)
    }

    @Test
    fun `navigation selects requested top-level page`() {
        val state = StudioNavigationState().navigateTo(StudioDestination.PROFILE)

        assertEquals(StudioDestination.PROFILE, state.destination)
    }

    @Test
    fun `selecting current page is idempotent`() {
        val state = StudioNavigationState()

        assertSame(state, state.navigateTo(StudioDestination.WORKSPACE))
    }
}
