package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StudioNavigationTest {
    @Test
    fun `home is the local-first default`() {
        assertEquals(StudioDestination.HOME, StudioNavigationState().destination)
    }

    @Test
    fun `navigation selects requested top-level page`() {
        val state = StudioNavigationState().navigateTo(StudioDestination.PROFILE)

        assertEquals(StudioDestination.PROFILE, state.destination)
    }

    @Test
    fun `selecting current page is idempotent`() {
        val state = StudioNavigationState()

        assertSame(state, state.navigateTo(StudioDestination.HOME))
    }

    @Test
    fun `navigation labels match the three real destinations`() {
        assertEquals(listOf("首页", "项目", "我的"), StudioDestination.entries.map { it.label })
    }
}
