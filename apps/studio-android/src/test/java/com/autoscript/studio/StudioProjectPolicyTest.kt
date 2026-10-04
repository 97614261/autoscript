package com.autoscript.studio

import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectSummary
import org.junit.Assert.*
import org.junit.Test

class StudioProjectPolicyTest {
    private fun project(id: String, mode: ProjectSourceMode) =
        ProjectSummary(id, id, mode, 720, 1280, 1L, 1L, null)

    @Test fun creationAndAllActiveEntriesAreVisualOnly() {
        assertEquals(ProjectSourceMode.VISUAL, StudioProjectPolicy.creationMode)
        assertTrue(StudioProjectPolicy.canOperate(ProjectSourceMode.VISUAL))
        assertFalse(StudioProjectPolicy.canOperate(ProjectSourceMode.LUA))
    }

    @Test fun historicalPartitionPreservesEveryProjectAndOrderWithoutMutatingData() {
        val projects = listOf(project("old", ProjectSourceMode.LUA), project("new", ProjectSourceMode.VISUAL),
            project("old-2", ProjectSourceMode.LUA))
        assertEquals(listOf(projects[1]), StudioProjectPolicy.active(projects))
        assertEquals(listOf(projects[0], projects[2]), StudioProjectPolicy.historical(projects))
        assertEquals(projects.toSet(), (StudioProjectPolicy.active(projects) + StudioProjectPolicy.historical(projects)).toSet())
        assertEquals(3, projects.size)
    }
}
