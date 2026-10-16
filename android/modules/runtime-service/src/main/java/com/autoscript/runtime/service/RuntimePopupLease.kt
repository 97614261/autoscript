package com.autoscript.runtime.service

/** Main-thread ownership: a stale expiry/click must never dismiss a replacement popup. */
internal class RuntimePopupLease {
    private var revision = 0L
    private var current: Long? = null

    fun begin(): Long = (++revision).also { current = it }
    fun end(ticket: Long): Boolean {
        if (current != ticket) return false
        current = null
        return true
    }
    fun clear() { current = null }
}
