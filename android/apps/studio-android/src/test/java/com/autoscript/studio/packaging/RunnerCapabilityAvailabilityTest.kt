package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerCapabilityAvailabilityTest {
    private val base = RunnerDependencyPlan(false, false)
    private val cv = RunnerDependencyPlan(true, false)
    private val full = RunnerDependencyPlan(true, true)

    private fun entry(id: String, family: RunnerTemplateFamily) =
        RunnerComponentEntry(id, VerifiedRunnerComponent(id, 100, RunnerComponentDescriptor(family, setOf("arm64-v8a"))), null)

    @Test fun baseAlsoNeedsAnExplicitTemplate() {
        assertEquals(RunnerCapabilityAvailability.MISSING, runnerCapabilityAvailability(base, emptyList(), null))
        assertFalse(runnerTemplateReady(base, emptyList(), null, true))
        assertFalse(runnerTemplateReady(base, listOf(entry("base", RunnerTemplateFamily.BASE)), null, false))
        assertTrue(runnerTemplateReady(base, listOf(entry("base", RunnerTemplateFamily.BASE)), "base", false))
    }

    @Test fun selectedArtifactCannotBorrowAnotherArtifactsAvailability() {
        val entries = listOf(entry("base", RunnerTemplateFamily.BASE), entry("cv", RunnerTemplateFamily.OPENCV))
        assertEquals(RunnerCapabilityAvailability.INCOMPATIBLE, runnerCapabilityAvailability(cv, entries, "base"))
        assertFalse(runnerTemplateReady(cv, entries, "base", false))
        assertTrue(runnerTemplateReady(cv, entries, "cv", false))
        assertFalse(runnerTemplateReady(cv, entries, "removed", false))
    }

    @Test fun corruptUnselectedArtifactDoesNotDescribeTheMissingFamily() {
        val entries = listOf(RunnerComponentEntry("bad", null, "bad", RunnerComponentState.CORRUPT))
        assertEquals(RunnerCapabilityAvailability.MISSING, runnerCapabilityAvailability(cv, entries, null))
        assertEquals(RunnerCapabilityAvailability.CORRUPT, runnerCapabilityAvailability(cv, entries, "bad"))
    }

    @Test fun embeddedFallbackDoesNotOverrideInvalidExplicitSelection() {
        assertTrue(runnerTemplateReady(full, emptyList(), null, true))
        assertFalse(runnerTemplateReady(full, emptyList(), "missing", true))
        val incompatible = RunnerComponentEntry("bad", null, "bad", RunnerComponentState.INCOMPATIBLE)
        assertEquals(RunnerCapabilityAvailability.INCOMPATIBLE, runnerCapabilityAvailability(full, listOf(incompatible), "bad"))
        assertFalse(runnerTemplateReady(full, listOf(incompatible), "bad", true))
    }

    @Test fun armOnlyTemplateIsValidForExportWithoutCurrentDeviceAbiGating() {
        assertTrue(runnerTemplateReady(cv, listOf(entry("arm", RunnerTemplateFamily.OPENCV)), "arm", false))
    }
}
