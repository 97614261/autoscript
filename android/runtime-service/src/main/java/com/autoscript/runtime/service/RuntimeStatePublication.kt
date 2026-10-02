package com.autoscript.runtime.service

/** A completed step is a new pause event even if the transient RUNNING state was not observed. */
internal fun shouldPublishRuntimeState(state: Int, root: Int, previousState: Int, previousRoot: Int, completedStep: Boolean): Boolean =
    completedStep || state != previousState || root != previousRoot
