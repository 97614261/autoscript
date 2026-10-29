package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RootConnectionSessionTest {
    private class Fixture {
        val launches = mutableListOf<Long>()
        val states = mutableListOf<RootConnectionState>()
        var releases = 0
        val session = RootConnectionSession(launches::add, { releases++ }, states::add)
    }

    @Test fun constructionAndIdleDisconnectNeverLaunchAuthorization() {
        val f = Fixture()
        assertTrue(f.launches.isEmpty())
        f.session.request()
        val first = f.launches.single()
        assertTrue(f.session.ready(first))
        f.session.end(first, failed = false)
        repeat(20) { f.session.end(first, failed = false) }
        assertEquals(listOf(first), f.launches)
        assertEquals(1, f.releases)
        assertEquals(RootConnectionState.STOPPED, f.session.state)
        assertNull(f.session.readyTicket())
        f.session.request()
        assertEquals(2, f.launches.size)
        assertNotEquals(first, f.launches.last())
    }

    @Test fun allRequestsDuringAuthorizationAndReadyReuseOneAttempt() {
        val f = Fixture()
        repeat(50) { f.session.request() }
        val ticket = f.launches.single()
        assertNull(f.session.readyTicket())
        assertTrue(f.session.ready(ticket))
        repeat(50) { f.session.request() }
        assertEquals(listOf(ticket), f.launches)
        assertEquals(ticket, f.session.readyTicket())
        assertEquals(listOf(RootConnectionState.STARTING, RootConnectionState.READY), f.states)
    }

    @Test fun denialTimeoutAndProcessFailureWaitForExplicitRetry() {
        val f = Fixture()
        f.session.request()
        val first = f.launches.single()
        f.session.end(first, failed = true)
        repeat(20) { f.session.end(first, failed = true) }
        assertEquals(RootConnectionState.FAILED, f.session.state)
        assertEquals(1, f.launches.size)
        assertEquals(1, f.releases)
        f.session.request()
        assertEquals(2, f.launches.size)
        assertEquals(RootConnectionState.STARTING, f.session.state)
    }

    @Test fun oldTimeoutReadyAndDisconnectCannotAffectReplacement() {
        val f = Fixture()
        f.session.request()
        val old = f.launches.single()
        f.session.end(old, failed = true)
        f.session.request()
        val replacement = f.launches.last()
        assertFalse(f.session.starting(old))
        assertFalse(f.session.ready(old))
        f.session.end(old, failed = true)
        assertTrue(f.session.ready(replacement))
        f.session.end(old, failed = false)
        assertEquals(RootConnectionState.READY, f.session.state)
        assertEquals(replacement, f.session.readyTicket())
        assertEquals(1, f.releases)
    }

    @Test fun stopAndReentrantDisconnectDoNotRestartOrPublishReady() {
        val f = Fixture()
        f.session.request()
        val ticket = f.launches.single()
        f.session.stop()
        assertFalse(f.session.ready(ticket))
        f.session.end(ticket, failed = true)
        assertEquals(RootConnectionState.STOPPED, f.session.state)
        assertEquals(1, f.launches.size)
        assertEquals(1, f.releases)

        lateinit var session: RootConnectionSession
        var releases = 0
        session = RootConnectionSession({}, { releases++; session.end(session.ticket, failed = false) }, {})
        session.request()
        session.end(session.ticket, failed = true)
        assertEquals(1, releases)
        assertEquals(RootConnectionState.FAILED, session.state)
    }
}
