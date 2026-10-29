package com.autoscript.runtime.service

/** Disconnects never launch su; late events belong to their original attempt. */
internal class RootConnectionSession(
    private val launch: (Long) -> Unit,
    private val release: () -> Unit,
    private val publish: (State) -> Unit,
) {
    enum class State { STOPPED, STARTING, READY, FAILED }

    private data class Snapshot(val state: State, val ticket: Long)
    @Volatile private var snapshot = Snapshot(State.STOPPED, 0L)
    val state: State get() = snapshot.state
    val ticket: Long get() = snapshot.ticket

    fun readyTicket(): Long? = snapshot.let { if (it.state == State.READY) it.ticket else null }

    fun request() {
        if (state == State.STARTING || state == State.READY) return
        snapshot = Snapshot(State.STARTING, Math.incrementExact(ticket))
        publish(state)
        launch(ticket)
    }

    fun starting(expected: Long): Boolean = ticket == expected && state == State.STARTING

    fun active(expected: Long): Boolean = ticket == expected &&
        state in setOf(State.STARTING, State.READY)

    fun ready(expected: Long): Boolean {
        if (!starting(expected)) return false
        snapshot = Snapshot(State.READY, expected)
        publish(state)
        return true
    }

    fun end(expected: Long, failed: Boolean) {
        if (!active(expected)) return
        // Invalidate before releasing: native detach may report another disconnect.
        snapshot = Snapshot(if (failed) State.FAILED else State.STOPPED, expected)
        release()
        publish(state)
    }

    fun stop() {
        snapshot = Snapshot(State.STOPPED, ticket)
        release()
        publish(state)
    }
}

internal typealias RootConnectionState = RootConnectionSession.State
