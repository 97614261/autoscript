package com.autoscript.runtime.service

/**
 * Coalesce old wakes before sampling native state. A completion submitted during dispatch
 * must retain its wake: the native next-wake snapshot may still say it is waiting on host.
 */
internal inline fun runCoalescedRuntimePump(removeQueued: () -> Unit, dispatch: () -> Unit) {
    removeQueued()
    dispatch()
}
