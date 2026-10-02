package com.autoscript.studio

/** User-facing launch and editor execution are distinct; single-step remains independent. */
internal enum class StudioRunEntry {
    LAUNCH, EDITOR, SINGLE_STEP;

    fun opensInterface(hasDefinition: Boolean, hasSubmittedValues: Boolean = false): Boolean =
        this == LAUNCH && hasDefinition && !hasSubmittedValues
}
