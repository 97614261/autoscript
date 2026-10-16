package com.autoscript.runtime.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class RootInputBridgeTest {
    private data class Event(val action: Int, val x: Int, val y: Int, val downTime: Long, val time: Long)

    private fun runSession(requests: List<List<Int>>, accept: (Int) -> Boolean = { true }): Pair<List<Int>, List<Event>> {
        val source = ByteArrayOutputStream()
        DataOutputStream(source).use { output -> requests.flatten().forEach(output::writeInt) }
        val replies = ByteArrayOutputStream()
        val events = mutableListOf<Event>()
        var clock = 100L
        runRootPointerBridge(DataInputStream(ByteArrayInputStream(source.toByteArray())), DataOutputStream(replies), { ++clock }) { action, x, y, down, now ->
            events += Event(action, x, y, down, now)
            accept(action)
        }
        val data = DataInputStream(ByteArrayInputStream(replies.toByteArray()))
        assertEquals(0x41534931, data.readInt())
        val statuses = buildList { while (data.available() > 0) add(data.readInt()) }
        return statuses to events
    }

    @Test
    fun continuousPointerUsesOneDownTimeAndEofReleasesAtLastPosition() {
        val (statuses, events) = runSession(listOf(listOf(0, 10, 20), listOf(2, 30, 40)))
        assertEquals(listOf(0, 0), statuses)
        assertEquals(listOf(0, 2, 1), events.map(Event::action))
        assertEquals(1, events.map(Event::downTime).distinct().size)
        assertEquals(30, events.last().x)
        assertEquals(40, events.last().y)
    }

    @Test
    fun invalidActionsAndStateTransitionsNeverReachInjector() {
        val (statuses, events) = runSession(listOf(listOf(2, 1, 2), listOf(9, 1, 2), listOf(0, -1, 2), listOf(0, 1, 2), listOf(0, 3, 4), listOf(1, 1, 2)))
        assertEquals(listOf(1, 1, 1, 0, 1, 0), statuses)
        assertEquals(listOf(0, 1), events.map(Event::action))
    }

    @Test
    fun uncertainDownAndTruncatedRequestsStillAttemptCleanup() {
        val (statuses, failed) = runSession(listOf(listOf(0, 1, 2))) { it != 0 }
        assertEquals(listOf(3), statuses)
        assertEquals(listOf(0, 1), failed.map(Event::action))
        val (partial, events) = runSession(listOf(listOf(0, 1, 2), listOf(2, 8)))
        assertEquals(listOf(0), partial)
        assertEquals(listOf(0, 1), events.map(Event::action))
    }

    @Test
    fun freshSessionCanReleaseAnUncertainPointerWithoutAcknowledgedDown() {
        val (statuses, events) = runSession(listOf(listOf(1, 3, 4)))
        assertEquals(listOf(0), statuses)
        assertEquals(listOf(1), events.map(Event::action))
    }
}
