package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeProtocol
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RecordingCancelConfirmationTest {
    @Test fun acceptedAndCurrentCancelIsTheOnlyDecisionThatClearsLease() {
        val lease = Any(); val leases = AtomicReference<Any?>(lease)
        assertTrue(confirmRecordingCancel(leases,lease,{ true },{ RuntimeProtocol.CONTROL_ACCEPTED }).isSuccess)
        assertNull(leases.get())
    }
    @Test fun allServiceRejectionsPreserveIdenticalLeaseForRetry() {
        for (status in listOf(RuntimeProtocol.CONTROL_SESSION_MISMATCH,RuntimeProtocol.CONTROL_INVALID_STATE,RuntimeProtocol.CONTROL_ENGINE_ERROR,99)) {
            val lease = Any(); val leases = AtomicReference<Any?>(lease)
            assertTrue(confirmRecordingCancel(leases,lease,{ true },{ status }).isFailure)
            assertSame(lease,leases.get())
            assertTrue(confirmRecordingCancel(leases,lease,{ true },{ RuntimeProtocol.CONTROL_ACCEPTED }).isSuccess)
            assertNull(leases.get())
        }
    }
    @Test fun binderExceptionDoesNotClearLeaseOrPreventRetry() {
        val lease = Any(); val leases = AtomicReference<Any?>(lease)
        val failure = confirmRecordingCancel(leases,lease,{ true },{ throw IllegalStateException("Binder failure") })
        assertTrue(failure.isFailure); assertSame(lease,leases.get())
        assertEquals("Binder failure",failure.exceptionOrNull()?.message)
        assertTrue(confirmRecordingCancel(leases,lease,{ true },{ RuntimeProtocol.CONTROL_ACCEPTED }).isSuccess)
    }
    @Test fun staleSessionBeforeCancelDoesNotSendAndLateSessionChangeDoesNotClaimSuccess() {
        val lease = Any(); val leases = AtomicReference<Any?>(lease)
        var calls = 0
        assertTrue(confirmRecordingCancel(leases,lease,{ false },{ calls++; RuntimeProtocol.CONTROL_ACCEPTED }).isFailure)
        assertEquals(0,calls); assertSame(lease,leases.get())
        var current = true
        assertTrue(confirmRecordingCancel(leases,lease,{ current },{ current=false; RuntimeProtocol.CONTROL_ACCEPTED }).isFailure)
        assertSame(lease,leases.get())
    }
    @Test fun oldAcceptedCompletionNeverClearsReplacementLease() {
        val old = Any(); val replacement = Any(); val leases = AtomicReference<Any?>(old)
        assertTrue(confirmRecordingCancel(leases,old,{ true },{ leases.set(replacement); RuntimeProtocol.CONTROL_ACCEPTED }).isFailure)
        assertSame(replacement,leases.get())
    }
}
