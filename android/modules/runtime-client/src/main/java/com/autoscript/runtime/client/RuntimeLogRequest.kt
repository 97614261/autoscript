package com.autoscript.runtime.client

/** UI history capacity can exceed the bounded AIDL snapshot size. */
internal fun runtimeLogRequestSize(maximumEntries: Int): Int? =
    maximumEntries.takeIf { it > 0 }?.coerceAtMost(100)
