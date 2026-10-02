package com.autoscript.runtime.client

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/** Reject duplicate starts instead of queueing a second run; stop bypasses the business path. */
internal class RuntimeStartGate {
    private val owner = AtomicReference<Job?>(null)

    fun begin(job: Job): Boolean = owner.compareAndSet(null, job)
    fun finish(job: Job) { owner.compareAndSet(job, null) }
    fun cancel() { owner.get()?.cancel() }
}
