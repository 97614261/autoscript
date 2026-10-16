package com.autoscript.runtime.service

internal fun canRestartRuntime(
    stoppedByUser: Boolean,
    expectedGeneration: Long,
    actualGeneration: Long,
    expectedHandle: Long,
    actualHandle: Long,
    rootReady: Boolean,
    restartCount: Int,
    recordingBusy: Boolean,
): Boolean = !stoppedByUser && expectedGeneration == actualGeneration &&
    expectedHandle != 0L && expectedHandle == actualHandle && rootReady &&
    restartCount in 0..9 && !recordingBusy
