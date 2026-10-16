package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeProtocol

/** Only a normal completion may let the last short tip reach its expiry. */
internal fun terminalClearsAllRuntimePrompts(state: Int, explicitlyStopped: Boolean): Boolean =
    state == RuntimeProtocol.STATE_FAILED || state == RuntimeProtocol.STATE_STOPPED && explicitlyStopped

/** Guards queued UI work across stop/restart; independent of the Binder connection generation. */
internal class RuntimePromptSession {
    private var revision = 0L
    private var active = false
    private var windowEnabled = false
    private var toastRevision = 0L
    private var toastPending: Long? = null

    @Synchronized fun begin(): Long {
        revision++
        active = true
        windowEnabled = true
        toastPending = null
        return revision
    }

    @Synchronized fun ticket(): Long = revision
    @Synchronized fun isCurrent(ticket: Long): Boolean = ticket == revision
    @Synchronized fun acceptsWindow(ticket: Long = revision): Boolean = active && windowEnabled && ticket == revision
    @Synchronized fun acceptsToast(ticket: Long): Boolean = active && ticket == revision
    @Synchronized fun acceptsToast(ticket: Long, toast: Long): Boolean = acceptsToast(ticket) && toastPending == toast
    @Synchronized fun closeWindow(ticket: Long = revision): Long {
        if (ticket == revision) windowEnabled = false
        return ticket
    }
    @Synchronized fun beginToast(ticket: Long): Long? {
        if (!acceptsToast(ticket)) return null
        return (++toastRevision).also { toastPending = it }
    }
    @Synchronized fun endToast(ticket: Long, toast: Long): Boolean {
        if (!acceptsToast(ticket, toast)) return false
        toastPending = null
        return true
    }
    @Synchronized fun hasToast(): Boolean = active && toastPending != null
    @Synchronized fun end(): Long {
        revision++
        active = false
        windowEnabled = false
        toastPending = null
        return revision
    }
}
