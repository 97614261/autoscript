package com.autoscript.studio

/**
 * Packaging artifacts are not runtime SDK installation. A base project still
 * needs a Runner APK; the current device ABI does not constrain the export target.
 */
internal enum class RunnerCapabilityAvailability {
    AVAILABLE,
    MISSING,
    INCOMPATIBLE,
    CORRUPT,
}

internal fun runnerCapabilityAvailability(
    plan: RunnerDependencyPlan,
    entries: Collection<RunnerComponentEntry>,
    selectedId: String?,
): RunnerCapabilityAvailability {
    if (selectedId != null) {
        val selected = entries.singleOrNull { it.id == selectedId }
            ?: return RunnerCapabilityAvailability.MISSING
        return when {
            selected.state == RunnerComponentState.CORRUPT -> RunnerCapabilityAvailability.CORRUPT
            selected.state == RunnerComponentState.INCOMPATIBLE -> RunnerCapabilityAvailability.INCOMPATIBLE
            selected.verified?.descriptor?.family != plan.family -> RunnerCapabilityAvailability.INCOMPATIBLE
            else -> RunnerCapabilityAvailability.AVAILABLE
        }
    }
    // Invalid entries have no trustworthy family. Do not attribute an unrelated
    // damaged APK to the requested family or let it mask a healthy alternative.
    return if (entries.any { it.state == RunnerComponentState.READY && it.verified?.descriptor?.family == plan.family }) {
        RunnerCapabilityAvailability.AVAILABLE
    } else {
        RunnerCapabilityAvailability.MISSING
    }
}

internal fun runnerTemplateReady(
    plan: RunnerDependencyPlan,
    entries: Collection<RunnerComponentEntry>,
    selectedId: String?,
    embeddedFull: Boolean,
): Boolean = if (selectedId != null) {
    runnerCapabilityAvailability(plan, entries, selectedId) == RunnerCapabilityAvailability.AVAILABLE
} else {
    embeddedFull && plan.family == RunnerTemplateFamily.FULL
}
