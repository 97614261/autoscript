package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol

/** Reschedule from the published read: a second read can miss a terminal transition. */
internal inline fun observeRuntimeStop(observeAndPublish: () -> Int, schedule: () -> Unit) {
    if (observeAndPublish() == RuntimeProtocol.STATE_STOPPING) schedule()
}

/** A completed step is a new pause event even if the transient RUNNING state was not observed. */
internal fun shouldPublishRuntimeState(state: Int, root: Int, previousState: Int, previousRoot: Int, completedStep: Boolean): Boolean =
    completedStep || state != previousState || root != previousRoot

/** A terminal listener may immediately prepare the next run. Finish old ownership first. */
internal inline fun publishFinalizedRuntimeState(finalizeState: () -> Unit, publishState: () -> Unit) {
    finalizeState()
    publishState()
}
