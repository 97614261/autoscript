package com.autoscript.studio

/** One current request; stale tickets/owners cannot consume a newer session's result. */
internal class EditorRequestTickets<T> {
    private data class Entry<T>(val owner: Any, val ticket: String, val value: T)
    private var active: Entry<T>? = null
    fun begin(owner: Any, ticket: String, value: T) {
        check(active == null) { "请先完成当前系统选择" }
        active = Entry(owner, ticket, value)
    }
    fun find(ticket: String?): T? = active?.takeIf { it.ticket == ticket }?.value
    fun ticketFor(owner: Any): String? = active?.takeIf { it.owner === owner }?.ticket
    fun take(ticket: String): T? {
        val entry = active?.takeIf { it.ticket == ticket } ?: return null
        active = null
        return entry.value
    }
}
