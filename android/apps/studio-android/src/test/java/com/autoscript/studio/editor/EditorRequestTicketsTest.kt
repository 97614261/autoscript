package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class EditorRequestTicketsTest {
    @Test fun previousOwnerOrTicketCannotCancelOrConsumeNewRequest() {
        val tickets = EditorRequestTickets<String>(); val first = Any(); val second = Any()
        tickets.begin(first, "old", "old-result")
        assertEquals("old-result", tickets.take("old")); assertNull(tickets.take("old"))
        tickets.begin(second, "new", "new-result")
        assertNull(tickets.ticketFor(first)); assertNull(tickets.take("old"))
        assertEquals("new-result", tickets.find("new")); assertEquals("new", tickets.ticketFor(second))
        assertEquals("new-result", tickets.take("new")); assertNull(tickets.find("new"))
    }
    @Test fun concurrentRequestIsRejectedWithoutReplacingOriginal() {
        val tickets = EditorRequestTickets<String>(); val owner = Any()
        tickets.begin(owner, "first", "first-result")
        try { tickets.begin(owner, "second", "second-result"); fail("must reject") } catch (_: IllegalStateException) {}
        assertEquals("first-result", tickets.take("first"))
    }
}
